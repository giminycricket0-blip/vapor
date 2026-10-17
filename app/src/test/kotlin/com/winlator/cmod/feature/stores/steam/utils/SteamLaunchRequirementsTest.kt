package com.winlator.cmod.feature.stores.steam.utils

import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Test

class SteamLaunchRequirementsTest {
    @Test fun signedInGenuineClientIsAccepted() {
        SteamLaunchRequirements.requireGenuineClient(true, false, "test-user", "test-token", 1L)
    }

    @Test fun replacementClientModesAreRejectedEvenWhenSignedIn() {
        for ((genuine, cold) in listOf(false to false, false to true, true to true)) {
            assertThrows(IllegalStateException::class.java) {
                SteamLaunchRequirements.requireGenuineClient(genuine, cold, "test-user", "test-token", 1L)
            }
        }
    }

    @Test fun missingCredentialsAreRejectedWithoutIncludingThemInTheError() {
        val cases = listOf(Triple(null, "test-secret", 1L), Triple("user", "  ", 1L), Triple("user", "test-secret", 0L))
        for ((user, token, id) in cases) {
            val error = assertThrows(IllegalStateException::class.java) {
                SteamLaunchRequirements.requireGenuineClient(true, false, user, token, id)
            }
            assertFalse(error.message.orEmpty().contains("test-secret"))
        }
    }
}
