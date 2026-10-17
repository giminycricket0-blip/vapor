package com.winlator.cmod.app.shell

import android.app.Application
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GameSessionFailureTest {
    private val failure = GameSessionFailure("Test game", "Steam could not sign in.\nPlease sign in again.")

    @Test fun failureIsDeliveredOnceWithoutConsumingOtherNavigationData() {
        val intent = Intent().putExtra("selected_menu_item_id", 42)
        failure.putInto(intent)

        assertEquals(failure, GameSessionFailure.takeFrom(intent))
        assertNull(GameSessionFailure.takeFrom(intent))
        assertEquals(42, intent.getIntExtra("selected_menu_item_id", 0))
    }

    @Test fun gameSettingsTargetSurvivesDeliveryAndRecreation() {
        val targeted = GameSessionFailure("Demo", "Display settings could not be prepared.", "/private/demo.desktop")
        val intent = Intent().also(targeted::putInto)
        assertEquals(targeted, GameSessionFailure.takeFrom(intent))
        val saved = Bundle()
        GameSessionFailure.saveTo(saved, targeted)
        assertEquals(targeted, GameSessionFailure.takeFrom(Intent(), saved))
    }

    @Test fun ordinarySessionReturnDoesNotCreateAnError() {
        assertNull(GameSessionFailure.takeFrom(Intent()))
    }

    @Test fun visibleFailureSurvivesActivityRecreation() {
        val savedState = Bundle()
        GameSessionFailure.saveTo(savedState, failure)

        assertEquals(failure, GameSessionFailure.takeFrom(Intent(), savedState))
    }

    @Test fun dismissedFailureIsNotReplayedFromTheOriginalLaunchIntent() {
        val originalIntent = Intent().also(failure::putInto)
        assertEquals(failure, GameSessionFailure.takeFrom(Intent(originalIntent)))
        val savedState = Bundle()
        GameSessionFailure.saveTo(savedState, failure)
        GameSessionFailure.saveTo(savedState, null)

        assertNull(GameSessionFailure.takeFrom(originalIntent, savedState))
        assertNull(GameSessionFailure.takeFrom(originalIntent))
    }

    @Test fun laterSessionCanDeliverANewFailureAfterDismissal() {
        val first = Intent().also(failure::putInto)
        assertEquals(failure, GameSessionFailure.takeFrom(first))
        val next = GameSessionFailure("Second game", "A required component is missing.")

        assertEquals(next, GameSessionFailure.takeFrom(Intent().also(next::putInto)))
    }

    @Test fun missingGameNameStillShowsTheActualError() {
        val unnamed = GameSessionFailure("", "The session could not continue.")
        assertEquals(unnamed, GameSessionFailure.takeFrom(Intent().also(unnamed::putInto)))
    }

    @Test fun emptyOrMalformedDeliveryDoesNotCreateAnEmptyDialog() {
        val empty = GameSessionFailure("Test game", " \n ")
        assertNull(GameSessionFailure.takeFrom(Intent().also(empty::putInto)))
        val malformed = Intent().putExtra("app.vapor.android.GAME_SESSION_FAILURE", "not a bundle")
        assertNull(GameSessionFailure.takeFrom(malformed))
        assertNull(GameSessionFailure.takeFrom(malformed))
    }
}
