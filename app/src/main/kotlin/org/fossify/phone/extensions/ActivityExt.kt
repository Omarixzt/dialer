package org.fossify.phone.extensions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.launchViewContactIntent
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.FIRST_CONTACT_ID
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.ON_CLICK_CALL_CONTACT
import org.fossify.commons.helpers.ON_CLICK_VIEW_CONTACT
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.activities.SimpleActivity

fun SimpleActivity.handleGenericContactClick(contact: Contact) {
    when (config.onContactClick) {
        ON_CLICK_CALL_CONTACT -> startCallWithConfirmationCheck(contact)
        ON_CLICK_VIEW_CONTACT -> startContactDetailsIntent(contact)
    }
}

fun SimpleActivity.launchCreateNewContactIntent() {
    Intent().apply {
        action = Intent.ACTION_INSERT
        data = ContactsContract.Contacts.CONTENT_URI
        launchActivityIntent(this)
    }
}

// handle private contacts differently, only Simple Contacts Pro can open them
fun Activity.startContactDetailsIntent(contact: Contact) {
    val simpleContacts = "org.fossify.contacts"
    val simpleContactsDebug = "org.fossify.contacts.debug"
    val isPrivateContact = contact.rawId > FIRST_CONTACT_ID
            && contact.contactId > FIRST_CONTACT_ID
            && contact.rawId == contact.contactId
            && (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
    if (isPrivateContact) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            `package` =
                if (isPackageInstalled(simpleContacts)) simpleContacts else simpleContactsDebug
            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )
            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey =
                SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())
            val publicUri =
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            runOnUiThread {
                launchViewContactIntent(publicUri)
            }
        }
    }
}

fun SimpleActivity.trySocialVoiceCall(call: org.fossify.phone.models.RecentCall) {
    ensureBackgroundThread {
        val resolver = contentResolver
        val platform = call.specificType.lowercase()
        val mimeType = when (platform) {
            "whatsapp" -> "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            "telegram" -> "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call"
            else -> ""
        }

        var dataId: Long? = null
        if (mimeType.isNotEmpty()) {
            val uri = ContactsContract.Data.CONTENT_URI
            val projection = arrayOf(ContactsContract.Data._ID)
            val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} LIKE ?"
            val rawNum = call.phoneNumber.replace(Regex("[^0-9]"), "")
            val queryNum = if (rawNum.length > 10) rawNum.takeLast(10) else rawNum
            val selectionArgs = arrayOf(mimeType, "%$queryNum%")
            
            try {
                resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        dataId = cursor.getLong(0)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        runOnUiThread {
            try {
                if (dataId != null) {
                    // WhatsApp / Telegram — direct VoIP call via contacts provider
                    val callIntent = Intent(Intent.ACTION_VIEW)
                    callIntent.setDataAndType(
                        Uri.parse("content://com.android.contacts/data/$dataId"),
                        mimeType
                    )
                    startActivity(callIntent)
                } else if (platform == "messenger") {
                    // Messenger — open conversation thread so user can tap the call button
                    val rawNum = call.phoneNumber.replace(Regex("[^0-9+]"), "")
                    val threadUri = Uri.parse("fb-messenger://user-thread/$rawNum")
                    val intent = Intent(Intent.ACTION_VIEW, threadUri)
                    intent.setPackage("com.facebook.orca")
                    startActivity(intent)
                } else if (platform == "instagram") {
                    // Instagram — open the app's inbox / DMs
                    val inboxUri = Uri.parse("instagram://direct_inbox")
                    val intent = Intent(Intent.ACTION_VIEW, inboxUri)
                    intent.setPackage("com.instagram.android")
                    startActivity(intent)
                } else {
                    throw Exception("No VoIP contact found")
                }
            } catch (e: Exception) {
                // Fallback to chat / app home
                val fallbackUri = when (platform) {
                    "whatsapp" -> Uri.parse("https://api.whatsapp.com/send?phone=${call.phoneNumber}")
                    "telegram" -> Uri.parse("tg://resolve?domain=${call.phoneNumber}")
                    "messenger" -> Uri.parse("fb-messenger://")
                    "instagram" -> Uri.parse("https://instagram.com/")
                    else -> null
                }
                if (fallbackUri != null) {
                    val chatIntent = Intent(Intent.ACTION_VIEW, fallbackUri)
                    try {
                        startActivity(chatIntent)
                    } catch (e2: Exception) {
                        android.widget.Toast.makeText(this, "Could not find the app to open this chat.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.widget.Toast.makeText(this, "App not supported for direct calls.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
