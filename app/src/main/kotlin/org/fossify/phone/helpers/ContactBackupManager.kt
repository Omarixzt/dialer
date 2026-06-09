package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.phone.database.CallType
import org.fossify.phone.database.PlatformSource
import org.fossify.phone.database.SocialCallDatabase
import org.fossify.phone.database.SocialCallEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

object ContactBackupManager {

    /**
     * Converts a list of SocialCallEntity into a formatted JSON string.
     * To save this string using SAF, pass it to [saveJsonToSafUri].
     */
    suspend fun exportDataToJson(context: Context, socialLinksList: List<SocialCallEntity>): String {
        return withContext(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                for (call in socialLinksList) {
                    val jsonObject = JSONObject().apply {
                        // We skip the 'id' if we want imports to generate fresh IDs, 
                        // but let's include it for completeness of the backup.
                        put("id", call.id)
                        put("callerName", call.callerName)
                        put("phoneNumber", call.phoneNumber)
                        put("timestamp", call.timestamp)
                        put("callType", call.callType.name)
                        put("platformSource", call.platformSource.name)
                        put("associatedContactId", call.associatedContactId)
                    }
                    jsonArray.put(jsonObject)
                }
                // Return formatted JSON string with 4-space indent
                jsonArray.toString(4)
            } catch (e: JSONException) {
                e.printStackTrace()
                throw Exception("JSONParsingException: Failed to encode data to JSON.", e)
            }
        }
    }

    /**
     * Helper method to write the JSON string to a user-selected Storage Access Framework (SAF) Uri.
     */
    suspend fun saveJsonToSafUri(context: Context, uri: Uri, jsonString: String) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                }
            } catch (e: IOException) {
                e.printStackTrace()
                throw IOException("Failed to write JSON string to SAF Uri.", e)
            }
        }
    }

    /**
     * Parses the JSON string and inserts the SocialCall records back into the Room Database.
     */
    suspend fun importDataFromJson(context: Context, jsonString: String) {
        withContext(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray(jsonString)
                val importedList = mutableListOf<SocialCallEntity>()
                
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    
                    val callType = CallType.valueOf(jsonObject.getString("callType"))
                    val platformSource = PlatformSource.valueOf(jsonObject.getString("platformSource"))
                    
                    val contactId = if (jsonObject.isNull("associatedContactId")) {
                        null
                    } else {
                        jsonObject.getLong("associatedContactId")
                    }

                    val entity = SocialCallEntity(
                        id = 0, // Resetting to 0 lets Room auto-generate new IDs and prevents PK conflicts
                        callerName = if (jsonObject.isNull("callerName")) null else jsonObject.getString("callerName"),
                        phoneNumber = jsonObject.getString("phoneNumber"),
                        timestamp = jsonObject.getLong("timestamp"),
                        callType = callType,
                        platformSource = platformSource,
                        associatedContactId = contactId
                    )
                    importedList.add(entity)
                }

                // Insert parsed entities into Room DB
                val dao = SocialCallDatabase.getDatabase(context).socialCallDao()
                for (call in importedList) {
                    dao.insertCall(call) // Using replace strategy from DAO if needed
                }
                
            } catch (e: JSONException) {
                e.printStackTrace()
                throw Exception("JSONParsingException: Invalid JSON format during import.", e)
            } catch (e: Exception) {
                e.printStackTrace()
                throw IOException("Failed to import data into the database.", e)
            }
        }
    }
}
