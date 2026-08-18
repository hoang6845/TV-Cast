package com.tvchromecast.screenmirroringplus.model.dao

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tvchromecast.screenmirroringplus.model.entity.Channel
import com.tvchromecast.screenmirroringplus.model.entity.PinnedCategory

@Database(entities = [Channel::class, PinnedCategory::class], version = 3, exportSchema = false)
abstract class AppDatabase: RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun pinnedCategoryDao(): PinnedCategoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS Channel_new (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        logo TEXT,
                        categories TEXT,
                        languages TEXT,
                        countries TEXT,
                        url TEXT NOT NULL,
                        isFavourite INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO Channel_new (id, name, logo, categories, languages, countries, url, isFavourite)
                    SELECT id, name, logo, `group`, NULL, NULL, url, isFavourite FROM Channel
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE Channel")
                db.execSQL("ALTER TABLE Channel_new RENAME TO Channel")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS PinnedCategory (
                        categoryName TEXT NOT NULL,
                        PRIMARY KEY(categoryName)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
