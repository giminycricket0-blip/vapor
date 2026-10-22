package com.winlator.cmod.shared.ui.focus

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val AccentBorder = com.winlator.cmod.shared.theme.WinNativeFocus

/**
 * A gamepad button no control claims comes back as its Generic.kcm fallback key: Y as SPACE, Start/L3/R3 as
 * DPAD_CENTER, which click whatever has focus. A (confirm), B (back) and the guide button keep theirs.
 */
internal fun isUnclaimedGamepadButton(keyCode: Int): Boolean =
    android.view.KeyEvent.isGamepadButton(keyCode) &&
        keyCode != android.view.KeyEvent.KEYCODE_BUTTON_A &&
        keyCode != android.view.KeyEvent.KEYCODE_BUTTON_B &&
        keyCode != android.view.KeyEvent.KEYCODE_BUTTON_MODE

/** For focus-navigated screens: only A presses the focused control. */
fun Modifier.controllerConfirmOnA(): Modifier = onKeyEvent { isUnclaimedGamepadButton(it.nativeKeyEvent.keyCode) }

fun Modifier.controllerFocusBorder(
    cornerRadius: Dp = 10.dp,
    borderWidth: Dp = 2.dp,
    color: Color = AccentBorder,
): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        this
            .onFocusChanged { focused = it.isFocused }
            .drawWithContent {
                drawContent()
                if (focused) {
                    val bw = borderWidth.toPx()
                    val cr = cornerRadius.toPx()
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(bw / 2, bw / 2),
                        size = Size(size.width - bw, size.height - bw),
                        cornerRadius = CornerRadius(cr, cr),
                        style = Stroke(width = bw),
                    )
                }
            }
    }

private val FocusHalo = com.winlator.cmod.shared.theme.WinNativeAccentGlow

/**
 * Soft accent halo just outside a focused control, drawn behind its content.
 * Layered strokes approximate a blur without a RenderEffect, so it costs the
 * same on every API level and stays cheap on handheld GPUs.
 */
fun DrawScope.drawFocusHalo(intensity: Float, cornerRadius: Dp, haloColor: Color = FocusHalo) {
    if (intensity <= 0f) return
    val cr = cornerRadius.toPx()
    val layers = 4
    for (i in layers downTo 1) {
        val spread = (i * 1.75f).dp.toPx()
        drawRoundRect(
            color = haloColor.copy(alpha = 0.10f * intensity * (layers + 1 - i) / layers),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(cr + spread, cr + spread),
        )
    }
}

/** Crisp focus ring drawn over the content, inset so it never clips at the edges. */
fun DrawScope.drawFocusRing(intensity: Float, cornerRadius: Dp, ringColor: Color = AccentBorder, width: Dp = 2.dp) {
    if (intensity <= 0f) return
    val w = width.toPx()
    val cr = cornerRadius.toPx()
    drawRoundRect(
        color = ringColor.copy(alpha = intensity),
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius((cr - w / 2).coerceAtLeast(0f), (cr - w / 2).coerceAtLeast(0f)),
        style = Stroke(width = w),
    )
}

fun Modifier.controllerFocusGlow(
    cornerRadius: Dp = 8.dp,
    color: Color = AccentBorder,
): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        val intensity by animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = tween(160),
            label = "controllerFocusGlow",
        )
        this
            .onFocusChanged { focused = it.isFocused }
            .drawWithContent {
                drawFocusHalo(intensity, cornerRadius)
                if (intensity > 0f) {
                    val cr = cornerRadius.toPx()
                    drawRoundRect(color = color.copy(alpha = 0.06f * intensity), cornerRadius = CornerRadius(cr, cr))
                }
                drawContent()
                drawFocusRing(intensity, cornerRadius, color)
            }
    }

