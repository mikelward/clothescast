package app.clothescast.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Resolves a `(lat, lon)` pair to a city-friendly name using Android's built-in
 * [Geocoder]. The Today screen uses the result to label the forecast next to
 * the date when the user is on device location (saved-fallback users already
 * carry a forward-geocoded `displayName`).
 *
 * Privacy: on Play Services devices the framework's `Geocoder` implementation
 * sends the coordinates to Google's geocoding service; on AOSP-only or stripped
 * builds [Geocoder.isPresent] returns false and we short-circuit. This is a
 * separate off-device send from the Open-Meteo forecast call — Open-Meteo
 * doesn't (yet) offer reverse geocoding.
 *
 * Returns null on every failure path; the worker treats null as "no friendly
 * name available" and the UI falls back to a date-only header.
 */
class ReverseGeocoder(
    private val context: Context,
    private val timeoutMillis: Long = 5_000L,
    private val maxAttempts: Int = 2,
    private val retryBackoffMillis: Long = 1_000L,
) {
    /** Best-effort city/locality name, or null if the geocoder is unavailable
     *  / times out / returns nothing useful. */
    suspend fun resolveCityName(latitude: Double, longitude: Double): String? = try {
        resolveInner(latitude, longitude)
    } catch (t: CancellationException) {
        // Coroutine cancellation must propagate — otherwise a Settings
        // toggle that cancels the cache-refresh worker mid-retry would
        // sail through and let the caller still write a stale
        // setLocation. The broad catch below would otherwise swallow it.
        throw t
    } catch (t: Throwable) {
        DiagLog.w(TAG, "Unexpected ${t.javaClass.simpleName} from resolveCityName; returning null.", t)
        null
    }

    private suspend fun resolveInner(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) {
            DiagLog.i(TAG, "Geocoder backend not available on this device; skipping reverse lookup.")
            return null
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        // Retry only on timeout — empty results / framework `onError`
        // resume with `emptyList()`, and a second call against the same
        // backend with the same coords won't conjure up addresses that
        // weren't there. Timeouts, by contrast, are routinely transient:
        // the daily worker runs from a low-priority background dispatcher
        // and the geocoder backend's first call after device idle can
        // exceed [timeoutMillis] cold while a second call seconds later
        // returns immediately.
        repeat(maxAttempts) { attempt ->
            val addresses = withTimeoutOrNull(timeoutMillis) { fetch(geocoder, latitude, longitude) }
            if (addresses != null) return addresses.firstOrNull()?.toCityName()
            val isLastAttempt = attempt == maxAttempts - 1
            if (isLastAttempt) {
                DiagLog.w(TAG, "Reverse geocode timed out after ${timeoutMillis}ms (final of $maxAttempts).")
            } else {
                DiagLog.w(TAG, "Reverse geocode timed out after ${timeoutMillis}ms; retrying (${attempt + 2}/$maxAttempts).")
                delay(retryBackoffMillis)
            }
        }
        return null
    }

    private suspend fun fetch(geocoder: Geocoder, lat: Double, lon: Double): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            fetchAsync(geocoder, lat, lon)
        } else {
            fetchSync(geocoder, lat, lon)
        }

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun fetchAsync(
        geocoder: Geocoder,
        lat: Double,
        lon: Double,
    ): List<Address> = suspendCancellableCoroutine { cont ->
        // Explicit object (not a SAM lambda) so we override `onError` too —
        // on backend / network failures the framework calls `onError` instead
        // of `onGeocode`, and the SAM form would leave it as the default
        // no-op, blocking us until `withTimeoutOrNull` expires on every
        // failure. Resume with empty so the caller falls back immediately.
        val listener = object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                if (cont.isActive) cont.resume(addresses)
            }

            override fun onError(errorMessage: String?) {
                DiagLog.w(TAG, "Async getFromLocation onError: ${errorMessage ?: "<no message>"}")
                if (cont.isActive) cont.resume(emptyList())
            }
        }
        try {
            geocoder.getFromLocation(lat, lon, 1, listener)
        } catch (t: Throwable) {
            DiagLog.w(TAG, "Async getFromLocation threw ${t.javaClass.simpleName}; returning empty.", t)
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun fetchSync(geocoder: Geocoder, lat: Double, lon: Double): List<Address> =
        withContext(Dispatchers.IO) {
            try {
                geocoder.getFromLocation(lat, lon, 1).orEmpty()
            } catch (t: IOException) {
                DiagLog.w(TAG, "Sync getFromLocation IO failure; returning empty.", t)
                emptyList()
            } catch (t: IllegalArgumentException) {
                DiagLog.w(TAG, "Sync getFromLocation rejected coordinates; returning empty.", t)
                emptyList()
            }
        }

    private fun Address.toCityName(): String? {
        val maxIdx = maxAddressLineIndex
        val lines = if (maxIdx < 0) emptyList<String>()
        else (0..maxIdx).mapNotNull { getAddressLine(it) }
        return pickCityName(
            locality = locality,
            subLocality = subLocality,
            subAdminArea = subAdminArea,
            adminArea = adminArea,
            countryCode = countryCode,
            countryName = countryName,
            postalCode = postalCode,
            addressLines = lines,
        )
    }

    companion object {
        private const val TAG = "ReverseGeocoder"
    }
}
