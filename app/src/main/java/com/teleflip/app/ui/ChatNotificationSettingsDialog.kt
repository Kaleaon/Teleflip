package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.teleflip.app.R
import com.teleflip.app.input.InputMode
import com.teleflip.app.input.KeypadInputEngine
import com.teleflip.app.telegram.TelegramManager

enum class MuteDurationMode {
    UNMUTE,
    HOUR_1,
    HOURS_8,
    DAYS_2,
    FOREVER,
    CUSTOM,
}

enum class CustomTimeUnit {
    MINUTES,
    HOURS,
    DAYS,
}

@Suppress("MagicNumber", "LongMethod", "ComplexMethod")
class ChatNotificationSettingsDialog(
    context: Context,
    private val telegramManager: TelegramManager,
    private val chatId: Long,
    private val chatTitle: String,
    private val timeoutMs: Long = 500L,
    private val onSettingsChanged: ((isMuted: Boolean) -> Unit)? = null,
) : BaseDialog(context) {

    private var selectedMode = MuteDurationMode.UNMUTE
    private var selectedUnit = CustomTimeUnit.MINUTES
    private var isSoundMuted = false
    private var currentToneIndex = 0
    private var customSoundUri: String? = null
    private var engine: KeypadInputEngine? = null

    private val availableTones = listOf(
        "Default" to null,
        "Beep" to "content://media/internal/audio/media/beep",
        "Chime" to "content://media/internal/audio/media/chime",
        "Whistle" to "content://media/internal/audio/media/whistle",
    )

    companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3600L
        const val SECONDS_PER_DAY = 86400L
        const val MUTE_FOREVER_SEC = 2147483647

        fun calculateCustomMuteDurationSeconds(rawValue: String, unit: CustomTimeUnit): Int {
            val value = rawValue.trim().toLongOrNull() ?: 0L
            if (value <= 0L) return 0
            val multiplier = when (unit) {
                CustomTimeUnit.MINUTES -> SECONDS_PER_MINUTE
                CustomTimeUnit.HOURS -> SECONDS_PER_HOUR
                CustomTimeUnit.DAYS -> SECONDS_PER_DAY
            }
            val totalSeconds = try {
                Math.multiplyExact(value, multiplier)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
            return if (totalSeconds > Int.MAX_VALUE) Int.MAX_VALUE else totalSeconds.toInt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_chat_notification_settings)
        setupDismissKeys()

        val tvTitle = findViewById<TextView>(R.id.tv_notification_title)
        val tvMode = findViewById<TextView>(R.id.tv_notification_input_mode)
        val btnUnmute = findViewById<Button>(R.id.btn_mute_preset_unmute)
        val btn1h = findViewById<Button>(R.id.btn_mute_preset_1h)
        val btn8h = findViewById<Button>(R.id.btn_mute_preset_8h)
        val btn2d = findViewById<Button>(R.id.btn_mute_preset_2d)
        val btnForever = findViewById<Button>(R.id.btn_mute_preset_forever)
        val btnCustom = findViewById<Button>(R.id.btn_mute_preset_custom)

        val layoutCustom = findViewById<LinearLayout>(R.id.layout_custom_duration)
        val etCustomValue = findViewById<EditText>(R.id.et_custom_duration_value)
        val btnMinutes = findViewById<Button>(R.id.btn_unit_minutes)
        val btnHours = findViewById<Button>(R.id.btn_unit_hours)
        val btnDays = findViewById<Button>(R.id.btn_unit_days)

        val btnToggleSound = findViewById<Button>(R.id.btn_toggle_sound_mute)
        val btnSelectTone = findViewById<Button>(R.id.btn_select_ringtone)

        val btnSave = findViewById<Button>(R.id.btn_save_notification_settings)
        val btnCancel = findViewById<Button>(R.id.btn_cancel_notification_settings)

        tvTitle?.text = "🔔 Notifications: $chatTitle"

        // Initialize state from telegramManager
        val isMutedCurrently = telegramManager.isChatMuted(chatId)
        isSoundMuted = telegramManager.isChatSoundMuted(chatId)
        customSoundUri = telegramManager.getChatSoundUri(chatId)

        if (customSoundUri != null) {
            val foundIdx = availableTones.indexOfFirst { it.second == customSoundUri }
            if (foundIdx >= 0) {
                currentToneIndex = foundIdx
            }
        }

        selectedMode = if (isMutedCurrently) MuteDurationMode.FOREVER else MuteDurationMode.UNMUTE

        if (etCustomValue != null && tvMode != null) {
            engine = KeypadInputEngine(
                editText = etCustomValue,
                modeIndicator = tvMode,
                timeoutMs = timeoutMs,
                onSymbolRequested = {
                    SymbolsDialog(context) { sym -> engine?.insertSymbol(sym) }.show()
                },
            ).apply {
                setMode(InputMode.NUMERIC)
            }
        }

        fun updatePresetSelection(mode: MuteDurationMode) {
            selectedMode = mode
            val buttons = listOf(
                MuteDurationMode.UNMUTE to btnUnmute,
                MuteDurationMode.HOUR_1 to btn1h,
                MuteDurationMode.HOURS_8 to btn8h,
                MuteDurationMode.DAYS_2 to btn2d,
                MuteDurationMode.FOREVER to btnForever,
                MuteDurationMode.CUSTOM to btnCustom,
            )
            buttons.forEach { (m, btn) ->
                if (m == selectedMode) {
                    btn?.setBackgroundResource(R.drawable.bg_button_selector)
                } else {
                    btn?.setBackgroundResource(R.drawable.bg_item_selector)
                }
            }
            if (selectedMode == MuteDurationMode.CUSTOM) {
                layoutCustom?.visibility = View.VISIBLE
                etCustomValue?.requestFocus()
            } else {
                layoutCustom?.visibility = View.GONE
            }
        }

        fun updateUnitSelection(unit: CustomTimeUnit) {
            selectedUnit = unit
            btnMinutes?.setBackgroundResource(if (unit == CustomTimeUnit.MINUTES) R.drawable.bg_button_selector else R.drawable.bg_item_selector)
            btnHours?.setBackgroundResource(if (unit == CustomTimeUnit.HOURS) R.drawable.bg_button_selector else R.drawable.bg_item_selector)
            btnDays?.setBackgroundResource(if (unit == CustomTimeUnit.DAYS) R.drawable.bg_button_selector else R.drawable.bg_item_selector)
        }

        fun updateSoundToggleUI() {
            if (isSoundMuted) {
                btnToggleSound?.text = "🔇 Sound: MUTED (Silent)"
            } else {
                btnToggleSound?.text = "🔊 Sound: ON"
            }
        }

        fun updateToneUI() {
            val (name, _) = availableTones[currentToneIndex]
            btnSelectTone?.text = "🔔 Ringtone: $name"
        }

        btnUnmute?.setOnClickListener { updatePresetSelection(MuteDurationMode.UNMUTE) }
        btn1h?.setOnClickListener { updatePresetSelection(MuteDurationMode.HOUR_1) }
        btn8h?.setOnClickListener { updatePresetSelection(MuteDurationMode.HOURS_8) }
        btn2d?.setOnClickListener { updatePresetSelection(MuteDurationMode.DAYS_2) }
        btnForever?.setOnClickListener { updatePresetSelection(MuteDurationMode.FOREVER) }
        btnCustom?.setOnClickListener { updatePresetSelection(MuteDurationMode.CUSTOM) }

        btnMinutes?.setOnClickListener { updateUnitSelection(CustomTimeUnit.MINUTES) }
        btnHours?.setOnClickListener { updateUnitSelection(CustomTimeUnit.HOURS) }
        btnDays?.setOnClickListener { updateUnitSelection(CustomTimeUnit.DAYS) }

        btnToggleSound?.setOnClickListener {
            isSoundMuted = !isSoundMuted
            updateSoundToggleUI()
        }

        btnSelectTone?.setOnClickListener {
            currentToneIndex = (currentToneIndex + 1) % availableTones.size
            customSoundUri = availableTones[currentToneIndex].second
            updateToneUI()
        }

        updatePresetSelection(selectedMode)
        updateUnitSelection(selectedUnit)
        updateSoundToggleUI()
        updateToneUI()

        btnSave?.setOnClickListener {
            engine?.commitCurrentChar()
            val muteForSeconds = when (selectedMode) {
                MuteDurationMode.UNMUTE -> 0
                MuteDurationMode.HOUR_1 -> 3600
                MuteDurationMode.HOURS_8 -> 28800
                MuteDurationMode.DAYS_2 -> 172800
                MuteDurationMode.FOREVER -> MUTE_FOREVER_SEC
                MuteDurationMode.CUSTOM -> {
                    val rawText = etCustomValue?.text?.toString().orEmpty()
                    calculateCustomMuteDurationSeconds(rawText, selectedUnit)
                }
            }

            telegramManager.setChatNotificationSettings(
                chatId = chatId,
                muteForSeconds = muteForSeconds,
                isSoundMuted = isSoundMuted,
                customSoundUri = customSoundUri,
            ) { isMuted ->
                val statusMsg = if (isMuted) "Muted $chatTitle" else "Unmuted $chatTitle"
                val soundMsg = if (isSoundMuted) " (Sound Off)" else ""
                Toast.makeText(context, "$statusMsg$soundMsg", Toast.LENGTH_SHORT).show()
                onSettingsChanged?.invoke(isMuted)
            }
            dismiss()
        }

        btnCancel?.setOnClickListener {
            engine?.commitCurrentChar()
            dismiss()
        }
    }

    override fun onBackPressed() {
        engine?.commitCurrentChar()
        dismiss()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isBackOrRightSoftKey(keyCode)) {
            engine?.commitCurrentChar()
            dismiss()
            return true
        }

        val focusedEdit = currentFocus as? EditText
        if (focusedEdit != null) {
            if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
                engine?.commitCurrentChar()
                focusedEdit.setText("")
                return true
            }
            val isInputKey =
                (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                    keyCode == KeyEvent.KEYCODE_STAR ||
                    keyCode == KeyEvent.KEYCODE_POUND ||
                    keyCode == KeyEvent.KEYCODE_DEL
            if (isInputKey && engine?.handleKeyEvent(keyCode, event) == true) {
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }
}
