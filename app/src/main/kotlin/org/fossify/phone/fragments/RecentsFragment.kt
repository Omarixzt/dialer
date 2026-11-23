package org.fossify.phone.fragments

import android.content.Context
import android.util.AttributeSet
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.database.SocialCallDatabase
import org.fossify.phone.databinding.FragmentRecentsBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.runAfterAnimations
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.extensions.trySocialVoiceCall
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentRecentsBinding
    private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null

    private var searchQuery: String? = null
    private var recentsHelper = RecentsHelper(context)

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }

        binding.recentsPlaceholder.text = context.getString(placeholderResId)
        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(properPrimaryColor)

        recentsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (invalidate) {
            allRecentCalls = emptyList()
        }

        refreshCallLog(loadAll = false) {
            binding.recentsList.runAfterAnimations {
                refreshCallLog(loadAll = true)
            }
        }
    }

    override fun onSearchClosed() {
        searchQuery = null
        showOrHidePlaceholder(allRecentCalls.isEmpty())
        recentsAdapter?.updateItems(allRecentCalls)
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        updateSearchResult()
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult() {
        ensureBackgroundThread {
            val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
            val recentCalls = allRecentCalls
                .filterIsInstance<RecentCall>()
                .filter {
                    it.name.contains(fixedText, true) || it.doesContainPhoneNumber(fixedText)
                }
                .sortedWith(
                    compareByDescending<RecentCall> { it.dayCode }
                        .thenByDescending { it.name.startsWith(fixedText, true) }
                        .thenByDescending { it.startTS }
                )

            prepareCallLog(recentCalls) {
                activity?.runOnUiThread {
                    showOrHidePlaceholder(recentCalls.isEmpty())
                    recentsAdapter?.updateItems(it, fixedText)
                }
            }
        }
    }

    private fun requestCallLogPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                binding.recentsPlaceholder2.beGone()
                refreshCallLog()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        if (show && !binding.progressIndicator.isVisible()) {
            binding.recentsPlaceholder.beVisible()
        } else {
            binding.recentsPlaceholder.beGone()
        }
    }

    private fun gotRecents(recents: List<CallLogItem>) {
        android.util.Log.d("DIALER_DEBUG", "gotRecents called with ${recents.size} items. Adapter is null? ${binding.recentsList.adapter == null}")
        val firstWhatsapp = recents.firstOrNull { (it as? RecentCall)?.specificType == "Whatsapp" } as? RecentCall
        android.util.Log.d("DIALER_DEBUG", "First Whatsapp call in UI: ${firstWhatsapp?.name} - ${firstWhatsapp?.specificType}")
        binding.progressIndicator.hide()
        if (recents.isEmpty()) {
            binding.apply {
                showOrHidePlaceholder(true)
                recentsPlaceholder2.beGoneIf(context.hasPermission(PERMISSION_READ_CALL_LOG))
                recentsList.beGone()
            }
        } else {
            binding.apply {
                showOrHidePlaceholder(false)
                recentsPlaceholder2.beGone()
                recentsList.beVisible()
            }

            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = true,
                    itemDelete = { deleted ->
                        allRecentCalls = allRecentCalls.filter { it !in deleted }
                    },
                    itemClick = {
                        val recentCall = it as RecentCall
                        if (recentCall.specificType.lowercase() in listOf("whatsapp", "telegram", "messenger", "instagram")) {
                            (activity as? SimpleActivity)?.trySocialVoiceCall(recentCall)
                        } else {
                            activity?.startCallWithConfirmationCheck(recentCall.phoneNumber, recentCall.name)
                        }
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        val contact = findContactByCall(recentCall)
                        if (contact != null) {
                            activity?.startContactDetailsIntent(contact)
                        } else {
                            activity?.startAddContactIntent(recentCall.phoneNumber)
                        }
                    }
                )

                binding.recentsList.adapter = recentsAdapter
                recentsAdapter?.updateItems(recents)
            } else {
                recentsAdapter?.updateItems(recents)
            }
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            allRecentCalls = it
            if (searchQuery.isNullOrEmpty()) {
                activity?.runOnUiThread { gotRecents(it) }
            } else {
                updateSearchResult()
            }

            callback?.invoke()
        }
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<CallLogItem>) -> Unit) {
        val queryCount = if (loadAll) Int.MAX_VALUE else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls.filterIsInstance<RecentCall>()
            .filter { it.id < Int.MAX_VALUE - 100000 }

        with(recentsHelper) {
            if (context.config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount) { systemCalls ->
                    mergeWithSocialCalls(systemCalls, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount) { systemCalls ->
                    mergeWithSocialCalls(systemCalls, callback)
                }
            }
        }
    }

    private fun mergeWithSocialCalls(systemCalls: List<RecentCall>, callback: (List<CallLogItem>) -> Unit) {
        ensureBackgroundThread {
            val socialDao = org.fossify.phone.database.SocialCallDatabase.getDatabase(context).socialCallDao()
            val socialEntities = kotlinx.coroutines.runBlocking { socialDao.getAllCallsList() }
            
            android.util.Log.d("DIALER_DEBUG", "Social Entities from DB size: ${socialEntities.size}")
            
            val socialRecentCalls = socialEntities.map { entity ->
                RecentCall(
                    id = Int.MAX_VALUE - 10000 - entity.id.toInt(), // Large positive ID to avoid collision with system calls and NO_ID (-1)
                    phoneNumber = entity.phoneNumber,
                    name = entity.callerName ?: entity.phoneNumber,
                    photoUri = "",
                    startTS = entity.timestamp, // RecentCall expects milliseconds
                    duration = 0,
                    type = when (entity.callType) {
                        org.fossify.phone.database.CallType.INCOMING -> android.provider.CallLog.Calls.INCOMING_TYPE
                        org.fossify.phone.database.CallType.OUTGOING -> android.provider.CallLog.Calls.OUTGOING_TYPE
                        else -> android.provider.CallLog.Calls.MISSED_TYPE
                    },
                    simID = -1,
                    simColor = 0,
                    specificNumber = "",
                    specificType = entity.platformSource.name.lowercase().replaceFirstChar { it.uppercase() },
                    isUnknownNumber = false
                ).also { android.util.Log.d("DIALER_DEBUG", "Mapped social call: id=${it.id}, name=${it.name}, type=${it.type}, specificType=${it.specificType}") }
            }

            val mergedCalls = (systemCalls + socialRecentCalls)
                .distinctBy { it.id }
                .sortedByDescending { it.startTS }
            val firstWhatsapp = mergedCalls.firstOrNull { (it as? RecentCall)?.specificType == "Whatsapp" }
            android.util.Log.d("DIALER_DEBUG", "mergedCalls size: ${mergedCalls.size}. First Whatsapp call: $firstWhatsapp")
            prepareCallLog(mergedCalls, callback)
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<CallLogItem>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            ensureBackgroundThread {
                val privateContacts = getPrivateContacts()
                val updatedCalls = updateNamesIfEmpty(
                    calls = maybeFilterPrivateCalls(calls, privateContacts),
                    contacts = contacts,
                    privateContacts = privateContacts
                )

                callback(
                    groupCallsByDate(updatedCalls)
                )
            }
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(context, privateCursor)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = context.baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun updateNamesIfEmpty(calls: List<RecentCall>, contacts: List<Contact>, privateContacts: List<Contact>): List<RecentCall> {
        if (calls.isEmpty()) return mutableListOf()

        val contactsWithNumbers = contacts.filter { it.phoneNumbers.isNotEmpty() }
        return calls.map { call ->
            if (call.phoneNumber == call.name) {
                val privateContact = privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) }
                val contact = contactsWithNumbers.firstOrNull { it.phoneNumbers.first().normalizedNumber == call.phoneNumber }

                when {
                    privateContact != null -> withUpdatedName(call = call, name = privateContact.getNameToDisplay())
                    contact != null -> withUpdatedName(call = call, name = contact.getNameToDisplay())
                    else -> call
                }
            } else {
                call
            }
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    private fun groupCallsByDate(recentCalls: List<RecentCall>): MutableList<CallLogItem> {
        val callLog = mutableListOf<CallLogItem>()
        var lastDayCode = ""
        for (call in recentCalls) {
            val currentDayCode = call.dayCode
            if (currentDayCode != lastDayCode) {
                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                lastDayCode = currentDayCode
            }

            callLog += call
        }

        return callLog
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return (activity as MainActivity).cachedContacts.find { it.name == recentCall.name && it.doesHavePhoneNumber(recentCall.phoneNumber) }
    }
}
