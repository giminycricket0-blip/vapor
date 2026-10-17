package com.winlator.cmod.feature.stores.steam.statsgen

import org.json.JSONObject

data class Achievement(
    val name: String,
    val displayName: Map<String, String>? = null,
    val description: Map<String, String>? = null,
    val hidden: Int = 0,
    val icon: String? = null,
    val iconGray: String? = null,
    val icongray: String? = null,
    val progress: Map<String, Any>? = null,
    val unlocked: Boolean? = null,
    val unlockTimestamp: Int? = null,
    val formattedUnlockTime: String? = null,
)

/** Missing progress stays unknown; only an explicit zero unlock time means locked. */
internal fun mergeAchievementUnlockState(
    achievements: List<Achievement>,
    nameToBlockBit: Map<String, Pair<Int, Int>>,
    response: JSONObject,
): List<Achievement> {
    val unknown = achievements.map { it.copy(unlocked = null, unlockTimestamp = null, formattedUnlockTime = null) }
    if (response.optInt("eresult", 2) != 1) return unknown
    val blocks = response.optJSONArray("achievementBlocks") ?: return unknown
    val timesByBlock = HashMap<Int, List<Long>>()
    try {
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            val id = block.getInt("achievementId")
            require(id >= 0 && id !in timesByBlock)
            val times = block.getJSONArray("unlockTimes")
            timesByBlock[id] = List(times.length()) { index ->
                val value = times.get(index)
                require(value is Int || value is Long)
                (value as Number).toLong().also { require(it in 0..0xFFFF_FFFFL) }
            }
        }
    } catch (_: Exception) {
        return unknown
    }
    return unknown.map { achievement ->
        val (block, bit) = nameToBlockBit[achievement.name] ?: return@map achievement
        val time = timesByBlock[block]?.getOrNull(bit) ?: return@map achievement
        achievement.copy(unlocked = time != 0L, unlockTimestamp = time.takeIf { it != 0L }?.toInt())
    }
}

data class Stat(
    val id: String,
    val name: String,
    val type: String,
    val default: String = "0",
    val global: String = "0",
    val min: String? = null,
)

data class ProcessingResult(
    val achievements: List<Achievement>,
    val stats: List<Stat>,
    val copyDefaultUnlockedImg: Boolean,
    val copyDefaultLockedImg: Boolean,
    val nameToBlockBit: Map<String, Pair<Int, Int>> = emptyMap(),
)

object StatType {
    const val STAT_TYPE_INT = "1"
    const val STAT_TYPE_FLOAT = "2"
    const val STAT_TYPE_AVGRATE = "3"
    const val STAT_TYPE_BITS = "4"

    const val ACHIEVEMENTS = "ACHIEVEMENTS"
    const val INT = "INT"
    const val FLOAT = "FLOAT"
    const val AVGRATE = "AVGRATE"
}
