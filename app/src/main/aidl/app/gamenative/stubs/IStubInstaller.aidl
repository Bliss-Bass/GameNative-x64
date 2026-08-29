// Installs the launcher entries GameNative generates for Linux applications.
//
// GameNative is an ordinary app: it can build and sign a stub but cannot install one without
// sending the user through the unknown-sources setting, an install prompt and, where Play
// services are present, a Play Protect scan. This service is part of the system image and holds
// INSTALL_PACKAGES, so it can complete the install outright.
//
// What it will install is narrow by design, since a privileged installer that takes any APK from
// any caller hands every app on the device a way to install anything. See StubInstallerService
// for the checks each submission has to pass.
package app.gamenative.stubs;

import app.gamenative.stubs.IStubInstallerCallback;

interface IStubInstaller {

    /**
     * Installs the stub read from [apk].
     *
     * A file descriptor rather than a path because the caller's APK sits in its private storage,
     * which this service cannot open for itself.
     */
    oneway void install(in ParcelFileDescriptor apk, in IStubInstallerCallback callback);

    /** Removes a stub this service installed. */
    oneway void uninstall(String packageName, in IStubInstallerCallback callback);

    /**
     * The stubs currently installed.
     *
     * The caller reconciles against this: it knows which Linux applications still exist, and
     * this service knows what is on the device.
     */
    List<String> installedStubs();
}
