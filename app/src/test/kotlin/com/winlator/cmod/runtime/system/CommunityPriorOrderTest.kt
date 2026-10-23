package com.winlator.cmod.runtime.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The community prior may only *reorder* candidates the router already judged eligible for
 * this device. These cover the ordering rule and the fact that no prior leaves the router's
 * own order untouched.
 */
class CommunityPriorOrderTest {
    private data class Candidate(val name: String, val dxvk: String)

    private val legacy = Candidate("legacy", "1.10.3-arm64ec-async")
    private val modern = Candidate("modern", "2.7.1-gplasync-arm64ec")

    private fun order(
        list: List<Candidate>,
        preferLegacy: Boolean?,
    ) = CommunityPrior.orderByPrior(list, preferLegacy) { it.dxvk.startsWith("1.") }

    @Test
    fun noPriorLeavesTheRoutersOrderAlone() {
        val original = listOf(modern, legacy)
        assertEquals(original, order(original, null))
    }

    @Test
    fun aLegacyPriorPutsLegacyFirst() {
        val ordered = order(listOf(modern, legacy), preferLegacy = true)
        assertEquals(listOf(legacy, modern), ordered)
    }

    @Test
    fun aModernPriorPutsModernFirst() {
        val ordered = order(listOf(legacy, modern), preferLegacy = false)
        assertEquals(listOf(modern, legacy), ordered)
    }

    @Test
    fun thePriorNeverAddsOrDropsCandidates() {
        val original = listOf(legacy, modern, Candidate("wined3d", "1.10.3-arm64ec-async"))
        val ordered = order(original, preferLegacy = false)
        assertEquals(original.size, ordered.size)
        assertEquals(original.toSet(), ordered.toSet())
    }

    @Test
    fun orderWithinEachGroupIsStable() {
        val early = Candidate("legacy-a", "1.10.3-a")
        val late = Candidate("legacy-b", "1.10.3-b")
        val ordered = order(listOf(modern, early, late), preferLegacy = true)
        assertEquals(listOf(early, late, modern), ordered)
    }
}
