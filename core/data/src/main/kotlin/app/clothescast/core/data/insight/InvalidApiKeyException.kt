package app.clothescast.core.data.insight

/**
 * Thrown when a previously stored API key can no longer be read. This is
 * distinct from [MissingApiKeyException]: the user had opted into the BYOK
 * path, so callers must not silently fall back to a shared proxy until the user
 * re-enters or clears that key.
 */
class InvalidApiKeyException(provider: String = "Gemini") :
    IllegalStateException("$provider API key could not be read; re-enter it")
