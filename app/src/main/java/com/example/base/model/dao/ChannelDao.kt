package com.example.base.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.base.model.entity.Channel
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Query("SELECT * FROM Channel")
    suspend fun getAllChannelsOnce(): List<Channel>

    @Query("SELECT COUNT(*) FROM Channel")
    suspend fun countChannels(): Int

    @Query("SELECT COUNT(*) FROM Channel WHERE languages IS NOT NULL OR countries IS NOT NULL")
    suspend fun countChannelsWithLanguageOrCountry(): Int

    @Query("SELECT * FROM Channel ORDER BY name COLLATE NOCASE ASC")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM Channel WHERE categories LIKE '%' || :category || '%' ORDER BY name COLLATE NOCASE ASC")
    fun getChannelsByCategory(category: String): Flow<List<Channel>>

    @Query("SELECT * FROM Channel WHERE languages LIKE '%' || :language || '%' ORDER BY name COLLATE NOCASE ASC")
    fun getChannelsByLanguage(language: String): Flow<List<Channel>>

    @Query("SELECT * FROM Channel WHERE countries LIKE '%' || :country || '%' ORDER BY name COLLATE NOCASE ASC")
    fun getChannelsByCountry(country: String): Flow<List<Channel>>
}
