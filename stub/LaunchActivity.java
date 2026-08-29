package app.gamenative.stub;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Hands the command in this stub's manifest to GameNative, then gets out of the way.
 *
 * The same class in every stub: what differs is the meta-data, so nothing here needs generating
 * per application, and the dex can be shipped prebuilt.
 */
public class LaunchActivity extends Activity {

    private static final String META_ARGV = "app.gamenative.stub.ARGV";
    private static final String HOST_PACKAGE = "app.gamenative";
    private static final String ACTION = "app.gamenative.action.LINUX_DESKTOP";
    private static final String EXTRA_ARGV = "linux_argv";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        String argv = null;
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(
                    getComponentName(), PackageManager.GET_META_DATA);
            if (info.metaData != null) {
                argv = info.metaData.getString(META_ARGV);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Cannot happen: this is our own component.
        }

        Intent intent = new Intent(ACTION);
        intent.setClassName(HOST_PACKAGE, HOST_PACKAGE + ".MainActivity");
        intent.putExtra(EXTRA_ARGV, argv);
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
}
