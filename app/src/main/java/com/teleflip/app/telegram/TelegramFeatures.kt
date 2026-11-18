package com.teleflip.app.telegram

import org.drinkless.tdlib.TdApi

/** Server-backed actions; callers choose how results are presented on the keypad UI. */
class TelegramFeatures(private val send: (TdApi.Function<*>, (TdApi.Object) -> Unit) -> Unit) {
    private inline fun <reified T : TdApi.Object> query(
        request: TdApi.Function<*>,
        crossinline done: (Result<T>) -> Unit,
    ) {
        send(request) { result ->
            done(
                when (result) {
                    is TdApi.Error -> Result.failure(IllegalStateException(result.message))
                    is T -> Result.success(result)
                    else -> Result.failure(IllegalStateException("Telegram returned an unexpected response"))
                },
            )
        }
    }

    fun scheduled(
        chatId: Long,
        done: (Result<List<TdApi.Message>>) -> Unit,
    ) {
        query<TdApi.Messages>(TdApi.GetChatScheduledMessages(chatId)) {
            done(
                it.map {
                        messages ->
                    messages.messages.filterNotNull().sortedBy { m -> (m.schedulingState as? TdApi.MessageSchedulingStateSendAtDate)?.sendDate ?: Int.MAX_VALUE }
                },
            )
        }
    }

    fun reschedule(
        chatId: Long,
        messageId: Long,
        state: TdApi.MessageSchedulingState?,
        done: (Result<TdApi.Ok>) -> Unit,
    ) {
        if (state is TdApi.MessageSchedulingStateSendAtDate && state.sendDate.toLong() <= System.currentTimeMillis() / 1000) {
            done(Result.failure(IllegalArgumentException("Choose a future send time")))
            return
        }
        query(TdApi.EditMessageSchedulingState(chatId, messageId, state), done)
    }

    fun delete(
        chatId: Long,
        ids: LongArray,
        forEveryone: Boolean,
        done: (Result<TdApi.Ok>) -> Unit,
    ) {
        if (ids.isEmpty()) {
            done(Result.failure(IllegalArgumentException("No messages selected")))
            return
        }
        query(TdApi.DeleteMessages(chatId, ids.distinct().toLongArray(), forEveryone), done)
    }

    fun properties(
        chatId: Long,
        messageId: Long,
        done: (Result<TdApi.MessageProperties>) -> Unit,
    ) = query(TdApi.GetMessageProperties(chatId, messageId), done)

    fun message(
        chatId: Long,
        messageId: Long,
        done: (Result<TdApi.Message>) -> Unit,
    ) = query(TdApi.GetMessage(chatId, messageId), done)

    fun editCaption(
        chatId: Long,
        messageId: Long,
        text: String,
        done: (Result<TdApi.Message>) -> Unit,
    ) = query(
        TdApi.EditMessageCaption(chatId, messageId, null, TdApi.FormattedText(text, emptyArray()), false),
        done,
    )

    fun messageLink(
        chatId: Long,
        messageId: Long,
        done: (Result<String>) -> Unit,
    ) {
        query<TdApi.MessageLink>(TdApi.GetMessageLink(chatId, messageId, 0, 0, "", false, false)) { done(it.map { link -> link.link }) }
    }

    fun translate(
        chatId: Long,
        messageId: Long,
        language: String,
        done: (Result<TdApi.FormattedText>) -> Unit,
    ) {
        if (!language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?"))) {
            done(Result.failure(IllegalArgumentException("Use a language code such as en, es or fr")))
            return
        }
        query(TdApi.TranslateMessageText(chatId, messageId, language, ""), done)
    }

