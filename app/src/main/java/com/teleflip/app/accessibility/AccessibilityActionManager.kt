package com.teleflip.app.accessibility

import android.view.View
import androidx.annotation.IdRes
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageSearchResult
import com.teleflip.app.report.AdbReporter
import java.util.concurrent.ConcurrentHashMap

/**
 * Functional interface for handling accessibility action dispatches.
 */
fun interface AccessibilityActionListener<T> {
    fun onAccessibilityAction(actionId: Int, item: T): Boolean
}

/**
 * Encapsulates a custom accessibility action for an entity [T].
 */
data class AccessibilityAction<T>(
    @IdRes val id: Int,
    val label: String,
    val perform: (item: T) -> Boolean,
)

/**
 * Interface for entity action providers.
 */
interface AccessibilityActionProvider<T> {
    fun getActions(item: T, listener: AccessibilityActionListener<T>? = null): List<AccessibilityAction<T>>
}

/**
 * Default action provider for [ChatModel].
 */
class DefaultChatActionProvider : AccessibilityActionProvider<ChatModel> {
    override fun getActions(
        item: ChatModel,
        listener: AccessibilityActionListener<ChatModel>?,
    ): List<AccessibilityAction<ChatModel>> {
        val actions = mutableListOf<AccessibilityAction<ChatModel>>()

        // Open chat action
        actions.add(
            AccessibilityAction(R.id.action_chat_open, "Open Chat") { chat ->
                listener?.onAccessibilityAction(R.id.action_chat_open, chat) ?: false
            },
        )

        // Pin / Unpin chat action
        val pinId = if (item.isPinned) R.id.action_chat_unpin else R.id.action_chat_pin
        val pinLabel = if (item.isPinned) "Unpin Chat" else "Pin Chat"
        actions.add(
            AccessibilityAction(pinId, pinLabel) { chat ->
                listener?.onAccessibilityAction(pinId, chat) ?: false
            },
        )

        // Mute / Unmute chat action
        val muteId = if (item.isMuted) R.id.action_chat_unmute else R.id.action_chat_mute
        val muteLabel = if (item.isMuted) "Unmute Notifications" else "Mute Notifications"
        actions.add(
            AccessibilityAction(muteId, muteLabel) { chat ->
                listener?.onAccessibilityAction(muteId, chat) ?: false
            },
        )

        // Archive / Unarchive chat action
        val archiveId = if (item.isArchived) R.id.action_chat_unarchive else R.id.action_chat_archive
        val archiveLabel = if (item.isArchived) "Unarchive Chat" else "Archive Chat"
        actions.add(
            AccessibilityAction(archiveId, archiveLabel) { chat ->
                listener?.onAccessibilityAction(archiveId, chat) ?: false
            },
        )

        return actions
    }
}

/**
 * Default action provider for [MessageModel].
 */
class DefaultMessageActionProvider : AccessibilityActionProvider<MessageModel> {
    override fun getActions(
        item: MessageModel,
        listener: AccessibilityActionListener<MessageModel>?,
    ): List<AccessibilityAction<MessageModel>> {
        val actions = mutableListOf<AccessibilityAction<MessageModel>>()

        // Reply to message
        actions.add(
            AccessibilityAction(R.id.action_message_reply, "Reply to Message") { msg ->
                listener?.onAccessibilityAction(R.id.action_message_reply, msg) ?: false
            },
        )

        // React to message
        actions.add(
            AccessibilityAction(R.id.action_message_react, "React to Message") { msg ->
                listener?.onAccessibilityAction(R.id.action_message_react, msg) ?: false
            },
        )

        // Copy text (if message contains text)
        if (item.text.isNotBlank()) {
            actions.add(
                AccessibilityAction(R.id.action_message_copy, "Copy Text") { msg ->
                    listener?.onAccessibilityAction(R.id.action_message_copy, msg) ?: false
                },
            )
        }

        // Delete message
        actions.add(
            AccessibilityAction(R.id.action_message_delete, "Delete Message") { msg ->
                listener?.onAccessibilityAction(R.id.action_message_delete, msg) ?: false
            },
        )

        // Voice message actions
        if (item.mediaType == MediaType.VOICE) {
            actions.add(
                AccessibilityAction(R.id.action_message_transcribe, "Transcribe Voice Message") { msg ->
                    listener?.onAccessibilityAction(R.id.action_message_transcribe, msg) ?: false
                },
            )
            actions.add(
                AccessibilityAction(R.id.action_message_play_voice, "Play Voice Note") { msg ->
                    listener?.onAccessibilityAction(R.id.action_message_play_voice, msg) ?: false
                },
            )
        }

        // Pin / Unpin message
        val pinId = if (item.isPinned) R.id.action_message_unpin else R.id.action_message_pin
        val pinLabel = if (item.isPinned) "Unpin Message" else "Pin Message"
        actions.add(
            AccessibilityAction(pinId, pinLabel) { msg ->
                listener?.onAccessibilityAction(pinId, msg) ?: false
            },
        )

        return actions
    }
}

