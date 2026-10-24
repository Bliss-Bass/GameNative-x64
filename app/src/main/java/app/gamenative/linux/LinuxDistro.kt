package app.gamenative.linux

/** How a distro installs software, as far as GameNative has to drive it. */
enum class LinuxPackageManager(
    /** Installs packages given as trailing arguments, without prompting. */
    val install: List<String>,
    /** Removes packages given as trailing arguments, without prompting. */
    val remove: List<String>,
    /** Refreshes the package index; null when [install] does it anyway. */
    val refresh: List<String>?,
    /** A guest binary present exactly when this is the distro's package manager. */
    val binary: String,
) {
    APT(
        install = listOf("apt-get", "install", "-y", "--no-install-recommends"),
        remove = listOf("apt-get", "purge", "-y", "--autoremove"),
        refresh = listOf("apt-get", "update"),
        binary = "usr/bin/apt-get",
    ),
    // -Syu rather than a separate -Sy: refreshing the index without upgrading is a partial
    // upgrade, which Arch does not support and which breaks library sonames.
    PACMAN(
        install = listOf("pacman", "-Syu", "--noconfirm", "--needed"),
        remove = listOf("pacman", "-Rns", "--noconfirm"),
        refresh = null,
        binary = "usr/bin/pacman",
    ),
    DNF(
        install = listOf("dnf", "install", "-y", "--setopt=install_weak_deps=False"),
        remove = listOf("dnf", "remove", "-y"),
        refresh = null,
        binary = "usr/bin/dnf",
    ),
    APK(
        install = listOf("apk", "add", "--no-progress"),
        remove = listOf("apk", "del", "--no-progress"),
        refresh = listOf("apk", "update"),
        binary = "sbin/apk",
    ),
}

/**
 * How far GameNative's Linux features go with a distro. Experimental ones install and run in the
 * Linux Terminal, and nothing else: no default distro, desktop, consoles, Linux Apps or browsers.
 */
enum class LinuxDistroSupport { SUPPORTED, EXPERIMENTAL }

/**
 * One installable Linux userland.
 *
 * [graphical] is whether the Android X session, Linux app stubs and app scanning run in it.
 * Only Ubuntu is, because the display stack and its browser sources are built on Ubuntu's
 * archive; the others are shells for the terminal and the Linux consoles, and can still run a
 * full desktop session of their own over VNC.
 *
 * [tarballUrl] is null for Ubuntu, whose install [LinuxRootfs] owns.
 */
data class LinuxDistro(
    val id: String,
    val displayName: String,
    val release: String,
    val packageManager: LinuxPackageManager,
    val graphical: Boolean,
    val tarballUrl: String?,
    val sha256: String?,
    /** Leading path elements to drop, as tar's --strip: proot-distro wraps its rootfs in one. */
    val stripComponents: Int,
    val downloadMb: Int,
    /** Expanded size plus room for a few packages; checked against free space before install. */
    val installedMb: Int,
    val support: LinuxDistroSupport = LinuxDistroSupport.SUPPORTED,
) {
    val supported: Boolean get() = support == LinuxDistroSupport.SUPPORTED
}

/**
 * The distros GameNative offers. The non-Ubuntu tarballs are proot-distro's x86_64 builds, which
 * are already adapted to PRoot (no systemd, no device nodes); their checksums are the ones
 * proot-distro's own plugins pin.
 */
object LinuxDistroCatalog {

    const val UBUNTU = "ubuntu"

    private const val PROOT_DISTRO = "https://github.com/termux/proot-distro/releases/download"

    val all: List<LinuxDistro> = listOf(
        LinuxDistro(
            id = UBUNTU,
            displayName = "Ubuntu",
            release = "24.04 LTS",
            packageManager = LinuxPackageManager.APT,
            graphical = true,
            tarballUrl = null,
            sha256 = null,
            stripComponents = 0,
            downloadMb = 30,
            installedMb = (LinuxRootfs.REQUIRED_BYTES / (1024 * 1024)).toInt(),
        ),
        LinuxDistro(
            id = "debian",
            displayName = "Debian",
            release = "13 (trixie)",
            packageManager = LinuxPackageManager.APT,
            graphical = false,
            tarballUrl = "$PROOT_DISTRO/v4.26.0/debian-trixie-x86_64-pd-v4.26.0.tar.xz",
            sha256 = "e2edc15363395936cf0cba8c440a108458dba58fb496d3d962909d7a8d9777ae",
            stripComponents = 1,
            downloadMb = 35,
            installedMb = 400,
        ),
        LinuxDistro(
            id = "archlinux",
            displayName = "Arch Linux",
            release = "rolling",
            packageManager = LinuxPackageManager.PACMAN,
            graphical = false,
            tarballUrl = "$PROOT_DISTRO/v4.22.1/archlinux-x86_64-pd-v4.22.1.tar.xz",
            sha256 = "a0cf76c31c79e260766dee80657bd7683fa30dafe900952018264462c1728e17",
            stripComponents = 1,
            downloadMb = 275,
            installedMb = 1_400,
        ),
        LinuxDistro(
            id = "alpine",
            displayName = "Alpine Linux",
            release = "3.22",
            packageManager = LinuxPackageManager.APK,
            graphical = false,
            tarballUrl = "$PROOT_DISTRO/v4.25.0/alpine-x86_64-pd-v4.25.0.tar.xz",
            sha256 = "f2326295fe49bea385c724ff88f5c3aab22bd44f8ca1dfd4244e678c34161d40",
            stripComponents = 1,
            downloadMb = 3,
            installedMb = 100,
            support = LinuxDistroSupport.EXPERIMENTAL,
        ),
        LinuxDistro(
            id = "fedora",
            displayName = "Fedora",
            release = "42",
            packageManager = LinuxPackageManager.DNF,
            graphical = false,
            tarballUrl = "$PROOT_DISTRO/v4.25.0/fedora-x86_64-pd-v4.25.0.tar.xz",
            sha256 = "b1a64e2bd37947cc8364ab7030409775748c666d2c2c4d5f331cddba653f2a97",
            stripComponents = 1,
            downloadMb = 33,
            installedMb = 500,
            support = LinuxDistroSupport.EXPERIMENTAL,
        ),
    )

    fun byId(id: String): LinuxDistro? = all.firstOrNull { it.id == id }

    fun nameOf(id: String): String = byId(id)?.displayName ?: id
}
