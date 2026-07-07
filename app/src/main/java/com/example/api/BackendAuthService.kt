package com.example.api

import android.util.Log
import kotlinx.coroutines.delay
import java.util.UUID

data class BackendUser(
    val userId: String,
    val authProvider: String,
    val email: String,
    val subscriptionStatus: String, // "free", "active", "grace_period", "cancelled", "expired", "refunded"
    val subscriptionPlan: String, // "none", "monthly_pro", "annual_pro"
    val purchaseToken: String? = null,
    val renewalDate: Long = 0L,
    val gracePeriodEnd: Long = 0L,
    val lastVerifiedAt: Long = System.currentTimeMillis(),
    val accountCreatedAt: Long = System.currentTimeMillis()
)

object BackendAuthService {
    private const val TAG = "BackendAuthService"

    // Simulate server-side user database
    private val usersDb = mutableMapOf<String, BackendUser>()
    private val passwordsDb = mutableMapOf<String, String>() // email -> password

    // Pre-seed some test users for demonstration and QA verification
    init {
        // 1. Returning Paid User (Active monthly subscription)
        val premiumId = "usr_premium_12345"
        val premiumUser = BackendUser(
            userId = premiumId,
            authProvider = "email",
            email = "premium@scribe.com",
            subscriptionStatus = "active",
            subscriptionPlan = "monthly_pro",
            purchaseToken = "tok_play_premium_9988",
            renewalDate = System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000), // 30 days from now
            gracePeriodEnd = 0L,
            lastVerifiedAt = System.currentTimeMillis(),
            accountCreatedAt = System.currentTimeMillis() - (60L * 24 * 60 * 60 * 1000)
        )
        usersDb[premiumUser.email] = premiumUser
        passwordsDb[premiumUser.email] = "password123"

        // 2. Returning Grace Period User
        val graceId = "usr_grace_23456"
        val graceUser = BackendUser(
            userId = graceId,
            authProvider = "email",
            email = "grace@scribe.com",
            subscriptionStatus = "grace_period",
            subscriptionPlan = "annual_pro",
            purchaseToken = "tok_play_grace_1122",
            renewalDate = System.currentTimeMillis() - (1L * 24 * 60 * 60 * 1000), // expired yesterday
            gracePeriodEnd = System.currentTimeMillis() + (13L * 24 * 60 * 60 * 1000), // 14 days grace period
            lastVerifiedAt = System.currentTimeMillis(),
            accountCreatedAt = System.currentTimeMillis() - (365L * 24 * 60 * 60 * 1000)
        )
        usersDb[graceUser.email] = graceUser
        passwordsDb[graceUser.email] = "password123"

        // 3. Returning Expired User
        val expiredId = "usr_expired_34567"
        val expiredUser = BackendUser(
            userId = expiredId,
            authProvider = "email",
            email = "expired@scribe.com",
            subscriptionStatus = "expired",
            subscriptionPlan = "monthly_pro",
            purchaseToken = "tok_play_expired_4455",
            renewalDate = System.currentTimeMillis() - (15L * 24 * 60 * 60 * 1000),
            gracePeriodEnd = System.currentTimeMillis() - (1L * 24 * 60 * 60 * 1000),
            lastVerifiedAt = System.currentTimeMillis(),
            accountCreatedAt = System.currentTimeMillis() - (45L * 24 * 60 * 60 * 1000)
        )
        usersDb[expiredUser.email] = expiredUser
        passwordsDb[expiredUser.email] = "password123"

        // 4. Returning Refunded/Revoked User
        val refundedId = "usr_refunded_45678"
        val refundedUser = BackendUser(
            userId = refundedId,
            authProvider = "email",
            email = "refunded@scribe.com",
            subscriptionStatus = "refunded",
            subscriptionPlan = "monthly_pro",
            purchaseToken = "tok_play_refunded_5566",
            renewalDate = System.currentTimeMillis() - (5L * 24 * 60 * 60 * 1000),
            gracePeriodEnd = 0L,
            lastVerifiedAt = System.currentTimeMillis(),
            accountCreatedAt = System.currentTimeMillis() - (10L * 24 * 60 * 60 * 1000)
        )
        usersDb[refundedUser.email] = refundedUser
        passwordsDb[refundedUser.email] = "password123"

