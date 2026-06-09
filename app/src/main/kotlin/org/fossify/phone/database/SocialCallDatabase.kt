package org.fossify.phone.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SocialCallDatabase(context: Context) : SQLiteOpenHelper(context, "social_calls_db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS social_calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                callerName TEXT,
                phoneNumber TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                callType TEXT NOT NULL,
                platformSource TEXT NOT NULL,
                associatedContactId INTEGER
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS social_calls")
        onCreate(db)
    }

    companion object {
        @Volatile
        private var INSTANCE: SocialCallDatabase? = null

        fun getDatabase(context: Context): SocialCallDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = SocialCallDatabase(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    fun socialCallDao(): SocialCallDao {
        return SocialCallDao(this)
    }
}
