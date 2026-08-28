package app.gamenative.utils

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import coil.Coil
import timber.log.Timber

/**
 * Hints to Android that a game session is active and frees Java-side caches so Wine/Proton
 * have more headroom. Does not prevent lmkd kills under critical memory pressure.
 */
object GameSessionMemory {
    private const val MODE_NONE = 1
    private const val MODE_GAME_PLAYING = 2

    @JvmStatic
    fun beginSession(context: Context) {
        notifyGameState(context, MODE_GAME_PLAYING)
        trimForGameplay(context)
    }

    @JvmStatic
    fun endSession(context: Context) {
        notifyGameState(context, MODE_NONE)
    }

    /**
     * Drop cover-art / library bitmap caches when memory is tight during gameplay.
     */
    @JvmStatic
    fun trimForGameplay(context: Context) {
        runCatching { Coil.imageLoader(context).memoryCache?.clear() }
            .onFailure { Timber.w(it, "GameSessionMemory: failed to clear Coil memory cache") }
    }

    private fun notifyGameState(context: Context, mode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val gameManager = context.getSystemService(GameManager::class.java) ?: return
            gameManager.setGameState(GameState(true, mode))
            Timber.d("GameSessionMemory: setGameState mode=%d", mode)
        }.onFailure { Timber.w(it, "GameSessionMemory: setGameState failed") }
    }
}
