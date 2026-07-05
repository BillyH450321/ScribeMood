package com.example.data

import kotlinx.coroutines.flow.Flow

class StoryRepository(private val storyDao: StoryDao) {
    val allStories: Flow<List<StoryEntity>> = storyDao.getAllStories()

    suspend fun getStoryById(id: Int): StoryEntity? {
        return storyDao.getStoryById(id)
    }

    suspend fun insertStory(story: StoryEntity): Long {
        return storyDao.insertStory(story)
    }

    suspend fun updateStory(story: StoryEntity) {
        storyDao.updateStory(story)
    }

    suspend fun deleteStory(story: StoryEntity) {
        storyDao.deleteStory(story)
    }

    fun getChatMessagesForStory(storyId: Int): Flow<List<ChatMessageEntity>> {
        return storyDao.getChatMessagesForStory(storyId)
    }

    suspend fun insertChatMessage(message: ChatMessageEntity) {
        storyDao.insertChatMessage(message)
    }

    suspend fun clearChatForStory(storyId: Int) {
        storyDao.clearChatForStory(storyId)
    }
}
