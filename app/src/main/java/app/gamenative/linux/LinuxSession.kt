package app.gamenative.linux

import android.content.Context
import android.view.View

/**
 * A running Linux graphical session, as everything outside the `linux` package sees one.
 *
 * The registry, the service and the UI deal in this rather than in a concrete session because
 * the transport behind it has already moved once (Xtigervnc/RFB → Android [com.winlator.xserver.XServer])
 * and may move again. Callers should not assume RFB or a particular display number layout.
 *
 * Freeform Android windows call [resize] so the X screen matches the client area and GTK/Qt
 * reflow. Android X updates [com.winlator.xserver.ScreenInfo] and top-level geometry; the RFB
 * path already had desktop-size encoding (and may no-op here if the view drives resize).
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
     * Matches the X screen (and APP-mode top-levels) to the Android freeform client area so
     * guests reflow instead of being scaled/squashed by the presenter.
     *
     * Implementations should treat this as "make it so": apply now and re-check a few times
     * against the live frame size, because freeform + SurfaceView + GTK settle on different
     * clocks and a single apply is often missed after several resizes.
     */
    fun resize(width: Int, height: Int)

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
