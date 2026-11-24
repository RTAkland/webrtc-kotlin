/*
 * Copyright © 2026 RTAkland
 * Author: RTAkland
 * Date: 2026-11-24
 */

package test

import cn.rtast.webrtc.configuration.RTCTransport
import cn.rtast.webrtc.configuration.rtcConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

class IceServerUrlTest {
    @Test
    fun builderAcceptsHostsWithOrWithoutScheme() {
        val config =
            rtcConfiguration {
                stun("stun:stun.example.com", 80)
                turn("turn:turn.example.com", 3478, "user", "pass", RTCTransport.TCP)
                stun("stun2.example.com")
                turn("turns:secure.example.com", 443, "user", "pass", tls = true)
            }

        assertEquals("stun:stun.example.com:80?transport=udp", config.iceServers[0].toString())
        assertEquals("turn:user:pass@turn.example.com:3478?transport=tcp", config.iceServers[1].toString())
        assertEquals("stun:stun2.example.com:3478?transport=udp", config.iceServers[2].toString())
        assertEquals("turns:user:pass@secure.example.com:443?transport=udp", config.iceServers[3].toString())
    }
}
