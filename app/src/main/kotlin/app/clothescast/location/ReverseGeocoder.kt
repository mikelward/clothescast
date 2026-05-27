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
     * city but the underlying address lacked a country code, or no
     * structured fields were populated so no detail could be built);
     * callers handle each as missing data without crashing.
     *
     * [addressDetail] is built from the platform Geocoder's structured
     * fields (subLocality / locality / adminArea / postalCode /
     * countryName) by [buildAddressDetail] — for display on the
     * Location settings page. We deliberately never parse the formatted
     * `addressLine` string here: that string sometimes carries a POI
     * prefix or duplicated street that any naïve trim would either
     * leak or over-strip.
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

    /**
     * Diagnostic-only return value pairing the raw `addressLines` from the
     * platform Geocoder with the [Result] derived from them. Surfaced to
     * the Developer settings page's reverse-geocode tester so a developer
     * can see exactly what the Geocoder returned for a given coord and
     * how [buildAddressDetail] folded the structured fields into the
     * displayed addressDetail.
     */
    data class DiagnosticResult(
        val addressLines: List<String>,
        val derived: Result,
    ) {
        companion object {
            val EMPTY = DiagnosticResult(addressLines = emptyList(), derived = Result.EMPTY)
        }
    }

    /** Best-effort city name + country code, or [Result.EMPTY] if the
     *  geocoder is unavailable / times out / returns nothing useful. */
    suspend fun resolve(latitude: Double, longitude: Double): Result = coRunCatching {
        resolveInner(latitude, longitude)
    }
        .onFailure { DiagLog.w(TAG, "Unexpected ${it.javaClass.simpleName} from resolve; returning empty.", it) }
        .getOrDefault(Result.EMPTY)

    /**
     * Diagnostic variant of [resolve] for the Developer settings page's
     * reverse-geocode tester. Single attempt (no retry — this is an
     * interactive tool, the user can hit "Resolve" again themselves),
     * and the returned [DiagnosticResult] carries the raw `addressLines`
     * alongside the derived [Result] so the caller can correlate inputs
     * with outputs.
     *
     * Never call from production code paths — the raw lines include
     * house numbers and other detail we deliberately omit when
     * [buildAddressDetail] composes the displayed addressDetail.
     */
    suspend fun resolveDiagnostic(latitude: Double, longitude: Double): DiagnosticResult = coRunCatching {
        if (!Geocoder.isPresent()) {
            DiagLog.i(TAG, "Geocoder backend not available; diagnostic resolve returning empty.")
            return@coRunCatching DiagnosticResult.EMPTY
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = withTimeoutOrNull(timeoutMillis) { fetch(geocoder, latitude, longitude) }
            ?: return@coRunCatching DiagnosticResult.EMPTY
        val address = addresses.firstOrNull() ?: return@coRunCatching DiagnosticResult.EMPTY
        DiagnosticResult(addressLines = address.extractLines(), derived = address.toResult())
    }
        .onFailure { DiagLog.w(TAG, "Unexpected ${it.javaClass.simpleName} from resolveDiagnostic; returning empty.", it) }
        .getOrDefault(DiagnosticResult.EMPTY)

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

    private fun Address.extractLines(): List<String> {
        val maxIdx = maxAddressLineIndex
        return if (maxIdx < 0) emptyList()
        else (0..maxIdx).mapNotNull { getAddressLine(it) }
    }

    private fun Address.toResult(): Result {
        val lines = extractLines()
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
        // Hand the raw structured fields plus pickCityName's resolved
        // city straight to buildAddressDetail and let it dedup. The
        // `recoveredCity` slot is what keeps the displayed detail
        // consistent with the city header when the Geocoder leaves
        // `locality` blank (inner-London residentials → "London")
        // or fills it with a hamlet distinct from the post town
        // (outer-London / UK villages: `locality="Oakley Green"`,
        // city = "Windsor"). The same slot stays harmless when the
        // Geocoder's `locality` already matches the recovered city —
        // dedup collapses it. When both subLocality and locality carry
        // real values and the city differs from both, all three
        // survive (`Suburb, Oakley Green, Windsor, …`).
        return Result(
            city = city,
            countryCode = normalisedCountry,
            addressDetail = buildAddressDetail(
                AddressParts(
                    subLocality = subLocality,
                    locality = locality,
                    recoveredCity = city,
                    adminArea = adminArea,
                    postalCode = postalCode,
                    countryName = countryName,
                    countryCode = countryCode,
                ),
            ),
        )
    }

    companion object {
        private const val TAG = "ReverseGeocoder"
    }
}

