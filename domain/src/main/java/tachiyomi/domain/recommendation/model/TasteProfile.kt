package tachiyomi.domain.recommendation.model

import java.time.Instant

data class TasteProfile(
    val tagWeights: Map<String, Double> = emptyMap(),
    val genreWeights: Map<String, Double> = emptyMap(),
    val authorWeights: Map<String, Double> = emptyMap(),
    val topTags: List<String> = emptyList(),
    val topFavoriteTitles: List<String> = emptyList(),
    val completedMangaTitles: Set<String> = emptySet(),
    val libraryMangaTitles: Set<String> = emptySet(),
    val dislikedMangaTitles: Set<String> = emptySet(),
    val totalLibraryCount: Int = 0,
    val lastProfileGeneratedAt: Instant = Instant.now(),
)
