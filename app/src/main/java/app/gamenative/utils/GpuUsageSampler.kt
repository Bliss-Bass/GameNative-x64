package app.gamenative.utils

import android.os.Process
import android.os.SystemClock
import java.io.File
import timber.log.Timber

/**
 * Logs how busy each of this app's processes keeps the GPU, from the kernel's DRM fdinfo
 * counters. Runs while `debug.gamenative.gpustats=1` is set or the container's Extra debug
 * logging is on, so presentation changes can be measured without Wine tracing slowing the game.
 *
 * Wine children run under the app's uid, so their fdinfo is readable without root.
 */
object GpuUsageSampler {
    private const val TAG = "GpuUsage"
    private const val PROP = "debug.gamenative.gpustats"
    private const val INTERVAL_MS = 2000L

    @Volatile private var forced = false
    private var thread: Thread? = null

    private class Client(val pid: Int, val name: String) {
        // xe: drm-cycles-<engine> against drm-total-cycles-<engine>. i915/amdgpu: drm-engine-<engine> ns.
        val cycles = HashMap<String, Long>()
        val totalCycles = HashMap<String, Long>()
        val engineNs = HashMap<String, Long>()
    }

    @JvmStatic
    @Synchronized
    fun start(extraDebugLogging: Boolean) {
        forced = extraDebugLogging
        if (thread != null) return
        thread = Thread(::run, "GpuUsageSampler").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    @JvmStatic
    fun stop() {
        forced = false
    }

    private fun enabled(): Boolean = forced || systemProperty(PROP) == "1"

    private fun run() {
        var previous: Map<String, Client> = emptyMap()
        var previousAt = 0L
        while (true) {
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!enabled()) {
                previous = emptyMap()
                continue
            }
            val now = SystemClock.elapsedRealtimeNanos()
            val current = sample()
            if (previous.isNotEmpty()) log(previous, current, now - previousAt)
            previous = current
            previousAt = now
        }
    }

    private fun sample(): Map<String, Client> {
        val uid = Process.myUid()
        val clients = HashMap<String, Client>()
        val procs = File("/proc").listFiles() ?: return clients
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (!ownedBy(proc, uid)) continue
            val fdinfo = File(proc, "fdinfo").listFiles() ?: continue
            for (fd in fdinfo) {
                val text = try { fd.readText() } catch (_: Exception) { continue }
                if (!text.contains("drm-client-id")) continue
                parse(text, pid, processName(proc), clients)
            }
        }
        return clients
    }

    private fun parse(text: String, pid: Int, name: String, into: HashMap<String, Client>) {
        var id: String? = null
        var driver = ""
        val pairs = ArrayList<Pair<String, String>>()
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "drm-client-id" -> id = value
                "drm-driver" -> driver = value
                else -> if (key.startsWith("drm-")) pairs.add(key to value)
            }
        }
        val clientId = "$driver:${id ?: return}"
        if (into.containsKey(clientId)) return
        val client = Client(pid, name)
        for ((key, value) in pairs) {
            val number = value.substringBefore(' ').toLongOrNull() ?: continue
            when {
                key.startsWith("drm-total-cycles-") -> client.totalCycles[key.removePrefix("drm-total-cycles-")] = number
                key.startsWith("drm-cycles-") -> client.cycles[key.removePrefix("drm-cycles-")] = number
                key.startsWith("drm-engine-") && !key.startsWith("drm-engine-capacity") ->
                    client.engineNs[key.removePrefix("drm-engine-")] = number
            }
        }
        into[clientId] = client
    }

    private fun log(before: Map<String, Client>, after: Map<String, Client>, wallNs: Long) {
        val lines = ArrayList<String>()
        for ((id, now) in after) {
            val was = before[id] ?: continue
            val busy = LinkedHashMap<String, Double>()
            for ((engine, cycles) in now.cycles) {
                val dc = cycles - (was.cycles[engine] ?: continue)
                val dt = (now.totalCycles[engine] ?: continue) - (was.totalCycles[engine] ?: continue)
                if (dt > 0 && dc > 0) busy[engine] = 100.0 * dc / dt
            }
            for ((engine, ns) in now.engineNs) {
                val dn = ns - (was.engineNs[engine] ?: continue)
                if (wallNs > 0 && dn > 0) busy[engine] = 100.0 * dn / wallNs
            }
            if (busy.isEmpty()) continue
            val engines = busy.entries.joinToString(" ") { "${it.key}=${"%.1f".format(it.value)}%" }
            lines.add("pid=${now.pid} ${now.name} $engines")
        }
        if (lines.isEmpty()) {
            Timber.tag(TAG).i("no GPU activity from this app")
        } else {
            lines.forEach { Timber.tag(TAG).i(it) }
        }
    }

    private fun ownedBy(proc: File, uid: Int): Boolean {
        return try {
            File(proc, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() == uid
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun processName(proc: File): String {
        return try {
            val cmd = File(proc, "cmdline").readText().split('\u0000').filter { it.isNotEmpty() }
            val exe = cmd.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
                ?: cmd.getOrNull(1)?.takeIf { cmd.firstOrNull()?.endsWith("linker64") == true }
                ?: cmd.firstOrNull()
                ?: ""
            exe.substringAfterLast('/').substringAfterLast('\\').ifEmpty { proc.name }
        } catch (_: Exception) {
            proc.name
        }
    }

    private fun systemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
