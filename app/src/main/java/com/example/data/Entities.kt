package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stories")
data class StoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val imagePath: String,
    val prompt: String,
    val storyParagraph: String,
    val audioPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val option1Title: String? = null,
    val option1Text: String? = null,
    val option2Title: String? = null,
    val option2Text: String? = null,
    val option3Title: String? = null,
    val option3Text: String? = null,
    val keyObjects: String? = null,
    val keyCharacters: String? = null,
    val settingElements: String? = null,
    val moodAtmosphere: String? = null
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val storyId: Int,
    val role: String, // "user" or "model"
    val messageText: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_session")
data class UserSessionEntity(
    @PrimaryKey val id: String = "current_session",
    val isLoggedIn: Boolean = false,
    val isOnboardingCompleted: Boolean = false,
    val userId: String = "",
    val authProvider: String = "email",
    val email: String = "",
    val subscriptionStatus: String = "free", // "free", "active", "grace_period", "cancelled", "expired", "refunded"
    val subscriptionPlan: String = "none", // "none", "monthly_pro", "annual_pro"
    val purchaseToken: String? = null,
    val renewalDate: Long = 0L,
    val gracePeriodEnd: Long = 0L,
    val lastVerifiedAt: Long = 0L,
    val accountCreatedAt: Long = 0L
)

