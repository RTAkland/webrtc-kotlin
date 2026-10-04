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

    public fun send(text: String): Boolean
    public fun send(data: ByteArray): Boolean
    public fun close(): Boolean
    public fun setBufferedAmountLowThreshold(threshold: Int): Boolean

}