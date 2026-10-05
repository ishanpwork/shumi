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
    val provider: String = "MangaBaka",
    val externalUrl: String? = null,
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
    private val cacheDurationMs = 12 * 60 * 60 * 1000L // 12 hours

    suspend fun fetchCandidates(
        keywords: List<String>,
        forceRefresh: Boolean = false,
    ): List<CandidateManga> = withIOContext {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedCandidates.isNotEmpty() && (now - lastFetchTimestamp) < cacheDurationMs) {
            return@withIOContext cachedCandidates
        }

        val results = mutableMapOf<String, CandidateManga>()

        // 1. Fetch from AniList Trending Korean manhwa
        try {
            val aniListCandidates = fetchAniListTrending()
            aniListCandidates.forEach { candidate ->
                results[candidate.title.lowercase().trim()] = candidate
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to fetch AniList trending candidates" }
        }

        // 2. Fetch from MangaBaka for top user taste keywords
        val searchKeywords = if (keywords.isNotEmpty()) keywords.take(4) else listOf("action", "fantasy", "regression", "system")
        for (kw in searchKeywords) {
            try {
                val bakaCandidates = fetchMangaBakaSearch(kw)
                bakaCandidates.forEach { candidate ->
                    val key = candidate.title.lowercase().trim()
                    if (!results.containsKey(key)) {
                        results[key] = candidate
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to search MangaBaka for keyword: $kw" }
            }
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

    private suspend fun fetchMangaBakaSearch(query: String): List<CandidateManga> {
        val url = "https://api.mangabaka.org/v1/series/search?q=$query&type_not=novel"
        val request = GET(url)
        val response = client.newCall(request).awaitSuccess()
        val bodyString = response.body?.string() ?: return emptyList()

        val parsed = json.parseToJsonElement(bodyString).jsonObject
        val dataArray = parsed["data"]?.jsonArray ?: return emptyList()

        val items = mutableListOf<CandidateManga>()
        for (elem in dataArray) {
            val obj = elem.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            val rating = obj["rating"]?.jsonPrimitive?.doubleOrNull

            // Titles
            val titlesObj = obj["titles"]?.jsonArray
            val titlesList = mutableListOf<String>()
            var primaryTitle: String? = null
            titlesObj?.forEach { tElem ->
                val tObj = tElem.jsonObject
                val tTitle = tObj["title"]?.jsonPrimitive?.content ?: ""
                val isPrimary = tObj["is_primary"]?.jsonPrimitive?.content == "true"
                val lang = tObj["language"]?.jsonPrimitive?.content ?: ""
                if (isPrimary && (lang == "en" || primaryTitle == null)) {
                    primaryTitle = tTitle
                }
                if (tTitle.isNotBlank()) {
                    titlesList.add(tTitle)
                }
            }
            val title = primaryTitle ?: titlesList.firstOrNull() ?: "Series #$id"

            // Cover
            val coverObj = obj["cover"]?.jsonObject?.get("x250")?.jsonObject
            val coverUrl = coverObj?.get("x1")?.jsonPrimitive?.content

            // Synopsis
            val synopsis = obj["description"]?.jsonPrimitive?.content

            // Status
            val status = obj["status"]?.jsonPrimitive?.content

            // Chapters
            val chapters = obj["total_chapters"]?.jsonPrimitive?.intOrNull

            // Genres
            val genres = obj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()

            // Tags
            val tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()

            items.add(
                CandidateManga(
                    id = id,
                    title = title,
                    alternativeTitles = titlesList,
                    coverUrl = coverUrl,
                    synopsis = synopsis,
                    status = status,
                    chapterCount = chapters,
                    rating = rating,
                    genres = genres,
                    tags = tags,
                    provider = "MangaBaka",
                    externalUrl = "https://mangabaka.org/$id",
                ),
            )
        }
        return items
    }

    private suspend fun fetchAniListTrending(): List<CandidateManga> {
        val query = """
            query {
              Page(page: 1, perPage: 30) {
                media(type: MANGA, sort: TRENDING_DESC, countryOfOrigin: "KR") {
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
                ),
            )
        }
        return items
    }
}
