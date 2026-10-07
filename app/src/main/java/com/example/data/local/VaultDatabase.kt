package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.FolderDao
import com.example.data.local.dao.MediaDao
import com.example.data.local.entity.VaultFolderEntity
import com.example.data.local.entity.VaultMediaEntity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE vault_media ADD COLUMN isCloudSynced INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        VaultFolderEntity::class,
        VaultMediaEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun getInstance(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "calculator_vault.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
