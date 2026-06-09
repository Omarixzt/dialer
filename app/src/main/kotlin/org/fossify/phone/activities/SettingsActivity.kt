package org.fossify.phone.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.lifecycle.lifecycleScope
    import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.commons.activities.ManageBlockedNumbersActivity
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.FeatureLockedDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.addLockedLabelIfNeeded
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getFontSizeText
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FONT_SIZE_EXTRA_LARGE
import org.fossify.commons.helpers.FONT_SIZE_LARGE
import org.fossify.commons.helpers.FONT_SIZE_MEDIUM
import org.fossify.commons.helpers.FONT_SIZE_SMALL
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ON_CLICK_CALL_CONTACT
import org.fossify.commons.helpers.ON_CLICK_VIEW_CONTACT
import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES
import org.fossify.commons.helpers.TAB_LAST_USED
import org.fossify.commons.helpers.isNougatPlus
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivitySettingsBinding
import org.fossify.phone.dialogs.ExportCallHistoryDialog
import org.fossify.phone.dialogs.ManageVisibleTabsDialog
import org.fossify.phone.extensions.canLaunchAccountsConfiguration
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.launchAccountsConfiguration
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.models.RecentCall
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    companion object {
        private const val CALL_HISTORY_FILE_TYPE = "application/json"
        private val IMPORT_CALL_HISTORY_FILE_TYPES = buildList {
            add("application/json")
            if (!isQPlus()) {
                // Workaround for https://github.com/FossifyOrg/Messages/issues/88
                add("application/octet-stream")
            }
        }
    }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)
    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                toast(R.string.importing)
                importCallHistory(uri)
            }
        }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(CALL_HISTORY_FILE_TYPE)) { uri ->
        if (uri != null) {
            toast(R.string.exporting)
            RecentsHelper(this).getRecentCalls(queryLimit = Int.MAX_VALUE) { recents ->
                exportCallHistory(recents, uri)
            }
        }
    }

    private val getSocialContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(R.string.importing)
            lifecycleScope.launch {
                try {
                    val jsonString = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return@launch
                    org.fossify.phone.helpers.ContactBackupManager.importDataFromJson(this@SettingsActivity, jsonString)
                    toast(R.string.importing_successful)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        }
    }

    private val saveSocialDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(CALL_HISTORY_FILE_TYPE)) { uri ->
        if (uri != null) {
            toast(R.string.exporting)
            lifecycleScope.launch {
                try {
                    val dao = org.fossify.phone.database.SocialCallDatabase.getDatabase(this@SettingsActivity).socialCallDao()
                    val calls = dao.getAllCallsList()
                    val jsonString = org.fossify.phone.helpers.ContactBackupManager.exportDataToJson(this@SettingsActivity, calls)
                    org.fossify.phone.helpers.ContactBackupManager.saveJsonToSafUri(this@SettingsActivity, uri, jsonString)
                    toast(R.string.exporting_successful)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        refreshMenuItems()

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(settingsNestedScrollview))
            setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupUseEnglish()
        setupLanguage()
        setupManageBlockedNumbers()
        setupManageSpeedDial()
        setupChangeDateTimeFormat()
        setupFontSize()
        setupManageShownTabs()
        setupDefaultTab()
        setupOnContactClick()
        setupDialPadOpen()
        setupGroupSubsequentCalls()
        setupStartNameWithSurname()
        setupFormatPhoneNumbers()
        setupDialpadVibrations()
        setupDialpadNumbers()
        setupDialpadBeeps()
        setupShowCallConfirmation()
        setupDisableProximitySensor()
        setupDisableSwipeToAnswer()
        setupAlwaysShowFullscreen()
        setupSocialDialerSettings()
        setupCallsExport()
        setupCallsImport()
        updateTextColors(binding.settingsHolder)

        binding.apply {
            arrayOf(
                settingsColorCustomizationSectionLabel,
                settingsGeneralSettingsLabel,
                settingsStartupLabel,
                settingsCallsLabel,
                settingsDialpadSectionLabel,
                settingsSocialSectionLabel,
                settingsMigrationSectionLabel
            ).forEach {
                it.setTextColor(getProperPrimaryColor())
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        updateMenuItemColors(menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.calling_accounts -> launchAccountsConfiguration()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.settingsToolbar.menu.apply {
            findItem(R.id.calling_accounts).isVisible = canLaunchAccountsConfiguration()
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupUseEnglish() {
        binding.apply {
            settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
            settingsUseEnglish.isChecked = config.useEnglish
            settingsUseEnglishHolder.setOnClickListener {
                settingsUseEnglish.toggle()
                config.useEnglish = settingsUseEnglish.isChecked
                exitProcess(0)
            }
        }
    }

    private fun setupLanguage() {
        binding.apply {
            settingsLanguage.text = Locale.getDefault().displayLanguage
            settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        }
    }

    private fun setupManageBlockedNumbers() {
        binding.apply {
            settingsManageBlockedNumbersLabel.text = addLockedLabelIfNeeded(R.string.manage_blocked_numbers)
            settingsManageBlockedNumbersHolder.beVisibleIf(isNougatPlus())
            settingsManageBlockedNumbersHolder.setOnClickListener {
                if (isOrWasThankYouInstalled()) {
                    Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                        startActivity(this)
                    }
                } else {
                    FeatureLockedDialog(this@SettingsActivity) { }
                }
            }
        }
    }

    private fun setupManageSpeedDial() {
        binding.settingsManageSpeedDialHolder.setOnClickListener {
            Intent(this, ManageSpeedDialActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupFontSize() {
        binding.settingsFontSize.text = getFontSizeText()
        binding.settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(R.string.large)),
                RadioItem(FONT_SIZE_EXTRA_LARGE, getString(R.string.extra_large))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                binding.settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupManageShownTabs() {
        binding.settingsManageTabsHolder.setOnClickListener {
            ManageVisibleTabsDialog(this)
        }
    }

    private fun setupDefaultTab() {
        binding.settingsDefaultTab.text = getDefaultTabText()
        binding.settingsDefaultTabHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(TAB_CONTACTS, getString(R.string.contacts_tab)),
                RadioItem(TAB_FAVORITES, getString(R.string.favorites_tab)),
                RadioItem(TAB_CALL_HISTORY, getString(R.string.call_history_tab)),
                RadioItem(TAB_LAST_USED, getString(R.string.last_used_tab))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.defaultTab) {
                config.defaultTab = it as Int
                binding.settingsDefaultTab.text = getDefaultTabText()
            }
        }
    }

    private fun getDefaultTabText() = getString(
        when (baseConfig.defaultTab) {
            TAB_CONTACTS -> R.string.contacts_tab
            TAB_FAVORITES -> R.string.favorites_tab
            TAB_CALL_HISTORY -> R.string.call_history_tab
            else -> R.string.last_used_tab
        }
    )

    private fun setupOnContactClick() {
        binding.settingsOnContactClick.text = getOnContactClickText()
        binding.settingsOnContactClickHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(ON_CLICK_CALL_CONTACT, getString(org.fossify.commons.R.string.call_contact)),
                RadioItem(ON_CLICK_VIEW_CONTACT, getString(org.fossify.commons.R.string.view_contact))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.onContactClick) {
                config.onContactClick = it as Int
                binding.settingsOnContactClick.text = getOnContactClickText()
            }
        }
    }

    private fun getOnContactClickText() = getString(
        when (config.onContactClick) {
            ON_CLICK_CALL_CONTACT -> org.fossify.commons.R.string.call_contact
            else -> org.fossify.commons.R.string.view_contact
        }
    )

    private fun setupDialPadOpen() {
        binding.apply {
            settingsOpenDialpadAtLaunch.isChecked = config.openDialPadAtLaunch
            settingsOpenDialpadAtLaunchHolder.setOnClickListener {
                settingsOpenDialpadAtLaunch.toggle()
                config.openDialPadAtLaunch = settingsOpenDialpadAtLaunch.isChecked
            }
        }
    }

    private fun setupGroupSubsequentCalls() {
        binding.apply {
            settingsGroupSubsequentCalls.isChecked = config.groupSubsequentCalls
            settingsGroupSubsequentCallsHolder.setOnClickListener {
                settingsGroupSubsequentCalls.toggle()
                config.groupSubsequentCalls = settingsGroupSubsequentCalls.isChecked
            }
        }
    }

    private fun setupStartNameWithSurname() {
        binding.apply {
            settingsStartNameWithSurname.isChecked = config.startNameWithSurname
            settingsStartNameWithSurnameHolder.setOnClickListener {
                settingsStartNameWithSurname.toggle()
                config.startNameWithSurname = settingsStartNameWithSurname.isChecked
            }
        }
    }

    private fun setupFormatPhoneNumbers() {
        binding.settingsFormatPhoneNumbers.isChecked = config.formatPhoneNumbers
        binding.settingsFormatPhoneNumbersHolder.setOnClickListener {
            binding.settingsFormatPhoneNumbers.toggle()
            config.formatPhoneNumbers = binding.settingsFormatPhoneNumbers.isChecked
        }
    }

    private fun setupDialpadVibrations() {
        binding.apply {
            settingsDialpadVibration.isChecked = config.dialpadVibration
            settingsDialpadVibrationHolder.setOnClickListener {
                settingsDialpadVibration.toggle()
                config.dialpadVibration = settingsDialpadVibration.isChecked
            }
        }
    }

    private fun setupDialpadNumbers() {
        binding.apply {
            settingsHideDialpadNumbers.isChecked = config.hideDialpadNumbers
            settingsHideDialpadNumbersHolder.setOnClickListener {
                settingsHideDialpadNumbers.toggle()
                config.hideDialpadNumbers = settingsHideDialpadNumbers.isChecked
            }
        }
    }

    private fun setupDialpadBeeps() {
        binding.apply {
            settingsDialpadBeeps.isChecked = config.dialpadBeeps
            settingsDialpadBeepsHolder.setOnClickListener {
                settingsDialpadBeeps.toggle()
                config.dialpadBeeps = settingsDialpadBeeps.isChecked
            }
        }
    }

    private fun setupShowCallConfirmation() {
        binding.apply {
            settingsShowCallConfirmation.isChecked = config.showCallConfirmation
            settingsShowCallConfirmationHolder.setOnClickListener {
                settingsShowCallConfirmation.toggle()
                config.showCallConfirmation = settingsShowCallConfirmation.isChecked
            }
        }
    }

    private fun setupDisableProximitySensor() {
        binding.apply {
            settingsDisableProximitySensor.isChecked = config.disableProximitySensor
            settingsDisableProximitySensorHolder.setOnClickListener {
                settingsDisableProximitySensor.toggle()
                config.disableProximitySensor = settingsDisableProximitySensor.isChecked
            }
        }
    }

    private fun setupDisableSwipeToAnswer() {
        binding.apply {
            settingsDisableSwipeToAnswer.isChecked = config.disableSwipeToAnswer
            settingsDisableSwipeToAnswerHolder.setOnClickListener {
                settingsDisableSwipeToAnswer.toggle()
                config.disableSwipeToAnswer = settingsDisableSwipeToAnswer.isChecked
            }
        }
    }

    private fun setupAlwaysShowFullscreen() {
        binding.apply {
            settingsAlwaysShowFullscreen.isChecked = config.alwaysShowFullscreen
            settingsAlwaysShowFullscreenHolder.setOnClickListener {
                settingsAlwaysShowFullscreen.toggle()
                config.alwaysShowFullscreen = settingsAlwaysShowFullscreen.isChecked
            }
        }
    }

    private fun setupSocialDialerSettings() {
        val prefs = getSharedPreferences("social_dialer_prefs", Context.MODE_PRIVATE)
        
        binding.settingsCaptureWhatsapp.isChecked = prefs.getBoolean("com.whatsapp", false)
        binding.settingsCaptureWhatsappHolder.setOnClickListener {
            binding.settingsCaptureWhatsapp.toggle()
            val isChecked = binding.settingsCaptureWhatsapp.isChecked
            prefs.edit()
                .putBoolean("com.whatsapp", isChecked)
                .putBoolean("com.whatsapp.w4b", isChecked)
                .apply()
        }

        binding.settingsCaptureTelegram.isChecked = prefs.getBoolean("org.telegram.messenger", false)
        binding.settingsCaptureTelegramHolder.setOnClickListener {
            binding.settingsCaptureTelegram.toggle()
            val isChecked = binding.settingsCaptureTelegram.isChecked
            prefs.edit()
                .putBoolean("org.telegram.messenger", isChecked)
                .putBoolean("org.telegram.messenger.web", isChecked)
                .apply()
        }

        binding.settingsCaptureMessenger.isChecked = prefs.getBoolean("com.facebook.orca", false)
        binding.settingsCaptureMessengerHolder.setOnClickListener {
            binding.settingsCaptureMessenger.toggle()
            prefs.edit().putBoolean("com.facebook.orca", binding.settingsCaptureMessenger.isChecked).apply()
        }

        binding.settingsCaptureInstagram.isChecked = prefs.getBoolean("com.instagram.android", false)
        binding.settingsCaptureInstagramHolder.setOnClickListener {
            binding.settingsCaptureInstagram.toggle()
            prefs.edit().putBoolean("com.instagram.android", binding.settingsCaptureInstagram.isChecked).apply()
        }

        binding.settingsAutoCleanup.isChecked = prefs.getBoolean("social_cleanup_enabled", true)
        binding.settingsAutoCleanupHolder.setOnClickListener {
            binding.settingsAutoCleanup.toggle()
            prefs.edit().putBoolean("social_cleanup_enabled", binding.settingsAutoCleanup.isChecked).apply()
        }

        val cn = android.content.ComponentName(this, org.fossify.phone.services.CallNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isGranted = flat != null && flat.contains(cn.flattenToString())
        
        binding.settingsNotificationAccessStatus.apply {
            if (isGranted) {
                text = getString(R.string.notification_access_active)
                setTextColor(resources.getColor(R.color.color_incoming_call, theme))
            } else {
                text = getString(R.string.notification_access_inactive)
                setTextColor(resources.getColor(R.color.color_missed_call, theme))
            }
        }

        binding.settingsNotificationAccessHolder.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.settingsBatteryOptimizationHolder.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${packageName}")
            startActivity(intent)
        }

        binding.settingsExportSocialCallsHolder.setOnClickListener {
            saveSocialDocument.launch("social_calls_backup.json")
        }

        binding.settingsImportSocialCallsHolder.setOnClickListener {
            getSocialContent.launch(IMPORT_CALL_HISTORY_FILE_TYPES.toTypedArray())
        }
    }

    private fun setupCallsExport() {
        binding.settingsExportCallsHolder.setOnClickListener {
            ExportCallHistoryDialog(this) { filename ->
                saveDocument.launch("$filename.json")
            }
        }
    }

    private fun setupCallsImport() {
        binding.settingsImportCallsHolder.setOnClickListener {
            getContent.launch(IMPORT_CALL_HISTORY_FILE_TYPES.toTypedArray())
        }
    }

    private fun importCallHistory(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)!!.use { inputStream ->
                inputStream.bufferedReader().readText()
            }

            val objects = Json.decodeFromString<List<RecentCall>>(jsonString)

            if (objects.isEmpty()) {
                toast(R.string.no_entries_for_importing)
                return
            }

            RecentsHelper(this).restoreRecentCalls(this, objects) {
                toast(R.string.importing_successful)
            }
        } catch (_: SerializationException) {
            toast(R.string.invalid_file_format)
        } catch (_: IllegalArgumentException) {
            toast(R.string.invalid_file_format)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun exportCallHistory(recents: List<RecentCall>, uri: Uri) {
        if (recents.isEmpty()) {
            toast(R.string.no_entries_for_exporting)
        } else {
            try {
                val outputStream = contentResolver.openOutputStream(uri)!!

                val jsonString = Json.encodeToString(recents)
                outputStream.use {
                    it.write(jsonString.toByteArray())
                }
                toast(R.string.exporting_successful)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}
