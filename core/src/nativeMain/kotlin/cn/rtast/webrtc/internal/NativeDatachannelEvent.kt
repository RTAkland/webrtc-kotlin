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

    object BufferedLow : NativeDatachannelEvent

    data class Error(
        val message: String,
    ) : NativeDatachannelEvent
}
