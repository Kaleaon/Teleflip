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
import com.teleflip.app.input.KeypadInputEngine
import com.teleflip.app.model.ProxyType
import com.teleflip.app.telegram.TelegramManager

@Suppress("MagicNumber", "LongMethod", "ComplexMethod")
class ProxySettingsDialog(
    context: Context,
    private val telegramManager: TelegramManager,
    private val timeoutMs: Long = 500L,
    private val onProxyChanged: (() -> Unit)? = null,
) : BaseDialog(context) {
    private val engines = mutableMapOf<Int, KeypadInputEngine>()
    private var selectedType = ProxyType.SOCKS5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_proxy_settings)
        setupDismissKeys()

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
        val btnClose = findViewById<Button>(R.id.btn_proxy_close)
        val containerSaved = findViewById<LinearLayout>(R.id.layout_saved_proxies_container)

        val editTexts = listOfNotNull(etServer, etPort, etUsername, etPassword, etSecret)
        editTexts.forEach { field ->
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

        fun updateTypeSelection(type: ProxyType) {
            selectedType = type
            if (type == ProxyType.SOCKS5) {
                btnSocks5?.setBackgroundResource(R.drawable.bg_button_selector)
                btnMtproto?.setBackgroundResource(R.drawable.bg_item_selector)
                layoutSocks5?.visibility = View.VISIBLE
                layoutMtproto?.visibility = View.GONE
            } else {
                btnSocks5?.setBackgroundResource(R.drawable.bg_item_selector)
                btnMtproto?.setBackgroundResource(R.drawable.bg_button_selector)
                layoutSocks5?.visibility = View.GONE
                layoutMtproto?.visibility = View.VISIBLE
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

            if (proxies.isEmpty()) {
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

            for (proxy in proxies) {
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
                        text = if (proxy.isEnabled) "Disable" else "Enable"
                        textSize = 11f
                        setTextColor(context.getColor(R.color.text_primary))
                        setBackgroundResource(if (proxy.isEnabled) R.drawable.bg_item_selector else R.drawable.bg_button_selector)
                        isFocusable = true
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
                buttonLayout.addView(btnToggle)

                val btnDel =
                    Button(context).apply {
                        text = "Delete"
                        textSize = 11f
                        setTextColor(context.getColor(R.color.text_error))
                        setBackgroundResource(R.drawable.bg_item_selector)
                        isFocusable = true
                        val lp = LinearLayout.LayoutParams(0, 36, 1f)
                        layoutParams = lp
                        setOnClickListener {
                            telegramManager.deleteProxy(proxy.id) { _, _ ->
                                refreshSavedProxiesList()
                                onProxyChanged?.invoke()
                            }
                        }
                    }
                buttonLayout.addView(btnDel)

                row.addView(buttonLayout)
                containerSaved?.addView(row)
            }
        }

        refreshSavedProxiesList()

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

        btnClose?.setOnClickListener { dismiss() }

        setupSoftkeys(
            left = "",
            center = "Save",
            right = "Close",
            onCenter = { btnSaveEnable?.performClick() },
            onRight = { dismiss() },
        )
    }

    private fun setupDismissKeys() {
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && isBackOrRightSoftKey(keyCode)) {
                dismiss()
                true
            } else {
                false
            }
        }
    }
}
