// Callbacks for a stub install or removal.
//
// Separate from the request because a PackageInstaller session completes long after the call
// that started it returns, and a binder thread must not be held waiting on one.
package app.gamenative.stubs;

interface IStubInstallerCallback {
    /**
     * The outcome of the request.
     *
     * @param packageName the stub concerned, or null when the request never got that far
     * @param status a PackageInstaller.STATUS_* code
     * @param message what went wrong, when anything did
     */
    void onResult(String packageName, int status, String message);
}
