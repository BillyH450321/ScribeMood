package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserSessionDao {
    @Query("SELECT * FROM user_session WHERE id = 'current_session' LIMIT 1")
    fun getUserSessionFlow(): Flow<UserSessionEntity?>

    @Query("SELECT * FROM user_session WHERE id = 'current_session' LIMIT 1")
    suspend fun getUserSession(): UserSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserSession(session: UserSessionEntity)

    @Update
    suspend fun updateUserSession(session: UserSessionEntity)

    @Query("DELETE FROM user_session WHERE id = 'current_session'")
    suspend fun deleteUserSession()
}
