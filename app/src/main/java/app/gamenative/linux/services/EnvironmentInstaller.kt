package app.gamenative.linux.services

import android.content.Context
import android.os.StatFs
import app.gamenative.linux.LinuxDistroCatalog
import app.gamenative.linux.LinuxDistros
import app.gamenative.linux.LinuxGraphics
import app.gamenative.linux.LinuxProgramLauncher
import app.gamenative.linux.LinuxRootfs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Sets up the Linux environment without GameNative's UI, for the Linux Services addon: its
 * Settings screen, its Linux Terminal launcher entry, and MDMs provisioning a device.
 *
 * Does what the Terminal screen's install button does -- [LinuxRootfs.install], then
 * [LinuxRootfs.ensureDisplaySession] -- when no supported distro is installed yet, and otherwise
 * gets the default distro ready for windows; reports progress through [listener] instead of a
 * progress bar. While it runs, [installing] holds [LinuxJobService] in the foreground: the download
 * is over a gigabyte, and a cached app would be frozen or killed partway through it.
 */
object EnvironmentInstaller {

    interface Listener {
        fun onEnvironment(state: JSONObject)
    }

    @Volatile
    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    @Volatile private var progress: LinuxRootfs.Progress? = null
    @Volatile private var error: String? = null
    @Volatile private var lastPublishMs = 0L

    /**
     * The environment as a JSON object: whether this device can run it at all, whether it is
     * installed and has its graphical session, whether jobs can run, and any install in progress
     * (with its step and a 0-1 fraction, or -1 when a step has no measure) or the last failure.
     */
    fun state(context: Context): JSONObject = JSONObject().apply {
        // Any supported distro is an environment jobs can run in, not only Ubuntu.
        val installed = LinuxDistros.integrated(context).isNotEmpty()
        val defaultId = LinuxDistros.defaultId(context)
        put("supported", LinuxRootfs.isSupported())
        put("installed", installed)
        // Windows in the default distro; another distro gets what windows need when one first opens.
        put("displaySession", installed && LinuxGraphics.isReady(context, defaultId))
        put("available", LinuxProgramLauncher.isAvailable(context))
        put("defaultDistro", defaultId)
        put(
            "distros",
            JSONArray(
                LinuxDistroCatalog.all.map { distro ->
                    val present = LinuxDistros.isInstalled(context, distro.id)
                    JSONObject()
                        .put("id", distro.id)
                        .put("name", distro.displayName)
                        .put("installed", present)
                        // Jobs run only in supported distros; experimental ones are terminal-only.
                        .put("supported", distro.supported)
                        .put("displaySession", present && distro.supported && LinuxGraphics.isReady(context, distro.id))
                },
            ),
        )
        put("installing", _installing.value)
        progress?.takeIf { _installing.value }?.let {
            put("progress", JSONObject().put("message", it.message).put("fraction", it.fraction.toDouble()))
        }
        error?.let { put("error", it) }
        put("requiredBytes", LinuxRootfs.REQUIRED_BYTES)
        put("freeBytes", runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(-1L))
    }

    /**
     * Installs the environment, or completes the graphical session of one that lacks it. Returns
     * false when this device cannot run it or an install is already under way; otherwise the
     * outcome arrives through [listener].
     */
    fun install(context: Context): Boolean {
        val app = context.applicationContext
        if (!LinuxRootfs.isSupported()) return false
        synchronized(this) {
            if (_installing.value) return false
            _installing.value = true
        }
        error = null
        progress = null
        publish(app, force = true)
        scope.launch {
            val result = if (LinuxDistros.integrated(app).isNotEmpty()) {
                // A distro is there already: get the default one ready for windows.
                LinuxGraphics.ensure(app, LinuxDistros.defaultId(app)) { onProgress(app, it) }
            } else {
                LinuxRootfs.install(app) { onProgress(app, it) }
                    .mapCatching { LinuxRootfs.ensureDisplaySession(app) { p -> onProgress(app, p) }.getOrThrow() }
            }
            result.onFailure {
                Timber.w(it, "[EnvironmentInstaller]: install failed")
                error = it.message ?: it.javaClass.simpleName
            }
            progress = null
            _installing.value = false
            publish(app, force = true)
        }
        return true
    }

    private fun onProgress(context: Context, value: LinuxRootfs.Progress) {
        val step = progress?.message != value.message
        progress = value
        publish(context, force = step)
    }

    /** Download progress arrives many times a second; the addon needs a few updates a second at most. */
    private fun publish(context: Context, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMs < 500) return
        lastPublishMs = now
        runCatching { listener?.onEnvironment(state(context)) }
    }
}
