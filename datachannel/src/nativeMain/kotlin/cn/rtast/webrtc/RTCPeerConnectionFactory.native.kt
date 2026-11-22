/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-02
 */

@file:OptIn(ExperimentalForeignApi::class)

@file:Suppress("ktlint:standard:no-wildcard-imports")

package cn.rtast.webrtc

import cn.rtast.webrtc.configuration.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import libdatachannel.LIBDATACHANNEL_VERSION
import libdatachannel.rtcCleanup
import libdatachannel.rtcInitLogger
import libdatachannel.rtcPreload
import kotlin.concurrent.Volatile

public actual class RTCPeerConnectionFactory actual constructor(
    logLevel: RTCLogLevel,
) {
    @Volatile
    private var closed = false

    public actual fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfiguration,
    ): RTCPeerConnection {
        check(!closed) { "RTCPeerConnectionFactory is closed" }
        check(initialized) { "Initialize the factory before creating peer connections" }
        return RTCPeerConnection(scope, config)
    }

    public actual fun createPeerConnection(
        scope: CoroutineScope,
        config: RTCConfigurationBuilder.() -> Unit,
    ): RTCPeerConnection = createPeerConnection(scope, rtcConfiguration(config))

    public actual fun onError(handler: (RTCException) -> Unit) {
        errorHandler = handler
    }

    public actual fun close() {
        synchronized(initLock) {
            if (closed) return
            closed = true
            if (referenceCount > 0) referenceCount--
            if (referenceCount == 0 && initialized) {
                rtcCleanup()
                initialized = false
            }
        }
    }

    public actual companion object {
        internal actual var errorHandler: ((RTCException) -> Unit)? = null

        private val initLock = SynchronizedObject()

        @Volatile
        private var initialized = false
        private var referenceCount = 0
    }

    init {
        synchronized(initLock) {
            if (!initialized) {
                rtcInitLogger(logLevel.toNative(), null)
                rtcPreload()
                initialized = true
            }
            referenceCount++
        }
    }
}

public actual val RTCPeerConnectionFactory.Companion.VERSION: String
    get() = LIBDATACHANNEL_VERSION
