package app.clothescast.core.data.insight

/**
 * Thrown when the user has not configured a Gemini API key — i.e. the
 * BYOK path is exercised without anything to send. Surfaced as a Toast
 * in Settings when a Test Voice tap can't proceed because the user
 * cleared their own key and the planner is on the BYOK branch.
 *
 * Lives in `:core:data` so the (`:app`-side) thrower and the
 * (`:core:data`-side) callers that wrap synthesis can both reference
 * the same type without forcing an Android dep into `:app`'s test
 * fixtures.
 */
class MissingApiKeyException(provider: String = "Gemini") :
    IllegalStateException("$provider API key has not been configured")
