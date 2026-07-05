package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
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
    private val repository: StoryRepository,
    private val billingManager: com.example.billing.BillingManager? = null
) : ViewModel() {

    private val tag = "ScribeViewModel"

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
        VoiceOption("Kore", "Male - Rich & Expressive"),
        VoiceOption("Aoede", "Female - Warm & Clear"),
        VoiceOption("Puck", "Male - Crisp & Energetic"),
        VoiceOption("Charon", "Male - Deep & Dramatic"),
        VoiceOption("Fenrir", "Male - Dark & Mysterious")
    )

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Idle)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    // API exponential backoff retry status
    val apiRetryStatus: StateFlow<RetryStatus?> = ApiRetryMonitor.retryStatus

    // Chat states
    private val _chatLoading = MutableStateFlow(false)
    val chatLoading: StateFlow<Boolean> = _chatLoading.asStateFlow()

    // Media Player variables
    private var mediaPlayer: MediaPlayer? = null
    private val _audioPlaybackState = MutableStateFlow<PlaybackState>(PlaybackState.Stopped)
    val audioPlaybackState: StateFlow<PlaybackState> = _audioPlaybackState.asStateFlow()

    private val _audioProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()

    private val _audioDurationText = MutableStateFlow("0:00 / 0:00")
    val audioDurationText: StateFlow<String> = _audioDurationText.asStateFlow()

    private var progressJob: Job? = null

    init {
        // Automatically select the most recent story if available
        viewModelScope.launch {
            allStories.collect { stories ->
                if (_selectedStory.value == null && stories.isNotEmpty()) {
                    selectStory(stories.first())
                }
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
                    fileName.contains("_" + voice.id + ".mp3", ignoreCase = true)
                }?.id ?: "Kore"
                _selectedVoice.value = matchedVoice
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
            val audioFile = File(context.filesDir, "narrative_audio_${story.id}_$voice.mp3")
            if (audioFile.exists()) {
                val wasPlaying = _audioPlaybackState.value == PlaybackState.Playing
                val updatedStory = story.copy(audioPath = audioFile.absolutePath)
                viewModelScope.launch {
                    repository.updateStory(updatedStory)
                    _selectedStory.value = updatedStory
                    if (wasPlaying) {
                        playAudio(audioFile.absolutePath)
                    } else {
                        stopAudio()
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

                val isPro = billingManager?.isProUser?.value == true
                
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
                val requestText = "Synthesize this story opening paragraph with a breathy, relaxed vocal style and a slower pace to match a nostalgic, hazy Dream Pop aesthetic: ${story.storyParagraph}"

                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(parts = listOf(Part(text = requestText)))
                    ),
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = SpeechConfig(
                            voiceConfig = VoiceConfig(
                                prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = voiceName)
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

                // Save to local cache directory as a voice-specific .mp3 file
                val audioFile = File(context.filesDir, "narrative_audio_${story.id}_$voiceName.mp3")
                FileOutputStream(audioFile).use { fos ->
                    fos.write(audioBytes)
                }

                // Update StoryEntity in database with the new audio path
                val updatedStory = story.copy(audioPath = audioFile.absolutePath)
                repository.updateStory(updatedStory)

                withContext(Dispatchers.Main) {
                    _selectedStory.value = updatedStory
                    _ttsState.value = TtsState.Success(audioFile.absolutePath)
                    // Auto-start playing the narrated file
                    playAudio(audioFile.absolutePath)
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
                        val voiceFile = File(filesDir, "narrative_audio_${story.id}_${voice.id}.mp3")
                        if (voiceFile.exists()) voiceFile.delete()
                    }
                    val legacyFile = File(filesDir, "narrative_audio_${story.id}.mp3")
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
    fun playAudio(path: String) {
        try {
            stopAudio()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                _audioPlaybackState.value = PlaybackState.Playing
                
                setOnCompletionListener {
                    _audioPlaybackState.value = PlaybackState.Completed
                    _audioProgress.value = 1.0f
                    stopProgressTracking()
                }
            }

            startProgressTracking()

        } catch (e: Exception) {
            Log.e(tag, "MediaPlayer setup failed", e)
            _audioPlaybackState.value = PlaybackState.Stopped
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _audioPlaybackState.value = PlaybackState.Paused
            stopProgressTracking()
        } else {
            player.start()
            _audioPlaybackState.value = PlaybackState.Playing
            startProgressTracking()
        }
    }

    fun stopAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        _audioPlaybackState.value = PlaybackState.Stopped
        _audioProgress.value = 0f
        _audioDurationText.value = "0:00 / 0:00"
        stopProgressTracking()
    }

    fun seekAudio(progress: Float) {
        val player = mediaPlayer ?: return
        val seekMs = (progress * player.duration).toInt()
        player.seekTo(seekMs)
        _audioProgress.value = progress
        updateDurationText(player.currentPosition, player.duration)
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val current = player.currentPosition
                        val total = player.duration
                        if (total > 0) {
                            _audioProgress.value = current.toFloat() / total.toFloat()
                            updateDurationText(current, total)
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
    private val repository: StoryRepository,
    private val billingManager: com.example.billing.BillingManager? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScribeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ScribeViewModel(repository, billingManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
