package com.teleflip.app.telegram

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TelegramFeatureRequestsTest {
    private var request: TdApi.Function<*>? = null
    private var response: TdApi.Object = TdApi.Ok()
    private val api =
        TelegramFeatures { sent, done ->
            request = sent
            done(response)
        }

    @Test fun serverErrorIsFailure() {
        response = TdApi.Error(403, "CHAT_WRITE_FORBIDDEN")
        api.leave(12) {
            assertTrue(it.isFailure)
            assertEquals("CHAT_WRITE_FORBIDDEN", it.exceptionOrNull()?.message)
        }
    }

    @Test fun unexpectedResponseIsFailure() {
        api.message(12, 44) { assertTrue(it.isFailure) }
    }

    @Test fun deleteDeduplicatesAndPreservesRevoke() {
        api.delete(12, longArrayOf(3, 3, 4), true) { assertTrue(it.isSuccess) }
        val sent = request as TdApi.DeleteMessages
        assertEquals(12L, sent.chatId)
        assertArrayEquals(longArrayOf(3, 4), sent.messageIds)
        assertTrue(sent.revoke)
    }

    @Test fun emptyDeleteNeverReachesServer() {
        api.delete(1, longArrayOf(), false) { assertTrue(it.isFailure) }
        assertNull(request)
    }

    @Test fun pastScheduleNeverReachesServer() {
        api.reschedule(1, 2, TdApi.MessageSchedulingStateSendAtDate(1, 0)) { assertTrue(it.isFailure) }
        assertNull(request)
    }

    @Test fun sendNowUsesNullSchedule() {
        api.reschedule(1, 2, null) { assertTrue(it.isSuccess) }
        assertNull((request as TdApi.EditMessageSchedulingState).schedulingState)
    }

    @Test fun formatUsesUtf16AndPreservesReply() {
        response = TdApi.Message()
        api.sendFormatted(12, "Hi 👋", TdApi.TextEntityTypeBold(), 99) { assertTrue(it.isSuccess) }
        val sent = request as TdApi.SendMessage
        val content = sent.inputMessageContent as TdApi.InputMessageText
        assertEquals(5, content.text.entities.single().length)
        assertEquals(99L, (sent.replyTo as TdApi.InputMessageReplyToMessage).messageId)
    }

    @Test fun blankFormatNeverReachesServer() {
        api.sendFormatted(1, " ", TdApi.TextEntityTypeBold(), 0) { assertTrue(it.isFailure) }
        assertNull(request)
    }

    @Test fun contactRequiresActualPhone() {
        api.sendContact(1, TdApi.Contact("", "Name", "", "", 9)) { assertTrue(it.isFailure) }
        assertNull(request)
    }

    @Test fun invalidTranslationCodeNeverReachesServer() {
        api.translate(1, 2, "English") { assertTrue(it.isFailure) }
        assertNull(request)
    }

    @Test fun privacyRetainsExceptionsAheadOfNewBase() {
        val exception = TdApi.UserPrivacySettingRuleRestrictUsers(longArrayOf(9))
        val rules = TdApi.UserPrivacySettingRules(arrayOf(exception, TdApi.UserPrivacySettingRuleAllowAll()))
        api.setPrivacy(TdApi.UserPrivacySettingShowStatus(), rules, TelegramFeatures.Audience.CONTACTS) { assertTrue(it.isSuccess) }
        val sent = request as TdApi.SetUserPrivacySettingRules
        assertSame(exception, sent.rules.rules[0])
        assertTrue(sent.rules.rules[1] is TdApi.UserPrivacySettingRuleAllowContacts)
        assertTrue(sent.rules.rules[2] is TdApi.UserPrivacySettingRuleRestrictAll)
    }

    @Test fun threadReplyUsesServerDiscussionChatAndTopic() {
        response = TdApi.Message()
        val info =
            TdApi.MessageThreadInfo().apply {
                chatId = 70
                messageThreadId = 80
            }
        api.replyToThread(info, 90, "Reply") { assertTrue(it.isSuccess) }
        val sent = request as TdApi.SendMessage
        assertEquals(70L, sent.chatId)
        assertEquals(80L, (sent.topicId as TdApi.MessageTopicThread).messageThreadId)
        assertEquals(90L, (sent.replyTo as TdApi.InputMessageReplyToMessage).messageId)
    }

    @Test fun forwardOrdersIdsAndKeepsAttribution() {
        response = TdApi.Messages()
        api.forward(1, 2, longArrayOf(9, 3, 9)) { assertTrue(it.isSuccess) }
        val sent = request as TdApi.ForwardMessages
        assertArrayEquals(longArrayOf(3, 9), sent.messageIds)
        assertFalse(sent.sendCopy)
        assertFalse(sent.removeCaption)
    }

    @Test fun emptyForwardNeverReachesServer() {
        api.forward(1, 2, longArrayOf()) { assertTrue(it.isFailure) }
        assertNull(request)
    }

    @Test fun albumRequiresMultipleExistingPhotos() {
        api.sendAlbum(1, listOf("/missing"), "caption", 0) { assertTrue(it.isFailure) }
        assertNull(request)
    }

    @Test fun albumHasOneCaptionAndPreservesReply() {
        val photo = File.createTempFile("teleflip", ".jpg").apply { writeBytes(byteArrayOf(1, 2)) }
        try {
            response = TdApi.Messages()
            api.sendAlbum(1, listOf(photo.path, photo.path), "caption", 55) { assertTrue(it.isSuccess) }
            val sent = request as TdApi.SendMessageAlbum
            assertEquals("caption", (sent.inputMessageContents[0] as TdApi.InputMessagePhoto).caption.text)
            assertEquals("", (sent.inputMessageContents[1] as TdApi.InputMessagePhoto).caption.text)
            assertEquals(55L, (sent.replyTo as TdApi.InputMessageReplyToMessage).messageId)
        } finally {
            photo.delete()
        }
    }

    @Test fun passwordChangePreservesRecoveryEmail() {
        response = TdApi.PasswordState()
        api.setPassword("old", "new", "hint") { assertTrue(it.isSuccess) }
        val sent = request as TdApi.SetPassword
        assertFalse(sent.setRecoveryEmailAddress)
        assertEquals("old", sent.oldPassword)
    }

    @Test fun callbackPreservesPayloadAndMessage() {
        response = TdApi.CallbackQueryAnswer()
        api.callback(1, 2, byteArrayOf(3, 4)) { assertTrue(it.isSuccess) }
        val sent = request as TdApi.GetCallbackQueryAnswer
        assertEquals(2L, sent.messageId)
        assertArrayEquals(byteArrayOf(3, 4), (sent.payload as TdApi.CallbackQueryPayloadData).data)
    }
}
