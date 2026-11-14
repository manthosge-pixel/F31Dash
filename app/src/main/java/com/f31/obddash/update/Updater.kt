package com.f31.obddash.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the GitHub releases of manthosge-pixel/f31dash (same idea as the launcher):
 * check releases/latest → download F31Dash.apk → install over the current app.
 *
 * In the car the Wi-Fi is WIFI_ENET (no internet), so the HTTP calls go over the mobile
 * network when there is one. Only these calls use it: the ENET connection stays on Wi-Fi.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/manthosge-pixel/f31dash/releases/latest"
    private const val ASSET = "F31Dash.apk"

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val current: String) : State()
        data class Available(val current: String, val latest: String, val url: String, val notes: String) : State()
        data class Downloading(val percent: Int) : State()
        data object Installing : State()
        data class Error(val msg: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cellNet: Network? = null
    @Volatile private var pendingApk: File? = null
    private var started = false

    fun init(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { cellNet = n }
                override fun onLost(n: Network) { if (n == cellNet) cellNet = null }
            })
        } catch (_: Exception) { }
    }

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /** quiet = no Error state shown (used for the automatic check at start-up). */
    fun check(ctx: Context, quiet: Boolean = false) {
        val app = ctx.applicationContext
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        if (!quiet) _state.value = State.Checking
        scope.launch {
            val cur = currentVersion(app)
            try {
                val j = JSONObject(httpText("$LATEST?t=${System.currentTimeMillis()}"))
                val tag = j.optString("tag_name").removePrefix("v")
                val assets = j.optJSONArray("assets")
                var url = ""
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == ASSET) url = a.optString("browser_download_url")
                }
                _state.value = if (url.isNotEmpty() && newer(tag, cur))
                    State.Available(cur, tag, url, j.optString("body").take(300))
                else State.UpToDate(cur)
            } catch (e: Exception) {
                _state.value = if (quiet) State.Idle else State.Error("Check failed: ${e.message}")
            }
        }
    }

    fun download(ctx: Context, url: String) {
        val app = ctx.applicationContext
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(0)
        scope.launch {
            try {
                val f = File(app.cacheDir, "update.apk")
                // a dropped connection gives a cut-off APK ("certificates" error): check size + readability, retry 3×
                var ok = false
                var err = ""
                for (attempt in 1..3) {
                    f.delete()
                    var c = open(url)
                    c.instanceFollowRedirects = true
                    var code = c.responseCode
                    var hop = 0
                    while (code in 300..399 && hop++ < 5) {
                        val loc = c.getHeaderField("Location"); c.disconnect()
                        c = open(loc); code = c.responseCode
                    }
                    if (code >= 400) throw Exception("HTTP $code")
                    val want = c.contentLengthLong
                    try {
                        c.inputStream.use { inp ->
                            FileOutputStream(f).use { out ->
                                val buf = ByteArray(65536)
                                var done = 0L
                                while (true) {
                                    val n = inp.read(buf); if (n <= 0) break
                                    out.write(buf, 0, n); done += n
                                    if (want > 0) _state.value = State.Downloading((done * 100 / want).toInt())
                                }
                            }
                        }
                    } catch (e: Exception) { err = e.message ?: "read error" }
                    c.disconnect()
                    val sizeOk = want <= 0 || f.length() == want
                    ok = sizeOk && app.packageManager.getPackageArchiveInfo(f.path, 0) != null
                    if (ok) break
                    if (err.isEmpty()) err = "${f.length() / 1024} of ${if (want > 0) want / 1024 else "?"} KB"
                }
                if (!ok) throw Exception("download incomplete ($err)")
                pendingApk = f
                withContext(Dispatchers.Main) { installPending(app) }
            } catch (e: Exception) {
                _state.value = State.Error("Update failed: ${e.message}")
            }
        }
    }

    /** Called again from onResume, after the user allowed "install unknown apps". */
    fun installPending(ctx: Context) {
        val f = pendingApk ?: return
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Turn on “Allow” and come back", Toast.LENGTH_LONG).show()
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
            return
        }
        pendingApk = null
        _state.value = State.Installing
        val app = ctx.applicationContext
        scope.launch {
            try {
                val pi = app.packageManager.packageInstaller
                val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = pi.createSession(p)
                pi.openSession(id).use { s ->
                    f.inputStream().use { inp ->
                        s.openWrite("app.apk", 0, f.length()).use { out ->
                            inp.copyTo(out, 65536)
                            s.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val cb = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java), flags)
                    s.commit(cb.intentSender)
                }
            } catch (e: Exception) {
                _state.value = State.Error("Install error: ${e.message}")
            }
        }
    }

    internal fun installResult(msg: String?) {
        _state.value = if (msg == null) State.Idle else State.Error(msg)
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val n = cellNet
        val c = (if (n != null) n.openConnection(u) else u.openConnection()) as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "F31Dash-updater")
        return c
    }

    private fun httpText(url: String): String {
        val c = open(url)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = c.responseCode
            if (code == 404) throw Exception("no release published yet")
            if (code >= 400) throw Exception("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** "0.29.12" newer than "0.28"? */
    fun newer(a: String, b: String): Boolean {
        val x = a.split("."); val y = b.split(".")
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val q = y.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (p != q) return p > q
        }
        return false
    }
}

/** Result of the session install. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android asks to confirm ("Update")
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (_: Exception) { }
            }
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Updater.installResult(null)
            Toast.makeText(ctx, "Updated ✓", Toast.LENGTH_LONG).show()
        } else {
            val m = "Install failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Updater.installResult(m)
            Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
        }
    }
}
