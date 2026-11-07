package com.teleflip.app.ui

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
class ListFocusPreservationTest {
    @Test
    fun testChatListAdapterPreservesFocusOnItemId() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TeleFlip)

        val recyclerView = RecyclerView(activity)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        activity.setContentView(recyclerView)

        val adapter = ChatListAdapter {}
        recyclerView.adapter = adapter

        val chat1 = ChatModel(101L, "Chat 1", "Msg 1", "10:00", 0, ChatType.DIRECT, "C1")
        val chat2 = ChatModel(102L, "Chat 2", "Msg 2", "10:05", 0, ChatType.DIRECT, "C2")

        adapter.submitList(listOf(chat1, chat2))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 400, 800)

        val holder1 = recyclerView.findViewHolderForAdapterPosition(0)
        assertNotNull("ViewHolder for position 0 should exist", holder1)
        holder1!!.itemView.requestFocus()
        assertTrue("Position 0 should have focus", holder1.itemView.hasFocus())

        // Reorder list: chat2 first, chat1 second
        val reorderedChats = listOf(chat2, chat1)
        var commitExecuted = false
        adapter.submitList(reorderedChats, Runnable { commitExecuted = true })
        Thread.sleep(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Commit callback should execute", commitExecuted)
        // Chat 1 is now at position 1
        val holderChat1New = recyclerView.findViewHolderForAdapterPosition(1)
        assertNotNull("ViewHolder at position 1 should exist", holderChat1New)
        assertTrue("Chat 1 at new position should retain focus", holderChat1New!!.itemView.hasFocus())

        controller.destroy()
    }

    @Test
    fun testChatListAdapterFallbackFocusWhenItemDeleted() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TeleFlip)

        val recyclerView = RecyclerView(activity)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        activity.setContentView(recyclerView)

        val adapter = ChatListAdapter {}
        recyclerView.adapter = adapter

        val chat1 = ChatModel(101L, "Chat 1", "Msg 1", "10:00", 0, ChatType.DIRECT, "C1")
        val chat2 = ChatModel(102L, "Chat 2", "Msg 2", "10:05", 0, ChatType.DIRECT, "C2")

        adapter.submitList(listOf(chat1, chat2))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 400, 800)

        val holder2 = recyclerView.findViewHolderForAdapterPosition(1)
        assertNotNull(holder2)
        holder2!!.itemView.requestFocus()

        // Submit list with chat2 deleted
        adapter.submitList(listOf(chat1))
        Thread.sleep(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val holder1 = recyclerView.findViewHolderForAdapterPosition(0)
        assertNotNull(holder1)
        assertTrue("Focus should fallback to nearest position 0 when item deleted", holder1!!.itemView.hasFocus())

        controller.destroy()
    }

    @Test
    fun testMessageAdapterPreservesFocusOnMessageId() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TeleFlip)

        val recyclerView = RecyclerView(activity)
        recyclerView.layoutManager = LinearLayoutManager(activity)
        activity.setContentView(recyclerView)

        val adapter = MessageAdapter()
        recyclerView.adapter = adapter

        val msg1 = MessageModel(201L, 1L, "Alice", "Hello", "10:00", false, MessageStatus.READ)
        val msg2 = MessageModel(202L, 1L, "Bob", "World", "10:01", false, MessageStatus.READ)

        adapter.submitList(listOf(msg1, msg2))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 400, 800)

        val holder2 = recyclerView.findViewHolderForAdapterPosition(1)
        assertNotNull(holder2)
        holder2!!.itemView.requestFocus()

        // Update list: msg2 text modified
        val msg2Updated = msg2.copy(text = "World (edited)")
        var commitExecuted = false
        adapter.submitList(listOf(msg1, msg2Updated), Runnable { commitExecuted = true })
        Thread.sleep(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(commitExecuted)
        val holder2New = recyclerView.findViewHolderForAdapterPosition(1)
        assertNotNull(holder2New)
        assertTrue("Message 2 should retain focus across list update", holder2New!!.itemView.hasFocus())

        controller.destroy()
    }

    @Test
    fun testSearchDialogPreservesSearchInputAndTabFocus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TeleFlip)

        val tm = mock(TelegramManager::class.java)
        val dialog = SearchDialog(activity, tm, {}, { _, _ -> })
        dialog.show()

        val etSearchInput = dialog.findViewById<EditText>(R.id.et_search_input)
        val tabChats = dialog.findViewById<TextView>(R.id.tab_search_chats)
        val tabContacts = dialog.findViewById<TextView>(R.id.tab_search_contacts)

        assertTrue("Input should be focused initially", etSearchInput.hasFocus())

        // Switching tab while input is focused keeps focus on input
        dialog.selectTab(SearchDialog.SearchTab.CONTACTS)
        assertTrue("Focus should remain on search input after selectTab", etSearchInput.hasFocus())

        // Focus tab directly
        tabContacts.isFocusableInTouchMode = true
        tabContacts.requestFocus()
        assertTrue("Tab Contacts should have focus", tabContacts.hasFocus())

        // Switching tab while tab is focused shifts focus to selected tab
        dialog.selectTab(SearchDialog.SearchTab.CHATS)
        assertTrue("Focus should move to Tab Chats when switching tabs from tab bar", tabChats.hasFocus())

        dialog.dismiss()
        controller.destroy()
    }

    @Test
    fun testSearchDialogDpadDownEmptyListRetainsInputAndTabFocus() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_TeleFlip)

        val tm = mock(TelegramManager::class.java)
        val dialog = SearchDialog(activity, tm, {}, { _, _ -> })
        dialog.show()

        val etSearchInput = dialog.findViewById<EditText>(R.id.et_search_input)
        val tabChats = dialog.findViewById<TextView>(R.id.tab_search_chats)

        // D-pad DOWN on empty search results keeps focus on search input
        assertTrue(etSearchInput.hasFocus())
        val dpadDown = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, dpadDown)
        assertTrue("D-pad DOWN on empty results should keep focus on search input", etSearchInput.hasFocus())

        // D-pad DOWN on empty search results when tab is focused keeps focus on tab
        tabChats.isFocusableInTouchMode = true
        tabChats.requestFocus()
        assertTrue(tabChats.hasFocus())
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, dpadDown)
        assertTrue("D-pad DOWN on empty results should keep focus on tab", tabChats.hasFocus())

        dialog.dismiss()
        controller.destroy()
    }
}
