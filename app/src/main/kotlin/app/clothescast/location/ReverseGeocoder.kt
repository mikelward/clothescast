package app.clothescast.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.clothescast.core.domain.util.coRunCatching
import app.clothescast.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Resolves a `(lat, lon)` pair to a city-friendly name and an ISO 3166-1
 * alpha-2 country code using Android's built-in [Geocoder]. The Today screen
 * uses the city name to label the forecast next to the date when the user is
 * on device location (saved-fallback users already carry a forward-geocoded
 * `displayName`); the country code feeds the holiday country filter in
 * Settings.
 *
 * Privacy: on Play Services devices the framework's `Geocoder` implementation
 * sends the coordinates to Google's geocoding service; on AOSP-only or stripped
 * builds [Geocoder.isPresent] returns false and we short-circuit. This is a
 * separate off-device send from the Open-Meteo forecast call — Open-Meteo
 * doesn't (yet) offer reverse geocoding.
 *
 * Returns an [Empty][ReverseGeocodeResult.EMPTY] result on every failure path;
 * callers treat blank fields as "no value available" and surface a date-only
 * header / no-country fallback.
 */
class ReverseGeocoder(
    private val context: Context,
    private val timeoutMillis: Long = 5_000L,
    private val maxAttempts: Int = 2,
    private val retryBackoffMillis: Long = 1_000L,
) {
    /**
     * Best-effort city/locality name + country code + address detail. Any
     * field can be null independently (e.g. the geocoder returned a usable
     * city but the address lacked a country code, or the first address line
     * was missing/empty so no detail could be derived); callers handle each
     * as missing data without crashing.
     *
     * [addressDetail] is the first address line with its leading component
     * dropped (e.g. "Cambridge, MA 02139, USA" from "1 Vassar St, Cambridge,
     * MA 02139, USA") — for display on the Location settings page.
     */
    data class Result(
        val city: String?,
        val countryCode: String?,
        val addressDetail: String? = null,
    ) {
        companion object {
            val EMPTY = Result(city = null, countryCode = null, addressDetail = null)
        }
    }

    /** Best-effort city name + country code, or [Result.EMPTY] if the
     *  geocoder is unavailable / times out / returns nothing useful. */
    suspend fun resolve(latitude: Double, longitude: Double): Result = coRunCatching {
        resolveInner(latitude, longitude)
    }
        .onFailure { DiagLog.w(TAG, "Unexpected ${it.javaClass.simpleName} from resolve; returning empty.", it) }
        .getOrDefault(Result.EMPTY)

    private suspend fun resolveInner(latitude: Double, longitude: Double): Result {
        if (!Geocoder.isPresent()) {
            DiagLog.i(TAG, "Geocoder backend not available on this device; skipping reverse lookup.")
            return Result.EMPTY
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
            if (addresses != null) return addresses.firstOrNull()?.toResult() ?: Result.EMPTY
            val isLastAttempt = attempt == maxAttempts - 1
            if (isLastAttempt) {
                DiagLog.w(TAG, "Reverse geocode timed out after ${timeoutMillis}ms (final of $maxAttempts).")
            } else {
                DiagLog.w(TAG, "Reverse geocode timed out after ${timeoutMillis}ms; retrying (${attempt + 2}/$maxAttempts).")
                delay(retryBackoffMillis)
            }
        }
        return Result.EMPTY
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

    private fun Address.toResult(): Result {
        val maxIdx = maxAddressLineIndex
        val lines = if (maxIdx < 0) emptyList<String>()
        else (0..maxIdx).mapNotNull { getAddressLine(it) }
        val city = pickCityName(
            locality = locality,
            subLocality = subLocality,
            subAdminArea = subAdminArea,
            adminArea = adminArea,
            countryCode = countryCode,
            countryName = countryName,
            postalCode = postalCode,
            addressLines = lines,
        )
        val normalisedCountry = countryCode?.takeIf { it.isNotBlank() }?.uppercase()
        return Result(
            city = city,
            countryCode = normalisedCountry,
            addressDetail = deriveAddressDetail(lines),
        )
    }

    companion object {
        private const val TAG = "ReverseGeocoder"
    }
}

/**
 * Builds the Location settings page's neighbourhood-level address line
 * from a Geocoder Address's [android.location.Address.getAddressLine]
 * outputs. Joins multi-line responses with ", " (some backends split the
 * address across separate lines — street / city-state / country), then
 * drops the leading comma-delimited component (typically the house
 * number and street, e.g. "1 Vassar St") so what remains is suburb +
 * city + postal code + country — ("Cambridge, MA 02139, USA" from
 * "1 Vassar St, Cambridge, MA 02139, USA"). Returns null when there
 * are no lines, no comma to split on, or stripping yields a blank.
 */
internal fun deriveAddressDetail(addressLines: List<String>): String? {
    if (addressLines.isEmpty()) return null
    val joined = addressLines.joinToString(", ")
    val commaIdx = joined.indexOf(',')
    if (commaIdx < 0) return null
    return joined.substring(commaIdx + 1).trim().takeIf { it.isNotBlank() }
}
