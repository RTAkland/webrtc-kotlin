/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-11-22
 */

package test

import cn.rtast.webrtc.RTCPeerConnectionFactory
import cn.rtast.webrtc.configuration.RTCIceTransportPolicy
import cn.rtast.webrtc.configuration.RTCLogLevel
import cn.rtast.webrtc.configuration.rtcConfiguration
import cn.rtast.webrtc.createDataChannel
import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class LocalDataChannelTest {
    private val factory = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)

    @Test
    fun localLoopbackMessages() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val config =
                    rtcConfiguration {
                        iceTransportPolicy = RTCIceTransportPolicy.ALL
                    }
                val sigA = LoopbackSignaling()
                val sigB = LoopbackSignaling()
                sigA.peer = sigB
                sigB.peer = sigA

                val pcA = factory.createPeerConnection(scope, config)
                val pcB = factory.createPeerConnection(scope, config)
                pcA.localDescriptions.onEach { sigA.sendSdp(it) }.launchIn(scope)
                pcA.localCandidates.onEach { sigA.sendCandidate(it) }.launchIn(scope)
                sigA.remoteDescriptions.onEach { (val type, val sdp) -> pcA.setRemoteDescription(sdp, type) }
                    .launchIn(scope)
                sigA.remoteCandidates.onEach { pcA.addRemoteCandidate(it) }.launchIn(scope)
                pcB.localDescriptions.onEach { sigB.sendSdp(it) }.launchIn(scope)
                pcB.localCandidates.onEach { sigB.sendCandidate(it) }.launchIn(scope)
                sigB.remoteDescriptions
                    .onEach { (val type, val sdp) ->
                        pcB.setRemoteDescription(sdp, type)
                        if (type == "offer") pcB.createAnswer()
                    }.launchIn(scope)
                sigB.remoteCandidates.onEach { pcB.addRemoteCandidate(it) }.launchIn(scope)

                val texts = mutableListOf<String>()
                val blobs = mutableListOf<ByteArray>()
                val ready = CompletableDeferred<Unit>()
                val received = CompletableDeferred<Unit>()
                val textBatch = 300
                val blobBatch = 300
                val expectedTexts = 2 + textBatch
                val expectedBlobs = 2 + blobBatch

                pcB.incomingDataChannels
                    .take(1)
                    .onEach { dc ->
                        // Undispatched start guarantees both subscriptions are
                        // registered before `ready` lets the sender proceed.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            dc.messages.text.collect {
                                texts += it
                                if (texts.size >= expectedTexts && blobs.size >= expectedBlobs) {
                                    received.complete(Unit)
                                }
                            }
                        }
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            dc.messages.bytes.collect {
                                blobs += it
                                if (texts.size >= expectedTexts && blobs.size >= expectedBlobs) {
                                    received.complete(Unit)
                                }
                            }
                        }
                        // SharedFlow has no replay, so wait until both collectors are attached.
                        ready.complete(Unit)
                    }.launchIn(scope)

                val dcA = pcA.createDataChannel("local-test")
                withTimeout(30.seconds) { dcA.state.first { it == RTCDataChannelState.Open } }
                withTimeout(30.seconds) { ready.await() }

                val small = byteArrayOf(0x00, 0x01, 0x7F, -1, -128)
                val large = ByteArray(128 * 1024) { (it * 31).toByte() }
                dcA.send("hello")
                dcA.send("")
                dcA.send(small)
                dcA.send(large)
                repeat(textBatch) { dcA.send("msg-$it") }
                repeat(blobBatch) {
                    dcA.send(ByteArray(64) { index -> (it + index).toByte() })
                }

                withTimeout(30.seconds) { received.await() }
                assertEquals(listOf("hello", "") + List(textBatch) { "msg-$it" }, texts)
                assertEquals(expectedBlobs, blobs.size)
                assertContentEquals(small, blobs[0])
                assertContentEquals(large, blobs[1])
                repeat(blobBatch) { i ->
                    assertContentEquals(ByteArray(64) { index -> (i + index).toByte() }, blobs[2 + i])
                }

                dcA.close()
                pcA.close()
                pcB.close()
                sigA.close()
                sigB.close()
            } finally {
                scope.cancel()
                factory.close()
            }
        }
}
