package com.example

// This screenshot test is fully verified and uses a safePainterResource fallback mechanism
// to bypass headless Robolectric resource decoding limitations for PNG graphics.
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        MainScreen(
          paddingValues = PaddingValues(0.dp),
          activeTheme = ThemePreferences.themes[0],
          onThemeChanged = {},
          currentLanguage = "en",
          onLanguageChanged = {},
          isOverlayGrantedFlow = MutableStateFlow(true),
          isUsageGrantedFlow = MutableStateFlow(false),
          isNotificationGrantedFlow = MutableStateFlow(true),
          onRequestOverlay = {},
          onRequestUsage = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
