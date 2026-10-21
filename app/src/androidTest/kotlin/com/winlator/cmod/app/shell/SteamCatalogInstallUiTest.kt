package com.winlator.cmod.app.shell

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.feature.settings.SteamAccountSetupScreen
import com.winlator.cmod.feature.stores.steam.data.SteamApp
import com.winlator.cmod.feature.stores.steam.service.SteamCatalogItem
import com.winlator.cmod.shared.theme.WinNativeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamCatalogInstallUiTest {
    @get:Rule val compose = createComposeRule()
    private val demo = SteamCatalogItem(22150, "Europa Universalis III Complete Demo", true, true,
        "", null, "", "Paradox Interactive", true, false, emptyList())

    @Test fun signedOutNativeEntryPointsArePackagedAndDoNotGrantAccess() {
        com.winlator.cmod.feature.stores.steam.wnsteam.WnSteamSession().use { session ->
            org.junit.Assert.assertNull(session.requestFreeLicense(22150))
            org.junit.Assert.assertNull(session.getWebAccessToken("not-a-token"))
        }
    }

    @Test fun signedInDemoUsesNativeAcquisitionAndBlocksDuplicateTaps() {
        var acquisitions = 0; var browser = 0
        compose.setContent { WinNativeTheme {
            var busy by remember { mutableStateOf(false) }
            SteamCatalogDetailsContent(demo, null, true, busy, null, {}, { acquisitions++; busy = true }, {}, {}, { browser++ }, {})
        } }
        compose.onNodeWithText("Install demo").performScrollTo().performClick()
        compose.onNodeWithText("Checking your Steam license…").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, acquisitions); assertEquals(0, browser) }
    }

    @Test fun signedOutDemoOffersNativeSignInWithoutOpeningAWebsite() {
        var login = 0; var browser = 0; var acquisitions = 0
        compose.setContent { WinNativeTheme {
            SteamCatalogDetailsContent(demo, null, false, false, null, {}, { acquisitions++ }, { login++ }, {}, { browser++ }, {})
        } }
        compose.onNodeWithText("Connect Steam to install").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, login); assertEquals(0, browser); assertEquals(0, acquisitions) }
    }

    @Test fun signedOutWebFeaturesOfferNativeAccountConnection() {
        var login = 0; var installs = 0
        compose.setContent { WinNativeTheme {
            SteamWebPanel("https://store.steampowered.com/wishlist/", signedIn = false,
                onConnect = { login++ }, onInstall = { installs++ }, onClose = {})
        } }
        compose.onNodeWithText("Connect Steam").performClick()
        compose.runOnIdle { assertEquals(1, login); assertEquals(0, installs) }
    }

    @Test fun closingWebModalPreservesTheUnderlyingSearch() {
        compose.setContent { WinNativeTheme {
            var showWeb by remember { mutableStateOf(true) }
            var query by remember { mutableStateOf("Helltaker") }
            androidx.compose.material3.OutlinedTextField(query, { query = it },
                label = { androidx.compose.material3.Text("Store search") })
            if (showWeb) SteamWebPanel("https://store.steampowered.com/wishlist/", false,
                {}, {}, { showWeb = false })
        } }
        compose.onNodeWithText("Wishlist").assertIsDisplayed()
        compose.onNodeWithText("store.steampowered.com").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Wishlist").assertDoesNotExist()
        compose.onNodeWithText("Helltaker").assertIsDisplayed()
    }

    @Test fun paidParentOffersItsSeparateDemoAndKeepsCheckoutSeparate() {
        var chosen = 0; var checkout = 0; var acquisitions = 0
        compose.setContent { WinNativeTheme {
            SteamCatalogDetailsContent(demo.copy(appId = 25800, isDemo = false, isFree = false, demoAppIds = listOf(22150)),
                null, true, false, null, {}, { acquisitions++ }, {}, { chosen = it }, { checkout++ }, {})
        } }
        compose.onNodeWithText("Install demo").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(22150, chosen); assertEquals(0, checkout) }
        compose.onNodeWithText("Buy on Steam").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checkout); assertEquals(0, acquisitions) }
    }

    @Test fun ownedPaidGameUsesExistingNativeGameManager() {
        var selected = 0; var checkout = 0
        compose.setContent { WinNativeTheme {
            SteamCatalogDetailsContent(demo.copy(appId = 25800, isDemo = false, isFree = false),
                SteamApp(25800, name = "Europa Universalis III"), true, false, null,
                { selected = it.id }, {}, {}, {}, { checkout++ }, {})
        } }
        compose.onNodeWithText("Open in your library").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(25800, selected); assertEquals(0, checkout) }
    }

    @Test fun failureRemainsInVaporAndAllowsRetry() {
        var attempts = 0; var checkout = 0
        compose.setContent { WinNativeTheme {
            SteamCatalogDetailsContent(demo, null, true, false, "Steam could not grant this download.",
                {}, { attempts++ }, {}, {}, { checkout++ }, {})
        } }
        compose.onNodeWithText("Steam could not grant this download.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Install demo").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, attempts); assertEquals(0, checkout) }
    }

    @Test fun unreleasedDemoCannotRequestALicense() {
        var attempts = 0
        compose.setContent { WinNativeTheme {
            SteamCatalogDetailsContent(demo.copy(comingSoon = true), null, true, false, null,
                {}, { attempts++ }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Download unavailable").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, attempts) }
    }

    @Test fun knownNonWindowsCatalogAppsDoNotOfferCommunityReports() {
        compose.setContent { WinNativeTheme {
            SteamCatalogDetailsContent(demo.copy(windows = false), null, true, false, null,
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Community reports").assertDoesNotExist()
    }

    @Test fun windowsCatalogAppsOfferCommunityReports() {
        compose.setContent { WinNativeTheme {
            SteamCatalogDetailsContent(demo.copy(windows = true), null, true, false, null,
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Community reports").assertExists()
    }

    @Test fun startupOffersOnlySteamAndDoesNotAutomaticallyLaunchLogin() {
        var connects = 0; var continued = 0
        compose.setContent { WinNativeTheme {
            SteamAccountSetupScreen(false, { connects++ }, { continued++ })
        } }
        compose.runOnIdle { assertEquals(0, connects); assertEquals(0, continued) }
        compose.onNodeWithText("Connect Epic Games").assertDoesNotExist()
        compose.onNodeWithText("GOG").assertDoesNotExist()
        compose.onNodeWithText("itch.io").assertDoesNotExist()
        compose.onNodeWithText("Connect Steam").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connects); assertEquals(0, continued) }
    }

    @Test fun connectedSteamAccountContinuesWithoutAnotherLogin() {
        var connects = 0; var continued = 0
        compose.setContent { WinNativeTheme {
            SteamAccountSetupScreen(true, { connects++ }, { continued++ })
        } }
        compose.onNodeWithText("Steam connected").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect Steam").assertDoesNotExist()
        compose.onNodeWithText("Set up later").assertDoesNotExist()
        compose.onNodeWithText("Continue to Vapor").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, connects); assertEquals(1, continued) }
    }

    @Test fun startupAllowsLocalGamesWithoutAnyStoreAccount() {
        var continued = 0
        compose.setContent { WinNativeTheme { SteamAccountSetupScreen(false, {}, { continued++ }) } }
        compose.onNodeWithText("Set up later").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, continued) }
    }
}
