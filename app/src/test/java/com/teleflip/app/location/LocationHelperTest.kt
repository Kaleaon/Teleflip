package com.teleflip.app.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLocationManager
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
class LocationHelperTest {
    private lateinit var context: Context
    private lateinit var locationManager: LocationManager
    private lateinit var shadowLocationManager: ShadowLocationManager
    private lateinit var locationHelper: LocationHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowLocationManager = shadowOf(locationManager)
        locationHelper = LocationHelper(context)
    }

    @Test
    fun testFreshLastKnownLocationReturnsImmediately() {
        val freshLocation =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 37.7749
                longitude = -122.4194
                accuracy = 5f
                time = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2)
            }
        shadowLocationManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, freshLocation)

        var successCount = 0
        var resultInfo: LocationInfo? = null

        locationHelper.fetchCurrentLocation(
            onSuccess = {
                successCount++
                resultInfo = it
            },
            onError = {
                throw AssertionError("Should not fail with fresh last known location")
            },
        )

        assertEquals(1, successCount)
        assertNotNull(resultInfo)
        assertEquals(37.7749, resultInfo!!.latitude, 0.0001)
        assertEquals(-122.4194, resultInfo!!.longitude, 0.0001)
    }

    @Test
    fun testOnLocationChangedTriggersSuccessOnceAndTimeoutDoesNotFire() {
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        var successCount = 0
        var errorCount = 0
        var resultInfo: LocationInfo? = null

        locationHelper.fetchCurrentLocation(
            onSuccess = {
                successCount++
                resultInfo = it
            },
            onError = {
                errorCount++
            },
        )

        // Simulate GPS location update
        val newLocation =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 40.7128
                longitude = -74.0060
                accuracy = 10f
                time = System.currentTimeMillis()
            }
        shadowLocationManager.simulateLocation(newLocation)
        ShadowLooper.idleMainLooper()

        assertEquals(1, successCount)
        assertEquals(0, errorCount)
        assertEquals(40.7128, resultInfo!!.latitude, 0.0001)

        // Simulate location changed firing a second time
        shadowLocationManager.simulateLocation(newLocation)
        ShadowLooper.idleMainLooper()
        assertEquals("onSuccess should not be called again", 1, successCount)

        // Advance main looper beyond 6-second timeout
        ShadowLooper.idleMainLooper(7000, TimeUnit.MILLISECONDS)

        assertEquals("onSuccess should only be called once total", 1, successCount)
        assertEquals("onError should not be called after location received", 0, errorCount)
    }

    @Test
    fun testTimeoutTriggersOnErrorWhenNoLocationReceivedAndNoLastKnown() {
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        var successCount = 0
        var errorCount = 0
        var errorMessage: String? = null

        locationHelper.fetchCurrentLocation(
            onSuccess = {
                successCount++
            },
            onError = { msg ->
                errorCount++
                errorMessage = msg
            },
        )

        assertEquals(0, successCount)
        assertEquals(0, errorCount)

        // Fast forward 6 seconds to trigger timeout
        ShadowLooper.idleMainLooper(6000, TimeUnit.MILLISECONDS)

        assertEquals(0, successCount)
        assertEquals(1, errorCount)
        assertTrue(errorMessage!!.contains("GPS timed out"))

        // Late location update should be ignored
        val lateLocation =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 51.5074
                longitude = -0.1278
                time = System.currentTimeMillis()
            }
        shadowLocationManager.simulateLocation(lateLocation)

        assertEquals(0, successCount)
        assertEquals(1, errorCount)
    }

    @Test
    fun testDisabledProvidersErrorWithNoLastKnown() {
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowLocationManager.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        var successCount = 0
        var errorCount = 0

        locationHelper.fetchCurrentLocation(
            onSuccess = { successCount++ },
            onError = { errorCount++ },
        )

        assertEquals(0, successCount)
        assertEquals(1, errorCount)
    }

    @Test
    fun testTimeoutTriggersSuccessWithStaleLastKnownLocationWhenTimeoutFires() {
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val staleLocation =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 34.0522
                longitude = -118.2437
                accuracy = 15f
                time = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10)
            }
        shadowLocationManager.setLastKnownLocation(LocationManager.GPS_PROVIDER, staleLocation)

        var successCount = 0
        var errorCount = 0
        var resultInfo: LocationInfo? = null

        locationHelper.fetchCurrentLocation(
            onSuccess = {
                successCount++
                resultInfo = it
            },
            onError = {
                errorCount++
            },
        )

        // At start, location isn't returned immediately because it's older than 5 minutes
        assertEquals(0, successCount)
        assertEquals(0, errorCount)

        // Fast forward 6 seconds to trigger timeout fallback
        ShadowLooper.idleMainLooper(6000, TimeUnit.MILLISECONDS)

        assertEquals(1, successCount)
        assertEquals(0, errorCount)
        assertNotNull(resultInfo)
        assertEquals(34.0522, resultInfo!!.latitude, 0.0001)
    }

    @Test
    fun testCopyGoogleMapsLinkAndOpenOrganicMaps() {
        LocationHelper.copyGoogleMapsLink(context, 37.7749, -122.4194)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals("https://maps.google.com/?q=37.7749,-122.4194", clip?.getItemAt(0)?.text?.toString())

        // Ensure openInOrganicMaps does not throw exceptions
        LocationHelper.openInOrganicMaps(context, 37.7749, -122.4194)
    }
}
