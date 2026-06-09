package org.fossify.phone.services

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fossify.phone.database.CallType
import org.fossify.phone.database.PlatformSource
import org.fossify.phone.database.SocialCallDatabase
import org.fossify.phone.database.SocialCallEntity

class CallNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        
        // 1. check if we care about this app's notifications
        val prefs = getSharedPreferences("social_dialer_prefs", Context.MODE_PRIVATE)
        val isCaptureEnabled = prefs.getBoolean(packageName, false)
        
        if (!isCaptureEnabled) {
            return // capturing is disabled or not set for this app, bail out
        }

        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        
        Log.d("CallNotification", "Raw Notif: pkg=$packageName, title='$title', text='$text', cat=${notification.category}")
        
        // 2. ensure this is actually a call notification
        val isCallCategory = notification.category == Notification.CATEGORY_CALL || 
                             notification.category == Notification.CATEGORY_MISSED_CALL
                             
        val isCallText = title.contains("call", true) || text.contains("call", true) || 
                         title.contains("مكالمة", true) || text.contains("مكالمة", true) ||
                         title.contains("ringing", true) || text.contains("ringing", true) ||
                         title.contains("يتصل", true) || text.contains("يتصل", true) ||
                         title.contains("جار", true) || text.contains("جار", true) ||
                         title.contains("الاتصال", true) || text.contains("الاتصال", true) ||
                         title.contains("dialing", true) || text.contains("dialing", true) ||
                         title.contains("يرن", true) || text.contains("يرن", true) ||
                         title.contains("صادرة", true) || text.contains("صادرة", true) ||
                         title.contains("واردة", true) || text.contains("واردة", true) ||
                         title.contains("missed", true) || text.contains("missed", true) ||
                         title.contains("فائتة", true) || text.contains("فائتة", true) ||
                         title.contains("لم يرد", true) || text.contains("لم يرد", true)

        if (!isCallCategory && !isCallText) {
            return
        }

        // 3. extract the juicy details from the extras bundle
        val callerNameOrNumber = title.ifEmpty { "Unknown" }
        val callDetails = text
        
        Log.d("CallNotification", "Intercepted call from: $callerNameOrNumber on $packageName")

        // 4. map the package name to your PlatformSource enum 
        val platformSource = when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> PlatformSource.WHATSAPP
            "com.facebook.orca" -> PlatformSource.MESSENGER
            "com.instagram.android" -> PlatformSource.INSTAGRAM
            "org.telegram.messenger", "org.telegram.messenger.web" -> PlatformSource.TELEGRAM
            else -> return // unknown platform
        }

        // 5. determine if it's INCOMING, OUTGOING, or MISSED
        val lowerCombined = "$title $text".lowercase()
        val isMissed = lowerCombined.contains("missed") || lowerCombined.contains("فائتة") || lowerCombined.contains("لم يرد")
        val isOutgoing = lowerCombined.contains("calling") || lowerCombined.contains("dialing") || 
                         lowerCombined.contains("outgoing") || lowerCombined.contains("جار") || 
                         lowerCombined.contains("يتصل") || lowerCombined.contains("صادرة") ||
                         lowerCombined.contains("يرن")

        val callType = when {
            isMissed -> CallType.MISSED
            isOutgoing -> CallType.OUTGOING
            else -> CallType.INCOMING
        }
        
        var finalNumber = callerNameOrNumber
        if (!finalNumber.matches(".*\\d+.*".toRegex())) {
            // It's a name, lookup phone number
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(finalNumber)
            try {
                contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        finalNumber = cursor.getString(0)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 6. insert this call into the Room DAO we created earlier
        CoroutineScope(Dispatchers.IO).launch {
            val dao = SocialCallDatabase.getDatabase(applicationContext).socialCallDao()
            val recentCalls = dao.getAllCallsList()
            val currentTime = System.currentTimeMillis()
            
            // Prevent duplicate entries for the same call within 60 seconds (e.g. ringing + missed call notification)
            val existingCall = recentCalls.find { 
                it.phoneNumber == finalNumber && 
                it.platformSource == platformSource && 
                Math.abs(it.timestamp - currentTime) < 60000
            }

            if (existingCall != null) {
                // Upgrade an INCOMING call to MISSED if we receive the missed notification shortly after
                if (existingCall.callType == CallType.INCOMING && callType == CallType.MISSED) {
                    dao.deleteCall(existingCall)
                    val newEntity = SocialCallEntity(
                        callerName = callerNameOrNumber,
                        phoneNumber = finalNumber,
                        timestamp = currentTime,
                        callType = callType,
                        platformSource = platformSource,
                        associatedContactId = null
                    )
                    dao.insertCall(newEntity)
                }
            } else {
                val entity = SocialCallEntity(
                    callerName = callerNameOrNumber,
                    phoneNumber = finalNumber,
                    timestamp = currentTime,
                    callType = callType,
                    platformSource = platformSource,
                    associatedContactId = null
                )
                dao.insertCall(entity)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // optional: handle when the notification is dismissed (e.g., to detect missed calls)
    }
}
