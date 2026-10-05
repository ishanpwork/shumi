package tachiyomi.domain.recommendation.model

data class Recommendation(
    val id: String,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val coverUrl: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val chapterCount: Int? = null,
    val score: Double = 0.0,
    val matchPercentage: Int = 0,
    val primaryMatchingTags: List<String> = emptyList(),
    val allTags: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
    val externalUrl: String? = null,
    val provider: String = "MangaBaka",
    val isInLibrary: Boolean = false,
)
