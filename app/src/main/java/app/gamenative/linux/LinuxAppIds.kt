package app.gamenative.linux

import app.gamenative.stubs.Stubs

/**
 * How a Linux app's id names its distro.
 *
 * An app's id ([LinuxAppScanner.LinuxApp.entryId]) is its desktop entry's name, prefixed with
 * `<distro>:` for every distro but Ubuntu. That id already travels everywhere an app is named --
 * stub meta-data, launch intents, session keys, the stub registry, shortcuts -- so the prefix is
 * enough to launch an app in its own distro, and everything made before there were several
 * distros (plain names) keeps meaning Ubuntu.
 */
object LinuxAppIds {

    private const val SEPARATOR = ':'

    fun qualify(distroId: String, entry: String): String =
        if (distroId == LinuxDistroCatalog.UBUNTU) entry else "$distroId$SEPARATOR$entry"

    /** The distro [id] belongs to; Ubuntu for a plain name, a command-only launch or nothing. */
    fun distroOf(id: String?): String {
        if (id == null || SEPARATOR !in id) return LinuxDistroCatalog.UBUNTU
        val prefix = id.substringBefore(SEPARATOR)
        return prefix.takeIf { LinuxDistroCatalog.byId(it) != null } ?: LinuxDistroCatalog.UBUNTU
    }

    /** [id] without its distro prefix: the desktop entry's own name. */
    fun entryOf(id: String): String =
        if (distroOf(id) == LinuxDistroCatalog.UBUNTU) id else id.substringAfter(SEPARATOR)

    /**
     * The stub package for [id]. Ubuntu's keep the names they always had; other distros get the
     * distro as its own segment, so `debian:foo` and an Ubuntu entry named `debian-foo` (both
     * `debian_foo` once sanitised) cannot collide.
     */
    fun stubPackage(id: String): String {
        val distro = distroOf(id)
        if (distro == LinuxDistroCatalog.UBUNTU) return Stubs.packageNameFor(id)
        val entry = entryOf(id).lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return "${Stubs.packageNameFor(distro)}.$entry"
    }
}
