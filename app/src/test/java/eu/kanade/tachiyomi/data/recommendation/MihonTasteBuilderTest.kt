package eu.kanade.tachiyomi.data.recommendation

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga

class MihonTasteBuilderTest {

    private val getLibraryManga: GetLibraryManga = mockk()
    private val tasteBuilder = MihonTasteBuilder(getLibraryManga)

    @Test
    fun `builds taste profile with higher weights for completed series`() = runTest {
        val mangaCompleted = Manga.create().copy(
            id = 1L,
            title = "Solo Leveling",
            genre = listOf("Action", "System", "Dungeon"),
            favoriteAt = 1000L,
        )
        val itemCompleted = LibraryManga(
            manga = mangaCompleted,
            categories = emptyList(),
            totalChapters = 200L,
            readCount = 200L, // 100% completed
            bookmarkCount = 5L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 1000L,
        )

        val mangaAbandoned = Manga.create().copy(
            id = 2L,
            title = "Boring Generic Story",
            genre = listOf("Harem", "Isekai"),
            favoriteAt = null,
        )
        val itemAbandoned = LibraryManga(
            manga = mangaAbandoned,
            categories = emptyList(),
            totalChapters = 50L,
            readCount = 2L, // dropped after 2 chapters
            bookmarkCount = 0L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 500L,
        )

        coEvery { getLibraryManga.await() } returns listOf(itemCompleted, itemAbandoned)

        val profile = tasteBuilder.buildTasteProfile()

        profile.totalLibraryCount shouldBe 2
        profile.completedMangaTitles.contains("sololeveling") shouldBe true
        profile.libraryMangaTitles.contains("sololeveling") shouldBe true

        // Completed genre tags should have top normalized weight (1.0)
        profile.tagWeights["System"] shouldBe 1.0
        profile.tagWeights["Dungeon"] shouldBe 1.0

        // Abandoned tags should have negligible / zero weight
        (profile.tagWeights["Isekai"] ?: 0.0) shouldBe 0.0
        profile.topTags.contains("System") shouldBe true
    }
}
