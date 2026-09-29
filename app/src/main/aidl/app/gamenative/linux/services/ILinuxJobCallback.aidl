// What the job engine tells the Linux Services addon. A copy lives in the addon; keep them identical.
package app.gamenative.linux.services;

import android.app.PendingIntent;

oneway interface ILinuxJobCallback {

    /** [id] changed state; [stateJson] is the same object ILinuxJobEngine.status lists. */
    void onJobState(String id, String stateJson);

    /**
     * GUI or terminal job [id] wants its window opened: on start, and again after a backoff when
     * its restart policy brings it back. The addon sends [launch] on the job's display and in its
     * window mode.
     */
    void onLaunchRequest(String id, in PendingIntent launch);

    /** API 2: the environment changed, or an install moved on; the object environment() returns. */
    void onEnvironment(String stateJson);
}
