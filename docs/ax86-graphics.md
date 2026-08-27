# GameNative on Bass:Lineout (ax86 x86_64)

Recommended device props for testing PC games with Mesa/Vulkan on ax86 tablets.
Set via `custom_device.prop`, `init.sh` fragments, or transient `adb shell setprop`.

## Default Mesa path (Intel iGPU / AMD with minigbm)

```properties
# Gralloc / HWC (match device/generic/x86_64_tablet/init.sh defaults)
gralloc.minigbm=1
```

Typical env vars inside GameNative container configs (not Android props):

```text
ZINK_DESCRIPTORS=lazy
MESA_SHADER_CACHE_MAX_SIZE=512MB
mesa_glthread=true
WINEESYNC=1
MESA_VK_WSI_PRESENT_MODE=mailbox
```

## AMD dGPU (RDNA3/RDNA4)

Prefer `gbm` + `drm` HWC when minigbm scanout is patched for GFX12. Confirm
`pastel`/RADV is the active Vulkan ICD on the test image before blaming GameNative.

## Graphics driver UI

On x86 hosts, hide Turnip/Adreno-specific options and prefer system Vulkan (Mesa
RADV / Intel ANV). Phase 2 wires `GPUInformation` and driver picker accordingly.

## References

- ax86 `device/generic/x86_64_tablet/init.sh`
- `vendor/ax86-lite/docs/examples/custom_device.prop`
- Port plan: Bliss vendor `docs/drafts/gamenative-x64-port-plan.md`
