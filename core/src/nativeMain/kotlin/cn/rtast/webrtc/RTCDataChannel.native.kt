/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:Suppress("ktlint:standard:no-wildcard-imports")

package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.RTCDataChannelConfig
import cn.rtast.webrtc.internal.NativeDatachannelEvent
import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import libdatachannel.*
import platform.posix.fprintf
import platform.posix.memcpy
import platform.posix.stderr
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

public actual class RTCDataChannel internal constructor(
    private val dc: Int,
    public actual val label: String,
    private val peer: RTCPeerConnection,
    scope: CoroutineScope,
    private val maxBufferedAmount: Long = RTCDataChannelConfig.DEFAULT_MAX_BUFFERED_AMOUNT,
    receiveBufferCapacity: Int = RTCDataChannelConfig.DEFAULT_RECEIVE_BUFFER_CAPACITY,
    private val maxReceiveMessageSize: Int = RTCDataChannelConfig.DEFAULT_MAX_RECEIVE_MESSAGE_SIZE,
) {
    private val nativeEvents = Channel<NativeDatachannelEvent>(Channel.UNLIMITED)

    /**
     * Coalesced wake-up signal: the native available callback may fire once per
     * message, but the consumer drains all pending messages per signal, so a
     * conflated channel keeps memory bounded regardless of the message rate.
     */
    private val availableSignal = Channel<Unit>(Channel.CONFLATED)

    // Reused across drains; never held across a suspension point with a memScoped arena.
    private val receiveSize = nativeHeap.alloc<IntVar>()

    // Fast-path buffer. Messages that do not fit fall back to peek + exact allocation.
    private var receiveBuffer: CPointer<ByteVar>? = null

    private val _state = MutableStateFlow(RTCDataChannelState.Connecting)
    private val _bufferedAmount = MutableStateFlow(0L)
    private val _bufferedAmountLow = Channel<Unit>(Channel.CONFLATED)

    @Suppress("ktlint:standard:backing-property-naming")
    private val _textMessages = MutableSharedFlow<String>(0, receiveBufferCapacity, BufferOverflow.SUSPEND)

    @Suppress("ktlint:standard:backing-property-naming")
    private val _binaryMessages = MutableSharedFlow<ByteArray>(0, receiveBufferCapacity, BufferOverflow.SUSPEND)

    public actual val state: StateFlow<RTCDataChannelState> = _state.asStateFlow()
    public actual val bufferedAmount: StateFlow<Long> = _bufferedAmount.asStateFlow()
    public actual val bufferedAmountLow: Flow<Unit> = _bufferedAmountLow.receiveAsFlow()
    public actual val messages: MessageFlow = MessageFlow()

    @Volatile
    private var cleanedUp = false
    private val selfRef = StableRef.create(this)

    public actual val protocol: String
        get() =
            if (isClosed) {
                ""
            } else {
                memScoped {
                    val buf = allocArray<ByteVar>(256)
                    val r = rtcGetDataChannelProtocol(dc, buf, 256)
                    if (r < 0) "" else buf.toKString()
                }
            }

    public actual val isOpen: Boolean get() = _state.value == RTCDataChannelState.Open
    public actual val isClosed: Boolean get() = _state.value == RTCDataChannelState.Closed
    public actual val availableAmount: Int
        get() = if (isClosed) 0 else rtcGetAvailableAmount(dc).coerceAtLeast(0)

    init {
        rtcSetUserPointer(dc, selfRef.asCPointer())
        rtcSetOpenCallback(dc, openCallback)
        rtcSetClosedCallback(dc, closeCallback)
        rtcSetAvailableCallback(dc, availableCallback)
        rtcSetBufferedAmountLowCallback(dc, bufferedAmountLowCallback)
        rtcSetErrorCallback(dc, errorCallback)

        _state.value =
            when {
                rtcIsClosed(dc) -> RTCDataChannelState.Closed
                rtcIsOpen(dc) -> RTCDataChannelState.Open
                else -> RTCDataChannelState.Connecting
            }
        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
        // Messages could already be pending if they arrived before the callbacks were set.
        if (rtcGetAvailableAmount(dc) > 0) availableSignal.trySend(Unit)
        scope.launch { consumeNativeEvents() }
    }

    private suspend fun consumeNativeEvents() {
        var stop = false
        try {
            while (!stop) {
                select {
                    availableSignal.onReceive {
                        drainAvailableMessages()
                    }

                    nativeEvents.onReceive { event ->
                        when (event) {
                            NativeDatachannelEvent.Open -> {
                                _state.value = RTCDataChannelState.Open
                            }

                            NativeDatachannelEvent.Closing -> {
                                _state.value = RTCDataChannelState.Closing
                            }

                            NativeDatachannelEvent.Closed -> {
                                // Match push-callback semantics: deliver messages that
                                // were already queued by the native side before closing.
                                drainAvailableMessages(quiet = true)
                                _state.value = RTCDataChannelState.Closed
                                stop = true
                            }

                            NativeDatachannelEvent.BufferedLow -> {
                                _bufferedAmountLow.trySend(Unit)
                                refreshBufferedAmount()
                            }

                            is NativeDatachannelEvent.Error -> {
                                handleError(event.message)
                            }
                        }
                    }
                }
            }
        } finally {
            doCleanup()
        }
    }

    /**
     * Pull all currently pending messages with `rtcReceiveMessage`.
     *
     * Messages that fit the reusable 64 KiB buffer take a single FFI call; larger
     * messages fall back to peeking the exact size and receiving into an exactly
     * sized array. Only this consumer thread copies payloads, so libdatachannel's
     * network thread is no longer blocked by large memcpy calls, and pending data
     * is bounded by the native receive buffer (SCTP flow control) instead of an
     * unbounded Kotlin queue.
     */
    private suspend fun drainAvailableMessages(quiet: Boolean = false) {
        if (cleanedUp) return
        val size = receiveSize
        val buffer =
            receiveBuffer
                ?: nativeHeap.allocArray<ByteVar>(REUSED_RECEIVE_BUFFER_SIZE).also { receiveBuffer = it }
        while (true) {
            // Fast path: messages that fit are received with a single FFI call.
            size.value = REUSED_RECEIVE_BUFFER_SIZE
            val fast = rtcReceiveMessage(dc, buffer, size.ptr)
            if (fast == RTC_ERR_SUCCESS) {
                if (!emitFromBuffer(buffer, size.value)) return
                continue
            }
            if (fast == RTC_ERR_NOT_AVAIL) return
            if (fast != RTC_ERR_TOO_SMALL) {
                if (!quiet) reportError("receive message failed: $fast", fatal = false)
                return
            }

            // Slow path: the message exceeds the reusable buffer. A null buffer peeks
            // the size without consuming it, then receive into an exactly sized array.
            size.value = 0
            val peek = rtcReceiveMessage(dc, null, size.ptr)
            if (peek == RTC_ERR_NOT_AVAIL) return
            if (peek != RTC_ERR_SUCCESS) {
                if (!quiet) reportError("receive message failed: $peek", fatal = false)
                return
            }
            if (!receiveExact(size, quiet)) return
        }
    }

    private suspend fun emitFromBuffer(
        buffer: CPointer<ByteVar>,
        signedSize: Int,
    ): Boolean {
        val isText = signedSize < 0
        val length = abs(signedSize)
        // Text size is negative and includes the terminating NUL byte.
        val payloadLength = if (isText) length - 1 else length
        if (maxReceiveMessageSize in 1..<payloadLength) {
            reportTooLarge(payloadLength)
            return false
        }

        val bytes = ByteArray(payloadLength)
        if (payloadLength > 0) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), buffer, payloadLength.convert()) }
        }
        if (isText) {
            _textMessages.emit(bytes.decodeToString())
        } else {
            _binaryMessages.emit(bytes)
        }
        return true
    }

    private suspend fun receiveExact(
        size: IntVar,
        quiet: Boolean,
    ): Boolean {
        val signedSize = size.value
        val isText = signedSize < 0
        val length = abs(signedSize)
        val payloadLength = if (isText) length - 1 else length
        if (maxReceiveMessageSize in 1..<payloadLength) {
            reportTooLarge(payloadLength)
            return false
        }

        val bytes = ByteArray(if (isText) length else payloadLength)
        val received =
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    size.value = bytes.size
                    rtcReceiveMessage(dc, pinned.addressOf(0), size.ptr)
                }
            } else {
                // Defensive: the fast path should have consumed empty messages.
                ByteArray(1).usePinned { pinned ->
                    size.value = 1
                    rtcReceiveMessage(dc, pinned.addressOf(0), size.ptr)
                }
            }
        if (received != RTC_ERR_SUCCESS) {
            if (!quiet) reportError("receive message failed: $received", fatal = false)
            return false
        }

        if (isText) {
            _textMessages.emit(bytes.decodeToString(0, payloadLength))
        } else {
            _binaryMessages.emit(bytes)
        }
        return true
    }

    private fun reportTooLarge(payloadLength: Int) {
        reportError(
            "incoming message too large: $payloadLength > $maxReceiveMessageSize",
            fatal = true,
        )
        _state.value = RTCDataChannelState.Closing
        rtcClose(dc)
    }

    private fun refreshBufferedAmount() {
        _bufferedAmount.value = rtcGetBufferedAmount(dc).toLong()
    }

    private fun handleError(message: String) {
        if (isClosed || _state.value == RTCDataChannelState.Closed) {
            reportError(message, fatal = false)
            return
        }
        reportError(message, fatal = true)
        _state.value = RTCDataChannelState.Closing
        rtcClose(dc)
    }

    private fun reportError(
        message: String,
        fatal: Boolean,
    ) {
        val handler = RTCPeerConnectionFactory.errorHandler
        if (handler != null) {
            handler(RTCException(message, fatal))
        } else {
            fprintf(stderr, "[kotlin-webrtc] datachannel error: %s\n", message)
        }
    }

    private fun doCleanup() {
        if (cleanedUp) return
        cleanedUp = true
        // Detach the user pointer first so late native callbacks become no-ops.
        rtcSetUserPointer(dc, null)
        rtcDelete(dc)
        peer.removeChannel(dc)
        _state.value = RTCDataChannelState.Closed
        nativeEvents.close()
        availableSignal.close()
        _bufferedAmountLow.close()
        nativeHeap.free(receiveSize.ptr)
        receiveBuffer?.let { nativeHeap.free(it) }
        receiveBuffer = null
        selfRef.dispose()
    }

    public actual fun setBufferedAmountLowThreshold(threshold: Int): Boolean {
        if (isClosed) return false
        rtcSetBufferedAmountLowThreshold(dc, threshold)
        return true
    }

    private fun canAcceptData(size: Int): Boolean {
        if (isClosed || _state.value == RTCDataChannelState.Closing) return false
        if (maxBufferedAmount <= 0) return true
        return size <= maxBufferedAmount && _bufferedAmount.value <= maxBufferedAmount - size
    }

    private suspend fun awaitCapacity(size: Int): Boolean {
        if (maxBufferedAmount <= 0) return !isClosed
        if (size > maxBufferedAmount) return false
        while (!isClosed && _state.value != RTCDataChannelState.Closing) {
            refreshBufferedAmount()
            if (_bufferedAmount.value <= maxBufferedAmount - size) return true
            delay(1.milliseconds)
        }
        return false
    }

    public actual fun send(text: String): Boolean {
        if (!canAcceptData(text.length)) return false
        val result = memScoped { rtcSendMessage(dc, text.cstr.ptr, -1) }
        refreshBufferedAmount()
        return result >= 0
    }

    public actual fun send(data: ByteArray): Boolean {
        if (!canAcceptData(data.size)) return false
        val result =
            if (data.isEmpty()) {
                memScoped { rtcSendMessage(dc, alloc<ByteVar>().ptr, 0) }
            } else {
                data.usePinned { pinned -> rtcSendMessage(dc, pinned.addressOf(0), data.size) }
            }
        refreshBufferedAmount()
        return result >= 0
    }

    public actual suspend fun sendAwait(text: String): Boolean = awaitCapacity(text.length) && send(text)

    public actual suspend fun sendAwait(data: ByteArray): Boolean = awaitCapacity(data.size) && send(data)

    public actual fun close(): Boolean {
        if (isClosed) return false
        _state.value = RTCDataChannelState.Closing
        rtcClose(dc)
        return true
    }

    public actual inner class MessageFlow internal actual constructor() {
        public actual val text: SharedFlow<String> = _textMessages.asSharedFlow()
        public actual val bytes: SharedFlow<ByteArray> = _binaryMessages.asSharedFlow()
    }

    public companion object {
        /** Messages up to this size are received with a single FFI call. */
        private const val REUSED_RECEIVE_BUFFER_SIZE = 64 * 1024

        private val openCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(NativeDatachannelEvent.Open)
            }

        private val closeCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(NativeDatachannelEvent.Closed)
            }

        private val availableCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.availableSignal.trySend(Unit)
            }

        private val bufferedAmountLowCallback =
            staticCFunction<Int, COpaquePointer?, Unit> { _, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(NativeDatachannelEvent.BufferedLow)
            }

        private val errorCallback =
            staticCFunction<Int, CPointer<ByteVar>?, COpaquePointer?, Unit> { _, error, user ->
                val self = user?.asStableRef<RTCDataChannel>()?.get() ?: return@staticCFunction
                self.nativeEvents.trySend(
                    NativeDatachannelEvent.Error(error?.toKString() ?: "unknown error"),
                )
            }
    }
}
