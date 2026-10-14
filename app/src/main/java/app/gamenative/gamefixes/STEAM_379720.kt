package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * DOOM (2016) (Steam)
 * Defaults to its OpenGL renderer, which needs WGL_ARB_pixel_format; containers only provide Vulkan.
 * Picking Vulkan from DOOMx64.exe makes it relaunch as DOOMx64vk.exe, a process the ColdClient
 * loader never injected, so SteamAPI_Init fails there. Starting DOOMx64vk.exe directly avoids that.
 */
val STEAM_Fix_379720: KeyedGameFix = KeyedCompositeGameFix(
    gameSource = GameSource.STEAM,
    gameId = "379720",
    fixes = listOf(
        ExecutableFix(executable = "DOOMx64vk.exe", replaces = "DOOMx64.exe"),
        LaunchArgFix(
            launchArgs = "+r_renderAPI 1",
            conflictingArgs = Regex("""\+r_renderAPI\s+0\b"""),
        ),
    ),
)
