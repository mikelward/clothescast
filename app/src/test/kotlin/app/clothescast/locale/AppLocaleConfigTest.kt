package app.clothescast.locale

import app.clothescast.core.domain.model.Region
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppLocaleConfigTest {
    @Test
    fun `translated region picker tags are advertised in locale config`() {
        val advertisedTags = File("src/main/res/xml/locales_config.xml")
            .readLocaleConfigTags()
            .toSet()
        val translatedPickerTags = Region.entries
            .mapNotNull { it.bcp47 }

        advertisedTags.shouldContainAll(translatedPickerTags)
    }

    private fun File.readLocaleConfigTags(): List<String> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(this)
        val locales = document.getElementsByTagName("locale")
        return List(locales.length) { index ->
            val element = locales.item(index) as Element
            element.getAttributeNS(ANDROID_NS, "name")
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
