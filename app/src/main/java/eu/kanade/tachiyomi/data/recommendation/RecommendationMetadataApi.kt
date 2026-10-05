package eu.kanade.tachiyomi.data.recommendation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

data class CandidateManga(
    val id: String,
    val title: String,
    val alternativeTitles: List<String> = emptyList(),
    val coverUrl: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val chapterCount: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val provider: String = "AniList",
    val externalUrl: String? = null,
    val seedTitle: String? = null,
    val countryOfOrigin: String? = null,
)

@Inject
@SingleIn(AppScope::class)
class RecommendationMetadataApi(
    private val network: NetworkHelper,
    private val json: Json,
) {
    private val client by lazy { network.client }

    // Memory cache with timestamp
    private var cachedCandidates: List<CandidateManga> = emptyList()
    private var lastFetchTimestamp: Long = 0L
    private val cacheDurationMs = 6 * 60 * 60 * 1000L // 6 hours

    suspend fun fetchCandidates(
        favoriteTitles: List<String>,
        keywords: List<String>,
        forceRefresh: Boolean = false,
    ): List<CandidateManga> = withIOContext {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedCandidates.isNotEmpty() && (now - lastFetchTimestamp) < cacheDurationMs) {
            return@withIOContext cachedCandidates
        }

        val results = mutableMapOf<String, CandidateManga>()

        // 1. Fetch direct collaborative recommendations based on user's top-read/favorite titles
        for (favTitle in favoriteTitles.take(5)) {
            try {
                val seedRecs = fetchAniListRecommendationsForTitle(favTitle)
                seedRecs.forEach { candidate ->
                    val key = candidate.title.lowercase().trim()
                    if (!results.containsKey(key)) {
                        results[key] = candidate
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to fetch AniList recommendations for seed title: $favTitle" }
            }
        }

        // 2. Fetch top-rated & trending across user's top genres on AniList (Manga & Manhwa)
        val searchGenres = if (keywords.isNotEmpty()) keywords.take(3) else emptyList()
        if (searchGenres.isNotEmpty()) {
            try {
                val genreCandidates = fetchAniListByGenres(searchGenres)
                genreCandidates.forEach { candidate ->
                    val key = candidate.title.lowercase().trim()
                    if (!results.containsKey(key)) {
                        results[key] = candidate
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to fetch AniList genre candidates for: $searchGenres" }
            }
        }

        // 3. Global trending & top-rated across all origins
        try {
            val trendingCandidates = fetchAniListTrending()
            trendingCandidates.forEach { candidate ->
                val key = candidate.title.lowercase().trim()
                if (!results.containsKey(key)) {
                    results[key] = candidate
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to fetch global trending AniList candidates" }
        }

        val allCandidates = results.values.toList()
        if (allCandidates.isNotEmpty()) {
            cachedCandidates = allCandidates
            lastFetchTimestamp = now
        }

        if (allCandidates.isEmpty() && cachedCandidates.isNotEmpty()) {
            return@withIOContext cachedCandidates
        }

        allCandidates
    }

    suspend fun fetchAniListRecommendationsForTitle(title: String): List<CandidateManga> {
        val query = """
            query (${'$'}search: String) {
              Media(type: MANGA, search: ${'$'}search) {
                id
                title {
                  english
                  romaji
                }
                recommendations(sort: RATING_DESC, perPage: 8) {
                  nodes {
                    rating
                    mediaRecommendation {
                      id
                      title {
                        english
                        romaji
                      }
                      description
                      status
                      chapters
                      meanScore
                      coverImage {
                        large
                      }
                      genres
                      tags {
                        name
                        rank
                      }
                      countryOfOrigin
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val jsonBody = """{"query": ${Json.encodeToString(kotlinx.serialization.serializer(), query)}, "variables": {"search": ${Json.encodeToString(kotlinx.serialization.serializer(), title)}}}"""
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = POST("https://graphql.anilist.co", body = jsonBody.toRequestBody(mediaType))
        val response = client.newCall(request).awaitSuccess()
        val bodyString = response.body?.string() ?: return emptyList()

        val parsed = json.parseToJsonElement(bodyString).jsonObject
        val mediaObj = parsed["data"]?.jsonObject?.get("Media")?.jsonObject ?: return emptyList()
        val recNodes = mediaObj["recommendations"]?.jsonObject?.get("nodes")?.jsonArray ?: return emptyList()

        val items = mutableListOf<CandidateManga>()
        for (node in recNodes) {
            val recObj = node.jsonObject["mediaRecommendation"]?.jsonObject ?: continue
            val id = recObj["id"]?.jsonPrimitive?.content ?: continue
            val titleObj = recObj["title"]?.jsonObject
            val englishTitle = titleObj?.get("english")?.jsonPrimitive?.content
            val romajiTitle = titleObj?.get("romaji")?.jsonPrimitive?.content
            val recTitle = englishTitle ?: romajiTitle ?: continue

            val altTitles = listOfNotNull(englishTitle, romajiTitle).distinct()
            val synopsis = recObj["description"]?.jsonPrimitive?.content?.replace(Regex("<.*?>"), "")
            val status = recObj["status"]?.jsonPrimitive?.content
            val chapters = recObj["chapters"]?.jsonPrimitive?.intOrNull
            val meanScore = recObj["meanScore"]?.jsonPrimitive?.doubleOrNull
            val coverUrl = recObj["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.content
            val origin = recObj["countryOfOrigin"]?.jsonPrimitive?.content

            val genres = recObj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            val tags = recObj["tags"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            } ?: emptyList()

            items.add(
                CandidateManga(
                    id = id,
                    title = recTitle,
                    alternativeTitles = altTitles,
                    coverUrl = coverUrl,
                    synopsis = synopsis,
                    status = status,
                    chapterCount = chapters,
                    rating = meanScore,
                    genres = genres,
                    tags = tags,
                    provider = "AniList",
                    externalUrl = "https://anilist.co/manga/$id",
                    seedTitle = title,
                    countryOfOrigin = origin,
                ),
            )
        }
        return items
    }

    suspend fun fetchAniListByGenres(genres: List<String>): List<CandidateManga> {
        val query = """
            query (${'$'}genres: [String]) {
              Page(page: 1, perPage: 25) {
                media(type: MANGA, genre_in: ${'$'}genres, sort: [SCORE_DESC, POPULARITY_DESC]) {
                  id
                  title {
                    english
                    romaji
                  }
                  description
                  status
                  chapters
                  meanScore
                  coverImage {
                    large
                  }
                  genres
                  tags {
                    name
                    rank
                  }
                  countryOfOrigin
                }
              }
            }
        """.trimIndent()

        val jsonBody = """{"query": ${Json.encodeToString(kotlinx.serialization.serializer(), query)}, "variables": {"genres": ${Json.encodeToString(kotlinx.serialization.serializer(), genres)}}}"""
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = POST("https://graphql.anilist.co", body = jsonBody.toRequestBody(mediaType))
        val response = client.newCall(request).awaitSuccess()
        val bodyString = response.body?.string() ?: return emptyList()

        val parsed = json.parseToJsonElement(bodyString).jsonObject
        val mediaArray = parsed["data"]?.jsonObject
            ?.get("Page")?.jsonObject
            ?.get("media")?.jsonArray ?: return emptyList()

        return parseAniListMediaList(mediaArray)
    }

    private suspend fun fetchAniListTrending(): List<CandidateManga> {
        val query = """
            query {
              Page(page: 1, perPage: 30) {
                media(type: MANGA, sort: [TRENDING_DESC, SCORE_DESC]) {
                  id
                  title {
                    english
                    romaji
                  }
                  description
                  status
                  chapters
                  meanScore
                  coverImage {
                    large
                  }
                  genres
                  tags {
                    name
                    rank
                  }
                  countryOfOrigin
                }
              }
            }
        """.trimIndent()

        val jsonBody = """{"query": ${Json.encodeToString(kotlinx.serialization.serializer(), query)}}"""
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = POST("https://graphql.anilist.co", body = jsonBody.toRequestBody(mediaType))
        val response = client.newCall(request).awaitSuccess()
        val bodyString = response.body?.string() ?: return emptyList()

        val parsed = json.parseToJsonElement(bodyString).jsonObject
        val mediaArray = parsed["data"]?.jsonObject
            ?.get("Page")?.jsonObject
            ?.get("media")?.jsonArray ?: return emptyList()

        return parseAniListMediaList(mediaArray)
    }

    private fun parseAniListMediaList(mediaArray: JsonArray): List<CandidateManga> {
        val items = mutableListOf<CandidateManga>()
        for (elem in mediaArray) {
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            val titleObj = obj["title"]?.jsonObject
            val englishTitle = titleObj?.get("english")?.jsonPrimitive?.content
            val romajiTitle = titleObj?.get("romaji")?.jsonPrimitive?.content
            val title = englishTitle ?: romajiTitle ?: continue

            val altTitles = listOfNotNull(englishTitle, romajiTitle).distinct()
            val synopsis = obj["description"]?.jsonPrimitive?.content?.replace(Regex("<.*?>"), "")
            val status = obj["status"]?.jsonPrimitive?.content
            val chapters = obj["chapters"]?.jsonPrimitive?.intOrNull
            val meanScore = obj["meanScore"]?.jsonPrimitive?.doubleOrNull
            val coverUrl = obj["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.content
            val origin = obj["countryOfOrigin"]?.jsonPrimitive?.content

            val genres = obj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            val tags = obj["tags"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            } ?: emptyList()

            items.add(
                CandidateManga(
                    id = id,
                    title = title,
                    alternativeTitles = altTitles,
                    coverUrl = coverUrl,
                    synopsis = synopsis,
                    status = status,
                    chapterCount = chapters,
                    rating = meanScore,
                    genres = genres,
                    tags = tags,
                    provider = "AniList",
                    externalUrl = "https://anilist.co/manga/$id",
                    countryOfOrigin = origin,
                ),
            )
        }
        return items
    }
}
