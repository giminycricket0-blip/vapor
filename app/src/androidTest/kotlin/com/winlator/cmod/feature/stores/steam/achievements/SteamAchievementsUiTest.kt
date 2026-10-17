package com.winlator.cmod.feature.stores.steam.achievements

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.statsgen.Achievement
import com.winlator.cmod.shared.theme.WinNativeTheme
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering fixtures only; no Steam account or native achievement request is used. */
@RunWith(AndroidJUnit4::class)
class SteamAchievementsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private val locked = Achievement("FIRST", displayName = mapOf("english" to "First steps"), unlocked = false)
    private val unlocked = Achievement("SECOND", displayName = mapOf("english" to "A new adventure"),
        description = mapOf("english" to "Complete the opening chapter."), unlocked = true, unlockTimestamp = 1700000000)

    @Test fun failedReadOffersRefreshWithoutClaimingEmptyOrLocked() {
        var refreshes = 0
        compose.setContent { WinNativeTheme {
            SteamAchievementsContent(123, "Fixture game", SteamAchievementsAvailability.READY,
                Result.failure(IOException("fixture")), { refreshes++ }, {}, {})
        } }
        compose.onNodeWithText(label(R.string.vapor_achievements_failed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.vapor_achievements_empty)).assertDoesNotExist()
        compose.onNodeWithText("0 / 0").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.vapor_achievements_refresh)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun successfulEmptyListIsDistinctFromFailure() {
        compose.setContent { WinNativeTheme {
            SteamAchievementsContent(123, "Fixture game", SteamAchievementsAvailability.READY,
                Result.success(emptyList()), {}, {}, {})
        } }
        compose.onNodeWithText(label(R.string.vapor_achievements_empty)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.vapor_achievements_failed)).assertDoesNotExist()
    }

    @Test fun incompleteAccountProgressDoesNotShowAFakeTotal() {
        compose.setContent { WinNativeTheme {
            SteamAchievementsContent(123, "Fixture game", SteamAchievementsAvailability.READY,
                Result.success(listOf(unlocked, locked.copy(unlocked = null))), {}, {}, {})
        } }
        compose.onNodeWithText(label(R.string.vapor_achievements_progress_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.vapor_achievements_unknown)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertDoesNotExist()
        compose.onNodeWithText("0 / 2").assertDoesNotExist()
    }

    @Test fun explicitRefreshCanDisplayNewProgress() {
        val result = mutableStateOf(Result.success(listOf(unlocked, locked)))
        compose.setContent { WinNativeTheme {
            SteamAchievementsContent(123, "Fixture game", SteamAchievementsAvailability.READY,
                result.value, { result.value = Result.success(listOf(unlocked, locked.copy(unlocked = true))) }, {}, {})
        } }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.vapor_achievements_refresh)).performClick()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.vapor_achievements_unknown)).assertDoesNotExist()
        capture("vapor-achievements-fixture.png")
    }

    @Test fun signedOutScreenOffersExistingSignInAction() {
        var signIns = 0
        compose.setContent { WinNativeTheme {
            SteamAchievementsContent(123, "Fixture game", SteamAchievementsAvailability.SIGNED_OUT,
                null, {}, { signIns++ }, {})
        } }
        compose.onNodeWithText(label(R.string.vapor_achievements_sign_in)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.vapor_achievements_refresh)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.common_ui_sign_in)).performClick()
        compose.runOnIdle { assertEquals(1, signIns) }
    }

    @Test fun handoffAndOfflineStateHideOldResultsAndDisableRefresh() {
        val availability = mutableStateOf(SteamAchievementsAvailability.READY)
        compose.setContent { WinNativeTheme {
            SteamAchievementsContent(123, "Fixture game", availability.value,
                Result.success(listOf(unlocked, locked)), {}, {}, {})
        } }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.runOnIdle { availability.value = SteamAchievementsAvailability.IN_GAME }
        compose.onNodeWithText(label(R.string.vapor_achievements_in_game)).assertIsDisplayed()
        compose.onNodeWithText("1 / 2").assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.vapor_achievements_refresh)).assertIsNotEnabled()
        compose.runOnIdle { availability.value = SteamAchievementsAvailability.OFFLINE }
        compose.onNodeWithText(label(R.string.vapor_achievements_offline)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.vapor_achievements_refresh)).assertIsNotEnabled()
    }

    @Test fun loadingBlocksDuplicateRefreshButKeepsCloseAvailable() {
        var closed = 0
        compose.setContent { WinNativeTheme {
            SteamAchievementsContent(123, "Fixture game", SteamAchievementsAvailability.READY,
                null, {}, {}, { closed++ })
        } }
        compose.onNodeWithContentDescription(label(R.string.vapor_achievements_refresh)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.vapor_achievements_empty)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.steam_common_back)).performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun narrowLargeTextLayoutKeepsRefreshCloseAndScrollingReachable() {
        var refreshed = 0; var closed = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) { WinNativeTheme {
                Box(Modifier.size(280.dp, 320.dp)) {
                    SteamAchievementsContent(123, "Fixture game", SteamAchievementsAvailability.READY,
                        Result.success(listOf(unlocked, locked)), { refreshed++ }, {}, { closed++ })
                }
            } }
        }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithText("First steps").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.vapor_achievements_refresh)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(label(R.string.steam_common_back)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, refreshed); assertEquals(1, closed) }
        capture("vapor-achievements-narrow-fixture.png")
    }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
