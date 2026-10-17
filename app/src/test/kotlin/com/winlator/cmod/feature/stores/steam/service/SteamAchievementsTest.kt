package com.winlator.cmod.feature.stores.steam.service

import android.app.Application
import android.content.Context
import com.winlator.cmod.feature.stores.steam.statsgen.Achievement
import com.winlator.cmod.feature.stores.steam.statsgen.mergeAchievementUnlockState
import com.winlator.cmod.feature.stores.steam.utils.PrefManager
import com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Synthetic native responses only; these tests do not earn or write Steam achievements. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SteamAchievementsTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var service: SteamService
    private lateinit var session: WnSteamSession
    private val appId = 123
    private val definitions = listOf(Achievement("FIRST"), Achievement("SECOND"))
    private val mapping = mapOf("FIRST" to (7 to 0), "SECOND" to (7 to 1))

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setField(PrefManager, "prefs", app.getSharedPreferences("achievement-test", Context.MODE_PRIVATE))
        PrefManager.refreshToken = "test-only-placeholder"
        service = SteamService()
        session = mock(WnSteamSession::class.java)
        SteamService.instance = service
        SteamService.wnSession = session
        SteamService.isRunning = true
        SteamService.isStopping = false
        SteamService.isLoggingOut = false
        SteamService._isLoggedInFlow.value = true
        SteamService.wnLoggedOnHandled = true
        `when`(session.state()).thenReturn(3)
        `when`(session.steamId()).thenReturn(42L)
    }

    @After fun teardown() {
        service.scope.cancel()
        SteamService.wnSession = null
        SteamService.instance = null
        SteamService.isRunning = false
        SteamService.isStopping = false
        SteamService.isLoggingOut = false
        SteamService._isLoggedInFlow.value = false
        SteamService.wnLoggedOnHandled = false
        ReflectionHelpers.setField(PrefManager, "prefs", null)
    }

    @Test fun singleNativeResponseSuppliesDefinitionsAndExplicitAccountProgress() = runBlocking {
        `when`(session.getUserStatsFull(appId)).thenReturn(response().toString())
        val achievements = load().getOrThrow()
        assertEquals(listOf("FIRST", "SECOND"), achievements.map { it.name })
        assertEquals(listOf(true, false), achievements.map { it.unlocked })
        assertEquals(1700000000, achievements.first().unlockTimestamp)
        assertNull(achievements.last().unlockTimestamp)
        verify(session, times(1)).getUserStatsFull(appId)
        verify(session, never()).getUserStatsSchema(anyInt())
        assertNull(service.connectJob)
    }

    @Test fun failedRefreshCannotReturnPreviousAccountProgress() = runBlocking {
        `when`(session.getUserStatsFull(appId)).thenReturn(response().toString(), null)
        assertTrue(load().isSuccess)
        assertTrue(load().isFailure)
    }

    @Test fun failedOrMalformedRequestsCannotClaimNoAchievements() = runBlocking {
        val invalid = listOf(null, "garbage", "{}",
            response().put("eresult", 2).toString(),
            response().put("schema", "").toString(),
            response().put("schema", "abc").toString(),
            response().put("schema", "zz").toString(),
            response().put("schema", "08").toString())
        for (json in invalid) {
            `when`(session.getUserStatsFull(appId)).thenReturn(json)
            assertTrue(load().isFailure)
        }
    }

    @Test fun validEmptySchemaCanReportNoAchievements() = runBlocking {
        `when`(session.getUserStatsFull(appId)).thenReturn(response(empty = true).toString())
        assertEquals(emptyList<Achievement>(), load().getOrThrow())
    }

    @Test fun gameOwnershipOrBackgroundSuspensionDoesNotCreateAnotherConnection() = runBlocking {
        service.suspendedForBionic = true
        assertTrue(load().isFailure)
        service.suspendedForBionic = false
        service.suspendedForBackground = true
        assertTrue(load().isFailure)
        verifyNoInteractions(session)
        assertNull(service.connectJob)
    }

    @Test fun offlineStoppingAndSignedOutRequestsCannotReachNativeStats() = runBlocking {
        `when`(session.state()).thenReturn(2)
        assertTrue(load().isFailure)
        `when`(session.state()).thenReturn(3)
        SteamService.isStopping = true
        assertTrue(load().isFailure)
        SteamService.isStopping = false
        SteamService._isLoggedInFlow.value = false
        assertTrue(load().isFailure)
        verify(session, never()).getUserStatsFull(anyInt())
        assertNull(service.connectJob)
    }

    @Test fun handoffDuringRequestDiscardsTheResult() = runBlocking {
        `when`(session.getUserStatsFull(appId)).thenAnswer {
            service.suspendedForBionic = true
            response().toString()
        }
        assertTrue(load().isFailure)
        assertNull(service.connectJob)
    }

    @Test fun accountChangeDuringRequestDiscardsTheResult() = runBlocking {
        `when`(session.getUserStatsFull(appId)).thenReturn(response().toString())
        `when`(session.steamId()).thenReturn(42L, 43L)
        assertTrue(load().isFailure)
    }

    @Test fun cancellationIsPropagatedInsteadOfBecomingAnEmptyOrFailedResult() = runBlocking {
        `when`(session.getUserStatsFull(appId)).thenThrow(CancellationException("fixture"))
        try {
            load()
            fail("Cancellation must reach the screen coroutine")
        } catch (_: CancellationException) { }
    }

    @Test fun missingBlocksOrTimesStayUnknownInsteadOfLocked() {
        val sparse = response().put("achievementBlocks", JSONArray("[{\"achievementId\":7,\"unlockTimes\":[0]}]"))
        val result = mergeAchievementUnlockState(definitions, mapping, sparse)
        assertEquals(false, result[0].unlocked)
        assertNull(result[1].unlocked)
        sparse.remove("achievementBlocks")
        assertTrue(mergeAchievementUnlockState(definitions, mapping, sparse).all { it.unlocked == null })
        assertTrue(mergeAchievementUnlockState(definitions, emptyMap(), response()).all { it.unlocked == null })
    }

    @Test fun invalidProgressDoesNotPreserveOldOrPartiallyParsedUnlocks() {
        val stale = definitions.map { it.copy(unlocked = true, unlockTimestamp = 123, formattedUnlockTime = "old") }
        val invalidBlocks = listOf(
            "[{\"achievementId\":7,\"unlockTimes\":[-1,0]}]",
            "[{\"achievementId\":7,\"unlockTimes\":[4294967296,0]}]",
            "[{\"achievementId\":7,\"unlockTimes\":[0.5,0]}]",
            "[{\"achievementId\":7,\"unlockTimes\":[\"0\",0]}]",
            "[{\"achievementId\":7,\"unlockTimes\":[123,0]},{}]",
            "[{\"achievementId\":7,\"unlockTimes\":[123,0]},{\"achievementId\":7,\"unlockTimes\":[0,0]}]",
        )
        for (blocks in invalidBlocks) {
            val result = mergeAchievementUnlockState(stale, mapping, response().put("achievementBlocks", JSONArray(blocks)))
            assertTrue(result.all { it.unlocked == null && it.unlockTimestamp == null && it.formattedUnlockTime == null })
        }
        assertTrue(mergeAchievementUnlockState(stale, mapping, response().put("eresult", 2)).all { it.unlocked == null })
    }

    @Test fun fullUnsignedSteamTimestampRemainsUnlocked() {
        val json = response().put("achievementBlocks", JSONArray("[{\"achievementId\":7,\"unlockTimes\":[4294967295,0]}]"))
        val first = mergeAchievementUnlockState(definitions, mapping, json).first()
        assertEquals(true, first.unlocked)
        assertEquals(4294967295L, first.unlockTimestamp!!.toUInt().toLong())
    }

    private suspend fun load() = SteamService.loadAchievements(appId, files.root.absolutePath)

    private fun response(empty: Boolean = false): JSONObject {
        val bits = section("0", entry("name", "FIRST")) + section("1", entry("name", "SECOND"))
        val stats = if (empty) byteArrayOf() else section("7", entry("type", "4") + section("bits", bits))
        val schema = section(appId.toString(), section("stats", stats)) + byteArrayOf(8)
        return JSONObject().put("eresult", 1)
            .put("schema", schema.joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("achievementBlocks", JSONArray("[{\"achievementId\":7,\"unlockTimes\":[1700000000,0]}]"))
    }
    private fun section(name: String, contents: ByteArray) = byteArrayOf(0) + name.toByteArray() + byteArrayOf(0) + contents + byteArrayOf(8)
    private fun entry(name: String, value: String) = byteArrayOf(1) + name.toByteArray() + byteArrayOf(0) + value.toByteArray() + byteArrayOf(0)
}
