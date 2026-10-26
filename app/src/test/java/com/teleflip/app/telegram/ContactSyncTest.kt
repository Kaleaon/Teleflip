package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.storage.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ContactSyncTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
    }

    @Test
    fun testSyncContactsEnabledDefaultValue() {
        `when`(mockPrefs.getBoolean(eq("pref_sync_contacts"), anyBoolean())).thenReturn(true)

        val manager = TelegramManager(mockContext)
        assertTrue("Default syncContactsEnabled should be true", manager.syncContactsEnabled)
    }

    @Test
    fun testSyncContactsEnabledToggle() {
        val manager = TelegramManager(mockContext)

        manager.syncContactsEnabled = false
        verify(mockEditor).putBoolean("pref_sync_contacts", false)

        manager.syncContactsEnabled = true
        verify(mockEditor).putBoolean("pref_sync_contacts", true)
    }

    @Test
    fun testStorageManagerSyncContactsSetting() {
        `when`(mockPrefs.getBoolean(eq("pref_sync_contacts"), anyBoolean())).thenReturn(true)

        assertTrue(StorageManager.isSyncContactsEnabled(mockContext))

        StorageManager.setSyncContactsEnabled(mockContext, false)
        verify(mockEditor).putBoolean("pref_sync_contacts", false)
    }

    @Test
    fun testSyncContactsWhenDisabledReturnsEarly() {
        `when`(mockPrefs.getBoolean(eq("pref_sync_contacts"), anyBoolean())).thenReturn(false)

        val manager = TelegramManager(mockContext)
        var callbackTriggered = false
        var successResult = true

        manager.syncContacts { success, _ ->
            callbackTriggered = true
            successResult = success
        }

        assertTrue("Callback should be triggered when sync is disabled", callbackTriggered)
        assertFalse("Success result should be false when disabled", successResult)
    }

    @Test
    fun testPhoneFormatNormalizationForContactSync() {
        val rawNum1 = "(555) 019-2834"
        val cleanNum1 = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(rawNum1, true)
        val normalized1 = if (!cleanNum1.startsWith("+")) "+$cleanNum1" else cleanNum1
        assertEquals("+5550192834", normalized1)

        val rawNum2 = "+1 800 555 0199"
        val cleanNum2 = org.telegram.PhoneFormat.PhoneFormat.stripExceptNumbers(rawNum2, true)
        val normalized2 = if (!cleanNum2.startsWith("+")) "+$cleanNum2" else cleanNum2
        assertEquals("+18005550199", normalized2)
    }

    @Test
    fun testLiveContactListObservableFallbackPopulation() {
        val manager = TelegramManager(mockContext)

        assertEquals("Initial contacts StateFlow should be empty", 0, manager.contacts.value.size)

        manager.loadContacts { contacts ->
            assertEquals("Callback should receive empty contacts", 0, contacts.size)
        }

        assertEquals("StateFlow contacts should match empty result", 0, manager.contacts.value.size)
    }
}
