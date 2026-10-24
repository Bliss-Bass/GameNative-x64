package app.gamenative.stub;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Hands whatever this stub's manifest names to GameNative.
 *
 * The same class in every stub: what differs is the meta-data, so nothing here needs generating
 * per entry, and the dex can be shipped prebuilt. Two kinds of entry go through it -- a Linux
 * application, named by the command to run, and an installed game, named by its id and store.
 *
 * A game is handed over and this activity finishes. A Linux application's window opens in this
 * activity's own task instead, with this activity left underneath, invisible, as the task's root.
 * Android names and badges a task after its root, so the window frame, the taskbar and recents
 * then show this stub's label and icon -- the application's -- rather than GameNative's.
 */
public class LaunchActivity extends Activity {

    private static final String HOST_PACKAGE = "app.gamenative";

    /** A Linux application: the command to run in the userland. */
    private static final String META_ARGV = "app.gamenative.stub.ARGV";
    /**
     * Which desktop entry that command came from. The host keys a running session on it, so
     * launching this entry again finds the session it already has instead of starting a second
     * copy on another display, and Android files the window's task under it.
     */
    private static final String META_ENTRY_ID = "app.gamenative.stub.ENTRY_ID";
    private static final String ACTION_LINUX = "app.gamenative.action.LINUX_DESKTOP";
    private static final String EXTRA_ARGV = "linux_argv";
    private static final String EXTRA_ENTRY_ID = "linux_entry_id";
    private static final String EXTRA_LABEL = "linux_label";
    /**
     * GameNative's entry for a Linux window opened in this task. Started for a result, which is
     * what tells the host which package is asking, and what tells this activity the window closed.
     */
    private static final String HOST_WINDOW_ACTIVITY = HOST_PACKAGE + ".ui.LinuxWindowEntryActivity";
    private static final int REQUEST_WINDOW = 1;
    private static final String STATE_HOSTING = "hosting_window";

    /** Whether the Linux window this stub opened is still above it. */
    private boolean hostingWindow;

    /** A game: its numeric id within the store it came from, and that store's name. */
    private static final String META_APP_ID = "app.gamenative.stub.APP_ID";
    private static final String META_GAME_SOURCE = "app.gamenative.stub.GAME_SOURCE";
    private static final String ACTION_GAME = "app.gamenative.LAUNCH_GAME";
    private static final String EXTRA_APP_ID = "app_id";
    private static final String EXTRA_GAME_SOURCE = "game_source";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        if (state != null && state.getBoolean(STATE_HOSTING)) {
            // Recreated under a window that is still open; its result will say when to go.
            hostingWindow = true;
            return;
        }

        Bundle meta = metaData();
        Intent intent = meta == null ? null : intentFor(meta);

        if (intent == null) {
            // Nothing to do and nothing to show: a stub with no target is one we built wrong, and
            // the user cannot act on that.
            Toast.makeText(this, "This entry is no longer valid", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (ACTION_LINUX.equals(intent.getAction()) && openWindowHere(intent)) {
            return;
        }

        intent.setClassName(HOST_PACKAGE, HOST_PACKAGE + ".MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            startActivity(intent);
        } catch (Exception error) {
            // The host being gone is the one failure a user can act on, and a stub with no UI of
            // its own has nowhere else to say so.
            Toast.makeText(this, "GameNative is not installed", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    /**
     * Opens the Linux window in this task, above this activity. False when the host predates the
     * entry activity, which leaves the caller to hand over through MainActivity as before.
     */
    private boolean openWindowHere(Intent linux) {
        Intent window = new Intent(linux).setClassName(HOST_PACKAGE, HOST_WINDOW_ACTIVITY);
        try {
            startActivityForResult(window, REQUEST_WINDOW);
            hostingWindow = true;
            return true;
        } catch (Exception older) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // The window closed, or never opened; either way there is nothing left to host. An empty
        // task would linger in recents under the application's name, and desktop mode relaunches
        // the tasks on a desk when it comes forward, which would open the application again.
        if (requestCode == REQUEST_WINDOW) finishAndRemoveTask();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(STATE_HOSTING, hostingWindow);
    }

    private Bundle metaData() {
        try {
            return getPackageManager().getActivityInfo(
                    getComponentName(), PackageManager.GET_META_DATA).metaData;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Cannot happen: this is our own component.
            return null;
        }
    }

    /**
     * The launch this stub stands for, or null if its meta-data names neither kind.
     *
     * The game id is read as a string and parsed here: meta-data carries it as a string either
     * way, and the host wants an int extra.
     */
    private Intent intentFor(Bundle meta) {
        String appId = meta.getString(META_APP_ID);
        if (appId != null) {
            try {
                Intent intent = new Intent(ACTION_GAME);
                intent.putExtra(EXTRA_APP_ID, Integer.parseInt(appId.trim()));
                intent.putExtra(EXTRA_GAME_SOURCE, meta.getString(META_GAME_SOURCE));
                return intent;
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        String argv = meta.getString(META_ARGV);
        if (argv != null) {
            Intent intent = new Intent(ACTION_LINUX);
            intent.putExtra(EXTRA_ARGV, argv);
            // Absent in stubs built before entry ids were carried, which the host allows for.
            intent.putExtra(EXTRA_ENTRY_ID, meta.getString(META_ENTRY_ID));
            // This stub's own label, which is the application's name: what the host shows for the
            // window and in its running-sessions notification.
            intent.putExtra(EXTRA_LABEL,
                    getApplicationInfo().loadLabel(getPackageManager()).toString());
            return intent;
        }

        return null;
    }
}
