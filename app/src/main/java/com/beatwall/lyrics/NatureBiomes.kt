package com.beatwall.lyrics

import androidx.core.graphics.ColorUtils

data class NatureBiome(
    val name: String,
    val zenith: Int,
    val midSky: Int,
    val horizon: Int,
    val accent: Int,
    val particleKind: Int // 0 = stars+meteor, 1 = ocean/bio motes, 2 = fireflies, 3 = solar/ember dust, 4 = perseid meteors
)

object NatureBiomes {

    private val biomes = arrayOf(
        // Days 1-6: Sky & Atmosphere
        NatureBiome("Boreal Aurora", 0xFF061224.toInt(), 0xFF0F3A4A.toInt(), 0xFF2E1065.toInt(), 0xFF34D399.toInt(), 0),
        NatureBiome("Sahara Golden Hour", 0xFF1F1209.toInt(), 0xFF7C2D12.toInt(), 0xFFD97706.toInt(), 0xFFFBBF24.toInt(), 3),
        NatureBiome("Alpine Dawn", 0xFF0C1E38.toInt(), 0xFF1E4E79.toInt(), 0xFF9D4E6E.toInt(), 0xFFFDA4AF.toInt(), 0),
        NatureBiome("Monsoon Twilight", 0xFF09151E.toInt(), 0xFF134E4A.toInt(), 0xFF334155.toInt(), 0xFF5EEAD4.toInt(), 1),
        NatureBiome("Stratosphere Indigo", 0xFF040B1E.toInt(), 0xFF1E3A8A.toInt(), 0xFF312E81.toInt(), 0xFF93C5FD.toInt(), 0),
        NatureBiome("Equinox Prism", 0xFF180C2E.toInt(), 0xFF6D28D9.toInt(), 0xFFBE5A52.toInt(), 0xFFFDBA74.toInt(), 3),

        // Days 7-12: Ocean & Fluid Physics
        NatureBiome("Bioluminescent Bay", 0xFF030D1A.toInt(), 0xFF082F49.toInt(), 0xFF0E7490.toInt(), 0xFF22D3EE.toInt(), 1),
        NatureBiome("Pacific Coral Dusk", 0xFF1E1028.toInt(), 0xFF831843.toInt(), 0xFFEA580C.toInt(), 0xFFFB7185.toInt(), 1),
        NatureBiome("Arctic Fjord", 0xFF071926.toInt(), 0xFF155E75.toInt(), 0xFF3B6E8C.toInt(), 0xFFA5F3FC.toInt(), 0),
        NatureBiome("Deep Trench", 0xFF020814.toInt(), 0xFF0A2240.toInt(), 0xFF134E5E.toInt(), 0xFF38BDF8.toInt(), 1),
        NatureBiome("Lagoon Mirage", 0xFF052023.toInt(), 0xFF0D9488.toInt(), 0xFF065F46.toInt(), 0xFF6EE7B7.toInt(), 1),
        NatureBiome("Red Sea Sunset", 0xFF1A0812.toInt(), 0xFF7F1D1D.toInt(), 0xFFB45309.toInt(), 0xFFFCA5A5.toInt(), 3),

        // Days 13-18: Forest & Biology
        NatureBiome("Firefly Meadow", 0xFF091410.toInt(), 0xFF14532D.toInt(), 0xFF3F6212.toInt(), 0xFFFDE047.toInt(), 2),
        NatureBiome("Redwood Mist", 0xFF071714.toInt(), 0xFF064E3B.toInt(), 0xFF1E3A2F.toInt(), 0xFFA7F3D0.toInt(), 2),
        NatureBiome("Cherry Blossom Breeze", 0xFF1E0F24.toInt(), 0xFF701A75.toInt(), 0xFF9D174D.toInt(), 0xFFF9A8D4.toInt(), 2),
        NatureBiome("Bamboo Rain", 0xFF061A17.toInt(), 0xFF115E59.toInt(), 0xFF166534.toInt(), 0xFF5EEAD4.toInt(), 1),
        NatureBiome("Autumn Canopy", 0xFF1C0E08.toInt(), 0xFF9A3412.toInt(), 0xFFB45309.toInt(), 0xFFFDBA74.toInt(), 2),
        NatureBiome("Moss & Dew", 0xFF071A19.toInt(), 0xFF0F766E.toInt(), 0xFF15803D.toInt(), 0xFF86EFAC.toInt(), 2),

        // Days 19-24: Geology & Chemistry
        NatureBiome("Volcanic Obsidian", 0xFF0D0914.toInt(), 0xFF3B0764.toInt(), 0xFF9A3412.toInt(), 0xFFFB923C.toInt(), 3),
        NatureBiome("Grand Canyon Dusk", 0xFF1A0F14.toInt(), 0xFF7C2D12.toInt(), 0xFF6B21A8.toInt(), 0xFFFDBA74.toInt(), 3),
        NatureBiome("Salt Flat Mirror", 0xFF0E172A.toInt(), 0xFF334155.toInt(), 0xFF64748B.toInt(), 0xFFE2E8F0.toInt(), 0),
        NatureBiome("Amethyst Geode", 0xFF110724.toInt(), 0xFF4C1D95.toInt(), 0xFF7E22CE.toInt(), 0xFFD8B4FE.toInt(), 0),
        NatureBiome("Copper Patina", 0xFF071B1E.toInt(), 0xFF0F766E.toInt(), 0xFF78350F.toInt(), 0xFF5EEAD4.toInt(), 3),
        NatureBiome("Sulfur & Starlight", 0xFF070D1E.toInt(), 0xFF1E3A8A.toInt(), 0xFF854D0E.toInt(), 0xFFFDE047.toInt(), 0),

        // Days 25-31: Cosmos & Light
        NatureBiome("Perseid Meteor Night", 0xFF03050F.toInt(), 0xFF0F172A.toInt(), 0xFF1E1B4B.toInt(), 0xFF7DD3FC.toInt(), 4),
        NatureBiome("Orion Nebula", 0xFF120618.toInt(), 0xFF881337.toInt(), 0xFF1E3A8A.toInt(), 0xFFFDA4AF.toInt(), 0),
        NatureBiome("Solar Eclipse Corona", 0xFF050712.toInt(), 0xFF1E1B4B.toInt(), 0xFF78350F.toInt(), 0xFFFDE68A.toInt(), 3),
        NatureBiome("Harvest Moonrise", 0xFF140D08.toInt(), 0xFF78350F.toInt(), 0xFF451A03.toInt(), 0xFFFCD34D.toInt(), 2),
        NatureBiome("Polaris Ice", 0xFF040D1E.toInt(), 0xFF1D4ED8.toInt(), 0xFF0E7490.toInt(), 0xFFBAE6FD.toInt(), 0),
        NatureBiome("Andromeda Drift", 0xFF0D061C.toInt(), 0xFF5B21B6.toInt(), 0xFF0369A1.toInt(), 0xFFC4B5FD.toInt(), 4),
        NatureBiome("Solstice Gold", 0xFF0B1410.toInt(), 0xFF065F46.toInt(), 0xFFB45309.toInt(), 0xFFFDE047.toInt(), 3)
    )

