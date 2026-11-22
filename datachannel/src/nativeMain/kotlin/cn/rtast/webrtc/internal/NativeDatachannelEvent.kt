/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-04
 */

package cn.rtast.webrtc.internal

internal sealed interface NativeDatachannelEvent {
    object Open : NativeDatachannelEvent

    object Closing : NativeDatachannelEvent

    object Closed : NativeDatachannelEvent

    data class TextMessage(
        val text: String,
    ) : NativeDatachannelEvent

    data class BinaryMessage(
        val data: ByteArray,
    ) : NativeDatachannelEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as BinaryMessage
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    object BufferedLow : NativeDatachannelEvent

    value class Error(
        val message: String,
    ) : NativeDatachannelEvent
}
