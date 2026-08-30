package app.gamenative.linux

import android.content.Context
import app.gamenative.utils.retireLinuxShortcuts
import kotlinx.coroutines.Dispatchers
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
 * Only ever takes back or repairs, never adds. Publishing is the user's decision, and an
 * application they removed an entry for should not have one reappear because it is still installed.
 */
object LinuxAppReconciler {

    /**
     * Reconciles against [apps], the result of a scan.
     *
     * Runs after a scan rather than on a schedule: apt runs in our own terminal, so the moment the
     * user can see a change is the moment we can too, and a background pass would only remove
     * things while they were not looking.
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

    /** Takes back everything we have published, for when the userland itself is going away. */
    suspend fun retireAll(context: Context) = reconcile(context, emptyList())

    /** Takes back the all-apps entries that no longer match an entry in the userland. */
    private suspend fun reconcileStubs(context: Context, apps: List<LinuxAppScanner.LinuxApp>) {
        val records = LinuxAppStubRegistry.all(context)
        if (records.isEmpty()) return

        val installed = LinuxAppStubs.installed(context, records.map { it.packageName })
        val byEntry = apps.associateBy { it.entryId }

        for (record in records) {
            // Removed by the user in Settings. Their decision stands, and forgetting it here is
            // what stops a later change to the entry from quietly reinstalling it.
            if (record.packageName !in installed) {
                Timber.i("[LinuxAppReconciler]: %s is gone from the device, forgetting it", record.packageName)
                LinuxAppStubRegistry.forget(context, record.entryId)
                continue
            }

            val app = byEntry[record.entryId]
            if (app == null) {
                Timber.i("[LinuxAppReconciler]: %s no longer exists, taking its entry back", record.entryId)
                LinuxAppStubs.remove(context, record.entryId, record.packageName)
                continue
            }

            if (LinuxAppStubs.contentFingerprint(context, app) != record.fingerprint) {
                Timber.i("[LinuxAppReconciler]: %s changed, republishing its entry", record.entryId)
                LinuxAppStubs.add(context, app)
                    .onFailure { Timber.w(it, "[LinuxAppReconciler]: could not republish %s", record.entryId) }
            }
        }
    }
}