    fun forDay(dayOfMonth: Int): NatureBiome {
        val idx = (dayOfMonth - 1).coerceIn(0, biomes.lastIndex)
        return biomes[idx]
    }

    /**
     * Smoothly cross-blends between Day N and Day N+1 around midnight (23:50 -> 00:10)
     * so there is never an abrupt snap at 00:00.
     */
    fun getBlendedBiome(dayOfMonth: Int, minuteOfDay: Int): NatureBiome {
        val today = forDay(dayOfMonth)
        return when {
            minuteOfDay >= 1430 -> {
                val nextDay = if (dayOfMonth >= 31) 1 else dayOfMonth + 1
                val next = forDay(nextDay)
                val t = ((minuteOfDay - 1430) / 20f).coerceIn(0f, 0.5f)
                blend(today, next, t)
            }
            minuteOfDay <= 10 -> {
                val prevDay = if (dayOfMonth <= 1) 31 else dayOfMonth - 1
                val prev = forDay(prevDay)
                val t = (0.5f + minuteOfDay / 20f).coerceIn(0.5f, 1f)
                blend(prev, today, t)
            }
            else -> today
        }
    }

    private fun blend(a: NatureBiome, b: NatureBiome, t: Float): NatureBiome {
        return NatureBiome(
            name = if (t < 0.5f) a.name else b.name,
            zenith = ColorUtils.blendARGB(a.zenith, b.zenith, t),
            midSky = ColorUtils.blendARGB(a.midSky, b.midSky, t),
            horizon = ColorUtils.blendARGB(a.horizon, b.horizon, t),
            accent = ColorUtils.blendARGB(a.accent, b.accent, t),
            particleKind = if (t < 0.5f) a.particleKind else b.particleKind
        )
    }
}
