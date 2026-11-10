package app.clothescast.backup

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a backup or a move to a new phone carries. The rules are an include list,
 * so a new store silently stays behind; this makes adding one a decision.
 */
class DataExtractionRulesTest {
    private val rules = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(sourceFile("src/main/res/xml/data_extraction_rules.xml"))

    @Test
    fun `backup and transfer carry the user's choices and nothing else`() {
        includes("cloud-backup") shouldBe CARRIED
        includes("device-transfer") shouldBe CARRIED
    }

    @Test
    fun `cloud backup needs end-to-end encryption`() {
        section("cloud-backup").getAttribute("disableIfNoEncryptionCapabilities") shouldBe "true"
    }

    @Test
    fun `every DataStore is either carried or deliberately left behind`() {
        val stores = sourceFile("src/main/kotlin").walk()
            .filter { it.extension == "kt" }
            .flatMap { STORE_NAME.findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        val carried = CARRIED.filter { it.first == "file" }
            .map { it.second.removePrefix("datastore/").removeSuffix(".preferences_pb") }
        (stores - carried.toSet() - LEFT_BEHIND).shouldBeEmpty()
    }

    @Test
    fun `the manifest turns on Auto Backup with these rules`() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()
        manifest.contains("""android:allowBackup="true"""") shouldBe true
        manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules"""") shouldBe true
        manifest.contains("""android:fullBackupOnly="true"""") shouldBe true
    }

    private fun section(name: String): Element =
        rules.getElementsByTagName(name).item(0) as Element

    private fun includes(name: String): Set<Pair<String, String>> {
        val nodes = section(name).getElementsByTagName("include")
        return List(nodes.length) { nodes.item(it) as Element }
            .map { it.getAttribute("domain") to it.getAttribute("path") }
            .toSet()
    }

    private fun sourceFile(relativePath: String): File =
        listOf(File(relativePath), File("app/$relativePath")).firstOrNull { it.exists() }
            ?: error("Not found from ${File(".").absolutePath}: $relativePath")

    private companion object {
        val CARRIED = setOf(
            "file" to "datastore/settings.preferences_pb",
            "file" to "datastore/secure_key_store.preferences_pb",
            "sharedpref" to "app_locale.xml",
        )

        /** Caches and device-local state: rebuilt on the new install. */
        val LEFT_BEHIND = setOf("google_forecast_cache", "insight_cache", "daily_history", "weather_alerts")

        val STORE_NAME = Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)"""")
    }
}