    fun sendContact(
        chatId: Long,
        contact: TdApi.Contact,
        done: (Result<TdApi.Message>) -> Unit,
    ) {
        if (contact.phoneNumber.isNullOrBlank()) {
            done(Result.failure(IllegalArgumentException("This contact has no shared phone number")))
            return
        }
        query(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                inputMessageContent = TdApi.InputMessageContact(contact)
            },
            done,
        )
    }

    fun sendFormatted(
        chatId: Long,
        text: String,
        type: TdApi.TextEntityType,
        replyToId: Long,
        done: (Result<TdApi.Message>) -> Unit,
    ) {
        if (text.isBlank()) {
            done(Result.failure(IllegalArgumentException("Write a message first")))
            return
        }
        query(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                inputMessageContent = TdApi.InputMessageText(TdApi.FormattedText(text, arrayOf(TdApi.TextEntity(0, text.length, type))), null, true)
                if (replyToId != 0L) replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyToId }
            },
            done,
        )
    }

    fun checkInvite(
        link: String,
        done: (Result<TdApi.ChatInviteLinkInfo>) -> Unit,
    ) = query(TdApi.CheckChatInviteLink(link.trim()), done)

    fun joinInvite(
        link: String,
        done: (Result<TdApi.Chat>) -> Unit,
    ) = query(TdApi.JoinChatByInviteLink(link.trim()), done)

    fun leave(
        chatId: Long,
        done: (Result<TdApi.Ok>) -> Unit,
    ) = query(TdApi.LeaveChat(chatId), done)

    fun markUnread(
        chatId: Long,
        unread: Boolean,
        done: (Result<TdApi.Ok>) -> Unit,
    ) = query(TdApi.ToggleChatIsMarkedAsUnread(chatId, unread), done)

    data class Command(val text: String, val description: String)

    fun botCommands(
        chatId: Long,
        done: (Result<List<Command>>) -> Unit,
    ) {
        query<TdApi.Chat>(TdApi.GetChat(chatId)) { chat ->
            chat.fold(onFailure = { done(Result.failure(it)) }, onSuccess = { value ->
                when (val type = value.type) {
                    is TdApi.ChatTypePrivate ->
                        query<TdApi.UserFullInfo>(TdApi.GetUserFullInfo(type.userId)) {
                            done(
                                it.map {
                                        info ->
                                    info.botInfo?.commands.orEmpty().map { cmd -> Command("/${cmd.command}", cmd.description) }
                                },
                            )
                        }
                    is TdApi.ChatTypeSupergroup ->
                        query<TdApi.SupergroupFullInfo>(
                            TdApi.GetSupergroupFullInfo(type.supergroupId),
                        ) { result -> groupCommands(result.map { it.botCommands.orEmpty().toList() }, done) }
                    is TdApi.ChatTypeBasicGroup ->
                        query<TdApi.BasicGroupFullInfo>(
                            TdApi.GetBasicGroupFullInfo(type.basicGroupId),
                        ) { result -> groupCommands(result.map { it.botCommands.orEmpty().toList() }, done) }
                    else -> done(Result.success(emptyList()))
                }
            })
        }
    }

    private fun groupCommands(
        result: Result<List<TdApi.BotCommands>>,
        done: (Result<List<Command>>) -> Unit,
    ) {
        result.fold(onFailure = { done(Result.failure(it)) }, onSuccess = { groups ->
            if (groups.isEmpty()) {
                done(Result.success(emptyList()))
                return@fold
            }
            val output = java.util.Collections.synchronizedList(mutableListOf<Command>())
            val remaining = java.util.concurrent.atomic.AtomicInteger(groups.size)
            val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            groups.forEach { group ->
                query<TdApi.User>(TdApi.GetUser(group.botUserId)) { user ->
                    user.fold(onFailure = { error.compareAndSet(null, it) }, onSuccess = { bot ->
                        val username = bot.usernames?.activeUsernames?.firstOrNull()
                        if (!username.isNullOrBlank()) output.addAll(group.commands.orEmpty().map { Command("/${it.command}@$username", it.description) })
                    })
                    if (remaining.decrementAndGet() == 0) {
                        val failure = error.get()
                        if (failure != null) done(Result.failure(failure)) else done(Result.success(output.sortedBy { it.text }))
                    }
                }
            }
        })
    }

    data class BotButton(val label: String, val inlineType: TdApi.InlineKeyboardButtonType? = null, val keyboardType: TdApi.KeyboardButtonType? = null)

    fun buttons(
        chatId: Long,
        messageId: Long,
        done: (Result<List<BotButton>>) -> Unit,
    ) {
        message(chatId, messageId) { result ->
            done(
                result.map { msg ->
                    when (val markup = msg.replyMarkup) {
                        is TdApi.ReplyMarkupInlineKeyboard -> markup.rows.orEmpty().flatMap { it.orEmpty().map { b -> BotButton(b.text, inlineType = b.type) } }
                        is TdApi.ReplyMarkupShowKeyboard -> markup.rows.orEmpty().flatMap { it.orEmpty().map { b -> BotButton(b.text, keyboardType = b.type) } }
                        else -> emptyList()
                    }
                },
            )
        }
    }

    fun callback(
        chatId: Long,
        messageId: Long,
        data: ByteArray,
        done: (Result<TdApi.CallbackQueryAnswer>) -> Unit,
    ) = query(
        TdApi.GetCallbackQueryAnswer(chatId, messageId, TdApi.CallbackQueryPayloadData(data)),
        done,
    )

    fun privacy(
        setting: TdApi.UserPrivacySetting,
        done: (Result<TdApi.UserPrivacySettingRules>) -> Unit,
    ) = query(TdApi.GetUserPrivacySettingRules(setting), done)

    enum class Audience { EVERYONE, CONTACTS, NOBODY }

    fun setPrivacy(
        setting: TdApi.UserPrivacySetting,
        previous: TdApi.UserPrivacySettingRules,
        audience: Audience,
        done: (Result<TdApi.Ok>) -> Unit,
    ) {
        // Keep explicit user/chat exceptions in their existing priority order.
        val exceptions =
            previous.rules.orEmpty().filterNot {
                it is TdApi.UserPrivacySettingRuleAllowAll || it is TdApi.UserPrivacySettingRuleAllowContacts || it is TdApi.UserPrivacySettingRuleRestrictAll || it is TdApi.UserPrivacySettingRuleRestrictContacts
            }
        val base =
            when (audience) {
                Audience.EVERYONE -> listOf(TdApi.UserPrivacySettingRuleAllowAll())
                Audience.CONTACTS -> listOf(TdApi.UserPrivacySettingRuleAllowContacts(), TdApi.UserPrivacySettingRuleRestrictAll())
                Audience.NOBODY -> listOf(TdApi.UserPrivacySettingRuleRestrictAll())
            }
        query(TdApi.SetUserPrivacySettingRules(setting, TdApi.UserPrivacySettingRules((exceptions + base).toTypedArray())), done)
    }

    fun privateChat(
        userId: Long,
        done: (Result<TdApi.Chat>) -> Unit,
    ) = query(TdApi.CreatePrivateChat(userId, false), done)

    fun chat(
        chatId: Long,
        done: (Result<TdApi.Chat>) -> Unit,
    ) = query(TdApi.GetChat(chatId), done)

    fun linkedMessage(
        url: String,
        done: (Result<TdApi.MessageLinkInfo>) -> Unit,
    ) = query(TdApi.GetMessageLinkInfo(url), done)

    fun thread(
        chatId: Long,
        messageId: Long,
        done: (Result<TdApi.MessageThreadInfo>) -> Unit,
    ) = query(TdApi.GetMessageThread(chatId, messageId), done)

    fun threadHistory(
        info: TdApi.MessageThreadInfo,
        fromId: Long,
        done: (Result<List<TdApi.Message>>) -> Unit,
    ) {
        query<TdApi.Messages>(TdApi.GetMessageThreadHistory(info.chatId, info.messageThreadId, fromId, 0, 50)) { result ->
            done(
                result.map {
                    it.messages.filterNotNull().filter {
                            m ->
                        m.id != fromId
                    }
                },
            )
        }
    }

    fun replyToThread(
        info: TdApi.MessageThreadInfo,
        replyId: Long,
        text: String,
        done: (Result<TdApi.Message>) -> Unit,
    ) {
        if (text.isBlank()) {
            done(Result.failure(IllegalArgumentException("Write a reply first")))
            return
        }
        query(
            TdApi.SendMessage().apply {
                chatId = info.chatId
                topicId = TdApi.MessageTopicThread(info.messageThreadId)
                replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyId }
                inputMessageContent = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
            },
            done,
        )
    }

    fun forward(
        fromChatId: Long,
        destinationId: Long,
        ids: LongArray,
        done: (Result<TdApi.Messages>) -> Unit,
    ) {
        if (ids.isEmpty() || ids.size > 100) {
            done(Result.failure(IllegalArgumentException("Select 1–100 messages")))
            return
        }
        query(TdApi.ForwardMessages(destinationId, null, fromChatId, ids.distinct().sorted().toLongArray(), null, false, false), done)
    }

    fun sendAlbum(
        chatId: Long,
        paths: List<String>,
        caption: String,
        replyId: Long,
        done: (Result<TdApi.Messages>) -> Unit,
    ) {
        if (paths.size !in 2..10 || paths.any { !java.io.File(it).isFile || java.io.File(it).length() == 0L }) {
            done(Result.failure(IllegalArgumentException("Choose 2–10 readable photos")))
            return
        }
        val content =
            paths.mapIndexed { i, path ->
                TdApi.InputMessagePhoto().apply {
                    photo = TdApi.InputPhoto().apply { photo = TdApi.InputFileLocal(path) }
                    this.caption = TdApi.FormattedText(if (i == 0) caption else "", emptyArray())
                }
            }.toTypedArray<TdApi.InputMessageContent>()
        query(
            TdApi.SendMessageAlbum().apply {
                this.chatId = chatId
                inputMessageContents = content
                if (replyId != 0L) replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyId }
            },
            done,
        )
    }

    fun passwordCallback(
        chatId: Long,
        messageId: Long,
        password: String,
        data: ByteArray,
        done: (Result<TdApi.CallbackQueryAnswer>) -> Unit,
    ) = query(
        TdApi.GetCallbackQueryAnswer(chatId, messageId, TdApi.CallbackQueryPayloadDataWithPassword(password, data)),
        done,
    )

    fun recoveryEmail(
        password: String,
        email: String,
        done: (Result<TdApi.PasswordState>) -> Unit,
    ) = query(TdApi.SetRecoveryEmailAddress(password, email), done)

    fun verifyRecoveryEmail(
        code: String,
        done: (Result<TdApi.PasswordState>) -> Unit,
    ) = query(TdApi.CheckRecoveryEmailAddressCode(code), done)

    fun passwordState(done: (Result<TdApi.PasswordState>) -> Unit) = query(TdApi.GetPasswordState(), done)

    fun setPassword(
        old: String,
        password: String,
        hint: String,
        done: (Result<TdApi.PasswordState>) -> Unit,
    ) = query(TdApi.SetPassword(old, password, hint, false, ""), done)
}