fun Modifier.controllerFocusItem(
    cornerRadius: Dp = 8.dp,
    color: Color = AccentBorder,
    onActivate: (() -> Unit)? = null,
): Modifier =
    composed {
        var focused by remember { mutableStateOf(false) }
        val intensity by animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = tween(160),
            label = "controllerFocusItem",
        )
        this
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (onActivate != null) {
                    Modifier.onKeyEvent { e ->
                        if (e.nativeKeyEvent.action != android.view.KeyEvent.ACTION_UP) {
                            false
                        } else {
                            when (e.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                android.view.KeyEvent.KEYCODE_BUTTON_A,
                                -> {
                                    onActivate()
                                    true
                                }

                                else -> false
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ).focusable()
            .drawWithContent {
                val cr = cornerRadius.toPx()
                if (intensity > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.28f * intensity),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }
                drawContent()
                if (intensity > 0f) {
                    drawRoundRect(
                        color = color.copy(alpha = 0.12f * intensity),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }
            }
    }

fun Modifier.controllerSliderEscape(): Modifier =
    composed {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        this.onPreviewKeyEvent { e ->
            if (e.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (e.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                    else -> false
                }
            }
        }
    }

fun Modifier.controllerTextFieldEscape(): Modifier = controllerSliderEscape()

fun Modifier.controllerMenuInput(
    onDismiss: () -> Unit,
    onSecondary: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    repeatMs: Long = 200L,
): Modifier =
    composed {
        val view = LocalView.current
        val lastMove = remember { longArrayOf(0L) }

        DisposableEffect(view) {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            val unregister = window?.let { com.winlator.cmod.shared.ui.nav.ControllerWindowInput.register(it) }
            val previousCallback = window?.callback
            // Translate before Compose tracks pressed keys. Re-entering Compose from its preview
            // handler can lose the paired press, leaving a focused button inert on A release.
            val confirmCallback = previousCallback?.let { original ->
                object : android.view.Window.Callback by original {
                    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                        val key = if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A) {
                            android.view.KeyEvent(event.downTime, event.eventTime, event.action,
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount,
                                event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
                        } else event
                        return original.dispatchKeyEvent(key)
                    }
                }
            }
            if (confirmCallback != null) window.callback = confirmCallback
            val decor = view.rootView
            val listener =
                android.view.View.OnGenericMotionListener { _, ev ->
                    if ((ev.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
                        ev.action == MotionEvent.ACTION_MOVE
                    ) {
                        val x = ev.getAxisValue(MotionEvent.AXIS_X)
                        val y = ev.getAxisValue(MotionEvent.AXIS_Y)
                        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
                        val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
                        val code =
                            when {
                                x < -0.5f || hx < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                x > 0.5f || hx > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                y < -0.5f || hy < -0.5f -> android.view.KeyEvent.KEYCODE_DPAD_UP
                                y > 0.5f || hy > 0.5f -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                else -> 0
                            }
                        if (code != 0) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastMove[0] >= repeatMs) {
                                lastMove[0] = now
                                decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                                decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                            }
                            return@OnGenericMotionListener true
                        }
                    }
                    false
                }
            decor.setOnGenericMotionListener(listener)
            onDispose {
                unregister?.invoke()
                if (window?.callback === confirmCallback && previousCallback != null) {
                    window.callback = previousCallback
                }
                decor.setOnGenericMotionListener(null)
            }
        }

        this.onPreviewKeyEvent { e ->
            when (e.nativeKeyEvent.keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_A -> {
                    view.dispatchKeyEvent(
                        android.view.KeyEvent(e.nativeKeyEvent.action, android.view.KeyEvent.KEYCODE_DPAD_CENTER),
                    )
                    true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_B -> {
                    if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        val imeVisible =
                            androidx.core.view.ViewCompat
                                .getRootWindowInsets(view)
                                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                        if (imeVisible) {
                            androidx.core.view.ViewCompat
                                .getWindowInsetsController(view)
                                ?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                        } else {
                            onDismiss()
                        }
                    }
                    true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_X -> {
                    if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) onSecondary?.invoke()
                    true
                }

                android.view.KeyEvent.KEYCODE_BUTTON_START -> {
                    if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) onStart?.invoke()
                    true
                }

                else -> isUnclaimedGamepadButton(e.nativeKeyEvent.keyCode)
            }
        }
    }
