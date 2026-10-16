package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Bad Cheese (Epic)
 *
 * Unreal 5.5 creates its viewport with D3D12. Epic containers stay on the DXVK
 * default (async-1.10.3), whose DXGI returns DXGI_ERROR_UNSUPPORTED for that
 * swapchain. vkd3d is the Vulkan DirectX 12 backend and is offered before launch,
 * the same way Doom offers its Vulkan renderer when OpenGL is selected.
 */
val EPIC_Fix_4c2659cf3411441dbc02ffc965643e47: KeyedGameFix = KeyedDxWrapperFix(
    gameSource = GameSource.EPIC,
    gameId = "4c2659cf3411441dbc02ffc965643e47",
    wrapper = "vkd3d",
)
