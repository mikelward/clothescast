package app.clothescast.ui.pairing

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.clothescast.core.domain.model.TtsEngine
import app.clothescast.data.SecureKeyStore
import app.clothescast.data.SettingsRepository
import app.clothescast.pairing.PairingServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/** How long the pairing server stays open before it times out. */
private const val PAIRING_TIMEOUT_MS = 5 * 60 * 1_000L

/** Side length (pixels) of the generated QR code bitmap. */
private const val QR_SIZE_PX = 512

sealed interface PairingState {
    /** Server is running and waiting for the phone to POST the key. */
    data class Waiting(val qrBitmap: Bitmap, val url: String) : PairingState

    /** Key received and stored successfully. */
    data object Received : PairingState

    /** The pairing window expired before a key was submitted. */
    data object Timeout : PairingState

    /** Could not determine local IP or the server failed to start. */
    data object Error : PairingState
}

/**
 * Drives the phone-pairing screen.
 *
 * On creation it:
 *   1. Discovers the device's LAN IPv4 address.
 *   2. Starts [PairingServer] on a random local port.
 *   3. Generates a QR code encoding `http://<ip>:<port>/pair/<token>`.
 *   4. Exposes [state] so the UI can render the QR code or react to success / timeout.
 *
 * The server is stopped and cleaned up in [onCleared] or when [retry] restarts it.
 */
class PairingViewModel(
    private val secureKeyStore: SecureKeyStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Error)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var server: PairingServer? = null
    private var timeoutJob: Job? = null

    init {
        startPairing()
    }

    fun retry() {
        stopServer()
        startPairing()
    }

    private fun startPairing() {
        viewModelScope.launch {
            val ip = getLocalIpAddress()
            if (ip == null) {
                _state.value = PairingState.Error
                return@launch
            }
            val srv = PairingServer { key ->
                viewModelScope.launch {
                    secureKeyStore.set(key)
                    // Mirror OnboardingViewModel.setApiKey: configuring a Gemini
                    // key during onboarding flips the TTS default to Gemini,
                    // unless the user already picked an engine in Settings.
                    settingsRepository.setTtsEngineIfUnset(TtsEngine.GEMINI)
                    _state.value = PairingState.Received
                }
            }
            val port = try {
                srv.start()
            } catch (e: Exception) {
                Log.e(TAG, "PairingServer failed to start", e)
                _state.value = PairingState.Error
                return@launch
            }
            server = srv

            val url = "http://$ip:$port/pair/${srv.token}"
            val qr = generateQrBitmap(url)
            _state.value = PairingState.Waiting(qrBitmap = qr, url = url)

            timeoutJob = viewModelScope.launch {
                delay(PAIRING_TIMEOUT_MS)
                if (_state.value is PairingState.Waiting) {
                    _state.value = PairingState.Timeout
                    stopServer()
                }
            }
        }
    }

    private fun stopServer() {
        timeoutJob?.cancel()
        timeoutJob = null
        server?.stop()
        server = null
    }

    override fun onCleared() {
        super.onCleared()
        stopServer()
    }

    class Factory(
        private val secureKeyStore: SecureKeyStore,
        private val settingsRepository: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PairingViewModel::class.java)) {
                "Unknown ViewModel: ${modelClass.name}"
            }
            return PairingViewModel(secureKeyStore, settingsRepository) as T
        }
    }

    companion object {
        private const val TAG = "PairingViewModel"
    }
}

private fun getLocalIpAddress(): String? =
    NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress

private fun generateQrBitmap(content: String): Bitmap {
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX)
    val pixels = IntArray(QR_SIZE_PX * QR_SIZE_PX) { i ->
        if (bits[i % QR_SIZE_PX, i / QR_SIZE_PX]) Color.BLACK else Color.WHITE
    }
    val bmp = createBitmap(QR_SIZE_PX, QR_SIZE_PX)
    bmp.setPixels(pixels, 0, QR_SIZE_PX, 0, 0, QR_SIZE_PX, QR_SIZE_PX)
    return bmp
}
