package app.gamenative.linux

import com.winlator.core.ProcessHelper
import java.io.File
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Ends a guest program and everything it started.
 *
 * What we hold a [Process] for is PRoot, not the program: PRoot spawns the guest binary, which
 * spawns whatever it likes, and all of it runs as this app's uid. Killing only the handle leaves
 * the rest behind -- an X server was found still serving, with its window manager and its client,
 * long after the screen that started them had gone and the app had logged the session as stopped.
 *
 * PRoot is asked to take its tracee down with it (`--kill-on-exit`) and does so when it exits of
 * its own accord. It cannot when we kill it, because a killed process runs no cleanup, so the
 * descendants are enumerated from /proc and ended here rather than left to it.
 *
 * Killing the process *group* would be the obvious alternative and is not available: children
 * spawned through ProcessBuilder stay in the app's own group, so signalling the group would kill
 * the app.
 */
object GuestProcesses {

    /** Long enough for an X server to remove its lock file and clients to notice the close. */
    private const val TERM_GRACE_MS = 1_500L

    private const val KILL_GRACE_MS = 500L

    /**
     * Ends [process] -- the PRoot host, whose pid is [pid] -- and its guest processes.
     *
     * SIGTERM first and from the leaves inward, so an X server writes out and unlinks its lock
     * and its clients see a clean disconnect instead of a severed socket. SIGKILL is the second
     * pass, for anything that ignored the first: PRoot itself does, its signal mask has SIGTERM
     * blocked, which is why terminating the handle alone never worked.
     */
    fun end(process: Process, pid: Int, label: String) {
        val guests = descendantsOf(pid)
        Timber.i("[GuestProcesses]: stopping %s (proot pid %d, %d guest processes)", label, pid, guests.size)

        (guests + pid).forEach { runCatching { ProcessHelper.terminateProcess(it) } }

        if (!process.waitFor(TERM_GRACE_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
        }

        // The guests are not children of ours, so no waitFor covers them; whatever is still in
        // /proc after the host has gone has to be killed by pid.
        val survivors = (guests + pid).filter(::isAlive)
        survivors.forEach { runCatching { ProcessHelper.killProcess(it) } }

        report(process, pid, guests, survivors, label)
    }

    /**
     * Says what actually happened, because this is the step that was silently failing: the log
     * used to claim a session had stopped while every process in it was still running.
     */
    private fun report(process: Process, pid: Int, guests: List<Int>, killed: List<Int>, label: String) {
        val exit = runCatching { process.exitValue().toString() }.getOrElse { "no exit status" }
        val stubborn = (guests + pid).filter(::isAlive)

        if (stubborn.isEmpty()) {
            Timber.i("[GuestProcesses]: %s stopped (proot exit %s, %d killed)", label, exit, killed.size)
        } else {
            Timber.w(
                "[GuestProcesses]: %s left %d processes alive after SIGKILL: %s (proot exit %s)",
                label, stubborn.size, stubborn.joinToString(), exit,
            )
        }
    }

    /**
     * Every process below [pid], deepest first.
     *
     * Ordering matters for the SIGTERM pass: an X server told to go away before its clients
     * takes their connections with it, and openbox in particular treats that as fatal.
     */
    fun descendantsOf(pid: Int): List<Int> {
        val children = childrenByParent()
        val ordered = mutableListOf<Int>()

        // Breadth first from the host, then reversed, which puts the leaves at the front.
        var frontier = children[pid].orEmpty()
        while (frontier.isNotEmpty()) {
            ordered += frontier
            frontier = frontier.flatMap { children[it].orEmpty() }
        }
        return ordered.reversed()
    }

    /** /proc read once, since walking a tree by re-reading each status file races itself. */
    private fun childrenByParent(): Map<Int, List<Int>> {
        val children = mutableMapOf<Int, MutableList<Int>>()

        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val parent = parentOf(pid) ?: return@forEach
            children.getOrPut(parent) { mutableListOf() } += pid
        }
        return children
    }

    private fun parentOf(pid: Int): Int? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("PPid:") }
                ?.substringAfter("PPid:")
                ?.trim()
                ?.toIntOrNull()
        }
    }.getOrNull()

    /**
     * Whether [pid] is still there. Vulnerable to pid reuse in principle; not in practice on the
     * scale of the milliseconds between killing something and asking.
     */
    private fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()
}
