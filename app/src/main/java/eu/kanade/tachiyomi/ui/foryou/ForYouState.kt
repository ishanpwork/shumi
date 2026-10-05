package eu.kanade.tachiyomi.ui.foryou

import androidx.compose.runtime.Immutable
import tachiyomi.domain.recommendation.model.Recommendation
import tachiyomi.domain.recommendation.model.TasteProfile

@Immutable
data class ForYouState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val recommendations: List<Recommendation> = emptyList(),
    val tasteProfile: TasteProfile? = null,
    val selectedTagFilter: String? = null,
    val selectedRecommendation: Recommendation? = null,
    val errorMessage: String? = null,
) {
    val filteredRecommendations: List<Recommendation>
        get() = if (selectedTagFilter.isNullOrBlank()) {
            recommendations
        } else {
            recommendations.filter { rec ->
                rec.primaryMatchingTags.any { it.equals(selectedTagFilter, ignoreCase = true) } ||
                    rec.allTags.any { it.equals(selectedTagFilter, ignoreCase = true) }
            }
        }

    val availableTags: List<String>
        get() = recommendations.flatMap { it.primaryMatchingTags }.distinct().take(10)
}
