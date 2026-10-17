package com.winlator.cmod.feature.settings

import android.app.Application
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DXVKConfigUtilsTest {
    @Test fun modernWindowsCacheHasAGuestPathAndRetainsTheLegacySetting() {
        val context = RuntimeEnvironment.getApplication()
        val env = EnvVars()
        DXVKConfigUtils.setEnvVars(context, DXVKConfigUtils.parseConfig("version=3.1.1-arm64ec-vapor-0"), env)

        // Modern DXVK opens these files with CreateFileW inside Wine's drive mapping.
        assertEquals("Z:\\home\\xuser\\.cache", env.get("DXVK_SHADER_CACHE_PATH"))
        assertEquals(context.filesDir.toString() + "/imagefs/" + ImageFs.CACHE_PATH,
            env.get("DXVK_STATE_CACHE_PATH"))
    }
}
