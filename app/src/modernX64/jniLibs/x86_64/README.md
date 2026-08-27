# x86_64 JNI prebuilts (Bliss ax86 port)

Phase 1 builds these from `app/src/main/cpp/` with the NDK for `x86_64-linux-android`.

Until then, copy built artifacts here (same basenames as `src/modern/jniLibs/arm64-v8a/`):

- libwinlator.so, libwinlator_11.so
- libproot.so, libproot-loader.so (Bionic path)
- libredirect-bionic-wx.so (via winlator packaging)
- libvulkan_renderer.so (Mesa path — no adrenotools on x86)
- libvirglrenderer.so, libpatchelf.so, libextras.so
- libxconnectorpatch.so, libhook_impl.so, libmain_hook.so

```bash
# Example after enabling CMake for a module:
# adb push out/lib/x86_64/libwinlator.so jniLibs/x86_64/
```
