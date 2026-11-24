/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-10-01
 */

@file:OptIn(NativeRuntimeApi::class)

@file:Suppress("ktlint:standard:no-wildcard-imports")

package benchmark

import cn.rtast.cryptokotlin.md.md5Digest
import cn.rtast.webrtc.RTCPeerConnectionFactory
import cn.rtast.webrtc.configuration.RTCIceTransportPolicy
import cn.rtast.webrtc.configuration.RTCLogLevel
import cn.rtast.webrtc.configuration.RTCTransport
import cn.rtast.webrtc.configuration.rtcConfiguration
import cn.rtast.webrtc.createDataChannel
import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import test.LoopbackSignaling
import kotlin.concurrent.Volatile
import kotlin.math.round
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class BenchmarkDataChannelThroughput {
    private val totalBytes = 32 * 1024 * 1024
    private val chunkSize = 128 * 1024
    private val backpressureLimit = 8L * 1024 * 1024
    private val seed = Random.nextInt()

    @Volatile
    private var sentBytes: Long = 0L

    @Volatile
    private var recvBytes: Long = 0L

    private val factory = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)

    @Test
    fun benchmarkFileTransfer() =
        runBlocking {
            val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                GC.collect()
                runSingle(rootScope)
            } finally {
                rootScope.cancel()
                factory.close()
            }
        }

    private suspend fun runSingle(parentScope: CoroutineScope) {
        sentBytes = 0L
        recvBytes = 0L
        val runScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        try {
            val sigA = LoopbackSignaling()
            val sigB = LoopbackSignaling()
            sigA.peer = sigB
            sigB.peer = sigA

            val config =
                rtcConfiguration {
                    stun("stun:stun.relay.metered.ca", 80)
                    turn(
                        "turn:global.relay.metered.ca", 80, "df00d64fd158b34176e5d75d", "iIDM2wUVDKavPsqT",
                        RTCTransport.TCP, false,
                    )
                    iceTransportPolicy = RTCIceTransportPolicy.ALL
                }
            val pcA = factory.createPeerConnection(runScope, config)
            val pcB = factory.createPeerConnection(runScope, config)

            pcA.localDescriptions.onEach { sigA.sendSdp(it) }.launchIn(runScope)
            pcA.localCandidates.onEach { sigA.sendCandidate(it) }.launchIn(runScope)
            sigA.remoteDescriptions.onEach { (val type, val sdp) -> pcA.setRemoteDescription(sdp, type) }
                .launchIn(runScope)
            sigA.remoteCandidates.onEach { pcA.addRemoteCandidate(it) }.launchIn(runScope)
            pcB.localDescriptions.onEach { sigB.sendSdp(it) }.launchIn(runScope)
            pcB.localCandidates.onEach { sigB.sendCandidate(it) }.launchIn(runScope)
            sigB.remoteDescriptions
                .onEach { (val type, val sdp) ->
                    pcB.setRemoteDescription(sdp, type)
                    if (type == "offer") pcB.createAnswer()
                }.launchIn(runScope)
            sigB.remoteCandidates.onEach { pcB.addRemoteCandidate(it) }.launchIn(runScope)

            val receivedPayload = CompletableDeferred<ByteArray>()
            val receiveBuffer = ByteArray(totalBytes)
            var receiveOffset = 0

            pcB.incomingDataChannels
                .take(1)
                .onEach { dc ->
                    dc.messages.bytes.collect { bytes ->
                        check(receiveOffset + bytes.size <= receiveBuffer.size) {
                            "received more than $totalBytes bytes"
                        }
                        bytes.copyInto(receiveBuffer, receiveOffset)
                        receiveOffset += bytes.size
                        recvBytes = receiveOffset.toLong()
                        if (receiveOffset == receiveBuffer.size && !receivedPayload.isCompleted) {
                            receivedPayload.complete(receiveBuffer)
                        }
                    }
                }.launchIn(runScope)

            val dcA = pcA.createDataChannel("bench")
            withTimeout(60.seconds) { dcA.state.first { it == RTCDataChannelState.Open } }

            val totalMb = totalBytes / 1024 / 1024
            println("benchmark: total=$totalMb MiB, chunk=${chunkSize / 1024} KiB, seed=$seed")

            val payload = ByteArray(totalBytes)
            Random(seed).nextBytes(payload)
            val sentDigest = md5Digest(payload)
            val sendBuffer = ByteArray(chunkSize)
            var received: ByteArray? = null
            val elapsed =
                measureTime {
                    var offset = 0
                    while (offset < payload.size) {
                        val n = minOf(chunkSize, payload.size - offset)
                        payload.copyInto(sendBuffer, 0, offset, offset + n)

                        while (dcA.bufferedAmount.value > backpressureLimit) {
                            delay(1.milliseconds)
                        }

                        val accepted =
                            if (n == chunkSize) {
                                dcA.send(sendBuffer)
                            } else {
                                dcA.send(sendBuffer.copyOf(n))
                            }
                        check(accepted) {
                            "send failed at $offset bytes (buffered=${dcA.bufferedAmount.value})"
                        }

                        offset += n
                        sentBytes = offset.toLong()
                    }

                    received = withTimeout(180.seconds) { receivedPayload.await() }
                }

            val receivedPayloadBytes = checkNotNull(received) { "receiver completed without payload" }
            check(receivedPayloadBytes.size == totalBytes)
            val recvDigest = md5Digest(receivedPayloadBytes)
            check(sentDigest.contentEquals(recvDigest)) {
                "MD5 mismatch: sent=${sentDigest.toHex()} recv=${recvDigest.toHex()}"
            }
            println("MD5 match: ${sentDigest.toHex()}")

            val seconds = elapsed.inWholeMilliseconds / 1000.0
            val sendMbps = if (seconds > 0) sentBytes / 1024.0 / 1024.0 / seconds else 0.0
            val recvMbps = if (seconds > 0) recvBytes / 1024.0 / 1024.0 / seconds else 0.0
            println(
                "sent=${sentBytes / 1024 / 1024} MiB recv=${recvBytes / 1024 / 1024} MiB " +
                        "time=${seconds.oneDecimal()} s " +
                        "send=${sendMbps.oneDecimal()} MiB/s recv=${recvMbps.oneDecimal()} MiB/s",
            )

            dcA.close()
            pcA.close()
            pcB.close()
            sigA.close()
            sigB.close()
        } finally {
            runScope.cancel()
        }
    }

    private fun ByteArray.toHex(): String =
        buildString(size * 2) {
            for (b in this@toHex) {
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }

    private fun Double.oneDecimal(): String = (round(this * 10) / 10).toString()

    private companion object {
        private const val HEX = "0123456789abcdef"
    }
}
