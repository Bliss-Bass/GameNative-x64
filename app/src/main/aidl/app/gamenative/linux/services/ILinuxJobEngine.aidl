// Runs the Linux jobs the Linux Services addon defines: background services, terminal programs and
// GUI applications in GameNative's rootfs, restarted when they exit.
//
// The rootfs and every guest process belong to GameNative's uid, so no other app can run them; the
// addon owns the definitions, the schedule and the window placement, and this owns the processes.
// Only the addon may bind: see LinuxJobCallers for the check, which is by signature rather than by
// a permission, since a permission a user app declares cannot vouch for a privileged caller.
//
// Jobs and states cross as JSON rather than parcelables so the two sides, built in different trees,
// need share nothing but this file. A copy lives in the addon; keep them identical.
package app.gamenative.linux.services;

import android.app.PendingIntent;
import app.gamenative.linux.services.ILinuxJobCallback;

interface ILinuxJobEngine {

    /** Bumped when a method is added, so the addon can tell what this build understands. */
    int apiVersion();

    /**
     * Replaces the job definitions with [jobsJson], a JSON array of jobs.
     *
     * Jobs no longer listed are stopped and forgotten, and running jobs whose definition changed
     * are restarted with the new one. Nothing is started otherwise: when to run is the addon's
     * call. Returns null on success, or why the document was refused.
     */
    String syncJobs(String jobsJson);

    /**
     * Starts [id], clearing a failed state. A GUI or terminal job is started by handing its
     * window back through ILinuxJobCallback.onLaunchRequest, since only the addon knows which
     * display and window mode it belongs in. Returns false for an unknown or disabled job.
     */
    boolean start(String id);

    /** Stops [id] and everything it started, and keeps it from restarting. */
    boolean stop(String id);

    /** Every job's state, as a JSON array. */
    String status();

    /** [id]'s log, read-only, or null when it has none yet. */
    ParcelFileDescriptor openLog(String id);

    /** The window for GUI or terminal job [id], without marking it started. Null for services. */
    PendingIntent guiLaunchIntent(String id);

    /**
     * Every guest process, as a JSON object: "processes" (pid, ppid, name, cmdline, status of
     * running / sleeping / paused / stale / zombie, rssKb, cpuSeconds, cpuPercent since the last
     * call, and owner: a job id, "session" or "orphan") and "jobs" (each job's state with its
     * process count, memory, CPU, and whether it is stale -- said to be running with no process --
     * or paused).
     */
    String inspect();

    /**
     * Ends guest process [pid], for clearing what inspect() reports as stale. Refuses anything
     * that is not one of this app's guest processes. A job's process ended this way counts as an
     * exit, so its restart policy applies; stop the job to keep it down.
     */
    boolean endProcess(int pid);

    void registerCallback(ILinuxJobCallback callback);

    void unregisterCallback(ILinuxJobCallback callback);

    // --- API 2. Appended, so the transaction codes above keep their values. ---

    /**
     * The Linux environment, as a JSON object: supported (this CPU can run it), installed,
     * displaySession (xterm and GUI apps can open), available (jobs can run), installing, progress
     * {message, fraction 0-1 or -1}, error (the last install's failure), requiredBytes, freeBytes.
     */
    String environment();

    /**
     * Installs the environment without GameNative's UI, or completes a partial one. Progress and
     * the outcome arrive through ILinuxJobCallback.onEnvironment. Returns false when this device
     * cannot run it or an install is already under way.
     */
    boolean installEnvironment();

    /** A new xterm window in the environment, or null when it has no graphical session yet. */
    PendingIntent terminalIntent();
}
