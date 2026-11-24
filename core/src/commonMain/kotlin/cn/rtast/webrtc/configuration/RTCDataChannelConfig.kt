/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */

package cn.rtast.webrtc.configuration

/**
 * Configuration for an RTC datachannel
 *
 * @property ordered whether messages are delivered in order
 * @property maxRetransmits max retransmission attempts, or null for unlimited
 * @property maxPacketLifeTime max time in ms to retransmit, or null for unlimited
 * @property protocol subprotocol name, empty for none
 * @property negotiated whether the channel is negotiated out-of-band
 * @property maxBufferedAmount maximum outgoing bytes buffered before [cn.rtast.webrtc.RTCDataChannel.send]
 * starts returning `false`; `0` disables the limit. A single message larger than the
 * limit is rejected as well. This is a soft limit: concurrent senders may overshoot it
 * slightly.
 * @property receiveBufferCapacity number of received messages buffered for collectors;
 * `0` applies immediate backpressure. Together with the peer connection's max message size,
 * this bounds per-channel receive memory.
 * @property maxReceiveMessageSize largest single incoming message accepted; larger messages
 * close the channel with an error. `0` disables the check. Defaults to 8 MiB.
 */
public class RTCDataChannelConfig internal constructor(
    public val ordered: Boolean,
    public val maxRetransmits: Int?,
    public val maxPacketLifeTime: Int?,
    public val protocol: String,
    public val negotiated: Boolean,
    public val maxBufferedAmount: Long,
    public val receiveBufferCapacity: Int,
    public val maxReceiveMessageSize: Int,
) {
    init {
        require(maxRetransmits == null || maxPacketLifeTime == null) {
            "maxRetransmits and maxPacketLifeTime are mutually exclusive"
        }
        require(maxRetransmits == null || maxRetransmits >= 0) {
            "maxRetransmits must be >= 0"
        }
        require(maxPacketLifeTime == null || maxPacketLifeTime >= 0) {
            "maxPacketLifeTime must be >= 0"
        }
        require(maxBufferedAmount >= 0) {
            "maxBufferedAmount must be >= 0"
        }
        require(receiveBufferCapacity >= 0) {
            "receiveBufferCapacity must be >= 0"
        }
        require(maxReceiveMessageSize >= 0) {
            "maxReceiveMessageSize must be >= 0"
        }
    }

    public companion object {
        /** Default outgoing buffer limit per channel: 16 MiB. */
        public const val DEFAULT_MAX_BUFFERED_AMOUNT: Long = 16L * 1024 * 1024

        /** Default number of buffered received messages per channel. */
        public const val DEFAULT_RECEIVE_BUFFER_CAPACITY: Int = 8

        /** Default hard limit for a single incoming message: 8 MiB. */
        public const val DEFAULT_MAX_RECEIVE_MESSAGE_SIZE: Int = 8 * 1024 * 1024

        /**
         * Default reliable, ordered channel
         */
        public val Reliable: RTCDataChannelConfig =
            RTCDataChannelConfig(
                ordered = true,
                maxRetransmits = null,
                maxPacketLifeTime = null,
                protocol = "",
                negotiated = false,
                maxBufferedAmount = DEFAULT_MAX_BUFFERED_AMOUNT,
                receiveBufferCapacity = DEFAULT_RECEIVE_BUFFER_CAPACITY,
                maxReceiveMessageSize = DEFAULT_MAX_RECEIVE_MESSAGE_SIZE,
            )
    }
}

public fun rtcDataChannelConfig(block: RTCDataChannelConfigBuilder.() -> Unit): RTCDataChannelConfig {
    val builder = RTCDataChannelConfigBuilder()
    builder.block()
    return builder.build()
}

/** Mutable builder for [RTCDataChannelConfig]. */
public class RTCDataChannelConfigBuilder {
    public var ordered: Boolean = true
    public var maxRetransmits: Int? = null
    public var maxPacketLifeTime: Int? = null
    public var protocol: String = ""
    public var negotiated: Boolean = false

    /**
     * Maximum outgoing bytes buffered before `send` returns `false`.
     * `0` disables the limit. Default: [RTCDataChannelConfig.DEFAULT_MAX_BUFFERED_AMOUNT].
     */
    public var maxBufferedAmount: Long = RTCDataChannelConfig.DEFAULT_MAX_BUFFERED_AMOUNT

    /**
     * Number of received messages buffered for collectors.
     * `0` applies immediate backpressure.
     * Default: [RTCDataChannelConfig.DEFAULT_RECEIVE_BUFFER_CAPACITY].
     */
    public var receiveBufferCapacity: Int = RTCDataChannelConfig.DEFAULT_RECEIVE_BUFFER_CAPACITY

    /**
     * Largest single incoming message accepted; larger messages close the channel
     * with a fatal error. `0` disables the check.
     * Default: [RTCDataChannelConfig.DEFAULT_MAX_RECEIVE_MESSAGE_SIZE].
     */
    public var maxReceiveMessageSize: Int = RTCDataChannelConfig.DEFAULT_MAX_RECEIVE_MESSAGE_SIZE

    /**
     * Sets retransmit limit
     * clears [maxPacketLifeTime]
     */
    public fun maxRetransmits(n: Int) {
        maxRetransmits = n
        maxPacketLifeTime = null
    }

    /**
     * Sets packet lifetime
     * clears [maxRetransmits]
     */
    public fun maxPacketLifeTime(millis: Int) {
        maxPacketLifeTime = millis
        maxRetransmits = null
    }

    /**
     * Marks the channel as unordered
     */
    public fun unordered() {
        ordered = false
    }

    internal fun build(): RTCDataChannelConfig =
        RTCDataChannelConfig(
            ordered,
            maxRetransmits,
            maxPacketLifeTime,
            protocol,
            negotiated,
            maxBufferedAmount,
            receiveBufferCapacity,
            maxReceiveMessageSize,
        )
}
