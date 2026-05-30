package app.clothescast.core.domain.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HomeSectionTest {
    @Test
    fun `normalize keeps a complete order unchanged`() {
        HomeSection.normalize(listOf(HomeSection.INSIGHT, HomeSection.OUTFIT)) shouldBe
            listOf(HomeSection.INSIGHT, HomeSection.OUTFIT)
    }

    @Test
    fun `normalize appends missing sections in default order`() {
        // A stored order that only named OUTFIT (e.g. predates INSIGHT being
        // reorderable) gets INSIGHT appended at the end.
        HomeSection.normalize(listOf(HomeSection.OUTFIT)) shouldBe
            listOf(HomeSection.OUTFIT, HomeSection.INSIGHT)
    }

    @Test
    fun `normalize de-duplicates while preserving first occurrence`() {
        HomeSection.normalize(
            listOf(HomeSection.INSIGHT, HomeSection.INSIGHT, HomeSection.OUTFIT),
        ) shouldBe listOf(HomeSection.INSIGHT, HomeSection.OUTFIT)
    }

    @Test
    fun `normalize of empty falls back to defaults`() {
        HomeSection.normalize(emptyList()) shouldBe HomeSection.DEFAULTS
    }
}
