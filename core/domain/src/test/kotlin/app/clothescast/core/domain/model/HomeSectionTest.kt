package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HomeSectionTest {
    @Test
    fun `normalize keeps a complete order unchanged`() {
        val complete = listOf(
            HomeSection.CHARTS,
            HomeSection.CONFIDENCE,
            HomeSection.CONDITIONS,
            HomeSection.INSIGHT,
            HomeSection.OUTFIT,
        )
        HomeSection.normalize(complete) shouldBe complete
    }

    @Test
    fun `normalize appends missing sections in default order`() {
        // A stored order that only named OUTFIT (e.g. predates the other
        // sections being reorderable) gets the rest appended in DEFAULTS order.
        HomeSection.normalize(listOf(HomeSection.OUTFIT)) shouldBe
            listOf(
                HomeSection.OUTFIT,
                HomeSection.CONDITIONS,
                HomeSection.INSIGHT,
                HomeSection.CONFIDENCE,
                HomeSection.CHARTS,
            )
    }

    @Test
    fun `normalize de-duplicates while preserving first occurrence`() {
        HomeSection.normalize(
            listOf(HomeSection.INSIGHT, HomeSection.INSIGHT, HomeSection.OUTFIT),
        ) shouldBe listOf(
            HomeSection.INSIGHT,
            HomeSection.OUTFIT,
            HomeSection.CONDITIONS,
            HomeSection.CONFIDENCE,
            HomeSection.CHARTS,
        )
    }

    @Test
    fun `normalize of empty falls back to defaults`() {
        HomeSection.normalize(emptyList()) shouldBe HomeSection.DEFAULTS
    }
}
