package app.clothescast.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location as AndroidLocation
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises [LocationResolver]'s deterministic paths through Robolectric's
 * [LocationManager] shadow: permission denial, fresh-cache shortcut, the
 * NETWORK→PASSIVE fallback for [LocationManager.getLastKnownLocation], and the
 * difference between [LocationResolver.resolve] (stale fallback allowed) and
 * [LocationResolver.resolveFresh] (stale treated as unavailable).
 *
 * Tests that need a live single-update to *not* arrive disable the NETWORK
 * provider so [LocationResolver]'s inner `requestSingle()` short-circuits
 * before registering a listener — that keeps the suspension free of looper
 * timing and the timeout path entirely deterministic.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LocationResolverTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = application
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Before
    fun grantLocationPermissions() {
        // Robolectric auto-grants manifest-declared permissions, but per-Application
        // state leaks across tests, so the "denied" case revokes and any later test
        // would start with permission missing. Re-grant in setup.
        shadowOf(application).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(locationManager).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, true)
    }

    @Test
    fun `returns null when coarse location is not granted`() {
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        runBlocking {
            resolver().resolve() shouldBe null
            resolver().resolveFresh(maxAgeMillis = 60_000L) shouldBe null
        }
    }

    @Test
    fun `returns the fresh NETWORK last-known fix without firing a live request`() {
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            androidLocation(LocationManager.NETWORK_PROVIDER, lat = 51.5, lon = -0.1, ageMs = 5_000L),
        )
        // Disabling the provider after seeding last-known would have masked a bug
        // where the resolver requests a live update even with a fresh cache — leave
        // it enabled and rely on the fresh-cache early return.
        val resolved = runBlocking { resolver().resolve() }
        resolved?.latitude shouldBe 51.5
        resolved?.longitude shouldBe -0.1
        resolved?.displayName shouldBe "Device location"
    }

    @Test
    fun `falls back to PASSIVE last-known when NETWORK provider is disabled`() {
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.PASSIVE_PROVIDER,
            androidLocation(LocationManager.PASSIVE_PROVIDER, lat = 40.7, lon = -74.0, ageMs = 5_000L),
        )
        val resolved = runBlocking { resolver().resolve() }
        resolved?.latitude shouldBe 40.7
        resolved?.longitude shouldBe -74.0
    }

    @Test
    fun `resolve falls back to the stale cached fix when a live request cannot fire`() {
        // NETWORK disabled → requestSingle() short-circuits to null; with
        // allowStaleFallback the cached PASSIVE value still wins even though it's
        // older than the default 1h maxAgeMillis.
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val staleAge = 2 * 60 * 60 * 1000L
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.PASSIVE_PROVIDER,
            androidLocation(LocationManager.PASSIVE_PROVIDER, lat = 35.0, lon = 139.0, ageMs = staleAge),
        )
        val resolved = runBlocking { resolver().resolve() }
        resolved?.latitude shouldBe 35.0
        resolved?.longitude shouldBe 139.0
    }

    @Test
    fun `resolveFresh returns null when only a stale cached fix is available`() {
        // Same shape as the resolve() test above, but resolveFresh's contract
        // forbids serving a stale fix: the live request can't fire, the cache is
        // too old, the function must surface null so the caller can fail open
        // (e.g. the at-home TTS gate falls back to speaking).
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.PASSIVE_PROVIDER,
            androidLocation(LocationManager.PASSIVE_PROVIDER, lat = 35.0, lon = 139.0, ageMs = 10 * 60 * 1000L),
        )
        runBlocking {
            resolver().resolveFresh(maxAgeMillis = 60_000L) shouldBe null
        }
    }

    @Test
    fun `returns null when no cached fix exists and no live request can fire`() {
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(locationManager).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, false)
        runBlocking {
            resolver().resolve() shouldBe null
            resolver().resolveFresh(maxAgeMillis = 60_000L) shouldBe null
        }
    }

    @Test
    fun `still resolves when the background grant is missing but coarse is granted`() {
        // hasBackgroundPermission() only logs a warning when missing; the actual
        // gate is hasCoarsePermission(). A user who's granted "while in use" but
        // not "all the time" should still see a foreground resolve succeed —
        // important for MainActivity's "Use my current location" home-pin capture.
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            androidLocation(LocationManager.NETWORK_PROVIDER, lat = 48.8, lon = 2.35, ageMs = 5_000L),
        )
        val resolved = runBlocking { resolver().resolve() }
        resolved?.latitude shouldBe 48.8
        resolved?.longitude shouldBe 2.35
    }

    private fun resolver(): LocationResolver = LocationResolver(
        context = context,
        timeoutMillis = 50L,
    )

    private fun androidLocation(provider: String, lat: Double, lon: Double, ageMs: Long): AndroidLocation =
        AndroidLocation(provider).apply {
            latitude = lat
            longitude = lon
            time = System.currentTimeMillis() - ageMs
        }
}
