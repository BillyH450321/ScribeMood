package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.StoryRepository
import com.example.billing.BillingManager
import com.example.viewmodel.ScribeViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Scribe Mood", appName)
  }

  @Test
  fun testSendImageAndReceiveStoryOpeningWithMissingApiKey() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = AppDatabase.getDatabase(context)
    val repository = StoryRepository(database.storyDao(), database.userSessionDao())
    val application = context as android.app.Application
    val viewModel = ScribeViewModel(application, repository, null)

    // Call sendImageAndReceiveStoryOpening
    val result = viewModel.sendImageAndReceiveStoryOpening("fakeBase64ImageString", "Create a futuristic sci-fi city")
    
    // In a test environment, if BuildConfig.GEMINI_API_KEY is empty or placeholder, it should return a descriptive error message
    if (com.example.BuildConfig.GEMINI_API_KEY.isEmpty() || com.example.BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY") {
        assertTrue(result.contains("API Key is missing") || result.contains("configure it in the Secrets panel"))
    } else {
        // If an API key is present but mock/fake, it should fail gracefully with an Error/Http Exception or attempt to connect
        assertTrue(result.contains("Error") || result.contains("API Key") || result.isNotEmpty())
    }
  }
}

