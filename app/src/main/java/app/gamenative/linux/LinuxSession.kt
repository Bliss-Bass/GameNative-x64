package app.gamenative.linux

import android.content.Context
import android.view.View

/**
 * A running Linux graphical session, as everything outside the `linux` package sees one.
 *
 * The registry, the service and the UI deal in this rather than in [LinuxDisplaySession] because
 * the thing behind it is going to be replaced: today a session is Xtigervnc with its frames
 * carried over RFB, and the plan is to point it at the same X server the games run on, which has
 * GLX, DRI3 and shared pixmaps that Xvnc can never have. That swap should cost a new
 * implementation of this interface and nothing else.
 *
 * There is deliberately no `resize` here. An X screen's size is negotiated by the attached
 * viewer -- [createView]'s result asks for the size it has been given and the server answers --
 * so a method on the session would only forward to whichever view happens to be attached, and
 * would invite two viewers to fight over one screen.
 */
interface LinuxSession {

    /** X display number, as in ":10". */
    val display: Int

    val isRunning: Boolean

    /** Runs [argv] against this session's display, for launching applications into it. */
    fun run(argv: String)

    /** Retells the session Android's density, so running clients resize their text. */
    fun setDpi(densityDpi: Int)

    /**
     * A view showing this session, which the caller must hand back to [releaseView].
     *
     * [onClosed] reports the viewer ending: a null failure means the session closed normally
     * (the last window in it exited), anything else that the transport broke.
     */
    fun createView(context: Context, onClosed: (Throwable?) -> Unit): View

    /** Detaches a view from [createView]. The session itself keeps running. */
    fun releaseView(view: View)

    /** Ends the session and everything running in it. */
    fun stop()
}