/**
 * Subset of the platform [android.location.Address] fields we use to
 * build the Location settings page's neighbourhood-level line. Lifted
 * out as a data class so [buildAddressDetail] is testable on the JVM
 * without instantiating an Android `Address`.
 */
internal data class AddressParts(
    val subLocality: String? = null,
    val locality: String? = null,
    /**
     * The city name `pickCityName` resolved from the same `Address`,
     * for cases where it can recover something more user-facing than
     * the structured `locality` — e.g. an inner-London Geocoder result
     * with blank `locality` but a post town ("London") sitting in the
     * formatted line, or a hamlet-locality result ("Oakley Green") for
     * which `pickCityName` pulls the post town ("Windsor") from the
     * postcode token. [buildAddressDetail] slots it in after `locality`
     * with the same dedup rules as every other field, so when the
     * Geocoder + city picker agree it costs nothing.
     */
    val recoveredCity: String? = null,
    val adminArea: String? = null,
    val postalCode: String? = null,
    val countryName: String? = null,
    val countryCode: String? = null,
)

/**
 * Builds the Location settings page's neighbourhood-level address line
 * directly from the platform Geocoder's *structured* fields, rather
 * than parsing the formatted [android.location.Address.getAddressLine]
 * string. The formatted line is where Google's backend gets creative —
 * prepending a POI name when the coordinate lands in water (e.g.
 * "Governors Island Ferry, 10 South St Slip 7, New York, NY 10004,
 * USA"), splitting the street across multiple `addressLines` on some
 * backends, ordering the house number differently in pt-BR / es-* /
 * many EU formats. Any string-level rule that strips the street ends
 * up either leaving a POI behind, stripping a meaningful neighbourhood
 * (Bela Vista from "Street, Bela Vista, São Paulo - SP, 01311-000,
 * Brazil"), or breaking some country we haven't tested. The structured
 * fields are the same data the formatter consumes, just without that
 * lossy formatting step.
 *
 * Output format: `subLocality, locality, recoveredCity, adminArea
 * postalCode, country` — each part skipped if blank or if it would
 * duplicate an earlier non-null part. The adminArea / postalCode pair
 * is joined on a single space (so a missing one doesn't leave dangling
 * whitespace). Country prefers [AddressParts.countryName] and falls
 * back to the ISO code when only that is set; dropped if it would
 * duplicate any earlier place name (city-states / SARs like Singapore
 * and Hong Kong populate every place field with the same string, and
 * the country-level fallback from `pickCityName` can land in the
 * recoveredCity slot for SARs).
 *
 * Examples (from the platform Geocoder on real coords):
 *  - NYC water-adjacent: subLocality=null, locality="New York",
 *    adminArea="NY", postalCode="10004", countryName="United States"
 *    → "New York, NY 10004, United States"
 *  - Brazil neighbourhood: subLocality="Bela Vista",
 *    locality="São Paulo", adminArea="SP", postalCode="01311-000",
 *    countryName="Brasil"
 *    → "Bela Vista, São Paulo, SP 01311-000, Brasil"
 *  - London neighbourhood: subLocality="Muswell Hill",
 *    locality="London", adminArea="England", postalCode="N10 3BN",
 *    countryName="United Kingdom"
 *    → "Muswell Hill, London, England N10 3BN, United Kingdom"
 *
 * Returns null when every meaningful field is blank.
 */
internal fun buildAddressDetail(parts: AddressParts): String? {
    val sub = parts.subLocality?.trim()?.takeIf { it.isNotEmpty() }
    val city = parts.locality?.trim()?.takeIf { it.isNotEmpty() && it != sub }
    val recovered = parts.recoveredCity?.trim()
        ?.takeIf { it.isNotEmpty() && it != sub && it != city }
    val region = listOfNotNull(
        parts.adminArea?.trim()?.takeIf { it.isNotEmpty() },
        parts.postalCode?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" ").takeIf { it.isNotEmpty() }
    val country = (parts.countryName?.trim()?.takeIf { it.isNotEmpty() }
        ?: parts.countryCode?.trim()?.takeIf { it.isNotEmpty() })
        ?.takeIf { it != sub && it != city && it != recovered }
    return listOfNotNull(sub, city, recovered, region, country)
        .joinToString(", ")
        .takeIf { it.isNotBlank() }
}
