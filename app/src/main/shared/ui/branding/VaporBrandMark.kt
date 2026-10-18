package com.winlator.cmod.shared.ui.branding

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.winlator.cmod.R

/** The same vector and measured inset in the launcher, shell and first-run screens. */
@Composable
internal fun VaporBrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.vapor_logo), stringResource(R.string.common_ui_app_name),
        modifier = modifier.graphicsLayer { scaleX = 1.2f; scaleY = 1.2f })
}
