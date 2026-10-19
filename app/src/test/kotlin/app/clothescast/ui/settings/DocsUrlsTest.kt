package app.clothescast.ui.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The app links the repo's docs through GitHub Pages, not github.com/blob
 * paths, so a doc can move without breaking links in shipped builds. Each URL
 * must also name a Markdown file that exists, as Pages renders `X.md` at
 * `X.html`.
 */
class DocsUrlsTest {

    private val pagesRoot = "https://mikelward.github.io/clothescast/"

    private fun sourceFor(url: String): File {
        url.startsWith(pagesRoot) shouldBe true
        url.endsWith(".html") shouldBe true
        val relative = url.removePrefix(pagesRoot).removeSuffix(".html") + ".md"
        return listOf(File("..", relative), File(relative)).firstOrNull { it.isFile }
            ?: error("$url has no source file $relative")
    }

    @Test
    fun `privacy policy URL is the Pages rendering of PRIVACY_md`() {
        sourceFor(PRIVACY_POLICY_URL).name shouldBe "PRIVACY.md"
    }

    @Test
    fun `smart home setup guide URL is the Pages rendering of its doc`() {
        sourceFor(SETUP_GUIDE_URL).name shouldBe "smart-home.md"
    }
}
