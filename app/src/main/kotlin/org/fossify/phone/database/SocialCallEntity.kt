package org.fossify.phone.database

enum class CallType { INCOMING, MISSED, OUTGOING }
enum class PlatformSource { WHATSAPP, MESSENGER, TELEGRAM, INSTAGRAM }

data class SocialCallEntity(
    val id: Long = 0,
    val callerName: String?,
    val phoneNumber: String,
    val timestamp: Long,
    val callType: CallType,
    val platformSource: PlatformSource,
    val associatedContactId: Long?
)
