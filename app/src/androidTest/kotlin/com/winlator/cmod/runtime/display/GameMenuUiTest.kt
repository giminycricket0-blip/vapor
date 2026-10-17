package com.winlator.cmod.runtime.display

import android.graphics.Bitmap
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.cmod.R
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual display host over an Android View fixture; no game, account or runtime is started. */
@RunWith(AndroidJUnit4::class)
class GameMenuUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var holder: XServerDrawerStateHolder
    private val actions = mutableListOf<String>()
    private var guestTaps = 0
    private var menuRequests = 0

    private fun label(id: Int) = compose.activity.getString(id)

    private fun showHost(compact: Boolean = false) {
        val frame = FrameLayout(compose.activity).apply {
            setBackgroundColor(0xff111b26.toInt())
            addView(TextView(context).apply {
                text = "UI TEST SURFACE\nNo game running"
                gravity = Gravity.CENTER
                setTextColor(0xffaebccc.toInt())
                textSize = 18f
            }, FrameLayout.LayoutParams(-1, -1))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) guestTaps++
                true
            }
        }
        holder = XServerDrawerStateHolder(XServerDrawerState(
            items = listOf(XServerDrawerItem(
                R.id.main_menu_input_controls, "Controls", "RTS", Icons.Outlined.SportsEsports,
            ), XServerDrawerItem(R.id.main_menu_frame_generation, "Performance", "", Icons.Outlined.Speed)),
            fpsLimit = 60,
            maxRefreshRate = 120,
            inputControlsProfileNames = listOf("None", "RTS", "FPS", "Virtual Gamepad"),
            inputControlsSelectedProfileIndex = 1,
            inputControlsShowOverlay = true,
        ))
        val listener = Proxy.newProxyInstance(
            XServerDrawerActionListener::class.java.classLoader,
            arrayOf(XServerDrawerActionListener::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "GameMenuUiTest listener"
                else -> {
                    actions.add(method.name)
                    when (method.name) {
                        "onInputControlsProfileSelected" -> holder.state = holder.state.copy(
                            inputControlsSelectedProfileIndex = args!![0] as Int,
                        )
                        "onInputControlsShowOverlayChanged" -> holder.state = holder.state.copy(
                            inputControlsShowOverlay = args!![0] as Boolean,
                        )
                        "onFPSLimitChanged" -> holder.state = holder.state.copy(fpsLimit = args!![0] as Int)
                    }
                    null
                }
            }
        } as XServerDrawerActionListener
        val callbacks = object : XServerDisplayHostCallbacks {
            override fun onMenuRequested() { menuRequests++; holder.openDrawer() }
            override fun onDrawerSlide() {}
            override fun onDrawerOpened() {}
            override fun onDrawerClosed() {}
            override fun onDrawerGestureClaimed() {}
            override fun onDialogVisibilityChanged(visible: Boolean) {}
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (compact) 1.5f else 1f)) {
                Box(if (compact) Modifier.requiredSize(280.dp, 320.dp) else Modifier.fillMaxSize()) {
                    XServerDisplayHost(frame, holder, listener, callbacks)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(label(R.string.vapor_open_game_menu)).performClick()
        compose.onNodeWithText(label(R.string.vapor_return_to_game)).assertIsDisplayed()
    }

    @Test fun menuAndReturnDoNotReachTheGameSurface() {
        showHost()
        openMenu()
        compose.onNodeWithText(label(R.string.vapor_return_to_game)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(label(R.string.vapor_open_game_menu)).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(holder.isDrawerOpen)
            assertEquals(1, menuRequests)
            assertEquals(0, guestTaps)
            assertTrue(actions.isEmpty())
        }
    }

    @Test fun outsideDismissalIsConsumedAndNextTouchReachesGame() {
        showHost()
        openMenu()
        compose.onNodeWithTag("session_menu_scrim").performTouchInput {
            click(Offset(width - 12f, height * 0.7f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(holder.isDrawerOpen)
            assertEquals(0, guestTaps)
        }
        compose.onRoot().performTouchInput { click(Offset(width - 12f, height * 0.7f)) }
        compose.runOnIdle { assertEquals(1, guestTaps) }
    }

    @Test fun controlsUseExistingCallbacksAndReturnKeepsSelection() {
        showHost()
        openMenu()
        compose.onNodeWithText(label(R.string.session_drawer_rail_label_input_controls)).performClick()
        compose.onNodeWithText("RTS").performClick()
        compose.onNodeWithText("FPS").performClick()
        compose.onNodeWithText(label(R.string.session_drawer_show_touchscreen_controls))
            .performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.vapor_return_to_game)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, holder.state.inputControlsSelectedProfileIndex)
            assertFalse(holder.state.inputControlsShowOverlay)
            assertFalse(holder.isPaneOpen())
            assertFalse(holder.consumeOverlayBack())
            assertEquals(listOf("onInputControlsProfileSelected", "onInputControlsShowOverlayChanged"), actions)
            assertEquals(0, guestTaps)
        }
        openMenu()
        compose.onNodeWithText(label(R.string.session_drawer_rail_label_input_controls)).performClick()
        compose.onNodeWithText("FPS").assertIsDisplayed()
        capture("Vapor-game-menu-controls-test.png")
    }

    @Test fun returnStaysReachableOnSmallScreenWithLargeText() {
        showHost(compact = true)
        openMenu()
        compose.onNodeWithText(label(R.string.session_drawer_rail_label_input_controls)).performClick()
        compose.onNodeWithText(label(R.string.vapor_return_to_game)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(holder.isDrawerOpen) }
    }

    @Test fun dismissingAPaneClearsItsPopupAndAllowsReopening() {
        showHost()
        openMenu()
        compose.onNodeWithText(label(R.string.session_drawer_rail_label_input_controls)).performClick()
        compose.onNodeWithText("RTS").performClick()
        compose.runOnIdle { holder.closeDrawer() }
        compose.waitForIdle()
        compose.onNodeWithText("Virtual Gamepad").assertDoesNotExist()
        compose.runOnIdle { assertFalse(holder.consumeOverlayBack()) }
        openMenu()
        compose.onNodeWithText(label(R.string.vapor_return_to_game)).performClick()
        compose.runOnIdle { assertEquals(2, menuRequests) }
    }

    @Test fun performanceExposesLimitFirstAndKeepsChoiceAcrossMenuReopen() {
        showHost()
        openMenu()
        compose.onNodeWithText(label(R.string.vapor_performance)).performClick()
        compose.onNodeWithText("60 FPS").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, holder.state.fpsLimit) }
        compose.onNode(hasText(label(R.string.session_drawer_fps_limiter_off)) and
            hasText(label(R.string.session_drawer_fps_limiter))).performClick()
        compose.runOnIdle { assertEquals(60, holder.state.fpsLimit) }
        compose.onNodeWithText(label(R.string.vapor_return_to_game)).performClick()
        openMenu()
        compose.onNodeWithText(label(R.string.vapor_performance)).performClick()
        compose.onNodeWithText("60 FPS").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, guestTaps); assertEquals(2, actions.count { it == "onFPSLimitChanged" }) }
        capture("Vapor-performance-limit-fixture.png")
    }

    @Test fun frameLimitIsReachableAtNarrowWidthAndLargeText() {
        showHost(compact = true)
        openMenu()
        compose.onNodeWithText(label(R.string.vapor_performance)).performClick()
        compose.onNodeWithText("60 FPS").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, holder.state.fpsLimit); assertEquals(0, guestTaps) }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
