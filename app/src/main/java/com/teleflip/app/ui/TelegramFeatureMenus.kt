package com.teleflip.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.MessageModel
import com.teleflip.app.telegram.TelegramFeatures
import com.teleflip.app.telegram.TelegramManager
import org.drinkless.tdlib.TdApi
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class TelegramFeatureMenus(
    private val activity: AppCompatActivity,
    private val manager: TelegramManager,
    private val notify: (String, Boolean) -> Unit,
    private val openChat: (ChatModel) -> Unit,
    private val openMessage: (ChatModel, Long) -> Unit,
    private val shareLocation: (Long) -> Unit,
) {
    private val api get() = manager.features

    private fun <T> display(
        result: Result<T>,
        action: (T) -> Unit,
    ) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            result.fold(onSuccess = action, onFailure = { notify(it.message ?: "Telegram operation failed", true) })
        }
    }

    private fun menu(
        title: String,
        options: List<MenuOption>,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (options.isEmpty()) notify("No items available", false) else OptionsMenuDialog(activity, title, options).show()
    }

    private fun input(
        title: String,
        initial: String = "",
        secret: Boolean = false,
        numeric: Boolean = false,
        action: (String) -> Unit,
    ) {
        KeypadTextDialog(activity, title, initial, secret, manager.keypadTimeoutMs, numeric = numeric, onSubmit = action).show()
    }

    private fun confirm(
        title: String,
        description: String,
        action: () -> Unit,
    ) {
        menu("$title\n$description", listOf(MenuOption(1, "Cancel") {}, MenuOption(2, "Confirm", action)))
    }

    private fun copy(value: String) {
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Telegram", value))
        notify("Copied", false)
    }

    private fun browse(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("https", "http", "tg")) {
            notify("Unsupported link type", true)
            return
        }
        if (uri.host in setOf("t.me", "telegram.me", "www.t.me", "www.telegram.me")) {
            val path = uri.pathSegments
            if (path.firstOrNull()?.startsWith("+") == true || path.firstOrNull() == "joinchat") {
                inviteLink(url)
                return
            }
            if (path.size >= 2 && (path.last().toLongOrNull() != null)) {
                api.linkedMessage(url) {
                    display(it) { info ->
                        val message = info.message
                        if (message == null) notify("The linked message is unavailable", true) else api.chat(info.chatId) { display(it) { chat -> openMessage(manager.featureChat(chat), message.id) } }
                    }
                }
                return
            }
            if (path.size == 1 && path[0].matches(Regex("[A-Za-z0-9_]+"))) {
                manager.resolveAndCreateChat("@${path[0]}") { chat, error -> if (chat != null) openChat(chat) else notify(error ?: "Could not open Telegram chat", true) }
                return
            }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: android.content.ActivityNotFoundException) {
            notify("No application can open this link", true)
        }
    }

    fun scheduled(chat: ChatModel) {
        api.scheduled(chat.id) { result ->
            display(result) { messages ->
                if (messages.isEmpty()) {
                    notify("No scheduled messages", false)
                    return@display
                }
                menu(
                    "Scheduled messages",
                    messages.mapIndexed { index, message ->
                        val whenText =
                            (message.schedulingState as? TdApi.MessageSchedulingStateSendAtDate)?.sendDate?.let {
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.toLong() * 1000))
                            } ?: "When online"
                        val text = (message.content as? TdApi.MessageText)?.text?.text ?: "Media message"
                        MenuOption(index + 1, "$whenText • ${text.take(60)}") { scheduledActions(chat, message) }
                    },
                )
            }
        }
    }

    private fun scheduledActions(
        chat: ChatModel,
        message: TdApi.Message,
    ) {
        menu(
            "Scheduled message",
            listOf(
                MenuOption(1, "Send now") {
                    confirm("Send now?", "This message will be sent immediately") {
                        api.reschedule(chat.id, message.id, null) {
                            display(it) {
                                notify("Message released for sending", false)
                                scheduled(chat)
                            }
                        }
                    }
                },
                MenuOption(2, "Change time") {
                    input("Send in how many minutes?", "60", numeric = true) { minutes ->
                        val delay = minutes.trim().toLongOrNull()
                        val date = System.currentTimeMillis() / 1000 + (delay ?: 0) * 60
                        if (delay == null || delay !in 1..525600 || date > Int.MAX_VALUE) {
                            notify("Enter 1–525600 minutes", true)
                        } else {
                            api.reschedule(chat.id, message.id, TdApi.MessageSchedulingStateSendAtDate(date.toInt(), 0)) { display(it) { scheduled(chat) } }
                        }
                    }
                },
                MenuOption(3, "Delete scheduled message") {
                    confirm("Delete scheduled message?", "It will not be sent") {
                        manager.deleteSelectedMessages(chat.id, longArrayOf(message.id), false) { display(it) { scheduled(chat) } }
                    }
                },
            ),
        )
    }

    fun contactPicker(chatId: Long) {
        manager.loadContacts { contacts ->
            activity.runOnUiThread {
                menu(
                    "Share a contact",
                    contacts.filter { it.phoneNumber.isNotBlank() }.mapIndexed { i, contact ->
                        MenuOption(i + 1, "${contact.firstName} ${contact.lastName}") {
                            confirm("Share this contact?", "${contact.firstName} ${contact.lastName}\n${contact.phoneNumber}") {
                                api.sendContact(
                                    chatId,
                                    TdApi.Contact(contact.phoneNumber, contact.firstName, contact.lastName, "", contact.userId),
                                ) { display(it) { notify("Contact queued for sending", false) } }
                            }
                        }
                    },
                )
            }
        }
    }

    fun contact(message: MessageModel) {
        api.message(message.chatId, message.id) { result ->
            display(result) { actual ->
                val contact = (actual.content as? TdApi.MessageContact)?.contact ?: return@display
                menu(
                    "${contact.firstName} ${contact.lastName}\n${contact.phoneNumber}",
                    listOf(
                        MenuOption(1, "Copy phone number") { copy(contact.phoneNumber) },
                        MenuOption(2, "Add to Telegram contacts") {
                            confirm("Add contact?", "${contact.firstName} ${contact.lastName}") {
                                manager.addContact(
                                    contact.phoneNumber,
                                    contact.firstName,
                                    contact.lastName,
                                ) { success, error -> notify(if (success) "Contact added" else error ?: "Could not add contact", !success) }
                            }
                        },
                        MenuOption(3, "Open Telegram chat") {
                            if (contact.userId != 0L) {
                                api.privateChat(contact.userId) { display(it) { chat -> openChat(manager.featureChat(chat)) } }
                            } else {
                                manager.resolveAndCreateChat(contact.phoneNumber) { chat, error -> if (chat != null) openChat(chat) else notify(error ?: "Could not open chat", true) }
                            }
                        },
                    ),
                )
            }
        }
    }

    fun invite() {
        input("Open a Telegram link") { link -> browse(link.trim()) }
    }

    private fun inviteLink(link: String) {
        api.checkInvite(link) { result ->
            display(result) { info ->
                if (info.subscriptionInfo != null) {
                    notify("This invite requires a subscription checkout", true)
                    return@display
                }
                confirm(if (info.createsJoinRequest) "Request to join?" else "Join chat?", "${info.title}\n${info.memberCount} members\n${info.description.orEmpty().take(120)}") {
                    api.joinInvite(link) { joined ->
                        if (joined.exceptionOrNull()?.message?.contains("INVITE_REQUEST_SENT") == true) {
                            activity.runOnUiThread { notify("Join request sent", false) }
                        } else {
                            display(joined) { openChat(manager.featureChat(it)) }
                        }
                    }
                }
            }
        }
    }

    fun leave(chat: ChatModel) {
        confirm("Leave ${chat.title}?", "You will stop receiving new messages from this chat") {
            api.leave(chat.id) {
                display(it) {
                    notify("Left chat", false)
                    manager.loadChats()
                }
            }
        }
    }

    fun unread(chat: ChatModel) {
        manager.markChatUnread(chat.id, !chat.isMarkedUnread) { display(it) { notify(if (chat.isMarkedUnread) "Unread mark cleared" else "Marked unread", false) } }
    }

    fun commands(chat: ChatModel) {
        api.botCommands(chat.id) {
            display(it) { commands ->
                if (commands.isEmpty()) {
                    notify("This chat has no published bot commands", false)
                } else {
                    menu("Bot commands", commands.mapIndexed { i, cmd -> MenuOption(i + 1, "${cmd.text} • ${cmd.description}") { manager.sendMessage(chat.id, cmd.text) } })
                }
            }
        }
    }

    fun buttons(message: MessageModel) {
        api.buttons(message.chatId, message.id) {
            display(it) { buttons ->
                menu(
                    "Bot buttons",
                    buttons.mapIndexed { i, button ->
                        MenuOption(i + 1, button.label) {
                            when (val type = button.inlineType) {
                                is TdApi.InlineKeyboardButtonTypeCallback ->
                                    api.callback(message.chatId, message.id, type.data) {
                                        display(it) { answer ->
                                            if (!answer.text.isNullOrBlank()) menu("Bot response", listOf(MenuOption(1, answer.text) {}))
                                            if (!answer.url.isNullOrBlank()) confirm("Open bot link?", answer.url) { browse(answer.url) }
                                        }
                                    }
                                is TdApi.InlineKeyboardButtonTypeCallbackWithPassword ->
                                    input("Two-step password for this bot action", secret = true) { password ->
                                        api.passwordCallback(message.chatId, message.id, password, type.data) {
                                            display(it) { answer ->
                                                if (!answer.text.isNullOrBlank()) menu("Bot response", listOf(MenuOption(1, answer.text) {}))
                                                if (!answer.url.isNullOrBlank()) confirm("Open bot link?", answer.url) { browse(answer.url) }
                                            }
                                        }
                                    }
                                is TdApi.InlineKeyboardButtonTypeUser -> api.privateChat(type.userId) { display(it) { chat -> openChat(manager.featureChat(chat)) } }
                                is TdApi.InlineKeyboardButtonTypeUrl -> confirm("Open link?", type.url) { browse(type.url) }
                                is TdApi.InlineKeyboardButtonTypeCopyText -> copy(type.text)
                                else ->
                                    when (button.keyboardType) {
                                        is TdApi.KeyboardButtonTypeText -> manager.sendMessage(message.chatId, button.label)
                                        is TdApi.KeyboardButtonTypeRequestPhoneNumber ->
                                            confirm("Share your phone number?", "The bot will receive your Telegram contact card") {
                                                manager.loadCurrentUser { profile ->
                                                    if (profile == null) {
                                                        notify(
                                                            "Could not load your contact",
                                                            true,
                                                        )
                                                    } else {
                                                        api.sendContact(message.chatId, TdApi.Contact(profile.phoneNumber, profile.firstName, profile.lastName, "", profile.userId)) {
                                                            display(it) { notify("Contact queued for sending", false) }
                                                        }
                                                    }
                                                }
                                            }
                                        is TdApi.KeyboardButtonTypeRequestLocation ->
                                            confirm(
                                                "Share your current location?",
                                                "The bot will receive your GPS coordinates",
                                            ) { shareLocation(message.chatId) }
                                        else -> notify("This button requires an interaction not yet supported by TeleFlip", true)
                                    }
                            }
                        }
                    },
                )
            }
        }
    }

    fun caption(message: MessageModel) {
        api.properties(message.chatId, message.id) {
            display(it) { properties ->
                if (!properties.canBeEdited) {
                    notify("This caption can no longer be edited", true)
                    return@display
                }
                input("Edit caption", message.mediaCaption) { text -> api.editCaption(message.chatId, message.id, text) { display(it) { notify("Caption updated", false) } } }
            }
        }
    }

    fun link(message: MessageModel) {
        api.messageLink(message.chatId, message.id) { display(it) { value -> copy(value) } }
    }

    fun translate(message: MessageModel) {
        input("Translate to language code (en, es, fr…)", Locale.getDefault().language) { language ->
            api.translate(message.chatId, message.id, language.trim()) {
                display(it) { text ->
                    input("Translation • LSK copies text", text.text) { copy(it) }
                }
            }
        }
    }

    fun links(message: MessageModel) {
        val urls = message.formatting.orEmpty().filter { it.kind == "url" }.map { it.url }.distinct()
        menu("Message links", urls.mapIndexed { i, url -> MenuOption(i + 1, url) { confirm("Open link?", url) { browse(url) } } })
    }

    fun reveal(message: MessageModel) {
        input("Message with spoilers revealed", message.text) { copy(it) }
    }

    fun save(message: MessageModel) {
        manager.openSavedMessages { chat, error ->
            if (chat == null) {
                notify(
                    error ?: "Could not open Saved Messages",
                    true,
                )
            } else {
                manager.forwardMessage(
                    message.chatId,
                    chat.id,
                    message.id,
                ) { success -> notify(if (success) "Forwarding to Saved Messages" else "This message could not be forwarded", !success) }
            }
        }
    }

    fun delete(message: MessageModel) {
        api.properties(message.chatId, message.id) {
            display(it) { props ->
                val options = mutableListOf<MenuOption>()
                if (props.canBeDeletedOnlyForSelf) options.add(MenuOption(1, "Delete for me") { deleteConfirmed(message, false) })
                if (props.canBeDeletedForAllUsers) options.add(MenuOption(2, "Delete for everyone") { deleteConfirmed(message, true) })
                menu("Delete message", options)
            }
        }
    }

    private fun deleteConfirmed(
        message: MessageModel,
        everyone: Boolean,
    ) {
        confirm("Delete message?", if (everyone) "For everyone in this chat" else "Only from your history") {
            manager.deleteSelectedMessages(message.chatId, longArrayOf(message.id), everyone) { display(it) { notify("Message deleted", false) } }
        }
    }

    fun formatting(
        chatId: Long,
        text: String,
        replyToId: Long,
        accepted: () -> Unit,
    ) {
        val types =
            listOf(
                "Bold" to TdApi.TextEntityTypeBold(),
                "Italic" to TdApi.TextEntityTypeItalic(),
                "Monospace" to TdApi.TextEntityTypeCode(),
                "Spoiler" to TdApi.TextEntityTypeSpoiler(),
                "Underline" to TdApi.TextEntityTypeUnderline(),
                "Strikethrough" to TdApi.TextEntityTypeStrikethrough(),
            )
        menu(
            "Send formatted draft",
            types.mapIndexed { i, (name, type) ->
                MenuOption(i + 1, name) {
                    api.sendFormatted(chatId, text, type, replyToId) {
                        display(it) {
                            accepted()
                            notify("Message queued for sending", false)
                        }
                    }
                }
            },
        )
    }

    private fun messageText(message: TdApi.Message): String =
        when (val content = message.content) {
            is TdApi.MessageText -> content.text.text
            is TdApi.MessagePhoto -> content.caption.text.ifBlank { "Photo" }
            is TdApi.MessageVideo -> content.caption.text.ifBlank { "Video" }
            else -> "Media message"
        }

    fun replies(message: MessageModel) {
        api.thread(message.chatId, message.id) { display(it) { info -> threadPage(info, 0) } }
    }

    private fun threadPage(
        info: TdApi.MessageThreadInfo,
        fromId: Long,
    ) {
        api.threadHistory(info, fromId) {
            display(it) { messages ->
                val options = mutableListOf(MenuOption(1, "Write a reply") { threadReply(info, info.messageThreadId) })
                messages.sortedBy { it.id }.forEach { message ->
                    options.add(
                        MenuOption(options.size + 1, messageText(message).take(80)) {
                            menu(
                                messageText(message),
                                listOf(
                                    MenuOption(1, "Reply to this message") { threadReply(info, message.id) },
                                    MenuOption(2, "Open message in chat") { api.chat(info.chatId) { display(it) { chat -> openMessage(manager.featureChat(chat), message.id) } } },
                                ),
                            )
                        },
                    )
                }
                val oldest = messages.minOfOrNull { it.id }
                if (messages.size >= 49 && oldest != null && oldest != fromId) options.add(MenuOption(options.size + 1, "Earlier replies") { threadPage(info, oldest) })
                menu("Replies / comments", options)
            }
        }
    }

    private fun threadReply(
        info: TdApi.MessageThreadInfo,
        replyId: Long,
    ) {
        input("Reply to discussion") { text ->
            api.replyToThread(info, replyId, text) {
                display(it) {
                    notify("Reply queued for sending", false)
                    threadPage(info, 0)
                }
            }
        }
    }

    fun selectMessages(chat: ChatModel) {
        val messages =
            manager.messages.value[chat.id].orEmpty().filter {
                it.status == com.teleflip.app.model.MessageStatus.READ || it.status == com.teleflip.app.model.MessageStatus.SENT
            }.takeLast(100).reversed()
        if (messages.isEmpty()) {
            notify("Load messages before selecting", false)
            return
        }
        val selected = linkedSetOf<Long>()

        fun picker(page: Int) {
            lateinit var dialog: OptionsMenuDialog
            val options = mutableListOf<MenuOption>()
            options.add(
                MenuOption(1, "Forward selected (${selected.size})") {
                    if (selected.isEmpty()) {
                        notify("Select some messages first", true)
                    } else {
                        menu(
                            "Forward to chat",
                            manager.chats.value.filterNot { it.isSecretChat }.mapIndexed { i, destination ->
                                MenuOption(i + 1, destination.title) {
                                    confirm("Forward ${selected.size} messages?", destination.title) {
                                        api.forward(
                                            chat.id,
                                            destination.id,
                                            selected.toLongArray(),
                                        ) { display(it) { accepted -> notify("Forwarding ${accepted.messages.filterNotNull().size} messages", false) } }
                                    }
                                }
                            },
                        )
                    }
                },
            )
            options.add(
                MenuOption(2, "Delete selected (${selected.size})") {
                    if (selected.isEmpty()) {
                        notify("Select some messages first", true)
                    } else {
                        menu(
                            "Delete selected",
                            listOf(
                                MenuOption(1, "For me") {
                                    confirm("Delete ${selected.size} messages?", "Only from your history") {
                                        manager.deleteSelectedMessages(chat.id, selected.toLongArray(), false) { display(it) { notify("Messages deleted", false) } }
                                    }
                                },
                                MenuOption(2, "For everyone") {
                                    confirm("Delete ${selected.size} messages?", "For everyone in the chat, if permitted") {
                                        manager.deleteSelectedMessages(chat.id, selected.toLongArray(), true) { display(it) { notify("Messages deleted", false) } }
                                    }
                                },
                            ),
                        )
                    }
                },
            )
            messages.forEach { message ->
                options.add(
                    MenuOption(options.size + 1, "${if (message.id in selected) "✓" else "○"} ${message.text.take(70)}") {
                        if (!selected.add(message.id)) selected.remove(message.id)
                        picker(dialog.currentPage)
                    },
                )
            }
            dialog = OptionsMenuDialog(activity, "Select loaded messages • ${selected.size} selected", options, page)
            dialog.show()
        }
        picker(0)
    }

    fun privacy() {
        val settings =
            listOf(
                "Last seen / online" to TdApi.UserPrivacySettingShowStatus(),
                "Phone number" to TdApi.UserPrivacySettingShowPhoneNumber(),
                "Profile photo" to TdApi.UserPrivacySettingShowProfilePhoto(),
                "Who can call" to TdApi.UserPrivacySettingAllowCalls(),
                "Peer-to-peer calls" to TdApi.UserPrivacySettingAllowPeerToPeerCalls(),
                "Group invitations" to TdApi.UserPrivacySettingAllowChatInvites(),
                "Forwarded-message link" to TdApi.UserPrivacySettingShowLinkInForwardedMessages(),
            )
        menu(
            "Privacy",
            settings.mapIndexed { i, (name, setting) ->
                MenuOption(i + 1, name) {
                    api.privacy(setting) {
                        display(it) { previous ->
                            menu(
                                "$name • keep existing exceptions",
                                TelegramFeatures.Audience.entries.mapIndexed { index, audience ->
                                    MenuOption(index + 1, audience.name.lowercase().replaceFirstChar { it.uppercase() }) {
                                        api.setPrivacy(setting, previous, audience) { display(it) { notify("Privacy updated", false) } }
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    fun twoStep() {
        api.passwordState {
            display(it) { state ->
                menu(
                    "Two-step verification: ${if (state.hasPassword) "On" else "Off"}",
                    listOf(
                        MenuOption(1, if (state.hasPassword) "Change password" else "Set password") {
                            passwordOld(state.hasPassword) { old ->
                                input("New two-step password", secret = true) { password ->
                                    if (password.isBlank()) {
                                        notify("Password cannot be blank", true)
                                    } else {
                                        input("Repeat new password", secret = true) { repeated ->
                                            if (password != repeated) {
                                                notify("Passwords do not match", true)
                                            } else {
                                                input("Password hint (optional)") { hint ->
                                                    if (hint == password) {
                                                        notify(
                                                            "The hint must differ from your password",
                                                            true,
                                                        )
                                                    } else {
                                                        api.setPassword(old, password, hint) { display(it) { notify("Two-step password updated", false) } }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        MenuOption(3, "Recovery email") {
                            passwordOld(state.hasPassword) { password ->
                                input("Recovery email address") { email ->
                                    if (email.isBlank()) {
                                        notify("Enter a recovery email address", true)
                                    } else {
                                        api.recoveryEmail(password, email.trim()) {
                                            display(it) { updated ->
                                                if (updated.recoveryEmailAddressCodeInfo != null) {
                                                    input(
                                                        "Email verification code",
                                                        numeric = true,
                                                    ) { code -> api.verifyRecoveryEmail(code.trim()) { display(it) { notify("Recovery email verified", false) } } }
                                                } else {
                                                    notify("Recovery email updated", false)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        MenuOption(2, "Disable two-step verification") {
                            if (!state.hasPassword) {
                                notify("Two-step verification is already off", false)
                            } else {
                                confirm("Disable two-step verification?", "Your account will lose this additional password protection") {
                                    passwordOld(true) { old -> api.setPassword(old, "", "") { display(it) { notify("Two-step verification disabled", false) } } }
                                }
                            }
                        },
                    ),
                )
            }
        }
    }

    private fun passwordOld(
        required: Boolean,
        next: (String) -> Unit,
    ) {
        if (required) input("Current two-step password", secret = true, action = next) else next("")
    }
}
