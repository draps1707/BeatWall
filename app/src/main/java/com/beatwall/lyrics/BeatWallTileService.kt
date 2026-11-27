package com.beatwall.lyrics

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * One-tap Quick Settings tile to cycle BeatWall worlds or return to Today (Live).
 * Long-pressing the tile opens MainActivity via ACTION_QS_TILE_PREFERENCES.
 */
class BeatWallTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val biomes = WorldBiome.values()
        val current = LocationStore.getPreviewDayOfYear(applicationContext)
        val next = when {
            current !in 1..biomes.size -> 1
            current < biomes.size -> current + 1
            else -> -1 // Back to Today (Live)
        }
        LocationStore.setPreviewDayOfYear(applicationContext, next)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val biomes = WorldBiome.values()
        val saved = LocationStore.getPreviewDayOfYear(applicationContext)
        tile.label = "BeatWall"
        tile.subtitle = if (saved in 1..biomes.size) {
            biomes[saved - 1].title
        } else {
            "Today (Live)"
        }
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
