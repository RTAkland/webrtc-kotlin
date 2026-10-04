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
    value class TextMessage(val text: String) : NativeDatachannelEvent
    value class BinaryMessage(val data: ByteArray) : NativeDatachannelEvent
    object BufferedLow : NativeDatachannelEvent
    value class Error(val message: String) : NativeDatachannelEvent
}