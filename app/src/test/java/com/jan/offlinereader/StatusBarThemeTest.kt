package com.jan.offlinereader

import android.app.Activity
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], application = ReaderApplication::class)
class StatusBarThemeTest {
    private fun inSystemMode(mode: String, check: (Activity) -> Unit) {
        RuntimeEnvironment.setQualifiers("+$mode")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val actualMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            assertEquals(if (mode == "night") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO, actualMode)
            check(activity)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun darkPaperUsesLightIconsInBothSystemModes() {
        listOf("notnight", "night").forEach { mode ->
            inSystemMode(mode) { activity ->
                applyReaderStatusBar(activity.window, "Dark")
                assertFalse(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
            }
        }
    }

    @Test fun lightAndSepiaPaperUseDarkIconsInBothSystemModes() {
        listOf("notnight", "night").forEach { mode ->
            inSystemMode(mode) { activity ->
                listOf("Light", "Sepia").forEach { theme ->
                    applyReaderStatusBar(activity.window, theme)
                    assertTrue(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
            }
        }
    }

    @Test fun changingPaperThemeUpdatesTheExistingWindow() {
        inSystemMode("notnight") { activity ->
            val icons = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            applyReaderStatusBar(activity.window, "Light")
            assertTrue(icons.isAppearanceLightStatusBars)
            applyReaderStatusBar(activity.window, "Dark")
            assertFalse(icons.isAppearanceLightStatusBars)
            applyReaderStatusBar(activity.window, "Sepia")
            assertTrue(icons.isAppearanceLightStatusBars)
        }
    }
}
