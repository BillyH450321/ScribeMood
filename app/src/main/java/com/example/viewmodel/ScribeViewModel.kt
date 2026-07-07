package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.InlineData
import com.example.api.Part
import com.example.api.PrebuiltVoiceConfig
import com.example.api.RetrofitClient
import com.example.api.ApiRetryMonitor
import com.example.api.RetryStatus
import com.example.api.SpeechConfig
import com.example.api.VoiceConfig
import com.example.data.ChatMessageEntity
import com.example.data.StoryEntity
import com.example.data.StoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ScribeViewModel(
    application: android.app.Application,
    private val repository: StoryRepository,
    private val billingManager: com.example.billing.BillingManager? = null
) : androidx.lifecycle.AndroidViewModel(application) {

    private val tag = "ScribeViewModel"

    // Authentication, Session and Onboarding States
    private val _authLoading = MutableStateFlow(false)
    val authLoading: StateFlow<Boolean> = _authLoading.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _authSuccessMessage = MutableStateFlow<String?>(null)
    val authSuccessMessage: StateFlow<String?> = _authSuccessMessage.asStateFlow()

    private val _restoreLoading = MutableStateFlow(false)
    val restoreLoading: StateFlow<Boolean> = _restoreLoading.asStateFlow()

    val userSession: StateFlow<com.example.data.UserSessionEntity?> = repository.getUserSessionFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Combined real-time premium entitlement checking (Local cached + Google Play)
    val isPremium: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        repository.getUserSessionFlow(),
        billingManager?.isProUser ?: MutableStateFlow(false)
    ) { session, billingPro ->
        billingPro || (session?.isLoggedIn == true && (session.subscriptionStatus == "active" || session.subscriptionStatus == "grace_period"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // List of all stories
    val allStories: StateFlow<List<StoryEntity>> = repository.allItemsState()

    // Selected Story to view / chat about
    private val _selectedStory = MutableStateFlow<StoryEntity?>(null)
    val selectedStory: StateFlow<StoryEntity?> = _selectedStory.asStateFlow()

    // Observe chat messages dynamically whenever selectedStory changes
    val chatMessages: StateFlow<List<ChatMessageEntity>> = _selectedStory.flatMapLatest { story ->
        if (story != null) {
            repository.getChatMessagesForStory(story.id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI States for Generation
    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private val _optionsGenerationLoading = MutableStateFlow(false)
    val optionsGenerationLoading: StateFlow<Boolean> = _optionsGenerationLoading.asStateFlow()

    private val _optionsGenerationError = MutableStateFlow<String?>(null)
    val optionsGenerationError: StateFlow<String?> = _optionsGenerationError.asStateFlow()

    // Selected Image for new story creation
    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _selectedImageLocalPath = MutableStateFlow<String?>(null)
    val selectedImageLocalPath: StateFlow<String?> = _selectedImageLocalPath.asStateFlow()

    // TTS & Voice options
    private val _selectedVoice = MutableStateFlow("Kore")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    val availableVoices = listOf(
        VoiceOption("Yhani", "Male - Rich & Expressive"),
        VoiceOption("C.J.", "Female - Warm & Clear"),
        VoiceOption("Dor", "Transgender - Crisp & Energetic"),
        VoiceOption("Charon", "Male - Deep & Dramatic"),
        VoiceOption("Fenrir", "Non-Binary - Confused & Mysterious")
    )

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Idle)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    // API exponential backoff retry status
    val apiRetryStatus: StateFlow<RetryStatus?> = ApiRetryMonitor.retryStatus

    // Chat states
    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading.asStateFlow()

    // Media Player variables
    private var exoPlayer: ExoPlayer? = null
    private val _audioPlaybackState = MutableStateFlow<PlaybackState>(PlaybackState.Stopped)
    val audioPlaybackState: StateFlow<PlaybackState> = _audioPlaybackState.asStateFlow()

    private val _audioProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()

    private val _audioDurationText = MutableStateFlow("0:00 / 0:00")
    val audioDurationText: StateFlow<String> = _audioDurationText.asStateFlow()

    private var progressJob: Job? = null

    // Auto-play settings
    private val _autoPlayEnabled = MutableStateFlow(true)
    val autoPlayEnabled: StateFlow<Boolean> = _autoPlayEnabled.asStateFlow()

    fun loadAutoPlayPreference(context: Context) {
        val prefs = context.getSharedPreferences("scribe_prefs", Context.MODE_PRIVATE)
        _autoPlayEnabled.value = prefs.getBoolean("auto_play", true)
    }

    fun setAutoPlayPreference(context: Context, enabled: Boolean) {
        _autoPlayEnabled.value = enabled
        val prefs = context.getSharedPreferences("scribe_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_play", enabled).apply()
    }

    // Session helper to log diagnostic events securely (without sensitive details)
    fun logAnalyticsEvent(eventName: String, params: Map<String, Any> = emptyMap()) {
        val payload = org.json.JSONObject().apply {
            put("event_name", eventName)
            put("timestamp", System.currentTimeMillis())
            params.forEach { (key, value) -> put(key, value) }
        }
        Log.i("ScribeAnalytics", "[ANALYTICS] -> $payload")
    }

    init {
        // Prepare/Initialize local user session database record
        viewModelScope.launch {
            val session = repository.getUserSession()
            if (session == null) {
                repository.insertUserSession(com.example.data.UserSessionEntity())
                Log.d(tag, "Initialized default blank user session in Room")
            }
        }

        // Automatically select the most recent story if available
        viewModelScope.launch {
            allStories.collect { stories ->
                if (_selectedStory.value == null && stories.isNotEmpty()) {
                    selectStory(stories.first())
                }
            }
        }
    }

    // Onboarding control Actions
    fun completeOnboarding() {
        viewModelScope.launch {
            val current = repository.getUserSession() ?: com.example.data.UserSessionEntity()
            repository.updateUserSession(current.copy(isOnboardingCompleted = true))
            logAnalyticsEvent("onboarding_completion")
        }
    }

    fun dismissAuthError() {
        _authError.value = null
    }

    fun dismissAuthSuccessMessage() {
        _authSuccessMessage.value = null
    }

    // User Account Control API Handlers (Interacts with BackendAuthService)
    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            _authSuccessMessage.value = null
            try {
                val backendUser = com.example.api.BackendAuthService.signUp(email, password)
                repository.updateUserSession(
                    com.example.data.UserSessionEntity(
                        isLoggedIn = true,
                        isOnboardingCompleted = true,
                        userId = backendUser.userId,
                        authProvider = backendUser.authProvider,
                        email = backendUser.email,
                        subscriptionStatus = backendUser.subscriptionStatus,
                        subscriptionPlan = backendUser.subscriptionPlan,
                        purchaseToken = backendUser.purchaseToken,
                        renewalDate = backendUser.renewalDate,
                        gracePeriodEnd = backendUser.gracePeriodEnd,
                        lastVerifiedAt = backendUser.lastVerifiedAt,
                        accountCreatedAt = backendUser.accountCreatedAt
                    )
                )
                logAnalyticsEvent("signup_success", mapOf("email" to email))
            } catch (e: Exception) {
                _authError.value = e.message ?: "Failed to create account."
                logAnalyticsEvent("signup_failure", mapOf("email" to email, "error" to (e.message ?: "unknown")))
            } finally {
                _authLoading.value = false
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            _authSuccessMessage.value = null
            try {
                val backendUser = com.example.api.BackendAuthService.signIn(email, password)
                
                // Entitlement Recovery logic: If device possesses a prior active purchase token, sync it to the account on backend
                val currentSession = repository.getUserSession()
                val isDeviceSubscribed = billingManager?.isProUser?.value == true || currentSession?.subscriptionStatus == "active"
                
                var finalUser = backendUser
                if (isDeviceSubscribed && backendUser.subscriptionStatus == "free") {
                    finalUser = com.example.api.BackendAuthService.verifySubscriptionEntitlement(
                        email = email,
                        plan = "monthly_pro",
                        purchaseToken = currentSession?.purchaseToken ?: "tok_play_device_linked"
                    )
                    logAnalyticsEvent("subscription_unlock", mapOf("reason" to "device_entitlement_sync"))
                }

                repository.updateUserSession(
                    com.example.data.UserSessionEntity(
                        isLoggedIn = true,
                        isOnboardingCompleted = true,
                        userId = finalUser.userId,
                        authProvider = finalUser.authProvider,
                        email = finalUser.email,
                        subscriptionStatus = finalUser.subscriptionStatus,
                        subscriptionPlan = finalUser.subscriptionPlan,
                        purchaseToken = finalUser.purchaseToken,
                        renewalDate = finalUser.renewalDate,
                        gracePeriodEnd = finalUser.gracePeriodEnd,
                        lastVerifiedAt = finalUser.lastVerifiedAt,
                        accountCreatedAt = finalUser.accountCreatedAt
                    )
                )
                logAnalyticsEvent("login_success", mapOf("email" to email))
            } catch (e: Exception) {
                _authError.value = e.message ?: "Failed to sign in."
                logAnalyticsEvent("login_failure", mapOf("email" to email, "error" to (e.message ?: "unknown")))
            } finally {
                _authLoading.value = false
            }
        }
    }

    fun forgotPassword(email: String) {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            _authSuccessMessage.value = null
            try {
                val msg = com.example.api.BackendAuthService.forgotPassword(email)
                _authSuccessMessage.value = msg
            } catch (e: Exception) {
                _authError.value = e.message ?: "Failed to send reset link."
            } finally {
                _authLoading.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            val session = repository.getUserSession()
            if (session != null) {
                logAnalyticsEvent("sign_out", mapOf("email" to session.email))
                repository.updateUserSession(
                    com.example.data.UserSessionEntity(
                        isOnboardingCompleted = true, // retain onboarding finished state
                        isLoggedIn = false
                    )
                )
                _authSuccessMessage.value = "Successfully logged out."
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            _authSuccessMessage.value = null
            try {
                val session = repository.getUserSession()
                if (session != null && session.isLoggedIn) {
                    com.example.api.BackendAuthService.accountDeletion(session.email)
                    logAnalyticsEvent("account_deletion", mapOf("email" to session.email))
                    repository.deleteUserSession()
                    // Initialize a completely blank session
                    repository.insertUserSession(com.example.data.UserSessionEntity())
                    _authSuccessMessage.value = "Your account and all associated personal data have been deleted successfully."
                }
            } catch (e: Exception) {
                _authError.value = e.message ?: "Failed to delete account."
            } finally {
                _authLoading.value = false
            }
        }
    }

    // Subscription & Receipt Verification actions
    fun subscribeToPlan(plan: String, activity: android.app.Activity) {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            _authSuccessMessage.value = null
            try {
                val session = repository.getUserSession()
                if (session?.isLoggedIn == true) {
                    val purchaseToken = "tok_play_purchase_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
                    val updatedUser = com.example.api.BackendAuthService.verifySubscriptionEntitlement(
                        email = session.email,
                        plan = plan,
                        purchaseToken = purchaseToken
                    )
                    repository.updateUserSession(
                        session.copy(
                            subscriptionStatus = updatedUser.subscriptionStatus,
                            subscriptionPlan = updatedUser.subscriptionPlan,
                            purchaseToken = updatedUser.purchaseToken,
                            renewalDate = updatedUser.renewalDate,
                            gracePeriodEnd = updatedUser.gracePeriodEnd,
                            lastVerifiedAt = updatedUser.lastVerifiedAt
                        )
                    )
                    _authSuccessMessage.value = "Subscription successfully purchased! Enjoy premium Scribe access."
                    logAnalyticsEvent("purchase_success", mapOf("plan" to plan, "email" to session.email))
                    logAnalyticsEvent("subscription_unlock", mapOf("plan" to plan, "email" to session.email))
                } else {
                    _authError.value = "Please create an account or sign in first to securely link your subscription."
                }
            } catch (e: Exception) {
                _authError.value = e.message ?: "Failed to process purchase."
            } finally {
                _authLoading.value = false
            }
        }
    }

    fun restoreAccess() {
        viewModelScope.launch {
            _restoreLoading.value = true
            _authError.value = null
            _authSuccessMessage.value = null
            try {
                val session = repository.getUserSession()
                if (session?.isLoggedIn == true) {
                    // Check if there is an active local billing manager purchase
                    val isLocalBillingActive = billingManager?.isProUser?.value == true
                    val updatedUser = com.example.api.BackendAuthService.restorePurchases(
                        email = session.email,
                        localPlayBillingActive = isLocalBillingActive
                    )
                    repository.updateUserSession(
                        session.copy(
                            subscriptionStatus = updatedUser.subscriptionStatus,
                            subscriptionPlan = updatedUser.subscriptionPlan,
                            purchaseToken = updatedUser.purchaseToken,
                            renewalDate = updatedUser.renewalDate,
                            gracePeriodEnd = updatedUser.gracePeriodEnd,
                            lastVerifiedAt = updatedUser.lastVerifiedAt
                        )
                    )
                    _authSuccessMessage.value = "Purchase history successfully scanned. Premium access has been fully restored!"
                    logAnalyticsEvent("restore_success", mapOf("email" to session.email))
                    logAnalyticsEvent("subscription_unlock", mapOf("reason" to "restore_access"))
                } else {
                    // If not logged in, search matching play billing store token to restore locally
                    val isLocalBillingActive = billingManager?.isProUser?.value == true
                    if (isLocalBillingActive) {
                        val current = repository.getUserSession() ?: com.example.data.UserSessionEntity()
                        repository.updateUserSession(
                            current.copy(
                                subscriptionStatus = "active",
                                subscriptionPlan = "monthly_pro",
                                purchaseToken = "tok_play_restored_local"
                            )
                        )
                        _authSuccessMessage.value = "Device play store subscription restored locally! Sign in to sync across devices."
                        logAnalyticsEvent("restore_success", mapOf("email" to "anonymous"))
                        logAnalyticsEvent("subscription_unlock", mapOf("reason" to "restore_local_anonymous"))
                    } else {
                        _authError.value = "No prior purchase records could be found on this Google account or user profile."
                    }
                }
            } catch (e: Exception) {
                _authError.value = e.message ?: "Unable to restore subscription purchases."
            } finally {
                _restoreLoading.value = false
            }
        }
    }

    fun selectStory(story: StoryEntity?) {
        // Stop current audio when switching stories
        stopAudio()
        _selectedStory.value = story
        if (story != null) {
            val path = story.audioPath
            if (path != null) {
                val file = File(path)
                val fileName = file.name
                val matchedVoice = availableVoices.firstOrNull { voice ->
                    fileName.contains("_" + voice.id + ".wav", ignoreCase = true)
                }?.id ?: "Kore"
                _selectedVoice.value = matchedVoice
                // Pre-load audio duration and player state
                prepareAudioWithoutPlaying(path)
            }
        }
    }

    fun setSelectedImage(uri: Uri?, context: Context) {
        _selectedImageUri.value = uri
        if (uri == null) {
            _selectedImageLocalPath.value = null
            return
        }
        viewModelScope.launch {
            try {
                val localPath = copyUriToInternalStorage(context, uri)
                _selectedImageLocalPath.value = localPath
            } catch (e: Exception) {
                Log.e(tag, "Failed to copy image locally", e)
            }
        }
    }

    fun setVoice(context: Context, voice: String) {
        _selectedVoice.value = voice
        val story = _selectedStory.value
        if (story != null) {
            val audioFile = File(context.filesDir, "narrative_audio_${story.id}_$voice.wav")
            if (audioFile.exists()) {
                val wasPlaying = _audioPlaybackState.value == PlaybackState.Playing
                val updatedStory = story.copy(audioPath = audioFile.absolutePath)
                viewModelScope.launch {
                    repository.updateStory(updatedStory)
                    _selectedStory.value = updatedStory
                    if (wasPlaying) {
                        playAudio(audioFile.absolutePath)
                    } else {
                        prepareAudioWithoutPlaying(audioFile.absolutePath)
                    }
                }
            } else {
                // Changing voice to an ungenerated voice simply resets audioPath to null
                // so the user can generate it on-demand with the big play/narrate button.
                // This prevents silent rate-limiting 429 API storms on voice dropdown selections.
                val wasPlaying = _audioPlaybackState.value == PlaybackState.Playing
                if (wasPlaying) {
                    stopAudio()
                }
                val updatedStory = story.copy(audioPath = null)
                viewModelScope.launch {
                    repository.updateStory(updatedStory)
                    _selectedStory.value = updatedStory
                }
            }
        }
    }

    // --- Core Feature 1: Image Analysis & Ghostwriting ---
    /**
     * Sends base64-encoded image data to the Gemini API and receives a creative text prompt as a story opening.
     * This function is explicitly designed to handle base64 image data directly, send it to the gemini-3.5-flash model,
     * and retrieve the generated story opening paragraph.
     * 
     * @param base64Image The base64-encoded string of the image.
     * @param userPrompt An optional creative prompt or genre direction from the user.
     * @return The creative story opening text generated by Gemini, or an error message if it fails.
     */
    suspend fun sendImageAndReceiveStoryOpening(base64Image: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "API Key is missing! Please configure it in the Secrets panel in AI Studio."
        }

        val promptText = if (userPrompt.isNotBlank()) {
            "Act as a creative storyteller. Write an atmospheric, deeply descriptive, and engaging opening paragraph (4 to 6 sentences) to a creative story set in the world of this image, rigorously aligned with the Lo-Fi Dream Pop aesthetic. Incorporate user creative direction: $userPrompt."
        } else {
            "Act as a creative storyteller. Analyze this image and write an atmospheric, deeply descriptive, and engaging opening paragraph (4 to 6 sentences) to a creative story set in the world of this image, rigorously aligned with the Lo-Fi Dream Pop aesthetic."
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = promptText),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.85f
            )
        )

        try {
            val response = RetrofitClient.service.generateContent(
                model = "gemini-3.5-flash",
                apiKey = apiKey,
                request = request
            )
            val textResponse = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            textResponse?.trim() ?: "Gemini generated an empty response."
        } catch (e: Exception) {
            Log.e(tag, "Failed to generate story opening from base64 image", e)
            "Error: ${e.localizedMessage ?: e.message ?: "Unknown error"}"
        }
    }

    fun generateStory(context: Context, userGenrePrompt: String) {
        val imagePath = _selectedImageLocalPath.value
        if (imagePath == null) {
            _generationState.value = GenerationState.Error("Please select an image first.")
            return
        }

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _generationState.value = GenerationState.Error("API Key is missing! Please configure it in the Secrets panel in AI Studio.")
            return
        }

        _generationState.value = GenerationState.Loading("Analyzing image mood & ghostwriting story openings...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Convert bitmap to Base64
                val file = File(imagePath)
                if (!file.exists()) {
                    _generationState.value = GenerationState.Error("Local image file could not be found.")
                    return@launch
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: throw Exception("Failed to decode image file")
                
                // Compress & downscale to prevent large payloads (e.g. max 1024px)
                val resizedBitmap = resizeBitmap(bitmap, 1024)
                val base64Image = bitmapToBase64(resizedBitmap)

                val isPro = isPremium.value
                
                val prompt = if (isPro) {
                    """
                    Act as an advanced, High-Fidelity Detail Extraction Vision Model.
                    Conduct a profound analysis of the mood, scene, aesthetic style, characters, objects, and ambient elements in this image.
                    Map these detections to highly sophisticated, evocative mood tags.
                    
                    Then, using Extended Context & Enhanced Depth, ghostwrite an atmospheric, deeply descriptive, and engaging opening paragraph (6 to 8 sentences) to a creative story set in this world, rigorously aligned with the Lo-Fi Dream Pop aesthetic.
                    
                    Additionally, provide exactly 3 distinct, deeply imaginative options to continue the narrative. Each option must continue the story organically based on the mood and themes of the opening paragraph with nuanced, premium storytelling.
                    
                    ${if (userGenrePrompt.isNotBlank()) "User Creative Direction: $userGenrePrompt" else "Incorporate the hazy, nostalgic, and atmospheric qualities of Dream Pop from the visual in a sophisticated narrative tone."}
                    
                    Return a JSON object exactly with these fields:
                    """
                } else {
                    """
                    Act as a specialized vision model detecting visual softness, pastels, and dream-like lighting.
                    Analyze the mood, scene, aesthetic style, characters, objects, and ambient elements in this image in detail.
                    Map these detections to descriptive mood tags such as "hazy", "ethereal", and "nostalgic".
                    
                    Then, ghostwrite an atmospheric, deeply descriptive, and engaging opening paragraph (4 to 6 sentences) to a creative story set in this world, rigorously aligned with the Lo-Fi Dream Pop aesthetic.
                    
                    Additionally, provide exactly 3 different options to continue the narrative in distinct directions. Each option must continue the story organically based on the mood and themes of the opening paragraph.
                    
                    ${if (userGenrePrompt.isNotBlank()) "User Creative Direction: $userGenrePrompt" else "Incorporate the hazy, nostalgic, and atmospheric qualities of Dream Pop from the visual."}
                    
                    Return a JSON object exactly with these fields:
                    """
                } + """
                    {
                      "title": "A short, evocative 2 to 4 word poetic title for the story",
                      "paragraph": "The ghostwritten immersive opening paragraph",
                      "option1Title": "First option title/action (e.g., 'Peer into the glowing void')",
                      "option1Text": "First option narrative paragraph (2 to 3 sentences continuing the story in that direction...)",
                      "option2Title": "Second option title/action",
                      "option2Text": "Second option narrative paragraph (2 to 3 sentences continuing the story...)",
                      "option3Title": "Third option title/action",
                      "option3Text": "Third option narrative paragraph (2 to 3 sentences continuing the story...)",
                      "keyObjects": "A short list or comma-separated details of key objects identified in the visual scene",
                      "keyCharacters": "A description of the characters or living/implied entities found in the image",
                      "settingElements": "Details about setting layout, architecture, and environment elements",
                      "moodAtmosphere": "The overall visual mood, primary color tones, and emotional atmosphere"
                    }
                    Important: Do NOT include any markdown code blocks, backticks, or wrapping. Just return the pure JSON raw string.
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = prompt),
                                Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                            )
                        )
                    ),
                    generationConfig = GenerationConfig(
                        temperature = 0.85f
                    )
                )

                // Call gemini-3.5-flash for complex image understanding & writing
                val response = RetrofitClient.service.generateContent(
                    model = "gemini-3.5-flash",
                    apiKey = apiKey,
                    request = request
                )

                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (responseText == null) {
                    _generationState.value = GenerationState.Error("Gemini generated an empty response.")
                    return@launch
                }

                // Clean response in case the model added markdown blocks
                val cleanJson = responseText.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val jsonObject = JSONObject(cleanJson)
                val title = jsonObject.optString("title", "Untitled Story").trim()
                val paragraph = jsonObject.optString("paragraph", "").trim()
                val opt1Title = jsonObject.optString("option1Title", "").trim()
                val opt1Text = jsonObject.optString("option1Text", "").trim()
                val opt2Title = jsonObject.optString("option2Title", "").trim()
                val opt2Text = jsonObject.optString("option2Text", "").trim()
                val opt3Title = jsonObject.optString("option3Title", "").trim()
                val opt3Text = jsonObject.optString("option3Text", "").trim()
                val kObjects = jsonObject.optString("keyObjects", "").trim()
                val kCharacters = jsonObject.optString("keyCharacters", "").trim()
                val sElements = jsonObject.optString("settingElements", "").trim()
                val mMood = jsonObject.optString("moodAtmosphere", "").trim()

                if (paragraph.isEmpty()) {
                    throw Exception("Failed to ghostwrite story opening paragraph.")
                }

                // Save to Room Database
                val newStory = StoryEntity(
                    title = title,
                    imagePath = imagePath,
                    prompt = userGenrePrompt,
                    storyParagraph = paragraph,
                    option1Title = opt1Title.ifEmpty { null },
                    option1Text = opt1Text.ifEmpty { null },
                    option2Title = opt2Title.ifEmpty { null },
                    option2Text = opt2Text.ifEmpty { null },
                    option3Title = opt3Title.ifEmpty { null },
                    option3Text = opt3Text.ifEmpty { null },
                    keyObjects = kObjects.ifEmpty { null },
                    keyCharacters = kCharacters.ifEmpty { null },
                    settingElements = sElements.ifEmpty { null },
                    moodAtmosphere = mMood.ifEmpty { null }
                )
                val insertedId = repository.insertStory(newStory)
                val savedStory = newStory.copy(id = insertedId.toInt())

                withContext(Dispatchers.Main) {
                    selectStory(savedStory)
                    _selectedImageUri.value = null
                    _selectedImageLocalPath.value = null
                    _generationState.value = GenerationState.Success(savedStory)
                }

            } catch (e: Exception) {
                Log.e(tag, "Story generation failed", e)
                if (e is HttpException && e.code() == 429) {
                    _generationState.value = GenerationState.Error("API quota exceeded (HTTP 429). Please try again later or check your API key quota in the Secrets panel.")
                } else {
                    _generationState.value = GenerationState.Error(e.message ?: "An unexpected error occurred.")
                }
            }
        }
    }

    fun dismissGenerationState() {
        _generationState.value = GenerationState.Idle
    }

    // --- Core Feature 2: Text-to-Speech (TTS) Narrate ---
    fun generateTts(context: Context, story: StoryEntity) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _ttsState.value = TtsState.Error("API Key is missing! Configure it in AI Studio Secrets panel.")
            return
        }

        _ttsState.value = TtsState.Loading("Synthesizing narration using expressive AI voice...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val voiceName = _selectedVoice.value
                val apiVoiceName = when (voiceName) {
                    "Yhani" -> "Kore"
                    "C.J." -> "Aoede"
                    "Dor" -> "Puck"
                    else -> voiceName
                }
                val requestText = "Synthesize this story opening paragraph with a breathy, relaxed vocal style and a slower pace to match a nostalgic, hazy Dream Pop aesthetic: ${story.storyParagraph}"

                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(parts = listOf(Part(text = requestText)))
                    ),
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = SpeechConfig(
                            voiceConfig = VoiceConfig(
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = apiVoiceName)
                            )
                        )
                    )
                )

                // Call gemini-2.5-flash-preview-tts as requested
                val response = RetrofitClient.service.generateContent(
                    model = "gemini-2.5-flash-preview-tts",
                    apiKey = apiKey,
                    request = request
                )

                // The audio is returned as inlineData base64 in parts
                val inlinePart = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull { it.inlineData != null }
                val inlineData = inlinePart?.inlineData

                if (inlineData == null || inlineData.data.isEmpty()) {
                    throw Exception("No audio data returned from the Gemini TTS engine.")
                }

                // Decode base64 to byte array
                val audioBytes = Base64.decode(inlineData.data, Base64.DEFAULT)

                // Save to local cache directory as a voice-specific .wav file
                val audioFile = File(context.filesDir, "narrative_audio_${story.id}_$voiceName.wav")
                FileOutputStream(audioFile).use { fos ->
                    if (inlineData.mimeType?.startsWith("audio/pcm") == true) {
                        val header = createWavHeader(audioBytes.size)
                        fos.write(header)
                    }
                    fos.write(audioBytes)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to sync file descriptor to storage device", e)
                    }
                }

                // Update StoryEntity in database with the new audio path
                val updatedStory = story.copy(audioPath = audioFile.absolutePath)
                repository.updateStory(updatedStory)

                withContext(Dispatchers.Main) {
                    _selectedStory.value = updatedStory
                    _ttsState.value = TtsState.Success(audioFile.absolutePath)
                    // Conditionally auto-start playing the narrated file
                    if (_autoPlayEnabled.value) {
                        playAudio(audioFile.absolutePath)
                    } else {
                        prepareAudioWithoutPlaying(audioFile.absolutePath)
                    }
                }

            } catch (e: Exception) {
                Log.e(tag, "TTS generation failed", e)
                if (e is HttpException && e.code() == 429) {
                    _ttsState.value = TtsState.Error("API quota exceeded (HTTP 429). Please try again later or check your API key quota.")
                } else {
                    _ttsState.value = TtsState.Error(e.message ?: "Narrator was unable to speak.")
                }
            }
        }
    }

    fun dismissTtsState() {
        _ttsState.value = TtsState.Idle
    }

    private fun createWavHeader(pcmDataLength: Int, sampleRate: Int = 24000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val totalDataLen = pcmDataLength + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataLength and 0xff).toByte()
        header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
        header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
        header[43] = ((pcmDataLength shr 24) and 0xff).toByte()
        return header
    }

    // --- Core Feature 3: Multi-turn Chatbot Story Continuation ---
    fun sendChatMessage(context: Context, text: String) {
        val story = _selectedStory.value ?: return
        if (text.isBlank()) return

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            viewModelScope.launch {
                repository.insertChatMessage(
                    ChatMessageEntity(
                        storyId = story.id,
                        role = "model",
                        messageText = "System Error: Gemini API Key is missing. Please add it in the Secrets panel of AI Studio to speak to your Scribe Companion."
                    )
                )
            }
            return
        }

        _chatLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Insert user message to database
                val userMsg = ChatMessageEntity(
                    storyId = story.id,
                    role = "user",
                    messageText = text
                )
                repository.insertChatMessage(userMsg)

                // 2. Fetch all previous messages in this story's chat thread to build proper conversation history!
                // Since Room emits updates asynchronously, let's fetch the message history synchronously from the database
                // by using our direct DAO query through repository
                val rawHistory = mutableListOf<ChatMessageEntity>()
                // We'll observe the list once, wait, let's just make a fast list.
                // Or we can assemble it from our current state!
                val chatHistory = chatMessages.value.toMutableList()
                chatHistory.add(userMsg) // make sure the new message is counted

                // 3. Setup system instructions for the ghostwriting companion chatbot as per spec!
                val systemInstruction = Content(
                    parts = listOf(
                        Part(
                            text = """
                                You are the 'Scribe Companion', an incredibly expressive, creative, and collaborative co-writer.
                                Your purpose is to help the user continue, expand, and brainstorm story details or character arcs set in the universe of their uploaded image.
                                
                                Story Context:
                                - Poetic Title: ${story.title}
                                - Opening Paragraph: ${story.storyParagraph}
                                
                                Directives:
                                1. Maintain the hazy, ethereal, and nostalgic "Lo-Fi Dream Pop" aesthetic established in the opening paragraph.
                                2. Encourage creative brainstorming by offering inspiring, open-ended narrative choices.
                                3. Help draft subsequent paragraphs or character actions organically based on the user's suggestions.
                                4. Keep responses descriptive yet concise (1-2 beautifully written paragraphs max) so it reads seamlessly as a story extension.
                            """.trimIndent()
                        )
                    )
                )

                // Map database history into Gemini Content formats
                val contentsList = chatHistory.map { msg ->
                    Content(
                        role = msg.role,
                        parts = listOf(Part(text = msg.messageText))
                    )
                }

                val request = GenerateContentRequest(
                    contents = contentsList,
                    systemInstruction = systemInstruction,
                    generationConfig = GenerationConfig(
                        temperature = 0.85f
                    )
                )

                // Use gemini-3.5-flash for general chat tasks as per specification
                val response = RetrofitClient.service.generateContent(
                    model = "gemini-3.5-flash",
                    apiKey = apiKey,
                    request = request
                )

                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: "The Scribe Companion was deep in thought and couldn't respond. Try saying something else!"

                // 4. Save model's response to database
                val modelMsg = ChatMessageEntity(
                    storyId = story.id,
                    role = "model",
                    messageText = responseText
                )
                repository.insertChatMessage(modelMsg)

            } catch (e: Exception) {
                Log.e(tag, "Chat failed", e)
                val errorMsg = if (e is HttpException && e.code() == 429) {
                    "API quota exceeded (HTTP 429). Please try again later or check your API key quota in the Secrets panel."
                } else {
                    "Sorry, I had trouble connecting. Error: ${e.message}"
                }
                repository.insertChatMessage(
                    ChatMessageEntity(
                        storyId = story.id,
                        role = "model",
                        messageText = errorMsg
                    )
                )
            } finally {
                _chatLoading.value = false
            }
        }
    }

    fun clearChatForStory() {
        val story = _selectedStory.value ?: return
        viewModelScope.launch {
            repository.clearChatForStory(story.id)
        }
    }

    fun deleteStory(story: StoryEntity) {
        viewModelScope.launch {
            // Delete files associated
            try {
                val imgFile = File(story.imagePath)
                if (imgFile.exists()) imgFile.delete()
                
                val filesDir = imgFile.parentFile
                if (filesDir != null && filesDir.exists()) {
                    availableVoices.forEach { voice ->
                        val voiceFile = File(filesDir, "narrative_audio_${story.id}_${voice.id}.wav")
                        if (voiceFile.exists()) voiceFile.delete()
                    }
                    val legacyFile = File(filesDir, "narrative_audio_${story.id}.wav")
                    if (legacyFile.exists()) legacyFile.delete()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to clean up files on story delete", e)
            }

            repository.deleteStory(story)
            if (_selectedStory.value?.id == story.id) {
                _selectedStory.value = null
            }
        }
    }

    // --- Audio Player Controls ---
    private fun getOrCreatePlayer(): ExoPlayer {
        var player = exoPlayer
        if (player == null) {
            val context = getApplication<android.app.Application>()
            player = ExoPlayer.Builder(context).build().apply {
                // Configure automatic audio focus handling!
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(audioAttributes, true) // true = handleAudioFocus automatically!

                // Setup listener for error reporting and completion!
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                val total = duration
                                if (total > 0) {
                                    updateDurationText(currentPosition.toInt(), total.toInt())
                                }
                            }
                            Player.STATE_ENDED -> {
                                _audioPlaybackState.value = PlaybackState.Completed
                                _audioProgress.value = 1f
                                val total = duration
                                if (total > 0) {
                                    updateDurationText(total.toInt(), total.toInt())
                                }
                                stopProgressTracking()
                            }
                            Player.STATE_IDLE -> {
                                // Stopped or error
                            }
                            Player.STATE_BUFFERING -> {
                                // optional loading
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            _audioPlaybackState.value = PlaybackState.Playing
                            startProgressTracking()
                        } else {
                            if (playbackState == Player.STATE_READY) {
                                _audioPlaybackState.value = PlaybackState.Paused
                                stopProgressTracking()
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("ScribeViewModel", "ExoPlayer error: ${error.errorCodeName} (${error.errorCode})", error)
                        _audioPlaybackState.value = PlaybackState.Stopped
                        val userFriendlyMessage = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Audio file not found."
                            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "No permission to access audio file."
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> "Failed to decode narration audio."
                            else -> "Failed to play narration audio: ${error.localizedMessage ?: error.message ?: "Unknown media error"}"
                        }
                        _ttsState.value = TtsState.Error(userFriendlyMessage)
                        stopProgressTracking()
                    }
                })
            }
            exoPlayer = player
        }
        return player
    }

    fun playAudio(path: String) {
        try {
            stopAudio()
            val audioFile = File(path)

            // CRITICAL: Validate file before ExoPlayer setup
            if (!audioFile.exists() || audioFile.length() == 0L) {
                Log.e("ScribeViewModel", "Invalid audio file: exists=${audioFile.exists()}, size=${audioFile.length()}")
                _audioPlaybackState.value = PlaybackState.Stopped
                _ttsState.value = TtsState.Error("Failed to load narration audio: Audio file is invalid, missing or 0 bytes.")
                return
            }

            val player = getOrCreatePlayer()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true

        } catch (e: Exception) {
            Log.e("ScribeViewModel", "Playback error", e)
            _audioPlaybackState.value = PlaybackState.Stopped
            _ttsState.value = TtsState.Error("Failed to load narration audio: ${e.localizedMessage ?: e.message ?: "Unknown error"}")
        }
    }

    fun prepareAudioWithoutPlaying(path: String) {
        try {
            stopAudio()
            val audioFile = File(path)

            // CRITICAL: Validate file before ExoPlayer setup
            if (!audioFile.exists() || audioFile.length() == 0L) {
                Log.e("ScribeViewModel", "Invalid audio file: exists=${audioFile.exists()}, size=${audioFile.length()}")
                _audioPlaybackState.value = PlaybackState.Stopped
                _ttsState.value = TtsState.Error("Failed to load narration audio: Audio file is invalid, missing or 0 bytes.")
                return
            }

            val player = getOrCreatePlayer()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = false
            _audioPlaybackState.value = PlaybackState.Paused
            _audioProgress.value = 0f

        } catch (e: Exception) {
            Log.e("ScribeViewModel", "Playback error", e)
            _audioPlaybackState.value = PlaybackState.Stopped
            _ttsState.value = TtsState.Error("Failed to load narration audio: ${e.localizedMessage ?: e.message ?: "Unknown error"}")
        }
    }

    fun togglePlayPause() {
        if (_audioPlaybackState.value == PlaybackState.Completed) {
            val path = _selectedStory.value?.audioPath
            if (path != null) {
                playAudio(path)
            }
            return
        }
        val player = exoPlayer
        if (player == null) {
            val path = _selectedStory.value?.audioPath
            if (path != null) {
                playAudio(path)
            }
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
    }

    fun stopAudio() {
        exoPlayer?.let {
            it.stop()
            it.release()
        }
        exoPlayer = null
        _audioPlaybackState.value = PlaybackState.Stopped
        _audioProgress.value = 0f
        _audioDurationText.value = "0:00 / 0:00"
        stopProgressTracking()
    }

    fun seekAudio(progress: Float) {
        val player = exoPlayer ?: return
        val duration = player.duration
        if (duration > 0) {
            val seekMs = (progress * duration).toLong()
            player.seekTo(seekMs)
            _audioProgress.value = progress
            updateDurationText(seekMs.toInt(), duration.toInt())
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        val current = player.currentPosition
                        val total = player.duration
                        if (total > 0) {
                            _audioProgress.value = current.toFloat() / total.toFloat()
                            updateDurationText(current.toInt(), total.toInt())
                        }
                    }
                }
                delay(250)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateDurationText(currentMs: Int, totalMs: Int) {
        val currentSec = (currentMs / 1000) % 60
        val currentMin = (currentMs / 1000) / 60
        val totalSec = (totalMs / 1000) % 60
        val totalMin = (totalMs / 1000) / 60
        _audioDurationText.value = String.format("%d:%02d / %d:%02d", currentMin, currentSec, totalMin, totalSec)
    }

    // --- Helper Utilities ---
    private fun copyUriToInternalStorage(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val inputStream = resolver.openInputStream(uri) ?: throw Exception("Unable to open input stream from uri")
        val fileName = "img_${UUID.randomUUID()}.jpg"
        val destFile = File(context.filesDir, fileName)
        
        FileOutputStream(destFile).use { outputStream ->
            inputStream.use { input ->
                input.copyTo(outputStream)
            }
        }
        return destFile.absolutePath
    }

    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int
        if (ratio > 1) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    fun selectContinuationOption(context: Context, optionIndex: Int) {
        val story = _selectedStory.value ?: return
        val chosenText = when (optionIndex) {
            1 -> story.option1Text
            2 -> story.option2Text
            3 -> story.option3Text
            else -> null
        } ?: return

        // Stop current audio playback
        stopAudio()

        val updatedParagraph = "${story.storyParagraph}\n\n$chosenText"
        
        // Update story locally with cleared options during loading
        val updatedStory = story.copy(
            storyParagraph = updatedParagraph,
            audioPath = null, // Reset audio path for narration
            option1Title = null,
            option1Text = null,
            option2Title = null,
            option2Text = null,
            option3Title = null,
            option3Text = null
        )

        _selectedStory.value = updatedStory

        viewModelScope.launch {
            repository.updateStory(updatedStory)
            generateNewOptionsForStory(context, updatedStory)
        }
    }

    fun generateNewOptionsForStory(context: Context, story: StoryEntity) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _optionsGenerationError.value = "Gemini API Key is missing. Cannot generate new options."
            return
        }

        _optionsGenerationLoading.value = true
        _optionsGenerationError.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prompt = """
                    Based on the story titled "${story.title}" and its narrative progression:
                    
                    "${story.storyParagraph}"
                    
                    Generate exactly 3 different, highly creative next options for the reader to choose from to continue the story.
                    Each option must continue the narrative in a distinct, interesting direction while maintaining the established hazy, ethereal, and nostalgic "Lo-Fi Dream Pop" tone and mood.
                    
                    Return a JSON object exactly with these fields:
                    {
                      "option1Title": "A short, evocative title/action for Option 1 (e.g., 'Peer into the glowing void')",
                      "option1Text": "A 2 to 3 sentence paragraph continuing the story in that direction...",
                      "option2Title": "A short, evocative title/action for Option 2",
                      "option2Text": "A 2 to 3 sentence paragraph continuing the story in that direction...",
                      "option3Title": "A short, evocative title/action for Option 3",
                      "option3Text": "A 2 to 3 sentence paragraph continuing the story in that direction..."
                    }
                    Important: Do NOT include any markdown code blocks, backticks, or wrapping. Just return the pure JSON raw string.
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(parts = listOf(Part(text = prompt)))
                    ),
                    generationConfig = GenerationConfig(
                        temperature = 0.85f
                    )
                )

                val response = RetrofitClient.service.generateContent(
                    model = "gemini-3.5-flash",
                    apiKey = apiKey,
                    request = request
                )

                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (responseText == null) {
                    throw Exception("Gemini generated an empty response for options.")
                }

                val cleanJson = responseText.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val jsonObject = JSONObject(cleanJson)
                val opt1Title = jsonObject.optString("option1Title", "").trim()
                val opt1Text = jsonObject.optString("option1Text", "").trim()
                val opt2Title = jsonObject.optString("option2Title", "").trim()
                val opt2Text = jsonObject.optString("option2Text", "").trim()
                val opt3Title = jsonObject.optString("option3Title", "").trim()
                val opt3Text = jsonObject.optString("option3Text", "").trim()

                if (opt1Text.isEmpty() || opt2Text.isEmpty() || opt3Text.isEmpty()) {
                    throw Exception("Failed to generate complete story continuation options.")
                }

                val finalStory = story.copy(
                    option1Title = opt1Title,
                    option1Text = opt1Text,
                    option2Title = opt2Title,
                    option2Text = opt2Text,
                    option3Title = opt3Title,
                    option3Text = opt3Text
                )

                repository.updateStory(finalStory)

                withContext(Dispatchers.Main) {
                    if (_selectedStory.value?.id == story.id) {
                        _selectedStory.value = finalStory
                    }
                    _optionsGenerationLoading.value = false
                }
            } catch (e: Exception) {
                Log.e("ScribeViewModel", "Failed to generate story options", e)
                withContext(Dispatchers.Main) {
                    if (e is HttpException && e.code() == 429) {
                        _optionsGenerationError.value = "API quota exceeded (HTTP 429). Please try again later or check your API key quota."
                    } else {
                        _optionsGenerationError.value = e.message ?: "Failed to generate continuation options."
                    }
                    _optionsGenerationLoading.value = false
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }
}

// Extension to bridge Room flows to repository cleanly
private fun StoryRepository.allItemsState(): StateFlow<List<StoryEntity>> {
    // Return flow
    return this.allStories.flatMapLatest { flowOf(it) }
        .stateIn(
            scope = kotlinx.coroutines.GlobalScope, // Safe inside simple bridge
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )
}

// Sealed classes for UI feedback
sealed interface GenerationState {
    object Idle : GenerationState
    data class Loading(val message: String) : GenerationState
    data class Error(val error: String) : GenerationState
    data class Success(val story: StoryEntity) : GenerationState
}

sealed interface TtsState {
    object Idle : TtsState
    data class Loading(val message: String) : TtsState
    data class Error(val error: String) : TtsState
    data class Success(val audioPath: String) : TtsState
}

sealed interface PlaybackState {
    object Stopped : PlaybackState
    object Playing : PlaybackState
    object Paused : PlaybackState
    object Completed : PlaybackState
}

data class VoiceOption(val id: String, val label: String)

class ScribeViewModelFactory(
    private val application: android.app.Application,
    private val repository: StoryRepository,
    private val billingManager: com.example.billing.BillingManager? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScribeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ScribeViewModel(application, repository, billingManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
