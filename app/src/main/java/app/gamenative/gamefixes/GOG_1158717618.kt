package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * STAR WARS: Bounty Hunter (GOG)
 *
 * TangoPC.exe creates a DirectX 12 device. A DXVK container leaves Wine's builtin
 * d3d12.dll in place, and that DLL asks DXVK's DXGI for IWineDXGIAdapter
 * (17399d75-964e-4c03-99f8-9d4fd196dd62). DXVK does not implement it, and the game
 * then page-faults in ucrtbase. vkd3d-proton's d3d12.dll is the build that talks
 * to DXVK's DXGI, so it is offered before launch the same way as other DX12 titles.
 */
val GOG_Fix_1158717618: KeyedGameFix = KeyedDxWrapperFix(
    gameSource = GameSource.GOG,
    gameId = "1158717618",
    wrapper = "vkd3d",
)
