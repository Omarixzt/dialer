package org.fossify.phone.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms.Intents.SECRET_CODE_ACTION
import android.telephony.TelephonyManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkAppSideloading
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isDefaultDialer
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.KeypadHelper
import org.fossify.commons.helpers.LOWER_ALPHA_INT
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.REQUEST_CODE_SET_DEFAULT_DIALER
import org.fossify.commons.helpers.isOreoPlus
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.ContactsAdapter
import org.fossify.phone.databinding.FragmentDialpadMainBinding
import org.fossify.phone.extensions.addCharacter
import org.fossify.phone.extensions.areMultipleSIMsAvailable
import org.fossify.phone.extensions.boundingBox
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.disableKeyboard
import org.fossify.phone.extensions.getKeyEvent
import org.fossify.phone.extensions.setupWithContacts
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.DIALPAD_TONE_LENGTH_MS
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.helpers.ToneGeneratorHelper
import org.fossify.phone.models.SpeedDial
import java.util.Locale
import kotlin.math.roundToInt
import org.fossify.phone.interfaces.RefreshItemsListener

class DialpadFragment(context: Context, attributeSet: AttributeSet) : MyViewPagerFragment<MyViewPagerFragment.DialpadInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentDialpadMainBinding
    private var allContacts = ArrayList<Contact>()
    private var speedDialValues = ArrayList<SpeedDial>()
    private var privateCursor: Cursor? = null
    private var toneGeneratorHelper: ToneGeneratorHelper? = null
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val pressedKeys = mutableSetOf<Char>()
    private var hasRussianLocale = false
    private val fragmentScope = CoroutineScope(Dispatchers.Main)

    private val russianCharsMap by lazy {
        hashMapOf(
            'а' to 2, 'б' to 2, 'в' to 2, 'г' to 2,
            'д' to 3, 'е' to 3, 'ё' to 3, 'ж' to 3, 'з' to 3,
            'и' to 4, 'й' to 4, 'к' to 4, 'л' to 4,
            'м' to 5, 'н' to 5, 'о' to 5, 'п' to 5,
            'р' to 6, 'с' to 6, 'т' to 6, 'у' to 6,
            'ф' to 7, 'х' to 7, 'ц' to 7, 'ч' to 7,
            'ш' to 8, 'щ' to 8, 'ъ' to 8, 'ы' to 8,
            'ь' to 9, 'э' to 9, 'ю' to 9, 'я' to 9
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentDialpadMainBinding.bind(this)
        innerBinding = DialpadInnerBinding(binding)
        hasRussianLocale = Locale.getDefault().language == "ru"
    }

    override fun setupFragment() {
        val simpleActivity = activity as? SimpleActivity ?: return
        if (simpleActivity.checkAppSideloading()) {
            return
        }

        binding.dialpadWrapper.apply {
            if (context.config.hideDialpadNumbers) {
                dialpad1Holder.isVisible = false
                dialpad2Holder.isVisible = false
                dialpad3Holder.isVisible = false
                dialpad4Holder.isVisible = false
                dialpad5Holder.isVisible = false
                dialpad6Holder.isVisible = false
                dialpad7Holder.isVisible = false
                dialpad8Holder.isVisible = false
                dialpad9Holder.isVisible = false
                dialpadPlusHolder.isVisible = true
                dialpad0Holder.visibility = View.INVISIBLE
            }

            arrayOf(
                dialpad0Holder,
                dialpad1Holder,
                dialpad2Holder,
                dialpad3Holder,
                dialpad4Holder,
                dialpad5Holder,
                dialpad6Holder,
                dialpad7Holder,
                dialpad8Holder,
                dialpad9Holder,
                dialpadPlusHolder,
                dialpadAsteriskHolder,
                dialpadHashtagHolder
            ).forEach {
                it.background =
                    ResourcesCompat.getDrawable(resources, R.drawable.dialpad_btn_bg, context.theme)
            }
        }

        speedDialValues = context.config.getSpeedDialValues()
        privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)

        toneGeneratorHelper = ToneGeneratorHelper(context, DIALPAD_TONE_LENGTH_MS)

        binding.dialpadWrapper.apply {
            if (hasRussianLocale) {
                dialpad2Letters.append("\nАБВГ")
                dialpad3Letters.append("\nДЕЁЖЗ")
                dialpad4Letters.append("\nИЙКЛ")
                dialpad5Letters.append("\nМНОП")
                dialpad6Letters.append("\nРСТУ")
                dialpad7Letters.append("\nФХЦЧ")
                dialpad8Letters.append("\nШЩЪЫ")
                dialpad9Letters.append("\nЬЭЮЯ")

                val fontSize = resources.getDimension(R.dimen.small_text_size)
                arrayOf(
                    dialpad2Letters,
                    dialpad3Letters,
                    dialpad4Letters,
                    dialpad5Letters,
                    dialpad6Letters,
                    dialpad7Letters,
                    dialpad8Letters,
                    dialpad9Letters
                ).forEach {
                    it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                }
            }

            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            setupCharClick(dialpadPlusHolder, '+', longClickable = false)
            setupCharClick(dialpadAsteriskHolder, '*', longClickable = false)
            setupCharClick(dialpadHashtagHolder, '#', longClickable = false)
        }

        binding.apply {
            dialpadClearChar.setOnClickListener { clearChar(it) }
            dialpadClearChar.setOnLongClickListener { clearInput(); true }
            dialpadCallButton.setOnClickListener { initCall(dialpadInput.value) }
            dialpadCallButton.setOnLongClickListener { initCallWithSimSelector() }
            dialpadInput.onTextChangeListener { dialpadValueChanged(it) }
            dialpadInput.requestFocus()
            dialpadInput.disableKeyboard()
        }
        refreshItems()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        context?.updateTextColors(binding.dialpadHolder)
        binding.dialpadClearChar.applyColorFilter(context?.getProperTextColor() ?: textColor)
        
        val callIcon = resources.getColoredDrawableWithColor(
            drawableId = R.drawable.ic_phone_vector,
            color = properPrimaryColor.getContrastColor()
        )
        binding.dialpadCallButton.setImageDrawable(callIcon)
        binding.dialpadCallButton.background.applyColorFilter(properPrimaryColor)

        binding.letterFastscroller.textColor = textColor.getColorStateList()
        binding.letterFastscroller.pressedTextColor = properPrimaryColor
        binding.letterFastscrollerThumb.setupWithFastScroller(binding.letterFastscroller)
        binding.letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
        binding.letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        ContactsHelper(context).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            activity?.runOnUiThread {
                gotContacts(contacts)
                callback?.invoke()
            }
        }
    }

    private fun dialpadPressed(char: Char, view: View?) {
        binding.dialpadInput.addCharacter(char)
        maybePerformDialpadHapticFeedback(view)
    }

    private fun clearChar(view: View) {
        binding.dialpadInput.dispatchKeyEvent(binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL))
        maybePerformDialpadHapticFeedback(view)
    }

    private fun clearInput() {
        binding.dialpadInput.setText("")
    }

    private fun clearInputWithDelay() {
        fragmentScope.launch {
            delay(1000)
            clearInput()
        }
    }

    private fun gotContacts(newContacts: ArrayList<Contact>) {
        allContacts = newContacts

        val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
        if (privateContacts.isNotEmpty()) {
            allContacts.addAll(privateContacts)
            allContacts.sort()
        }

        activity?.runOnUiThread {
            if (binding.dialpadInput.value.isEmpty()) {
                dialpadValueChanged("")
            }
        }
    }

    override fun onSearchClosed() {
        // Implement if needed
    }

    override fun onSearchQueryChanged(text: String) {
        // Search bar logic for dialpad if needed
    }

    private fun dialpadValueChanged(text: String) {
        val len = text.length
        if (len > 8 && text.startsWith("*#*#") && text.endsWith("#*#*")) {
            val secretCode = text.substring(4, text.length - 4)
            if (isOreoPlus()) {
                if (context?.isDefaultDialer() == true) {
                    context?.getSystemService(TelephonyManager::class.java)?.sendDialerSpecialCode(secretCode)
                } else {
                    val intent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    intent.putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context?.packageName)
                    activity?.startActivityForResult(intent, REQUEST_CODE_SET_DEFAULT_DIALER)
                }
            } else {
                val intent = Intent(SECRET_CODE_ACTION, "android_secret_code://$secretCode".toUri())
                context?.sendBroadcast(intent)
            }
            return
        }

        (binding.dialpadList.adapter as? ContactsAdapter)?.finishActMode()

        val filtered = allContacts.filter { contact ->
            var convertedName = KeypadHelper.convertKeypadLettersToDigits(
                contact.name.normalizeString()
            ).filterNot { it.isWhitespace() }

            if (hasRussianLocale) {
                var currConvertedName = ""
                convertedName.lowercase(Locale.getDefault()).forEach { char ->
                    val convertedChar = russianCharsMap.getOrElse(char) { char }
                    currConvertedName += convertedChar
                }
                convertedName = currConvertedName
            }

            contact.doesContainPhoneNumber(text) || (convertedName.contains(text, true))
        }.sortedWith(compareBy {
            !it.doesContainPhoneNumber(text)
        }).toMutableList() as ArrayList<Contact>

        binding.letterFastscroller.setupWithContacts(binding.dialpadList, filtered)

        ContactsAdapter(
            activity = activity as SimpleActivity,
            contacts = filtered,
            recyclerView = binding.dialpadList,
            highlightText = text,
            itemClick = {
                activity?.startCallWithConfirmationCheck(it as Contact)
                clearInputWithDelay()
            },
            profileIconClick = {
                activity?.startContactDetailsIntent(it as Contact)
            }).apply {
            binding.dialpadList.adapter = this
        }

        binding.dialpadPlaceholder.beVisibleIf(filtered.isEmpty())
        binding.dialpadList.beVisibleIf(filtered.isNotEmpty())
    }

    private fun initCall(number: String = binding.dialpadInput.value, name: String? = null) {
        if (number.isNotEmpty()) {
            activity?.startCallWithConfirmationCheck(number, name ?: number)
            clearInputWithDelay()
        } else {
            context?.let {
                RecentsHelper(it).getRecentCalls(queryLimit = 1) {
                    val mostRecentNumber = it.firstOrNull()?.phoneNumber
                    if (!mostRecentNumber.isNullOrEmpty()) {
                        activity?.runOnUiThread {
                            binding.dialpadInput.setText(mostRecentNumber)
                        }
                    }
                }
            }
        }
    }

    private fun initCallWithSimSelector(): Boolean {
        val number = binding.dialpadInput.value
        return if (context?.areMultipleSIMsAvailable() == true && number.isNotEmpty()) {
            activity?.startCallWithConfirmationCheck(
                recipient = number,
                name = number,
                forceSimSelector = true
            )
            true
        } else {
            false
        }
    }

    private fun speedDial(id: Int): Boolean {
        if (binding.dialpadInput.value.length == 1) {
            val speedDial = speedDialValues.firstOrNull { it.id == id }
            if (speedDial?.isValid() == true) {
                initCall(speedDial.number, context?.let { speedDial.getName(it) })
                return true
            }
        }
        return false
    }

    private fun startDialpadTone(char: Char) {
        if (context?.config?.dialpadBeeps == true) {
            pressedKeys.add(char)
            toneGeneratorHelper?.startTone(char)
        }
    }

    private fun stopDialpadTone(char: Char) {
        if (context?.config?.dialpadBeeps == true) {
            if (!pressedKeys.remove(char)) return
            if (pressedKeys.isEmpty()) {
                toneGeneratorHelper?.stopTone()
            } else {
                startDialpadTone(pressedKeys.last())
            }
        }
    }

    private fun maybePerformDialpadHapticFeedback(view: View?) {
        if (context?.config?.dialpadVibration == true) {
            view?.performHapticFeedback()
        }
    }

    private fun performLongClick(view: View, char: Char) {
        if (char == '0') {
            clearChar(view)
            dialpadPressed('+', view)
        } else {
            val result = speedDial(char.digitToInt())
            if (result) {
                stopDialpadTone(char)
                clearChar(view)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCharClick(view: View, char: Char, longClickable: Boolean = true) {
        view.isClickable = true
        view.isLongClickable = true
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dialpadPressed(char, view)
                    startDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                        longPressHandler.postDelayed({
                            performLongClick(view, char)
                        }, longPressTimeout)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val viewContainsTouchEvent = if (event.rawX.isNaN() || event.rawY.isNaN()) {
                        false
                    } else {
                        view.boundingBox.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                    }

                    if (!viewContainsTouchEvent) {
                        stopDialpadTone(char)
                        if (longClickable) {
                            longPressHandler.removeCallbacksAndMessages(null)
                        }
                    }
                }
            }
            false
        }
    }
}
