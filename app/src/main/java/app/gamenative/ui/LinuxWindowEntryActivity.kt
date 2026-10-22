package app.gamenative.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.gamenative.stubs.StubRegistry
import timber.log.Timber

/**
 * Where a Linux application's all-apps stub opens its window from.
 *
 * The stub stays underneath as the root of its task, so Android names the task after the
 * application rather than after GameNative (see the stub's LaunchActivity). That needs the window
 * to open in the stub's task, which means an exported activity here; this one only checks the
 * caller and forwards, so [LinuxSessionActivity] itself stays unexported.
 *
 * Only the stub this app built and registered for the entry gets through: the stub starts this
 * activity for a result, which is what makes [getCallingPackage] available to check.
 */
class LinuxWindowEntryActivity : Activity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val argv = intent.getStringExtra(EXTRA_ARGV)
        val caller = callingPackage
        val registered = entryId?.takeIf { it.isNotBlank() }
            ?.let { StubRegistry.linux.of(this, it)?.packageName }

        if (argv.isNullOrBlank() || caller == null || caller != registered) {
            Timber.w("[LinuxWindowEntryActivity]: refused %s for entry %s (registered %s)", caller, entryId, registered)
            finish()
            return
        }

        val window = LinuxSessionActivity.intent(this, argv, entryId, intent.getStringExtra(EXTRA_LABEL))
            .apply {
                // Into this task, the stub's, rather than a document of its own.
                removeFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                // The stub finishes when its result arrives, which is when the window closes.
                addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
            }
        startActivity(window)
        finish()
    }

    private companion object {
        // The stub's extras, which match MainActivity's for the same launch.
        const val EXTRA_ARGV = "linux_argv"
        const val EXTRA_ENTRY_ID = "linux_entry_id"
        const val EXTRA_LABEL = "linux_label"
    }
}