/**
 * Default action provider for [MessageSearchResult].
 */
class DefaultSearchResultActionProvider : AccessibilityActionProvider<MessageSearchResult> {
    override fun getActions(
        item: MessageSearchResult,
        listener: AccessibilityActionListener<MessageSearchResult>?,
    ): List<AccessibilityAction<MessageSearchResult>> {
        val actions = mutableListOf<AccessibilityAction<MessageSearchResult>>()

        actions.add(
            AccessibilityAction(R.id.action_search_jump, "Jump to Message") { searchResult ->
                listener?.onAccessibilityAction(R.id.action_search_jump, searchResult) ?: false
            },
        )

        return actions
    }
}

/**
 * Centralized application-wide accessibility action manager.
 */
object AccessibilityActionManager {
    private const val TAG = "AccessibilityActionManager"
    private val providers = ConcurrentHashMap<Class<*>, AccessibilityActionProvider<*>>()

    init {
        registerDefaultProviders()
    }

    fun registerDefaultProviders() {
        registerProvider(ChatModel::class.java, DefaultChatActionProvider())
        registerProvider(MessageModel::class.java, DefaultMessageActionProvider())
        registerProvider(MessageSearchResult::class.java, DefaultSearchResultActionProvider())
    }

    fun <T : Any> registerProvider(
        clazz: Class<T>,
        provider: AccessibilityActionProvider<T>,
    ) {
        providers[clazz] = provider
    }

    fun <T : Any> unregisterProvider(clazz: Class<T>) {
        providers.remove(clazz)
    }

    fun resetProviders() {
        providers.clear()
        registerDefaultProviders()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getProvider(clazz: Class<T>): AccessibilityActionProvider<T>? {
        return providers[clazz] as? AccessibilityActionProvider<T>
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> bindActions(
        view: View,
        item: T?,
        listener: AccessibilityActionListener<T>? = null,
    ) {
        clearActions(view)
        if (item == null) return

        val provider = providers[item::class.java] as? AccessibilityActionProvider<T> ?: return
        val actions =
            try {
                provider.getActions(item, listener)
            } catch (e: Exception) {
                AdbReporter.reportError(TAG, "Failed to resolve actions for item $item", e)
                emptyList()
            }

        if (actions.isEmpty()) return

        val trackedIds = mutableSetOf<Int>()

        for (action in actions) {
            try {
                val actionCompat = AccessibilityNodeInfoCompat.AccessibilityActionCompat(action.id, action.label)
                ViewCompat.replaceAccessibilityAction(view, actionCompat, null) { _, _ ->
                    dispatchAction(action, item)
                }
                trackedIds.add(action.id)
            } catch (e: Exception) {
                AdbReporter.reportError(TAG, "Failed to bind action ${action.id} (${action.label}) on view", e)
            }
        }

        if (trackedIds.isNotEmpty()) {
            view.setTag(R.id.tag_accessibility_action_ids, trackedIds)
        }
    }

    fun clearActions(view: View) {
        @Suppress("UNCHECKED_CAST")
        val trackedIds = view.getTag(R.id.tag_accessibility_action_ids) as? Set<Int>
        if (!trackedIds.isNullOrEmpty()) {
            for (actionId in trackedIds) {
                try {
                    ViewCompat.removeAccessibilityAction(view, actionId)
                } catch (e: Exception) {
                    AdbReporter.reportError(TAG, "Failed to remove accessibility action $actionId during clearActions", e)
                }
            }
            view.setTag(R.id.tag_accessibility_action_ids, null)
        }
        try {
            ViewCompat.setAccessibilityDelegate(view, null)
        } catch (e: Exception) {
            AdbReporter.reportError(TAG, "Failed to reset accessibility delegate during clearActions", e)
        }
    }

    private fun <T : Any> dispatchAction(
        action: AccessibilityAction<T>,
        item: T,
    ): Boolean {
        return try {
            AdbReporter.reportInfo(TAG, "Dispatched action ${action.id} (${action.label}) for item: $item")
            val result = action.perform(item)
            if (!result) {
                AdbReporter.reportWarning(TAG, "Action ${action.id} (${action.label}) returned false")
            }
            result
        } catch (e: Exception) {
            AdbReporter.reportError(TAG, "Error executing accessibility action ${action.id} (${action.label})", e)
            false
        }
    }
}
