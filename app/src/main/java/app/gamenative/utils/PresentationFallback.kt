package app.gamenative.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.PresentationPath
import app.gamenative.ui.util.SnackbarManager
import com.winlator.container.Container
import com.winlator.xserver.XServer
import com.winlator.xserver.extensions.DRI3Extension
import timber.log.Timber

/**
 * Resolves [PresentationPath.AUTO] per game: Direct (DRI3), or Software copy once a session's DRI3
 * buffer imports were all rejected. The fallback is tied to the app's versionCode, so an update
 * tries DRI3 again instead of leaving the game on the slow path for good.
 */
object PresentationFallback {
    const val EXTRA_KEY = "presentationFallback"
    private const val SOFTWARE_PREFIX = "software@"

    @JvmStatic
    fun resolve(chosen: PresentationPath, fallback: String?, versionCode: Int): PresentationPath = when {
        chosen != PresentationPath.AUTO -> chosen
        fallback == "$SOFTWARE_PREFIX$versionCode" -> PresentationPath.SOFTWARE
        else -> PresentationPath.DRI3
    }

    @JvmStatic
    fun forLaunch(container: Container?): PresentationPath =
        resolve(PrefManager.presentationPath, container?.getExtra(EXTRA_KEY), BuildConfig.VERSION_CODE)

    /**
     * Watch this session's DRI3 imports when Automatic picked DRI3 for it. A rejected import with no
     * successful one before it records the fallback; a successful import clears an old one.
     */
    @JvmStatic
    fun watch(context: Context, xServer: XServer, container: Container, launched: PresentationPath?) {
        val dri3 = xServer.getExtension<DRI3Extension>(DRI3Extension.MAJOR_OPCODE.toInt()) ?: return
        val automatic = PrefManager.presentationPath == PresentationPath.AUTO &&
            !HostBionicLibs.isPresentationOverridden()
        if (!automatic || launched != PresentationPath.DRI3) {
            dri3.setImportListener(null)
            return
        }
        dri3.setImportListener(Session(context.applicationContext, container))
    }

    private class Session(
        private val context: Context,
        private val container: Container,
    ) : DRI3Extension.ImportListener {
        private val main = Handler(Looper.getMainLooper())

        @Volatile private var imported = false

        @Volatile private var fellBack = false

        override fun onImport(imported: Boolean) {
            if (imported) {
                if (this.imported) return
                this.imported = true
                main.post { clear() }
            } else if (!this.imported && !fellBack) {
                fellBack = true
                main.post { record() }
            }
        }

        private fun clear() {
            if (container.getExtra(EXTRA_KEY).isEmpty()) return
            container.putExtra(EXTRA_KEY, null)
            container.saveData()
            Timber.i("PresentationFallback: DRI3 imports work again for %s", container.id)
        }

        private fun record() {
            if (imported) return
            container.putExtra(EXTRA_KEY, "$SOFTWARE_PREFIX${BuildConfig.VERSION_CODE}")
            container.saveData()
            Timber.w("PresentationFallback: DRI3 import rejected for %s; next launch uses software", container.id)
            SnackbarManager.show(context.getString(R.string.present_auto_fell_back))
        }
    }
}
