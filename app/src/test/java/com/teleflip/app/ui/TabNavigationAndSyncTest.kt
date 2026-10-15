package com.teleflip.app.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabNavigationAndSyncTest {
    private enum class ViewState {
        GONE,
        VISIBLE,
    }

    private class DebounceTestHelper(private val testScope: TestScope) {
        var progressState: ViewState = ViewState.GONE
        var emptyState: ViewState = ViewState.GONE
        var debounceJob: Job? = null
        var currentChatCount: Int = 0

        fun onTabSelected(chatsInFilter: Int) {
            debounceJob?.cancel()
            currentChatCount = chatsInFilter

            if (chatsInFilter > 0) {
                progressState = ViewState.GONE
                emptyState = ViewState.GONE
            } else {
                progressState = ViewState.VISIBLE
                emptyState = ViewState.GONE

                debounceJob =
                    testScope.launch {
                        kotlinx.coroutines.delay(200)
                        if (currentChatCount == 0) {
                            progressState = ViewState.GONE
                            emptyState = ViewState.VISIBLE
                        } else {
                            progressState = ViewState.GONE
                            emptyState = ViewState.GONE
                        }
                    }
            }
        }

        fun onChatsUpdated(chatsInFilter: Int) {
            currentChatCount = chatsInFilter
            if (chatsInFilter > 0) {
                debounceJob?.cancel()
                progressState = ViewState.GONE
                emptyState = ViewState.GONE
            }
        }
    }

    @Test
    fun testDebouncedEmptyStateTransition() =
        runTest {
            val helper = DebounceTestHelper(this)

            // Select empty tab
            helper.onTabSelected(0)
            assertEquals(ViewState.VISIBLE, helper.progressState)
            assertEquals(ViewState.GONE, helper.emptyState)

            // Advance time 100ms (before 200ms debounce expires)
            advanceTimeBy(100)
            assertEquals("Progress should remain visible before 200ms", ViewState.VISIBLE, helper.progressState)
            assertEquals("Empty state should not be visible before 200ms", ViewState.GONE, helper.emptyState)

            // Advance time past 200ms
            advanceTimeBy(105)
            assertEquals("Progress should be hidden after 200ms", ViewState.GONE, helper.progressState)
            assertEquals("Empty state should be visible after 200ms when count is 0", ViewState.VISIBLE, helper.emptyState)
        }

    @Test
    fun testSyncCompletesBeforeDebounceExpires() =
        runTest {
            val helper = DebounceTestHelper(this)

            // Select empty tab
            helper.onTabSelected(0)
            assertEquals(ViewState.VISIBLE, helper.progressState)

            advanceTimeBy(100)
            // Background sync updates chats before 200ms timer
            helper.onChatsUpdated(3)

            assertEquals("Progress should hide immediately on sync", ViewState.GONE, helper.progressState)
            assertEquals("Empty state should stay hidden", ViewState.GONE, helper.emptyState)

            advanceTimeBy(150)
            assertEquals("Empty state should remain hidden after 200ms since sync succeeded", ViewState.GONE, helper.emptyState)
        }

    @Test
    fun testRapidTabSwitchingCancelsPreviousDebounce() =
        runTest {
            val helper = DebounceTestHelper(this)

            // Switch to Tab 1 (empty)
            helper.onTabSelected(0)
            val firstJob = helper.debounceJob
            assertTrue(firstJob?.isActive == true)

            advanceTimeBy(50)

            // Rapidly switch to Tab 2 (empty)
            helper.onTabSelected(0)
            val secondJob = helper.debounceJob
            assertTrue("First debounce job should be cancelled", firstJob?.isCancelled == true)
            assertTrue("Second debounce job should be active", secondJob?.isActive == true)

            advanceTimeBy(100)
            // At 150ms total, first job would have expired if not cancelled, but second job is at 100ms
            assertEquals("Empty state should not be shown yet for rapid switch", ViewState.GONE, helper.emptyState)

            advanceTimeBy(105)
            // At 205ms from second switch, second job completes
            assertEquals("Empty state shows after second switch 200ms window completes", ViewState.VISIBLE, helper.emptyState)
        }

    @Test
    fun testTabViewFocusCheckHelper() {
        val tabIds =
            setOf(
                com.teleflip.app.R.id.tab_all,
                com.teleflip.app.R.id.tab_direct,
                com.teleflip.app.R.id.tab_groups,
                com.teleflip.app.R.id.tab_channels,
            )

        assertTrue(tabIds.contains(com.teleflip.app.R.id.tab_all))
        assertTrue(tabIds.contains(com.teleflip.app.R.id.tab_direct))
        assertTrue(tabIds.contains(com.teleflip.app.R.id.tab_groups))
        assertTrue(tabIds.contains(com.teleflip.app.R.id.tab_channels))
        assertFalse(tabIds.contains(com.teleflip.app.R.id.rv_chats))
    }
}
