package eu.kanade.tachiyomi.data.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.firstOrNull
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.recommendation.model.TasteProfile
import tachiyomi.domain.track.repository.TrackRepository
import java.time.Instant

@Inject
@SingleIn(AppScope::class)
class MihonTasteBuilder(
    private val getLibraryManga: GetLibraryManga,
    private val mangaRepository: MangaRepository,
    private val historyRepository: HistoryRepository,
    private val trackRepository: TrackRepository,
) {

    suspend fun buildTasteProfile(): TasteProfile {
        val library = getLibraryManga.await()
        val tagWeights = mutableMapOf<String, Double>()
        val genreWeights = mutableMapOf<String, Double>()
        val authorWeights = mutableMapOf<String, Double>()
        val completedTitles = mutableSetOf<String>()
        val libraryTitles = mutableSetOf<String>()

        // 1. Process Library Manga
        for (item in library) {
            val manga = item.manga
            libraryTitles.addAll(TitleNormalizer.extractVariants(manga.title))

            val total = item.totalChapters.coerceAtLeast(0)
            val read = item.readCount.coerceAtLeast(0)
            val completionRatio = if (total > 0) read.toDouble() / total.toDouble() else 0.0

            // Base preference weight based on actual reading completion
            var weight = when {
                completionRatio >= 0.90 -> 1.00
                completionRatio >= 0.70 -> 0.85
                completionRatio >= 0.40 -> 0.60
                completionRatio >= 0.20 -> 0.35
                read > 0 && total >= 10 && completionRatio < 0.15 -> -0.35 // Abandoned quickly
                else -> 0.25 // Added but unread or just started
            }

            // Bookmark boost (strong intentional interest)
            if (item.hasBookmarks) {
                weight += 0.20
            }

            // Favorite boost
            if (manga.favorite) {
                weight += 0.15
            }

            // Mark as completed
            if (completionRatio >= 0.85 && total >= 5) {
                completedTitles.addAll(TitleNormalizer.extractVariants(manga.title))
            }

            // Parse genres / tags
            manga.genre?.forEach { rawGenre ->
                val clean = rawGenre.trim()
                if (clean.isNotBlank()) {
                    tagWeights[clean] = (tagWeights[clean] ?: 0.0) + weight
                    genreWeights[clean] = (genreWeights[clean] ?: 0.0) + weight
                }
            }

            // Author weights
            manga.author?.let { author ->
                val cleanAuthor = author.trim()
                if (cleanAuthor.isNotBlank()) {
                    authorWeights[cleanAuthor] = (authorWeights[cleanAuthor] ?: 0.0) + weight
                }
            }
        }

        // 2. Read manga not in library (e.g. read from Browse)
        try {
            val readNotInLibrary = mangaRepository.getReadMangaNotInLibrary()
            for (manga in readNotInLibrary) {
                libraryTitles.addAll(TitleNormalizer.extractVariants(manga.title))
            }
        } catch (_: Exception) {}

        // 3. Favorites
        try {
            val favorites = mangaRepository.getFavorites()
            for (manga in favorites) {
                libraryTitles.addAll(TitleNormalizer.extractVariants(manga.title))
            }
        } catch (_: Exception) {}

        // 4. Reading history
        try {
            val history = historyRepository.getHistory("").firstOrNull() ?: emptyList()
            for (entry in history) {
                libraryTitles.addAll(TitleNormalizer.extractVariants(entry.title))
            }
        } catch (_: Exception) {}

        // 5. Tracked manga (AniList, MyAnimeList, Kitsu, etc.)
        try {
            val tracks = trackRepository.getTracksAsFlow().firstOrNull() ?: emptyList()
            for (track in tracks) {
                libraryTitles.addAll(TitleNormalizer.extractVariants(track.title))
            }
        } catch (_: Exception) {}

        // Normalize tag weights to [0.0 .. 1.0]
        val maxTagWeight = (tagWeights.values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val normalizedTags = tagWeights.mapValues { (_, score) ->
            (score / maxTagWeight).coerceIn(0.0, 1.0)
        }

        val maxGenreWeight = (genreWeights.values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val normalizedGenres = genreWeights.mapValues { (_, score) ->
            (score / maxGenreWeight).coerceIn(0.0, 1.0)
        }

        val topTags = normalizedTags.entries
            .filter { it.value >= 0.35 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(15)

        // Identify user's top engaged and loved titles to seed collaborative recommendations
        val topFavoriteTitles = library
            .map { item ->
                val manga = item.manga
                val total = item.totalChapters.coerceAtLeast(0)
                val read = item.readCount.coerceAtLeast(0)
                val completionRatio = if (total > 0) read.toDouble() / total.toDouble() else 0.0

                var engagementScore = 0.0
                if (manga.favorite) engagementScore += 6.0
                if (item.hasBookmarks) engagementScore += 4.0
                engagementScore += completionRatio * 5.0
                if (read >= 10) engagementScore += 3.0
                if (read >= 30) engagementScore += 3.0

                item to engagementScore
            }
            .filter { it.second > 1.0 }
            .sortedByDescending { it.second }
            .map { cleanTitleForSearch(it.first.manga.title) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)

        return TasteProfile(
            tagWeights = normalizedTags,
            genreWeights = normalizedGenres,
            authorWeights = authorWeights,
            topTags = topTags,
            topFavoriteTitles = topFavoriteTitles,
            completedMangaTitles = completedTitles,
            libraryMangaTitles = libraryTitles,
            dislikedMangaTitles = emptySet(),
            totalLibraryCount = library.size,
            lastProfileGeneratedAt = Instant.now(),
        )
    }

    fun cleanTitleForSearch(title: String): String {
        return TitleNormalizer.clean(title)
    }
}
