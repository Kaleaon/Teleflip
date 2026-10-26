package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.storage.ChatExportOptions
import com.teleflip.app.storage.StorageLocationType
import com.teleflip.app.storage.StorageManager

class ChatExportDialog(
    context: Context,
    private val chatId: Long,
    private val chatTitle: String,
    private val onStartExport: (ChatExportOptions) -> Unit,
) : BaseDialog(context) {
    private var includeText = true
    private var includePhotos = true
    private var includeGifs = true
    private var includeVideos = true
    private var includeVoice = true
    private var includeDocuments = true
    private var storageType = StorageLocationType.INTERNAL

    private var btnText: Button? = null
    private var btnPhotos: Button? = null
    private var btnGifs: Button? = null
    private var btnVideos: Button? = null
    private var btnVoice: Button? = null
    private var btnDocuments: Button? = null
    private var btnStorage: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_chat_export)
        setupDismissKeys()

        val isSdAvailable = StorageManager.isSdCardAvailable(context)
        val defaultType = StorageManager.getSelectedStorageType(context)
        storageType =
            if (isSdAvailable && defaultType == StorageLocationType.SD_CARD) {
                StorageLocationType.SD_CARD
            } else if (isSdAvailable) {
                StorageLocationType.SD_CARD
            } else {
                StorageLocationType.INTERNAL
            }

        findViewById<TextView>(R.id.tv_export_chat_name)?.text = "Chat: $chatTitle"

        btnText = findViewById(R.id.btn_toggle_text)
        btnPhotos = findViewById(R.id.btn_toggle_photos)
        btnGifs = findViewById(R.id.btn_toggle_gifs)
        btnVideos = findViewById(R.id.btn_toggle_videos)
        btnVoice = findViewById(R.id.btn_toggle_voice)
        btnDocuments = findViewById(R.id.btn_toggle_documents)
        btnStorage = findViewById(R.id.btn_toggle_storage)

        updateButtonLabels()

        btnText?.setOnClickListener { toggleText() }
        btnPhotos?.setOnClickListener { togglePhotos() }
        btnGifs?.setOnClickListener { toggleGifs() }
        btnVideos?.setOnClickListener { toggleVideos() }
        btnVoice?.setOnClickListener { toggleVoice() }
        btnDocuments?.setOnClickListener { toggleDocuments() }
        btnStorage?.setOnClickListener { toggleStorage() }

        findViewById<Button>(R.id.btn_start_export)?.setOnClickListener {
            startExport()
        }

        findViewById<Button>(R.id.btn_cancel_export)?.setOnClickListener {
            dismiss()
        }

        val tvSoftkeyLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvSoftkeyCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvSoftkeyRight = findViewById<TextView>(R.id.tv_softkey_right)

        tvSoftkeyLeft?.text = "Start"
        tvSoftkeyCenter?.text = "Toggle"
        tvSoftkeyRight?.text = "Cancel"

        tvSoftkeyLeft?.setOnClickListener { startExport() }
        tvSoftkeyCenter?.setOnClickListener { currentFocus?.performClick() }
        tvSoftkeyRight?.setOnClickListener { dismiss() }

        btnText?.requestFocus()
        btnText?.post {
            btnText?.requestFocus()
        }
    }

    private fun toggleText() {
        includeText = !includeText
        updateButtonLabels()
    }

    private fun togglePhotos() {
        includePhotos = !includePhotos
        updateButtonLabels()
    }

    private fun toggleGifs() {
        includeGifs = !includeGifs
        updateButtonLabels()
    }

    private fun toggleVideos() {
        includeVideos = !includeVideos
        updateButtonLabels()
    }

    private fun toggleVoice() {
        includeVoice = !includeVoice
        updateButtonLabels()
    }

    private fun toggleDocuments() {
        includeDocuments = !includeDocuments
        updateButtonLabels()
    }

    private fun toggleStorage() {
        val isSdAvailable = StorageManager.isSdCardAvailable(context)
        storageType =
            if (isSdAvailable) {
                if (storageType == StorageLocationType.SD_CARD) {
                    StorageLocationType.INTERNAL
                } else {
                    StorageLocationType.SD_CARD
                }
            } else {
                StorageLocationType.INTERNAL
            }
        updateButtonLabels()
    }

    private fun updateButtonLabels() {
        btnText?.text = if (includeText) "[1] ☑ Text Messages (HTML, TXT)" else "[1] ☐ Text Messages (HTML, TXT)"
        btnPhotos?.text = if (includePhotos) "[2] ☑ Photos & Images" else "[2] ☐ Photos & Images"
        btnGifs?.text = if (includeGifs) "[3] ☑ GIFs & Animations" else "[3] ☐ GIFs & Animations"
        btnVideos?.text = if (includeVideos) "[4] ☑ Videos" else "[4] ☐ Videos"
        btnVoice?.text = if (includeVoice) "[5] ☑ Voice Notes & Audio" else "[5] ☐ Voice Notes & Audio"
        btnDocuments?.text = if (includeDocuments) "[6] ☑ Documents & Files" else "[6] ☐ Documents & Files"

        val isSdAvailable = StorageManager.isSdCardAvailable(context)
        btnStorage?.text =
            when {
                storageType == StorageLocationType.SD_CARD && isSdAvailable -> "[7] 💾 Destination: MicroSD Card"
                isSdAvailable -> "[7] 📱 Destination: Internal Storage"
                else -> "[7] 📱 Destination: Internal (No SD Card)"
            }
    }

    private fun startExport() {
        dismiss()
        val options =
            ChatExportOptions(
                includeText = includeText,
                includePhotos = includePhotos,
                includeGifs = includeGifs,
                includeVideos = includeVideos,
                includeVoice = includeVoice,
                includeDocuments = includeDocuments,
                targetStorageType = storageType,
            )
        onStartExport(options)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
            startExport()
            return true
        }
        if (SoftkeyUtils.isCenterKey(keyCode)) {
            val focused = currentFocus
            if (focused != null && focused.isEnabled) {
                focused.performClick()
                return true
            }
        }
        if (SoftkeyUtils.isSoftRightKey(keyCode)) {
            dismiss()
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_1 -> {
                toggleText()
                return true
            }
            KeyEvent.KEYCODE_2 -> {
                togglePhotos()
                return true
            }
            KeyEvent.KEYCODE_3 -> {
                toggleGifs()
                return true
            }
            KeyEvent.KEYCODE_4 -> {
                toggleVideos()
                return true
            }
            KeyEvent.KEYCODE_5 -> {
                toggleVoice()
                return true
            }
            KeyEvent.KEYCODE_6 -> {
                toggleDocuments()
                return true
            }
            KeyEvent.KEYCODE_7 -> {
                toggleStorage()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