        // 5. Returning Free Tier User
        val freeId = "usr_free_56789"
        val freeUser = BackendUser(
            userId = freeId,
            authProvider = "email",
            email = "free@scribe.com",
            subscriptionStatus = "free",
            subscriptionPlan = "none",
            purchaseToken = null,
            renewalDate = 0L,
            gracePeriodEnd = 0L,
            lastVerifiedAt = System.currentTimeMillis(),
            accountCreatedAt = System.currentTimeMillis() - (2L * 24 * 60 * 60 * 1000)
        )
        usersDb[freeUser.email] = freeUser
        passwordsDb[freeUser.email] = "password123"
    }

    // Helper to simulate rate limiting or server outages
    private var serverOnline = true
    fun setServerOnline(online: Boolean) {
        serverOnline = online
    }

    private suspend fun simulateNetwork() {
        if (!serverOnline) {
            throw java.io.IOException("Unable to resolve host - Server is currently offline.")
        }
        delay(600) // Simulate server network latency
    }

    /**
     * Server API: Sign up a new user
     */
    suspend fun signUp(email: String, password: String, authProvider: String = "email"): BackendUser {
        simulateNetwork()
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isEmpty()) throw IllegalArgumentException("Email cannot be empty.")
        if (password.length < 6) throw IllegalArgumentException("Password must be at least 6 characters.")
        if (usersDb.containsKey(normalizedEmail)) {
            throw IllegalStateException("An account with this email already exists.")
        }

        val newUser = BackendUser(
            userId = "usr_" + UUID.randomUUID().toString().replace("-", "").take(12),
            authProvider = authProvider,
            email = normalizedEmail,
            subscriptionStatus = "free",
            subscriptionPlan = "none",
            purchaseToken = null,
            renewalDate = 0L,
            gracePeriodEnd = 0L,
            lastVerifiedAt = System.currentTimeMillis(),
            accountCreatedAt = System.currentTimeMillis()
        )
        usersDb[normalizedEmail] = newUser
        passwordsDb[normalizedEmail] = password
        Log.d(TAG, "Server: Created account for $normalizedEmail with ID: ${newUser.userId}")
        return newUser
    }

    /**
     * Server API: Sign in user
     */
    suspend fun signIn(email: String, password: String): BackendUser {
        simulateNetwork()
        val normalizedEmail = email.trim().lowercase()
        if (!usersDb.containsKey(normalizedEmail)) {
            throw IllegalArgumentException("No account found with this email.")
        }
        val registeredPassword = passwordsDb[normalizedEmail]
        if (registeredPassword != password) {
            throw IllegalArgumentException("Incorrect password. Please try again.")
        }

        val user = usersDb[normalizedEmail]!!
        Log.d(TAG, "Server: Logged in $normalizedEmail")
        return user
    }

    /**
     * Server API: Forgot password
     */
    suspend fun forgotPassword(email: String): String {
        simulateNetwork()
        val normalizedEmail = email.trim().lowercase()
        if (!usersDb.containsKey(normalizedEmail)) {
            throw IllegalArgumentException("No account found with this email.")
        }
        Log.d(TAG, "Server: Sent password reset email to $normalizedEmail")
        return "A password reset link has been dispatched to $normalizedEmail. Please check your inbox."
    }

    /**
     * Server API: Verify client purchase token and register/renew subscription server-side
     */
    suspend fun verifySubscriptionEntitlement(email: String, plan: String, purchaseToken: String): BackendUser {
        simulateNetwork()
        val normalizedEmail = email.trim().lowercase()
        val user = usersDb[normalizedEmail] ?: throw IllegalArgumentException("User not logged in or doesn't exist.")

        // Server-side validation of receipt/token
        val duration = if (plan == "annual_pro") {
            365L * 24 * 60 * 60 * 1000
        } else {
            30L * 24 * 60 * 60 * 1000
        }

        val updatedUser = user.copy(
            subscriptionStatus = "active",
            subscriptionPlan = plan,
            purchaseToken = purchaseToken,
            renewalDate = System.currentTimeMillis() + duration,
            gracePeriodEnd = 0L,
            lastVerifiedAt = System.currentTimeMillis()
        )
        usersDb[normalizedEmail] = updatedUser
        Log.d(TAG, "Server: Subscription successfully verified & linked to $normalizedEmail. Token: $purchaseToken")
        return updatedUser
    }

    /**
     * Server API: Restore purchases (find existing subscription under other devices or Google Play account)
     */
    suspend fun restorePurchases(email: String, localPlayBillingActive: Boolean): BackendUser {
        simulateNetwork()
        val normalizedEmail = email.trim().lowercase()
        val user = usersDb[normalizedEmail] ?: throw IllegalArgumentException("User not authenticated.")

        if (localPlayBillingActive) {
            // Google Play confirms the device has an active subscription purchase, so sync it with the user account
            val updatedUser = user.copy(
                subscriptionStatus = "active",
                subscriptionPlan = "monthly_pro",
                purchaseToken = "tok_play_restored_" + UUID.randomUUID().toString().take(6),
                renewalDate = System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000),
                gracePeriodEnd = 0L,
                lastVerifiedAt = System.currentTimeMillis()
            )
            usersDb[normalizedEmail] = updatedUser
            return updatedUser
        } else {
            // Otherwise, check if user already had an active purchase in backend db
            if (user.subscriptionStatus == "active" || user.subscriptionStatus == "grace_period") {
                return user.copy(lastVerifiedAt = System.currentTimeMillis())
            } else {
                throw IllegalStateException("No previous active subscription purchases were found for this account in the store database.")
            }
        }
    }

    /**
     * Server API: Sync entitlements after login
     */
    suspend fun syncEntitlementsAfterLogin(email: String): BackendUser {
        simulateNetwork()
        val normalizedEmail = email.trim().lowercase()
        val user = usersDb[normalizedEmail] ?: throw IllegalArgumentException("User not found.")
        return user.copy(lastVerifiedAt = System.currentTimeMillis())
    }

    /**
     * Server API: Account deletion
     */
    suspend fun accountDeletion(email: String) {
        simulateNetwork()
        val normalizedEmail = email.trim().lowercase()
        if (usersDb.containsKey(normalizedEmail)) {
            usersDb.remove(normalizedEmail)
            passwordsDb.remove(normalizedEmail)
            Log.d(TAG, "Server: Deleted account $normalizedEmail")
        } else {
            throw IllegalArgumentException("Account not found.")
        }
    }
}
