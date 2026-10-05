package com.shilapi.xcertplay.network

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Passively counts mDNS multicast datagrams (224.0.0.251 and ff02::fb) arriving on the hotspot
 * interface, split per address family. "foreign" counts datagrams whose source is not one of the
 * interface's own addresses, so a non-zero foreign count proves another station's Bonjour traffic
 * reaches Android user space, while a persistent zero leaves Wi-Fi association and multicast
 * delivery in question. Only counts are exported; packet content, names and addresses never are.
 */
internal class ApMdnsTrafficProbe(
    interfaceName: String?,
    ownAddresses: Set<InetAddress> = defaultOwnAddresses(interfaceName),
    openV4: (() -> MulticastSocket)? = { openMdnsSocket(interfaceName, IpVersion.V4) },
    openV6: (() -> MulticastSocket)? = { openMdnsSocket(interfaceName, IpVersion.V6) },
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val v4 = FamilyCounters("v4")
    private val v6 = FamilyCounters("v6")
    private var previousSampleNs: Long? = null
    private val workers = mutableListOf<Thread>()

    init {
        openV4?.let { v4.attach(it, ownAddresses, closed) }?.let(workers::add)
        openV6?.let { v6.attach(it, ownAddresses, closed) }?.let(workers::add)
    }

    @Synchronized
    fun snapshot(): String {
        val now = nowNs()
        val elapsed = previousSampleNs?.let { ((now - it).coerceAtLeast(0)) / 1_000_000 }
        previousSampleNs = now
        if (elapsed == null) return "apMdns=baseline"
        val parts = listOf(v4, v6).map { it.delta() }
        val packets = parts.sumOf { it.packets }
        val foreign = parts.sumOf { it.foreign }
        val family = parts.joinToString(" ") { it.familyText }
        return "apMdns windowMs=$elapsed packets=$packets foreign=$foreign $family"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (family in listOf(v4, v6)) family.close()
        for (worker in workers) worker.interrupt()
    }

    private class FamilyCounters(private val label: String) {
        private val packets = AtomicLong()
        private val foreign = AtomicLong()
        private val failureClass = AtomicReference<String?>()
        private var previousPackets = 0L
        private var previousForeign = 0L
        private var socket: MulticastSocket? = null

        fun attach(
            open: () -> MulticastSocket,
            ownAddresses: Set<InetAddress>,
            closed: AtomicBoolean,
        ): Thread? {
            val opened = try {
                open()
            } catch (error: Exception) {
                failureClass.set(error.javaClass.simpleName)
                null
            }
            socket = opened ?: return null
            return Thread({
                receive(opened, ownAddresses, closed)
            }, "diplay-ap-mdns-probe-$label").apply {
                isDaemon = true
                start()
            }
        }

        private fun receive(socket: MulticastSocket, ownAddresses: Set<InetAddress>, closed: AtomicBoolean) {
            val buffer = ByteArray(512)
            while (!closed.get()) {
                val datagram = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(datagram)
                } catch (_: IOException) {
                    if (!closed.get()) failureClass.set("receive")
                    return
                }
                val source = datagram.address ?: continue
                packets.incrementAndGet()
                if (source !in ownAddresses) foreign.incrementAndGet()
            }
        }

        fun delta(): FamilyDelta {
            val failure = failureClass.get()
            if (failure != null && packets.get() == 0L) {
                return FamilyDelta(0, 0, "$label=unavailable failureClass=$failure")
            }
            val packetsNow = packets.get()
            val foreignNow = foreign.get()
            val packetDelta = (packetsNow - previousPackets).coerceAtLeast(0)
            val foreignDelta = (foreignNow - previousForeign).coerceAtLeast(0)
            previousPackets = packetsNow
            previousForeign = foreignNow
            return FamilyDelta(
                packetDelta,
                foreignDelta,
                "$label=$packetDelta/$foreignDelta" + if (failure != null) " failureClass=$failure" else "",
            )
        }

        fun close() {
            runCatching { socket?.close() }
        }
    }

    private class FamilyDelta(val packets: Long, val foreign: Long, val familyText: String)

    private enum class IpVersion { V4, V6 }

    private companion object {
        private const val MDNS_V4_GROUP = "224.0.0.251"
        private const val MDNS_V6_GROUP = "ff02::fb"

        fun defaultOwnAddresses(interfaceName: String?): Set<InetAddress> =
            interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
                ?.let { Collections.list(it.inetAddresses).toSet() }
                ?: emptySet()

        fun openMdnsSocket(interfaceName: String?, version: IpVersion): MulticastSocket {
            val network = interfaceName?.let {
                NetworkInterface.getByName(it)
                    ?: throw IOException("Hotspot interface is unavailable")
            } ?: throw IOException("Hotspot interface name is unavailable")
            val address = interfaceAddress(network, version)
            val group = if (version == IpVersion.V4) {
                InetAddress.getByName(MDNS_V4_GROUP)
            } else {
                val linkLocal = address as? Inet6Address
                    ?: throw IOException("No IPv6 link-local address on the hotspot interface")
                Inet6Address.getByAddress(null, InetAddress.getByName(MDNS_V6_GROUP).address, linkLocal.scopeId)
            }
            return MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(address, 0))
                joinGroup(InetSocketAddress(group, 0), network)
            }
        }

        private fun interfaceAddress(network: NetworkInterface, version: IpVersion): InetAddress {
            val addresses = Collections.list(network.inetAddresses)
            return when (version) {
                IpVersion.V4 -> addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?: throw IOException("No IPv4 address on the hotspot interface")
                IpVersion.V6 -> addresses.firstOrNull { it is Inet6Address && it.isLinkLocalAddress && it.scopeId > 0 }
                    ?: throw IOException("No IPv6 link-local address on the hotspot interface")
            }
        }
    }
}
