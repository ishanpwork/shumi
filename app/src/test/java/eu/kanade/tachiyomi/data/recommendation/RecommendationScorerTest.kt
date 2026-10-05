package eu.kanade.tachiyomi.data.recommendation

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.recommendation.model.TasteProfile

class RecommendationScorerTest {

    private val scorer = RecommendationScorer()

    @Test
    fun `scores candidate highly when tags match user taste profile`() {
        val taste = TasteProfile(
            tagWeights = mapOf(
                "System" to 0.95,
                "Regression" to 0.90,
                "Murim" to 0.85,
                "Action" to 0.80,
                "Romance" to 0.05,
            ),
            genreWeights = mapOf(
                "Action" to 0.80,
            ),
            completedMangaTitles = setOf("sololeveling"),
            libraryMangaTitles = setOf("sololeveling", "omniscientreader"),
        )

        val candidate = CandidateManga(
            id = "12345",
            title = "Pick Me Up, Infinite Gacha",
            tags = listOf("System", "Regression", "Action", "Dungeon"),
            genres = listOf("Action", "Fantasy"),
            chapterCount = 120,
            rating = 8.6,
        )

        val result = scorer.score(candidate, taste)

        result.matchPercentage shouldBeGreaterThan 80
        result.score shouldBeGreaterThan 0.80
        result.primaryMatchingTags.contains("System") shouldBe true
        result.primaryMatchingTags.contains("Regression") shouldBe true
        result.reasons.isNotEmpty() shouldBe true
        result.isInLibrary shouldBe false
    }

    @Test
    fun `marks recommendation as in library when user already has title`() {
        val taste = TasteProfile(
            tagWeights = mapOf("Action" to 0.8),
            libraryMangaTitles = setOf("pickmeupinfinitegacha"),
        )

        val candidate = CandidateManga(
            id = "999",
            title = "Pick Me Up! Infinite Gacha",
            tags = listOf("Action"),
        )

        val result = scorer.score(candidate, taste)
        result.isInLibrary shouldBe true
    }

    @Test
    fun `gives baseline score when tags do not overlap`() {
        val taste = TasteProfile(
            tagWeights = mapOf(
                "Murim" to 0.9,
                "Martial Arts" to 0.85,
            ),
        )

        val candidate = CandidateManga(
            id = "100",
            title = "A Romantic Comedy",
            tags = listOf("Romance", "School Life", "Slice of Life"),
            rating = 7.0,
        )

        val result = scorer.score(candidate, taste)
        result.matchPercentage shouldBe 28 // Low baseline match with zero tag overlap
        result.primaryMatchingTags.isEmpty() shouldBe true
    }
}
