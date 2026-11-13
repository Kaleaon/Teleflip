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
import com.teleflip.app.model.ProxyType
import com.teleflip.app.storage.ProxyStorage
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.launch

@Suppress("MagicNumber", "LongMethod", "ComplexMethod")
class ProxySettingsDialog(
    context: Context,
    private val telegramManager: TelegramManager,
    private val timeoutMs: Long = 500L,
    private val onProxyChanged: (() -> Unit)? = null,
) : BaseDialog(context) {
    private val engines = mutableMapOf<Int, KeypadInputEngine>()
    private var selectedType = ProxyType.SOCKS5
    private var softkeyManager: SoftkeyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_proxy_settings)
        setupDismissKeys()

        val softkeyBar = findViewById<View>(R.id.softkey_bar)
        val tvLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = findViewById<TextView>(R.id.tv_softkey_right)

        if (tvLeft != null && tvCenter != null && tvRight != null) {
            softkeyManager = SoftkeyManager(softkeyBar, tvLeft, tvCenter, tvRight)
        }

        val tvMode = findViewById<TextView>(R.id.tv_proxy_input_mode)
        val tvStatus = findViewById<TextView>(R.id.tv_proxy_active_status)
        val btnSocks5 = findViewById<Button>(R.id.btn_proxy_type_socks5)
        val btnMtproto = findViewById<Button>(R.id.btn_proxy_type_mtproto)
        val etServer = findViewById<EditText>(R.id.et_proxy_server)
        val etPort = findViewById<EditText>(R.id.et_proxy_port)
        val etUsername = findViewById<EditText>(R.id.et_proxy_username)
        val etPassword = findViewById<EditText>(R.id.et_proxy_password)
        val etSecret = findViewById<EditText>(R.id.et_proxy_secret)
        val layoutSocks5 = findViewById<LinearLayout>(R.id.layout_socks5_credentials)
        val layoutMtproto = findViewById<LinearLayout>(R.id.layout_mtproto_credentials)
        val btnSaveEnable = findViewById<Button>(R.id.btn_proxy_save_enable)
        val btnDisable = findViewById<Button>(R.id.btn_proxy_disable)
        val btnAutoFailoverToggle = findViewById<Button>(R.id.btn_auto_failover_toggle)
        val btnClose = findViewById<Button>(R.id.btn_proxy_close)
        val containerSaved = findViewById<LinearLayout>(R.id.layout_saved_proxies_container)

        fun updateAutoFailoverButton() {
            val autoEnabled = ProxyStorage.isAutoProxyFailoverEnabled(context)
            btnAutoFailoverToggle?.text = if (autoEnabled) "🔄 Auto Failover: Enabled" else "⏸️ Auto Failover: Disabled (Manual)"
            btnAutoFailoverToggle?.setTextColor(context.getColor(if (autoEnabled) R.color.focus_highlight_gold else R.color.text_secondary))
        }

        btnAutoFailoverToggle?.setOnClickListener {
            val current = ProxyStorage.isAutoProxyFailoverEnabled(context)
            ProxyStorage.setAutoProxyFailoverEnabled(context, !current)
            updateAutoFailoverButton()
            Toast.makeText(context, if (!current) "Auto failover enabled" else "Auto failover disabled (manual mode)", Toast.LENGTH_SHORT).show()
        }
        updateAutoFailoverButton()

        val focusChangeListener =
            View.OnFocusChangeListener { _, _ ->
                updateSoftkeys()
            }

        val editTexts = listOfNotNull(etServer, etPort, etUsername, etPassword, etSecret)
        editTexts.forEach { field ->
            field.onFocusChangeListener = focusChangeListener
            engines[field.id] =
                KeypadInputEngine(
                    editText = field,
                    modeIndicator = tvMode,
                    timeoutMs = timeoutMs,
                    onSymbolRequested = {
                        SymbolsDialog(context) { sym -> engines[field.id]?.insertSymbol(sym) }.show()
                    },
                )
        }
        etPort?.id?.let { portId ->
            engines[portId]?.setMode(InputMode.NUMERIC)
        }

        listOfNotNull(btnSocks5, btnMtproto, btnSaveEnable, btnDisable, btnAutoFailoverToggle, btnClose).forEach {
            it.onFocusChangeListener = focusChangeListener
        }

        window?.decorView?.viewTreeObserver?.addOnGlobalFocusChangeListener { _, _ ->
            updateSoftkeys()
        }

        fun updateTypeSelection(type: ProxyType) {
            selectedType = type
            if (type == ProxyType.SOCKS5) {
                btnSocks5?.setBackgroundResource(R.drawable.bg_button_selector)
                btnMtproto?.setBackgroundResource(R.drawable.bg_item_selector)
                layoutSocks5?.visibility = View.VISIBLE
                layoutMtproto?.visibility = View.GONE
                etPort?.nextFocusDownId = R.id.et_proxy_username
                btnSaveEnable?.nextFocusUpId = R.id.et_proxy_password
            } else {
                btnSocks5?.setBackgroundResource(R.drawable.bg_item_selector)
                btnMtproto?.setBackgroundResource(R.drawable.bg_button_selector)
                layoutSocks5?.visibility = View.GONE
                layoutMtproto?.visibility = View.VISIBLE
                etPort?.nextFocusDownId = R.id.et_proxy_secret
                btnSaveEnable?.nextFocusUpId = R.id.et_proxy_secret
            }
        }

        btnSocks5?.setOnClickListener { updateTypeSelection(ProxyType.SOCKS5) }
        btnMtproto?.setOnClickListener { updateTypeSelection(ProxyType.MTPROTO) }
        updateTypeSelection(ProxyType.SOCKS5)

        fun refreshSavedProxiesList() {
            containerSaved?.removeAllViews()
            val proxies = telegramManager.getProxies()
            val active = telegramManager.getEnabledProxy()

            if (active != null) {
                tvStatus?.text = "Status: Active Proxy [${active.type.name}] ${active.server}:${active.port}"
            } else {
                tvStatus?.text = "Status: Proxy Disabled (Direct Connection)"
            }

            val prevAboveId = btnAutoFailoverToggle?.id ?: R.id.btn_proxy_disable
            if (proxies.isEmpty()) {
                if (btnAutoFailoverToggle != null) {
                    btnDisable?.nextFocusDownId = R.id.btn_auto_failover_toggle
                    btnAutoFailoverToggle.nextFocusDownId = R.id.btn_proxy_close
                } else {
                    btnDisable?.nextFocusDownId = R.id.btn_proxy_close
                }
                btnClose?.nextFocusUpId = prevAboveId
                val emptyTv =
                    TextView(context).apply {
                        text = "No proxy servers configured."
                        setTextColor(context.getColor(R.color.text_hint))
                        textSize = 12f
                        setPadding(0, 8, 0, 8)
                    }
                containerSaved?.addView(emptyTv)
                return
            }

            var prevToggleBtn: Button? = null
            var prevDelBtn: Button? = null

            for ((index, proxy) in proxies.withIndex()) {
                val row =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.bg_item_selector)
                        setPadding(12, 10, 12, 10)
                        val lp =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        lp.setMargins(0, 0, 0, 8)
                        layoutParams = lp
                    }

                val titleTv =
                    TextView(context).apply {
                        val statusText = if (proxy.isEnabled) " [ACTIVE]" else ""
                        text = "• [${proxy.type.name}] ${proxy.server}:${proxy.port}$statusText"
                        setTextColor(context.getColor(if (proxy.isEnabled) R.color.focus_highlight_gold else R.color.text_primary))
                        textSize = 13f
                    }
                row.addView(titleTv)

                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val pingMs = telegramManager.pingProxy(proxy, timeoutMs = 2000L)
                    val pingStr = if (pingMs != null) " [${pingMs}ms]" else " [Offline]"
                    val statusText = if (proxy.isEnabled) " [ACTIVE]" else ""
                    titleTv.text = "• [${proxy.type.name}] ${proxy.server}:${proxy.port}$statusText$pingStr"
                }

                val buttonLayout =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val lp =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                        lp.setMargins(0, 6, 0, 0)
                        layoutParams = lp
                    }

                val btnToggle =
                    Button(context).apply {
                        id = View.generateViewId()
                        text = if (proxy.isEnabled) "Disable" else "Enable"
                        textSize = 11f
                        setTextColor(context.getColor(R.color.text_primary))
                        setBackgroundResource(if (proxy.isEnabled) R.drawable.bg_item_selector else R.drawable.bg_button_selector)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        onFocusChangeListener = focusChangeListener
                        val lp = LinearLayout.LayoutParams(0, 36, 1f).apply { marginEnd = 6 }
                        layoutParams = lp
                        setOnClickListener {
                            if (proxy.isEnabled) {
                                telegramManager.disableProxy { _, _ ->
                                    refreshSavedProxiesList()
                                    onProxyChanged?.invoke()
                                }
                            } else {
                                telegramManager.enableProxy(proxy.id) { success, err ->
                                    if (success) {
                                        refreshSavedProxiesList()
                                        onProxyChanged?.invoke()
                                    } else {
                                        Toast.makeText(context, err ?: "Error enabling proxy", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }

                val btnDel =
                    Button(context).apply {
                        id = View.generateViewId()
                        text = "Delete"
                        textSize = 11f
                        setTextColor(context.getColor(R.color.text_error))
                        setBackgroundResource(R.drawable.bg_item_selector)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        onFocusChangeListener = focusChangeListener
                        val lp = LinearLayout.LayoutParams(0, 36, 1f)
                        layoutParams = lp
                        setOnClickListener {
                            telegramManager.deleteProxy(proxy.id) { _, _ ->
                                refreshSavedProxiesList()
                                onProxyChanged?.invoke()
                            }
                        }
                    }

                btnToggle.nextFocusRightId = btnDel.id
                btnDel.nextFocusLeftId = btnToggle.id

                if (index == 0) {
                    if (btnAutoFailoverToggle != null) {
                        btnDisable?.nextFocusDownId = R.id.btn_auto_failover_toggle
                        btnAutoFailoverToggle.nextFocusDownId = btnToggle.id
                    } else {
                        btnDisable?.nextFocusDownId = btnToggle.id
                    }
                    btnToggle.nextFocusUpId = prevAboveId
                } else {
                    prevToggleBtn?.nextFocusDownId = btnToggle.id
                    prevDelBtn?.nextFocusDownId = btnDel.id
                    btnToggle.nextFocusUpId = prevToggleBtn?.id ?: View.NO_ID
                    btnDel.nextFocusUpId = prevDelBtn?.id ?: View.NO_ID
                }

                prevToggleBtn = btnToggle
                prevDelBtn = btnDel

                buttonLayout.addView(btnToggle)
                buttonLayout.addView(btnDel)

                row.addView(buttonLayout)
                containerSaved?.addView(row)
            }

            prevToggleBtn?.nextFocusDownId = R.id.btn_proxy_close
            prevDelBtn?.nextFocusDownId = R.id.btn_proxy_close
            btnClose?.nextFocusUpId = prevToggleBtn?.id ?: prevAboveId
        }

        refreshSavedProxiesList()
        updateSoftkeys()

        btnSaveEnable?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }

            val server = etServer?.text?.toString()?.trim().orEmpty()
            val portStr = etPort?.text?.toString()?.trim().orEmpty()
            val port = portStr.toIntOrNull() ?: 1080

            if (server.isEmpty()) {
                Toast.makeText(context, "Please enter a valid server address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val username = etUsername?.text?.toString()?.trim().orEmpty()
            val password = etPassword?.text?.toString()?.trim().orEmpty()
            val secret = etSecret?.text?.toString()?.trim().orEmpty()

            telegramManager.addProxy(
                server = server,
                port = port,
                type = selectedType,
                username = username,
                password = password,
                secret = secret,
                enableNow = true,
            ) { success, errorMsg ->
                if (success) {
                    Toast.makeText(context, "Proxy enabled successfully!", Toast.LENGTH_SHORT).show()
                    etServer?.text?.clear()
                    etPort?.text?.clear()
                    etUsername?.text?.clear()
                    etPassword?.text?.clear()
                    etSecret?.text?.clear()
                    refreshSavedProxiesList()
                    onProxyChanged?.invoke()
                } else {
                    Toast.makeText(context, errorMsg ?: "Failed to set proxy", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDisable?.setOnClickListener {
            telegramManager.disableProxy { success, _ ->
                if (success) {
                    Toast.makeText(context, "Proxy disabled", Toast.LENGTH_SHORT).show()
                    refreshSavedProxiesList()
                    onProxyChanged?.invoke()
                }
            }
        }

        btnClose?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }
            dismiss()
        }
    }

    private fun updateSoftkeys() {
        val focused = currentFocus
        if (focused is EditText) {
            softkeyManager?.update(
                left = "Clear",
                center = "Save",
                right = "Close",
                onLeft = {
                    engines[focused.id]?.commitCurrentChar()
                    focused.setText("")
                },
                onCenter = {
                    engines.values.forEach { it.commitCurrentChar() }
                    findViewById<Button>(R.id.btn_proxy_save_enable)?.performClick()
                },
                onRight = {
                    engines.values.forEach { it.commitCurrentChar() }
                    dismiss()
                },
            )
        } else {
            softkeyManager?.update(
                left = "",
                center = "Select",
                right = "Close",
                onLeft = null,
                onCenter = {
                    engines.values.forEach { it.commitCurrentChar() }
                    val current = currentFocus
                    if (current is Button) {
                        current.performClick()
                    } else {
                        findViewById<Button>(R.id.btn_proxy_save_enable)?.performClick()
                    }
                },
                onRight = {
                    engines.values.forEach { it.commitCurrentChar() }
                    dismiss()
                },
            )
        }
    }

    override fun dismiss() {
        engines.values.forEach { it.commitCurrentChar() }
        super.dismiss()
    }

    override fun onBackPressed() {
        engines.values.forEach { it.commitCurrentChar() }
        dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (softkeyManager?.handleKeyEvent(keyCode, event) == true) {
            return true
        }

        if (isBackOrRightSoftKey(keyCode)) {
            engines.values.forEach { it.commitCurrentChar() }
            dismiss()
            return true
        }

        if (isDpadNavigationKey(keyCode)) {
            engines.values.forEach { it.commitCurrentChar() }
        }

        val focusedEdit = currentFocus as? EditText
        if (focusedEdit != null) {
            if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
                engines[focusedEdit.id]?.commitCurrentChar()
                focusedEdit.setText("")
                return true
            }
            val isInputKey =
                (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                    keyCode == KeyEvent.KEYCODE_STAR ||
                    keyCode == KeyEvent.KEYCODE_POUND ||
                    keyCode == KeyEvent.KEYCODE_DEL
            if (isInputKey && engines[focusedEdit.id]?.handleKeyEvent(keyCode, event) == true) {
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun isDpadNavigationKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
}
