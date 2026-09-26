package app.clothescast.ui.settings

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the committed `res/raw/aboutlibraries.json` that the Licenses page
 * renders every entry of. `exportBundledLicenses` (app/build.gradle.kts) drops
 * the org.jetbrains.compose redirect modules whose androidx artifact is also
 * listed, since each would otherwise show as a second, identical row. It once
 * matched on version as well, and a Compose BOM that moved androidx a patch
 * ahead of the redirects (1.12.1 vs 1.12.0) let all of them through.
 */
class BundledLicensesTest {

    private val uniqueIds: List<String> by lazy {
        val file = listOf("src/main/res/raw/aboutlibraries.json", "app/src/main/res/raw/aboutlibraries.json")
            .map(::File)
            .firstOrNull { it.exists() }
            ?: error("aboutlibraries.json not found from cwd ${File(".").absolutePath}")
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("libraries").jsonArray
            .map { it.jsonObject.getValue("uniqueId").jsonPrimitive.content }
    }

    @Test
    fun `no compose redirect is listed beside the androidx artifact it aliases`() {
        uniqueIds.shouldNotBeEmpty()
        val androidxArtifacts = uniqueIds
            .filterNot { it.startsWith("org.jetbrains.compose") }
            .map { it.substringAfter(':') }
            .toSet()
        uniqueIds
            .filter { it.startsWith("org.jetbrains.compose") && it.substringAfter(':') in androidxArtifacts }
            .shouldBeEmpty()
    }
}
