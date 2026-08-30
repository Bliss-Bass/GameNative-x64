package app.gamenative.stub;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Hands whatever this stub's manifest names to GameNative, then gets out of the way.
 *
 * The same class in every stub: what differs is the meta-data, so nothing here needs generating
 * per entry, and the dex can be shipped prebuilt. Two kinds of entry go through it -- a Linux
 * application, named by the command to run, and an installed game, named by its id and store.
 */
public class LaunchActivity extends Activity {

    private static final String HOST_PACKAGE = "app.gamenative";

    /** A Linux application: the command to run in the userland. */
    private static final String META_ARGV = "app.gamenative.stub.ARGV";
    private static final String ACTION_LINUX = "app.gamenative.action.LINUX_DESKTOP";
    private static final String EXTRA_ARGV = "linux_argv";

    /** A game: its numeric id within the store it came from, and that store's name. */
    private static final String META_APP_ID = "app.gamenative.stub.APP_ID";
    private static final String META_GAME_SOURCE = "app.gamenative.stub.GAME_SOURCE";
    private static final String ACTION_GAME = "app.gamenative.LAUNCH_GAME";
    private static final String EXTRA_APP_ID = "app_id";
    private static final String EXTRA_GAME_SOURCE = "game_source";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        Bundle meta = metaData();
        Intent intent = meta == null ? null : intentFor(meta);

        if (intent == null) {
            // Nothing to do and nothing to show: a stub with no target is one we built wrong, and
            // the user cannot act on that.
            Toast.makeText(this, "This entry is no longer valid", Toast.LENGTH_LONG).show();
            finish();
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
            return intent;
        }

        return null;
    }
}
