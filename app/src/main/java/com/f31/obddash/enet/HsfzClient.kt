package com.f31.obddash.enet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * BMW ENET diagnostics (HSFZ protocol) over the Wi-Fi ENET adapter.
 *
 * Frame: [length u32][control u16][source][target][UDS bytes...]
 * length = 2 + UDS length (source + target + data).
 * The gateway first echoes our request (control 0x0002), then sends the
 * ECU answer (control 0x0001, source = ECU, target = tester).
 */
class HsfzClient(private val network: Network?) : Closeable {

    companion object {
        const val DIAG_PORT = 6801
        const val DISCOVERY_PORT = 6811
        const val TESTER = 0xF4
        const val CTRL_DIAG = 0x0001
        const val CTRL_VEHICLE_IDENT = 0x0011
        const val CTRL_ALIVE_REQ = 0x0040      // gateway: "are you still there?"
        const val CTRL_ALIVE_RESP = 0x0041     // we must answer, or the gateway drops the connection
        val CTRL_NACK = setOf(0x0003, 0x0043, 0x00FF)  // gateway can't route to that ECU

        const val ECU_DME = 0x12
        const val ECU_EGS = 0x18
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    /** Average request round-trip (ms), exponential moving average. */
    @Volatile var avgLatencyMs: Double = 0.0
        private set

    private fun noteLatency(t0: Long) {
        val ms = (System.nanoTime() - t0) / 1e6
        avgLatencyMs = if (avgLatencyMs == 0.0) ms else avgLatencyMs * 0.8 + ms * 0.2
    }

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    fun connect(host: String, timeoutMs: Int = 3000) {
        val s = Socket()
        network?.bindSocket(s)
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, DIAG_PORT), timeoutMs)
        s.soTimeout = 1500
        socket = s
        input = DataInputStream(BufferedInputStream(s.getInputStream()))
        output = s.getOutputStream()
    }

    /** Answers that arrived while we were waiting for another ECU (src to UDS response). */
    private val inbox = ArrayDeque<Pair<Int, ByteArray>>()
    private val sentAt = HashMap<Int, Long>()

    /** Sends one UDS request to [target] ECU and returns the UDS response (positive or 7F negative). */
    @Synchronized
    fun request(target: Int, uds: ByteArray, timeoutMs: Long = 1500): ByteArray {
        send(target, uds)
        return await(target, uds, timeoutMs)
    }

    /**
     * Sends without waiting. Use with [await] to have several ECUs (e.g. gearbox and engine)
     * work on requests at the same time. Never send two requests to the same ECU at once.
     */
    @Synchronized
    fun send(target: Int, uds: ByteArray) {
        val out = output ?: throw IOException("Not connected")
        val len = uds.size + 2
        val frame = ByteBuffer.allocate(6 + len)
            .putInt(len)
            .putShort(CTRL_DIAG.toShort())
            .put(TESTER.toByte())
            .put(target.toByte())
            .put(uds)
            .array()
        sentAt[target] = System.nanoTime()
        out.write(frame)
        out.flush()
    }

    private fun matches(target: Int, uds: ByteArray, src: Int, resp: ByteArray): Boolean {
        val functional = target == 0xDF || target == 0x33
        if (!functional && src != target) return false
        val sid = uds[0].toInt() and 0xFF
        val rsid = resp[0].toInt() and 0xFF
        if (rsid == 0x7F) return resp.size >= 3 && (resp[1].toInt() and 0xFF) == sid
        if (rsid != sid + 0x40) return false
        // OBD Mode 01: PID must match; ReadDataByIdentifier: DID must match (skip late answers)
        if (sid == 0x01 && uds.size >= 2 && (resp.size < 2 || resp[1] != uds[1])) return false
        if (sid == 0x22 && uds.size >= 3 && (resp.size < 3 || resp[1] != uds[1] || resp[2] != uds[2])) return false
        return true
    }

    /** Waits for the answer to a request sent earlier with [send]. */
    @Synchronized
    fun await(target: Int, uds: ByteArray, timeoutMs: Long = 1500): ByteArray {
        // already arrived while waiting for someone else?
        val it = inbox.iterator()
        while (it.hasNext()) {
            val (src, resp) = it.next()
            if (matches(target, uds, src, resp)) {
                it.remove(); sentAt[target]?.let { t -> noteLatency(t) }; return resp
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            socket?.soTimeout = left.toInt().coerceAtLeast(1)
            val (ctrl, payload) = readFrame()
            if (ctrl == CTRL_ALIVE_REQ) { answerAlive(); continue }
            if (ctrl in CTRL_NACK) throw SocketTimeoutException("Gateway cannot reach ECU %02X".format(target))
            if (ctrl != CTRL_DIAG || payload.size < 3) continue            // ack etc.
            val src = payload[0].toInt() and 0xFF
            val dst = payload[1].toInt() and 0xFF
            if (dst != TESTER) continue
            val resp = payload.copyOfRange(2, payload.size)
            if (resp.size >= 3 && (resp[0].toInt() and 0xFF) == 0x7F && (resp[2].toInt() and 0xFF) == 0x78) continue // pending
            if (matches(target, uds, src, resp)) {
                sentAt[target]?.let { t -> noteLatency(t) }
                return resp
            }
            inbox.addLast(src to resp)
            while (inbox.size > 16) inbox.removeFirst()
        }
        throw SocketTimeoutException("No response from ECU %02X".format(target))
    }

    private fun answerAlive() {
        val out = output ?: return
        out.write(byteArrayOf(0, 0, 0, 2, 0, CTRL_ALIVE_RESP.toByte(), 0, TESTER.toByte()))
        out.flush()
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val inp = input ?: throw IOException("Not connected")
        val len = inp.readInt()
        val ctrl = inp.readUnsignedShort()
        if (len < 0 || len > 65536) throw IOException("Bad HSFZ frame length $len")
        val payload = ByteArray(len)
        inp.readFully(payload)
        return ctrl to payload
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }
}

object EnetDiscovery {
    data class Vehicle(val ip: String, val vin: String?)

    /** Broadcasts the HSFZ "vehicle identification" request and returns the first car that answers. */
    fun discover(network: Network?, timeoutMs: Int = 2500): Vehicle? {
        DatagramSocket().use { sock ->
            network?.bindSocket(sock)
            sock.broadcast = true
            sock.soTimeout = 500
            val req = byteArrayOf(0, 0, 0, 0, 0, HsfzClient.CTRL_VEHICLE_IDENT.toByte())
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() - lastSend > 800) {
                    sock.send(
                        DatagramPacket(
                            req, req.size,
                            InetAddress.getByName("255.255.255.255"), HsfzClient.DISCOVERY_PORT
                        )
                    )
                    lastSend = System.currentTimeMillis()
                }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (pkt.length <= 6) continue // our own broadcast echo
                val text = String(pkt.data, 6, pkt.length - 6, Charsets.ISO_8859_1)
                if (!text.contains("DIAGADR") && !text.contains("BMW")) continue
                val vin = Regex("BMWVIN([A-Z0-9]{17})").find(text)?.groupValues?.get(1)
                return Vehicle(pkt.address.hostAddress ?: continue, vin)
            }
        }
        return null
    }
}

object NetworkUtil {
    /**
     * The ENET Wi-Fi has no internet, so Android may route sockets over mobile data.
     * We bind our sockets to the Wi-Fi network explicitly.
     */
    @Suppress("DEPRECATION")
    fun findWifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = replace(Regex("[^0-9A-Fa-f]"), "")
    require(clean.isNotEmpty() && clean.length % 2 == 0) { "Hex must have an even number of digits" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
