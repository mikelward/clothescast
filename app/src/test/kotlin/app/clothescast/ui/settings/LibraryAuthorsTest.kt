package app.clothescast.ui.settings

import com.mikepenz.aboutlibraries.entity.Developer
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.Organization
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for [authorsOrEmpty] — the attribution line the license details
 * dialog shows under a component's version. Apache-2.0 §4 asks that
 * attribution travel with the code, so what this returns is the page's answer
 * to "whose code is this", and an empty string is what suppresses the line
 * rather than printing a bare "By".
 *
 * The cases are built as [Library] values rather than parsed from an export:
 * the bundled `res/raw/aboutlibraries.json` declares a developer for every
 * component that names anyone at all, so the organization fallback and the
 * nobody-named case have no live example — and the library's own JSON parser
 * is the Android one, which reads through `org.json` and returns nothing in a
 * plain JVM test. Stock stand-in names throughout; nothing here is anybody's.
 */
class LibraryAuthorsTest {

    private fun library(
        developers: List<Developer> = emptyList(),
        organization: Organization? = null,
    ) = Library(
        uniqueId = "com.example:example",
        artifactVersion = "1.0.0",
        name = "Example",
        description = null,
        website = null,
        developers = persistentListOf(*developers.toTypedArray()),
        organization = organization,
        scm = null,
    )

    @Test
    fun `a declared developer is the attribution`() {
        library(developers = listOf(Developer("Ada Example", null))).authorsOrEmpty() shouldBe
            "Ada Example"
    }

    @Test
    fun `every developer is named, in the order the export lists them`() {
        library(
            developers = listOf(Developer("Ada Example", null), Developer("Grace Example", null)),
        ).authorsOrEmpty() shouldBe "Ada Example, Grace Example"
    }

    @Test
    fun `the publishing organization stands in when no developer is named`() {
        library(organization = Organization("Example Organization", null)).authorsOrEmpty() shouldBe
            "Example Organization"
    }

    /** A developer entry is what the POM gave us; an unnamed one attributes nothing. */
    @Test
    fun `a developer with no name falls through to the organization`() {
        library(
            developers = listOf(Developer(null, "https://example.com")),
            organization = Organization("Example Organization", null),
        ).authorsOrEmpty() shouldBe "Example Organization"
    }

    @Test
    fun `a component naming nobody attributes nobody`() {
        library().authorsOrEmpty() shouldBe ""
    }
}
