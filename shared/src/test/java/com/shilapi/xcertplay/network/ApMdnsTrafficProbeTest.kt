package com.shilapi.xcertplay.network

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import org.junit.Assert.*
import org.junit.Test

class ApMdnsTrafficProbeTest {
    @Test fun countsPacketsAsForeignWhenSourceIsNotAnOwnAddress() {
        var clock = 0L
        val receiver = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        val probe = ApMdnsTrafficProbe(
            interfaceName = null,
            ownAddresses = emptySet(),
            openV4 = { receiver },
            openV6 = null,
            nowNs = { clock },
        )
        assertEquals("apMdns=baseline", probe.snapshot())
        send(receiver, "127.0.0.1", 3)
        clock = 10_000_000_000L
        val result = waitForPackets(probe, 3)
        assertEquals(3L, result.packets)
        assertEquals(3L, result.foreign)
        assertTrue(result.windows.first().contains("windowMs=10000"))
        assertTrue(result.windows.joinToString().contains("v4="))
        assertFalse(result.windows.joinToString().contains("127.0.0.1"))
        probe.close()
    }

    @Test fun excludesOwnAddressSourcesFromTheForeignCount() {
        var clock = 0L
        val receiver = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        val probe = ApMdnsTrafficProbe(
            interfaceName = null,
            ownAddresses = setOf(InetAddress.getByName("127.0.0.1")),
            openV4 = { receiver },
            openV6 = null,
            nowNs = { clock },
        )
        probe.snapshot()
        send(receiver, "127.0.0.1", 2)
        clock = 10_000_000_000L
        val result = waitForPackets(probe, 2)
        assertEquals(2L, result.packets)
        assertEquals(0L, result.foreign)
        probe.close()
    }

    @Test fun countsIPv6PacketsSeparately() {
        val receiver = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
        }
        var clock = 0L
        val probe = ApMdnsTrafficProbe(
            interfaceName = null,
            ownAddresses = emptySet(),
            openV4 = null,
            openV6 = { receiver },
            nowNs = { clock },
        )
        assertTrue(probe.snapshot().contains("apMdns=baseline"))
        send(receiver, "::1", 2)
        clock = 10_000_000_000L
        val result = waitForPackets(probe, 2)
        assertEquals(2L, result.packets)
        assertEquals(2L, result.foreign)
        assertTrue(result.windows.joinToString().contains("v6="))
        probe.close()
    }

    @Test fun openFailureReportsUnavailableWithoutExposingDetails() {
        val probe = ApMdnsTrafficProbe(
            interfaceName = "wlan1",
            ownAddresses = emptySet(),
            openV4 = { throw IOException("phone name/password/address must stay private") },
            openV6 = null,
        )
        assertTrue(probe.snapshot().contains("apMdns=baseline"))
        val result = probe.snapshot()
        assertTrue(result.contains("v4=unavailable failureClass=IOException"))
        assertFalse(result.contains("phone") || result.contains("wlan1"))
        probe.close()
    }

    @Test fun packetsArrivingBeforeFirstSampleAreIncludedInFirstDelta() {
        val receiver = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        var clock = 0L
        val probe = ApMdnsTrafficProbe(
            interfaceName = null,
            ownAddresses = emptySet(),
            openV4 = { receiver },
            openV6 = null,
            nowNs = { clock },
        )
        send(receiver, "127.0.0.1", 1)
        assertTrue(probe.snapshot().contains("apMdns=baseline"))
        clock = 5_000_000_000L
        val result = waitForPackets(probe, 1)
        assertEquals(1L, result.packets)
        assertEquals(1L, result.foreign)
        assertTrue(result.windows.first().contains("windowMs=5000"))
        probe.close()
    }

    @Test fun closeIsIdempotentAndSnapshotRemainsSafeAfterClose() {
        val receiver = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        val probe = ApMdnsTrafficProbe(
            interfaceName = null,
            ownAddresses = emptySet(),
            openV4 = { receiver },
            openV6 = null,
        )
        probe.close()
        probe.close()
        assertTrue(probe.snapshot().contains("apMdns=baseline"))
    }

    private class Accumulated(val packets: Long, val foreign: Long, val windows: List<String>)

    private fun waitForPackets(probe: ApMdnsTrafficProbe, expected: Long): Accumulated {
        var totalPackets = 0L
        var totalForeign = 0L
        val windows = mutableListOf<String>()
        repeat(200) {
            val snapshot = probe.snapshot()
            windows.add(snapshot)
            totalPackets += Regex("packets=(\\d+)").find(snapshot)?.groupValues?.get(1)?.toLong() ?: 0
            totalForeign += Regex("foreign=(\\d+)").find(snapshot)?.groupValues?.get(1)?.toLong() ?: 0
            if (totalPackets >= expected) return Accumulated(totalPackets, totalForeign, windows)
            Thread.sleep(5)
        }
        throw AssertionError("Probe never counted $expected packets")
    }

    private fun send(receiver: MulticastSocket, target: String, count: Int) {
        val senderAddress = InetAddress.getByName(target)
        val wildcard = if (':' in target) "::" else "0.0.0.0"
        DatagramSocket(InetSocketAddress(InetAddress.getByName(wildcard), 0)).use { sender ->
            val payload = ByteArray(16)
            val address = receiver.localSocketAddress as InetSocketAddress
            repeat(count) {
                sender.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        senderAddress,
                        address.port,
                    ),
                )
            }
        }
    }
}
