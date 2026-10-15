package com.teleflip.app.location

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import java.util.Locale

data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val googleMapsUrl: String,
    val openStreetMapUrl: String
)

class LocationHelper(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun fetchCurrentLocation(
        onSuccess: (LocationInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            // Check last known location first (immediate)
            val gpsLast = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLast = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLast = when {
                gpsLast != null && netLast != null -> if (gpsLast.time > netLast.time) gpsLast else netLast
                gpsLast != null -> gpsLast
                else -> netLast
            }

            if (bestLast != null && (System.currentTimeMillis() - bestLast.time) < 1000 * 60 * 5) {
                // If last known location is fresh (< 5 mins), return it immediately
                onSuccess(buildLocationInfo(bestLast))
                return
            }

            // Otherwise request single fresh fix
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    locationManager.removeUpdates(this)
                    onSuccess(buildLocationInfo(loc))
                }
                override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }

            val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val hasNet = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!hasGps && !hasNet) {
                if (bestLast != null) {
                    onSuccess(buildLocationInfo(bestLast))
                } else {
                    onError("Location providers are turned off. Please enable GPS in device settings.")
                }
                return
            }

            if (hasGps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            } else {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            }

            // Fallback timeout after 6 seconds to use best last known location if still waiting
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                locationManager.removeUpdates(listener)
                if (bestLast != null) {
                    onSuccess(buildLocationInfo(bestLast))
                } else {
                    onError("GPS timed out acquiring position. Try outdoors with clear sky view.")
                }
            }, 6000)

        } catch (e: Exception) {
            onError("Location error: ${e.message}")
        }
    }

    private fun buildLocationInfo(loc: Location): LocationInfo {
        val lat = loc.latitude
        val lng = loc.longitude
        val latStr = String.format(Locale.US, "%.6f", lat)
        val lngStr = String.format(Locale.US, "%.6f", lng)
        val gmaps = "https://maps.google.com/?q=$latStr,$lngStr"
        val osm = "https://www.openstreetmap.org/?mlat=$latStr&mlon=$lngStr"

        return LocationInfo(
            latitude = lat,
            longitude = lng,
            accuracyMeters = loc.accuracy,
            googleMapsUrl = gmaps,
            openStreetMapUrl = osm
        )
    }

    companion object {
        fun openInOrganicMaps(context: Context, latitude: Double, longitude: Double) {
            try {
                val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("app.organicmaps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // If Organic Maps explicit package fails, try generic geo intent
                try {
                    val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))
                    genericIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(genericIntent)
                } catch (e2: Exception) {
                    com.teleflip.app.report.AdbReporter.reportPopup("LocationHelper", "No map application found to view coordinates", true)
                    Toast.makeText(context, "No map application found to view coordinates", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun copyGoogleMapsLink(context: Context, latitude: Double, longitude: Double) {
            val link = "https://maps.google.com/?q=$latitude,$longitude"
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Google Maps Location", link))
            com.teleflip.app.report.AdbReporter.reportPopup("LocationHelper", "Google Maps link copied to clipboard!", false)
            Toast.makeText(context, "Google Maps link copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }
}
