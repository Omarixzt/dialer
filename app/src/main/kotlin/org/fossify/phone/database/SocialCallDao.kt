package org.fossify.phone.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class SocialCallDao(private val dbHelper: SocialCallDatabase) {
    
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    
    suspend fun insertCall(call: SocialCallEntity): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("callerName", call.callerName)
            put("phoneNumber", call.phoneNumber)
            put("timestamp", call.timestamp)
            put("callType", call.callType.name)
            put("platformSource", call.platformSource.name)
            put("associatedContactId", call.associatedContactId)
        }
        val id = db.insertWithOnConflict("social_calls", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        _updates.tryEmit(Unit)
        id
    }

    suspend fun deleteCall(call: SocialCallEntity) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("social_calls", "id = ?", arrayOf(call.id.toString()))
        _updates.tryEmit(Unit)
    }

    suspend fun deleteCallsByIds(ids: List<Long>) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val args = ids.joinToString(",") { "?" }
        db.delete("social_calls", "id IN ($args)", ids.map { it.toString() }.toTypedArray())
        _updates.tryEmit(Unit)
    }

    suspend fun deleteAllCalls() = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.delete("social_calls", null, null)
        _updates.tryEmit(Unit)
    }

    suspend fun cleanupOldCalls(maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000, maxCount: Int = 500) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        
        // 1. Delete calls older than maxAgeMs
        val cutOffTime = System.currentTimeMillis() - maxAgeMs
        db.delete("social_calls", "timestamp < ?", arrayOf(cutOffTime.toString()))
        
        // 2. Keep only the last maxCount calls
        val cursor = db.rawQuery("SELECT timestamp FROM social_calls ORDER BY timestamp DESC LIMIT 1 OFFSET ?", arrayOf((maxCount - 1).toString()))
        cursor.use {
            if (it.moveToFirst()) {
                val offsetTimestamp = it.getLong(0)
                db.delete("social_calls", "timestamp < ?", arrayOf(offsetTimestamp.toString()))
            }
        }
        _updates.tryEmit(Unit)
    }

    fun getAllCalls(): Flow<List<SocialCallEntity>> {
        return _updates.onStart { emit(Unit) }.map { getAllCallsList() }
    }

    suspend fun getAllCallsList(): List<SocialCallEntity> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM social_calls ORDER BY timestamp DESC", null)
        val list = mutableListOf<SocialCallEntity>()
        cursor.use {
            if (it.moveToFirst()) {
                do {
                    list.add(
                        SocialCallEntity(
                            id = it.getLong(it.getColumnIndexOrThrow("id")),
                            callerName = it.getString(it.getColumnIndexOrThrow("callerName")),
                            phoneNumber = it.getString(it.getColumnIndexOrThrow("phoneNumber")),
                            timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                            callType = CallType.valueOf(it.getString(it.getColumnIndexOrThrow("callType"))),
                            platformSource = PlatformSource.valueOf(it.getString(it.getColumnIndexOrThrow("platformSource"))),
                            associatedContactId = if (it.isNull(it.getColumnIndexOrThrow("associatedContactId"))) null else it.getLong(it.getColumnIndexOrThrow("associatedContactId"))
                        )
                    )
                } while (it.moveToNext())
            }
        }
        list
    }
}
