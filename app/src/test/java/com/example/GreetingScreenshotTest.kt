package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.example.billing.BillingManager
import com.example.data.AppDatabase
import com.example.data.StoryRepository
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ScribeViewModel
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = AppDatabase.getDatabase(context)
    val repository = StoryRepository(database.storyDao(), database.userSessionDao())
    val billingManager = BillingManager(context, CoroutineScope(Dispatchers.Main))
    val application = context as android.app.Application
    val viewModel = ScribeViewModel(application, repository, billingManager)

    composeTestRule.setContent {
      MyApplicationTheme {
        HeaderSection(
          viewModel = viewModel,
          billingManager = billingManager,
          onOpenProfile = {},
          onOpenPaywall = {}
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
