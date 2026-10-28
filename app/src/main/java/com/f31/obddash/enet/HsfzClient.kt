package com.f31.obddash.enet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import com.f31.obddash.data.OBD_PID_LEN

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
    private var input: InputStream? = null

    /**
     * Receive buffer. Bytes stay here until a whole HSFZ frame has arrived, so a timeout
     * in the middle of a frame never loses data (that used to desync the stream and drop
     * the connection on slower Wi-Fi like the head unit's).
     */
    private var rbuf = ByteArray(4096)
    private var rlen = 0
    private var output: OutputStream? = null

    /** Average request round-trip (ms), exponential moving average. */
    @Volatile var avgLatencyMs: Double = 0.0
        private set

    /** Per-ECU average answer time and late-answer count. */
    private val ecuLatency = HashMap<Int, Double>()
    val lateByEcu = HashMap<Int, Int>()

    private fun noteLatency(t0: Long, ecu: Int = -1) {
        val ms = (System.nanoTime() - t0) / 1e6
        avgLatencyMs = if (avgLatencyMs == 0.0) ms else avgLatencyMs * 0.8 + ms * 0.2
        if (ecu >= 0) ecuLatency[ecu] = ecuLatency[ecu]?.let { it * 0.8 + ms * 0.2 } ?: ms
    }

    /** How long to wait for [ecu]: a few times its usual answer time, 150–400 ms. */
    @Synchronized
    fun timeoutFor(ecu: Int): Long =
        ((ecuLatency[ecu] ?: 100.0) * 6).toLong().coerceIn(200, 400)

    @Synchronized
    fun latencyReport(): String =
        ecuLatency.entries.sortedBy { it.key }.joinToString("  ") { (e, ms) ->
            "%02X: %.0f ms, late %d".format(e, ms, lateByEcu[e] ?: 0)
        }

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    fun connect(host: String, timeoutMs: Int = 3000) {
        val s = Socket()
        network?.bindSocket(s)
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, DIAG_PORT), timeoutMs)
        s.soTimeout = 1500
        socket = s
        input = s.getInputStream()
        rlen = 0
        output = s.getOutputStream()
    }

    private val sentAt = HashMap<Int, Long>()

    /** Answers from an ECU we asked recently, that came too late (after we stopped waiting). Dropped. */
    @Volatile var lateAnswers = 0
        private set

    /** Answers from an ECU we never asked — a sign that another tester (phone, BimmerLink…) is connected. */
    @Volatile var foreignAnswers = 0
        private set

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
        // several PIDs in one request: the answer must contain exactly those PIDs
        // (two requests may start with the same PID, so the first one alone proves nothing)
        if (sid == 0x01 && uds.size > 2) {
            val want = (1 until uds.size).map { uds[it].toInt() and 0xFF }.toSet()
            val got = HashSet<Int>()
            var i = 1
            while (i < resp.size) {
                val pid = resp[i].toInt() and 0xFF
                val len = OBD_PID_LEN[pid] ?: return got.isEmpty() || got.all { it in want }
                got += pid
                i += 1 + len
            }
            if (got.any { it !in want }) return false
        }
        if (sid == 0x22 && uds.size >= 3 && (resp.size < 3 || resp[1] != uds[1] || resp[2] != uds[2])) return false
        return true
    }

    /** Waits for the answer to a request sent earlier with [send]. */
    @Synchronized
    fun await(target: Int, uds: ByteArray, timeoutMs: Long = 1500): ByteArray {
        // Late answers to earlier requests are never kept: re-using one as the answer to a
        // newer request made the data lag a cycle behind and caused a chain of timeouts.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            socket?.soTimeout = left.toInt().coerceAtLeast(20)
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
                sentAt[target]?.let { t -> noteLatency(t, src) }
                return resp
            }
            val askedAt = sentAt[src]
            if (askedAt != null && System.nanoTime() - askedAt < 10_000_000_000L) {
                lateAnswers++
                lateByEcu[src] = (lateByEcu[src] ?: 0) + 1
            }
            else foreignAnswers++
        }
        throw SocketTimeoutException("No response from ECU %02X".format(target))
    }

    /**
     * After a timeout: wait (up to [maxMsAfterSend] after the request was sent) for the late
     * answer from [target] and throw it away. Sending more requests meanwhile only queues
     * them up in the gateway, which made the stall longer.
     */
    @Synchronized
    fun drain(target: Int, maxMsAfterSend: Long) {
        val sent = sentAt[target] ?: return
        val deadlineNs = sent + maxMsAfterSend * 1_000_000L
        while (true) {
            val leftMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (leftMs <= 0) return
            socket?.soTimeout = leftMs.toInt().coerceAtLeast(20)
            val (ctrl, payload) = try { readFrame() } catch (e: SocketTimeoutException) { return }
            if (ctrl == CTRL_ALIVE_REQ) { answerAlive(); continue }
            if (ctrl != CTRL_DIAG || payload.size < 3) continue
            if ((payload[1].toInt() and 0xFF) != TESTER) continue
            val src = payload[0].toInt() and 0xFF
            if (payload.size >= 5 && (payload[2].toInt() and 0xFF) == 0x7F && (payload[4].toInt() and 0xFF) == 0x78) continue
            lateAnswers++
            lateByEcu[src] = (lateByEcu[src] ?: 0) + 1
            if (src == target) return
        }
    }

    private fun answerAlive() {
        val out = output ?: return
        out.write(byteArrayOf(0, 0, 0, 2, 0, CTRL_ALIVE_RESP.toByte(), 0, TESTER.toByte()))
        out.flush()
    }

    /** Takes one complete frame out of the buffer, or null if it hasn't fully arrived yet. */
    private fun takeFrame(): Pair<Int, ByteArray>? {
        if (rlen < 6) return null
        val len = ((rbuf[0].toInt() and 0xFF) shl 24) or ((rbuf[1].toInt() and 0xFF) shl 16) or
            ((rbuf[2].toInt() and 0xFF) shl 8) or (rbuf[3].toInt() and 0xFF)
        val ctrl = ((rbuf[4].toInt() and 0xFF) shl 8) or (rbuf[5].toInt() and 0xFF)
        if (len < 0 || len > 65536) throw IOException("Bad HSFZ frame length $len")
        if (rlen < 6 + len) return null
        val payload = rbuf.copyOfRange(6, 6 + len)
        val rest = rlen - 6 - len
        System.arraycopy(rbuf, 6 + len, rbuf, 0, rest)
        rlen = rest
        return ctrl to payload
    }

    /**
     * Reads one frame. A SocketTimeoutException may be thrown while waiting, but whatever
     * part of a frame already arrived stays in [rbuf] for the next call.
     */
    private fun readFrame(): Pair<Int, ByteArray> {
        val inp = input ?: throw IOException("Not connected")
        while (true) {
            takeFrame()?.let { return it }
            if (rlen == rbuf.size) rbuf = rbuf.copyOf(rbuf.size * 2)
            val n = inp.read(rbuf, rlen, rbuf.size - rlen)
            if (n < 0) throw IOException("Connection closed by the car/adapter")
            rlen += n
        }
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null; rlen = 0
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
    /** IPv4 address the screen/phone has on [net] (to see if it is on the car adapter's Wi-Fi). */
    fun ipv4(ctx: Context, net: Network?): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        if (net == null) return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.mapNotNull { it.address as? java.net.Inet4Address }
            ?.firstOrNull()?.hostAddress
    }

    fun sameSubnet(a: String?, b: String?): Boolean {
        if (a == null || b == null) return true
        return a.substringBeforeLast('.') == b.substringBeforeLast('.')
    }

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
