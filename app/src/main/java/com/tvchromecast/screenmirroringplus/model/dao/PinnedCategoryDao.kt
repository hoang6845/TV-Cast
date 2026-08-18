package com.tvchromecast.screenmirroringplus.model.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvchromecast.screenmirroringplus.model.entity.PinnedCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface PinnedCategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPinnedCategory(category: PinnedCategory)

    @Query("DELETE FROM PinnedCategory WHERE categoryName = :categoryName")
    suspend fun deletePinnedCategory(categoryName: String)

    @Query("SELECT * FROM PinnedCategory")
    fun getAllPinnedCategories(): Flow<List<PinnedCategory>>

    @Query("SELECT * FROM PinnedCategory")
    suspend fun getAllPinnedCategoriesOnce(): List<PinnedCategory>
}
