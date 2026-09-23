package app.gamenative.linux

import android.content.Context
import app.gamenative.stubs.StubInstallerClient
import app.gamenative.stubs.StubRegistry
import app.gamenative.stubs.Stubs
import app.gamenative.utils.retireLinuxShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Keeps what Android shows in step with what the userland actually has.
 *
 * A Linux app is not installed through us: apt adds and removes entries whenever the user runs it,
 * and nothing tells Android about it. So after every scan we compare what we have published --
 * entries in the all-apps list, shortcuts on the home screen -- against what the scan found, and
 * take back whatever no longer stands for anything.
 *
 * When the ROM's stub installer (the Bliss addon) is present, applications that appear in the
 * userland are published into the all-apps list automatically. Entries the user removed stay
 * suppressed until they add them again from Linux Apps.
 */
object LinuxAppReconciler {

    /** Fire-and-forget scans after a terminal or graphical session where apt may have run. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Reconciles against [apps], the result of a scan.
     *
     * Runs after a scan rather than on a schedule: apt runs in our own terminal (and sometimes
     * inside a desktop session), so the moment the user leaves that session is the moment we can
     * see the change -- without a background pass removing things while they were not looking.
     */
    suspend fun reconcile(context: Context, apps: List<LinuxAppScanner.LinuxApp>) {
        runCatching {
            // A missing userland means every entry is stale, which is different from a scan that
            // found nothing: it is why the scan result alone is not enough to go on.
            val live = if (LinuxRootfs.isInstalled(context)) apps else emptyList()

            reconcileStubs(context, live)
            withContext(Dispatchers.Main) { retireLinuxShortcuts(context, live) }
        }.onFailure { Timber.w(it, "[LinuxAppReconciler]: could not reconcile") }
    }

    /** Scan the userland, then reconcile. Shared by the Linux Apps screen and session hooks. */
    suspend fun scanAndReconcile(context: Context) {
        if (!LinuxRootfs.isInstalled(context)) {
            reconcile(context, emptyList())
            return
        }
        val apps = LinuxAppScanner.scan(context)
        reconcile(context, apps)
    }

    /**
     * Schedules a scan + reconcile on a process-scoped IO job.
     *
     * Call when the user leaves the terminal or a Linux session stops: that is when apt installs
     * from those surfaces become visible without opening Linux Apps.
     */
    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch {
            Timber.i("[LinuxAppReconciler]: session-triggered scan")
            scanAndReconcile(app)
        }
    }

    /** Takes back everything we have published, for when the userland itself is going away. */
    suspend fun retireAll(context: Context) = reconcile(context, emptyList())

    /**
     * Whether silent auto-publish is available: trampoline packaged and the privileged installer
     * on device. Without the installer, each stub would need a user install prompt, which is not
     * what "newly installed appears in the drawer" means.
     */
    private fun canAutoPublish(context: Context): Boolean =
        LinuxAppStubs.isSupported(context) && StubInstallerClient.isAvailable(context)

    /** Takes back, repairs, and (when the addon is present) publishes all-apps entries. */
    private suspend fun reconcileStubs(context: Context, apps: List<LinuxAppScanner.LinuxApp>) {
        val records = StubRegistry.linux.all(context)
        val byEntry = apps.associateBy { it.entryId }
        val installed = if (records.isEmpty()) {
            emptySet()
        } else {
            Stubs.installed(context, records.map { it.packageName })
        }

        for (record in records) {
            // Removed by the user in Settings. Suppress so auto-publish does not put it back.
            if (record.packageName !in installed) {
                Timber.i("[LinuxAppReconciler]: %s is gone from the device, suppressing it", record.packageName)
                StubRegistry.linux.forget(context, record.entryId)
                StubRegistry.linux.suppress(context, record.entryId)
                continue
            }

            val app = byEntry[record.entryId]
            if (app == null) {
                Timber.i("[LinuxAppReconciler]: %s no longer exists, taking its entry back", record.entryId)
                // Gone from apt, not a user drawer preference -- allow auto-publish if reinstalled.
                LinuxAppStubs.remove(context, record.entryId, record.packageName, suppress = false)
                continue
            }

            if (LinuxAppStubs.contentFingerprint(context, app) != record.fingerprint) {
                Timber.i("[LinuxAppReconciler]: %s changed, republishing its entry", record.entryId)
                LinuxAppStubs.add(context, app)
                    .onFailure { Timber.w(it, "[LinuxAppReconciler]: could not republish %s", record.entryId) }
            }
        }

        if (!canAutoPublish(context)) return

        for (app in apps) {
            if (StubRegistry.linux.of(context, app.entryId) != null) continue
            if (StubRegistry.linux.isSuppressed(context, app.entryId)) continue
            Timber.i("[LinuxAppReconciler]: auto-publishing %s into the app list", app.entryId)
            LinuxAppStubs.add(context, app)
                .onFailure { Timber.w(it, "[LinuxAppReconciler]: could not auto-publish %s", app.entryId) }
        }
    }
}
