package tachiyomi.domain.recommendation.repository

import tachiyomi.domain.recommendation.model.Recommendation
import tachiyomi.domain.recommendation.model.TasteProfile

interface RecommendationRepository {
    suspend fun getTasteProfile(): TasteProfile
    suspend fun getRecommendations(forceRefresh: Boolean = false): List<Recommendation>
    suspend fun dismissRecommendation(title: String)
    suspend fun likeRecommendation(title: String)
}
