/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-09-29
 */

package cn.rtast.webrtc

import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

public expect class RTCDataChannel {
    public val label: String
    public val protocol: String
    public val state: StateFlow<RTCDataChannelState>
    public val bufferedAmount: StateFlow<Long>
    public val bufferedAmountLow: Flow<Unit>
    public val isOpen: Boolean
    public val isClosed: Boolean
    public val availableAmount: Int
    public val messages: MessageFlow

    @Suppress("RedundantInnerClassModifier")
    public inner class MessageFlow internal constructor() {
        public val text: SharedFlow<String>
        public val bytes: SharedFlow<ByteArray>
    }

    /**
     * Sends [text] immediately.
     *
     * Returns `false` if the channel is closing, the configured buffer limit is
     * reached, the message is larger than that limit, or the native send failed;
     * nothing is queued in that case.
     */
    public fun send(text: String): Boolean

    /**
     * Sends [data] immediately. An empty array is a valid empty binary message.
     *
     * Returns `false` if the channel is closing, the configured buffer limit is
     * reached, the message is larger than that limit, or the native send failed;
     * nothing is queued in that case.
     */
    public fun send(data: ByteArray): Boolean

    /**
     * Sends [text] once the outgoing buffer is below the configured limit,
     * waiting without blocking the caller's thread otherwise.
     *
     * If the peer stops draining forever this suspends indefinitely; wrap the call
     * in `withTimeout` when a deadline is required. A message larger than the
     * configured buffer limit is rejected immediately.
     */
    public suspend fun sendAwait(text: String): Boolean

    /**
     * Sends [data] once the outgoing buffer is below the configured limit,
     * waiting without blocking the caller's thread otherwise.
     *
     * If the peer stops draining forever this suspends indefinitely; wrap the call
     * in `withTimeout` when a deadline is required. A message larger than the
     * configured buffer limit is rejected immediately.
     */
    public suspend fun sendAwait(data: ByteArray): Boolean

    public fun close(): Boolean

    public fun setBufferedAmountLowThreshold(threshold: Int): Boolean
}
