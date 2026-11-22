/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-11-22
 */

@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

@file:Suppress("ktlint:standard:no-wildcard-imports")

package test

import cn.rtast.webrtc.*
import cn.rtast.webrtc.configuration.RTCDataChannelConfig
import cn.rtast.webrtc.configuration.RTCLogLevel
import cn.rtast.webrtc.configuration.rtcConfiguration
import cn.rtast.webrtc.configuration.rtcDataChannelConfig
import cn.rtast.webrtc.state.RTCDataChannelState
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MemorySafetyTest {
    private class PeerPair(
        val pcA: RTCPeerConnection,
        val pcB: RTCPeerConnection,
        val sigA: LoopbackSignaling,
        val sigB: LoopbackSignaling,
        val incoming: Channel<RTCDataChannel>,
    ) {
        fun close() {
            pcA.close()
            pcB.close()
            sigA.close()
            sigB.close()
            incoming.close()
        }
    }

    private fun openPair(
        scope: CoroutineScope,
        factory: RTCPeerConnectionFactory,
    ): PeerPair {
        val sigA = LoopbackSignaling()
        val sigB = LoopbackSignaling()
        sigA.peer = sigB
        sigB.peer = sigA

        val config = rtcConfiguration {}
        val pcA = factory.createPeerConnection(scope, config)
        val pcB = factory.createPeerConnection(scope, config)
        pcA.localDescriptions.onEach { sigA.sendSdp(it) }.launchIn(scope)
        pcA.localCandidates.onEach { sigA.sendCandidate(it) }.launchIn(scope)
        sigA.remoteDescriptions.onEach { (val type, val sdp) -> pcA.setRemoteDescription(sdp, type) }.launchIn(scope)
        sigA.remoteCandidates.onEach { pcA.addRemoteCandidate(it) }.launchIn(scope)
        pcB.localDescriptions.onEach { sigB.sendSdp(it) }.launchIn(scope)
        pcB.localCandidates.onEach { sigB.sendCandidate(it) }.launchIn(scope)
        sigB.remoteDescriptions
            .onEach { (val type, val sdp) ->
                pcB.setRemoteDescription(sdp, type)
                if (type == "offer") pcB.createAnswer()
            }.launchIn(scope)
        sigB.remoteCandidates.onEach { pcB.addRemoteCandidate(it) }.launchIn(scope)

        val incoming = Channel<RTCDataChannel>(Channel.UNLIMITED)
        pcB.incomingDataChannels.onEach { incoming.trySend(it) }.launchIn(scope)
        return PeerPair(pcA, pcB, sigA, sigB, incoming)
    }

    private suspend fun openChannel(
        pair: PeerPair,
        config: RTCDataChannelConfig,
    ): Pair<RTCDataChannel, RTCDataChannel> {
        val dcA = pair.pcA.createDataChannel("mem-test", config)
        withTimeout(30.seconds) { dcA.state.first { it == RTCDataChannelState.Open } }
        val dcB = withTimeout(30.seconds) { pair.incoming.receive() }
        return dcA to dcB
    }

    @Test
    fun sendStopsAtConfiguredBufferLimit() =
        runBlocking {
            val factory = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val pair = openPair(scope, factory)
                val cap = 256L * 1024
                val [dcA, dcB] = openChannel(pair, rtcDataChannelConfig { maxBufferedAmount = cap })

                // Stall the receive side so the sender's outgoing buffer fills up.
                val slowCollector =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        dcB.messages.bytes.collect { delay(50.milliseconds) }
                    }

                val chunk = ByteArray(16 * 1024)
                val rssBefore = readRssBytes()
                var sent = 0
                var rejected = 0
                var attempts = 0
                while (attempts < 1024) {
                    if (dcA.send(chunk)) {
                        sent++
                    } else {
                        rejected++
                        if (rejected >= 16) break
                    }
                    attempts++
                }
                val buffered = dcA.bufferedAmount.value
                GC.collect()
                delay(100.milliseconds)
                val rssAfter = readRssBytes()
                println(
                    "[cap] sent=${sent * chunk.size / 1024 / 1024} MiB rejected=$rejected " +
                            "buffered=${buffered / 1024} KiB rssDelta=${(rssAfter - rssBefore) / 1024 / 1024} MiB",
                )

                assertTrue(rejected > 0, "send never hit the ${cap / 1024} KiB limit; buffered=$buffered")
                assertTrue(
                    buffered <= cap + chunk.size,
                    "buffered=$buffered exceeded cap=$cap plus one chunk",
                )
                assertTrue(
                    rssAfter - rssBefore < 64L * 1024 * 1024,
                    "RSS grew by ${(rssAfter - rssBefore) / 1024 / 1024} MiB during the cap test",
                )

                slowCollector.cancelAndJoin()
                val resumed = withTimeoutOrNull(30.seconds) { dcA.sendAwait(chunk) }
                assertEquals(true, resumed, "sendAwait did not resume after backpressure cleared")

                pair.close()
            } finally {
                scope.cancel()
                factory.close()
            }
        }

    @Test
    fun repeatedChannelLifecycleDoesNotGrowMemory() =
        runBlocking {
            val factory = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val pair = openPair(scope, factory)
                val payloadPerCycle = 4 * 1024 * 1024
                val chunk = ByteArray(128 * 1024) { 0x5A }

                suspend fun cycle() {
                    val [dcA, dcB] = openChannel(pair, RTCDataChannelConfig.Reliable)
                    val received = CompletableDeferred<Unit>()
                    var receivedBytes = 0L
                    val collector =
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            dcB.messages.bytes.collect {
                                receivedBytes += it.size
                                if (receivedBytes >= payloadPerCycle && !received.isCompleted) {
                                    received.complete(Unit)
                                }
                            }
                        }
                    repeat(payloadPerCycle / chunk.size) {
                        assertTrue(dcA.sendAwait(chunk), "send failed")
                    }
                    withTimeout(60.seconds) { received.await() }
                    collector.cancelAndJoin()
                    dcA.close()
                    withTimeout(30.seconds) { dcB.state.first { it == RTCDataChannelState.Closed } }
                }

                repeat(3) { cycle() }
                GC.collect()
                delay(100.milliseconds)

                var previous = readRssBytes()
                val deltas = mutableListOf<Long>()
                repeat(3) {
                    repeat(8) { cycle() }
                    GC.collect()
                    delay(100.milliseconds)
                    val current = readRssBytes()
                    deltas += current - previous
                    previous = current
                }
                println("[lifecycle] rss deltas MiB=${deltas.map { it / 1024 / 1024 }}")
                deltas.forEachIndexed { index, delta ->
                    assertTrue(
                        delta < 24L * 1024 * 1024,
                        "RSS grew by ${delta / 1024 / 1024} MiB in segment $index",
                    )
                }

                pair.close()
            } finally {
                scope.cancel()
                factory.close()
            }
        }

    @Test
    fun closingOneFactoryDoesNotAffectOthers() {
        val first = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)
        val second = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            first.close()
            first.close() // idempotent
            val pc = second.createPeerConnection(scope, rtcConfiguration {})
            assertTrue(!pc.isClosed, "second factory was broken by closing the first one")
            pc.close()
        } finally {
            scope.cancel()
            second.close()
        }
    }

    @Test
    fun repeatedPeerConnectionLifecycleDoesNotGrowMemory() =
        runBlocking {
            val factory = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)

            suspend fun cycle() {
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                try {
                    val pair = openPair(scope, factory)
                    val [dcA, dcB] = openChannel(pair, RTCDataChannelConfig.Reliable)
                    val received = CompletableDeferred<Unit>()
                    val collector =
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            dcB.messages.bytes.collect { received.complete(Unit) }
                        }
                    assertTrue(dcA.sendAwait(ByteArray(64 * 1024)), "send failed")
                    withTimeout(30.seconds) { received.await() }
                    collector.cancelAndJoin()
                    pair.close()
                } finally {
                    scope.cancel()
                }
            }

            try {
                repeat(3) { cycle() }
                GC.collect()
                delay(100.milliseconds)

                var previous = readRssBytes()
                val deltas = mutableListOf<Long>()
                repeat(3) {
                    repeat(5) { cycle() }
                    GC.collect()
                    delay(100.milliseconds)
                    val current = readRssBytes()
                    deltas += current - previous
                    previous = current
                }
                println("[pc-lifecycle] rss deltas MiB=${deltas.map { it / 1024 / 1024 }}")
                deltas.forEachIndexed { index, delta ->
                    assertTrue(
                        delta < 24L * 1024 * 1024,
                        "RSS grew by ${delta / 1024 / 1024} MiB in segment $index",
                    )
                }
            } finally {
                factory.close()
            }
        }

    @Test
    fun oversizedIncomingMessageClosesChannel() =
        runBlocking {
            val factory = RTCPeerConnectionFactory(RTCLogLevel.DISABLED)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val pair = openPair(scope, factory)
                val [dcA, dcB] = openChannel(pair, rtcDataChannelConfig { maxReceiveMessageSize = 32 * 1024 })
                val error = CompletableDeferred<RTCException>()
                factory.onError {
                    if (it.message.startsWith("incoming message too large")) error.complete(it)
                }

                // Data channels are bidirectional: B sends back over the same stream,
                // and A enforces the 32 KiB receive limit configured on its object.
                assertTrue(dcB.send(ByteArray(64 * 1024)), "sending the oversized message failed")

                val reported = withTimeout(30.seconds) { error.await() }
                assertTrue(reported.fatal, "oversized message should be a fatal error")
                withTimeout(30.seconds) {
                    dcA.state.first { it == RTCDataChannelState.Closing || it == RTCDataChannelState.Closed }
                }

                pair.close()
            } finally {
                factory.onError { }
                scope.cancel()
                factory.close()
            }
        }

    private fun readRssBytes(): Long =
        memScoped {
            val file = fopen("/proc/self/status", "r") ?: return@memScoped 0L
            try {
                val line = allocArray<ByteVar>(256)
                while (fgets(line, 256, file) != null) {
                    val text = line.toKString()
                    if (text.startsWith("VmRSS:")) {
                        val kb =
                            text
                                .removePrefix("VmRSS:")
                                .trim()
                                .substringBefore(' ')
                                .toLong()
                        return@memScoped kb * 1024
                    }
                }
                0L
            } finally {
                fclose(file)
            }
        }
}
