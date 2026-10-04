/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-04
 */


package cn.rtast.webrtc.internal

import cn.rtast.webrtc.RTCDataChannel
import cn.rtast.webrtc.state.RTCConnectionState
import cn.rtast.webrtc.state.RTCGatheringState
import cn.rtast.webrtc.state.RTCIceState

internal sealed interface NativePeerConnectionEvent {
    class LocalDescription(val sdp: String, val type: String) : NativePeerConnectionEvent
    class LocalCandidate(val candidate: String, val mid: String) : NativePeerConnectionEvent
    class ConnectionState(val state: RTCConnectionState) : NativePeerConnectionEvent
    class IceState(val state: RTCIceState) : NativePeerConnectionEvent
    class GatheringState(val state: RTCGatheringState) : NativePeerConnectionEvent
    class IncomingChannel(val channel: RTCDataChannel) : NativePeerConnectionEvent
}
