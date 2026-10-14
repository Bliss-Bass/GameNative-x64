package app.gamenative.ui.data

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import app.gamenative.utils.SteamSourceCompat

/** Steam app id to the latest ProtonDB and Steam ratings shown on library badges. */
object SteamSourceCompatStore {
    val byAppId: SnapshotStateMap<Int, SteamSourceCompat> = mutableStateMapOf()

    fun publish(values: Map<Int, SteamSourceCompat>) {
        if (values.isEmpty()) return
        byAppId.putAll(values)
    }
}
