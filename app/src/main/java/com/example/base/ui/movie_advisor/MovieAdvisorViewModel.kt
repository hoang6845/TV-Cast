package com.example.base.ui.movie_advisor

import androidx.lifecycle.viewModelScope
import com.example.base.R
import com.example.base.utils.HttpClientProvider
import hoang.dqm.codebase.base.viewmodel.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class MovieAdvisorViewModel : BaseViewModel() {

    private val okHttpClient by lazy {
        HttpClientProvider.provide(context)
    }

    private val _uiState = MutableStateFlow(MovieAdvisorUiState())
    val uiState: StateFlow<MovieAdvisorUiState> = _uiState

    private val cachedShowPages = linkedMapOf<Int, List<TvmazeShow>>()

    fun selectContentType(type: MovieAdvisorContentType) {
        _uiState.update { it.copy(contentType = type) }
    }

    fun selectGenres(genres: Set<String>) {
        _uiState.update { it.copy(selectedGenres = genres) }
    }

    fun selectMoods(moods: Set<String>) {
        _uiState.update { it.copy(selectedMoods = moods) }
    }

    fun selectYearRange(range: MovieAdvisorYearRange) {
        _uiState.update { it.copy(selectedYearRange = range) }
    }

    fun selectRating(rating: MovieAdvisorRatingOption) {
        _uiState.update { it.copy(selectedRating = rating) }
    }

    fun backToEdit() {
        _uiState.update {
            it.copy(
                canFindNext = false,
                resultState = MovieAdvisorResultState.Idle
            )
        }
    }

    fun getRecommendations(description: String) {
        if (_uiState.value.resultState == MovieAdvisorResultState.Loading) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    canFindNext = false,
                    resultState = MovieAdvisorResultState.Loading
                )
            }

            try {
                val shows = fetchShows(startPage = 0, pageCount = TVMAZE_PAGE_COUNT)
                val recommendations = buildRecommendations(shows, description)

                _uiState.update {
                    it.copy(
                        canFindNext = true,
                        resultState = if (recommendations.isEmpty()) {
                            MovieAdvisorResultState.Empty
                        } else {
                            MovieAdvisorResultState.Success(recommendations)
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        canFindNext = false,
                        resultState = MovieAdvisorResultState.Error(
                            e.message ?: getString(R.string.text_something_went_wrong)
                        )
                    )
                }
            }
        }
    }

    fun findNext(description: String) {
        val current = _uiState.value
        if (!current.canFindNext || current.resultState == MovieAdvisorResultState.Loading) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    canFindNext = false,
                    resultState = MovieAdvisorResultState.Loading
                )
            }

            try {
                val shows = fetchShows(
                    startPage = TVMAZE_PAGE_COUNT,
                    pageCount = TVMAZE_PAGE_COUNT
                )
                val recommendations = buildRecommendations(shows, description)

                _uiState.update {
                    it.copy(
                        canFindNext = false,
                        resultState = if (recommendations.isEmpty()) {
                            MovieAdvisorResultState.Empty
                        } else {
                            MovieAdvisorResultState.Success(recommendations)
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        canFindNext = false,
                        resultState = MovieAdvisorResultState.Error(
                            e.message ?: getString(R.string.text_something_went_wrong)
                        )
                    )
                }
            }
        }
    }

    private fun fetchShows(startPage: Int, pageCount: Int): List<TvmazeShow> {
        val shows = mutableListOf<TvmazeShow>()
        for (page in startPage until startPage + pageCount) {
            val cached = cachedShowPages[page]
            if (cached != null) {
                shows += cached
            } else {
                val request = Request.Builder()
                    .url("$TVMAZE_BASE_URL/shows?page=$page")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (response.code == 404) {
                    response.close()
                    break
                }
                response.use {
                    if (!response.isSuccessful) error("Unable to load movie data")
                    val body = response.body?.string().orEmpty()
                    val pageShows = parseShows(JSONArray(body))
                    cachedShowPages[page] = pageShows
                    shows += pageShows
                }
            }
        }
        return shows.distinctBy { it.id }
    }

    private fun parseShows(jsonArray: JSONArray): List<TvmazeShow> {
        return buildList {
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(index) ?: continue
                add(
                    TvmazeShow(
                        id = item.optInt("id"),
                        name = item.optString("name"),
                        type = item.optString("type"),
                        genres = item.optJSONArray("genres").toStringList(),
                        premiered = item.optString("premiered").takeIf { it.isNotBlank() && it != "null" },
                        averageRuntime = item.optNullableInt("averageRuntime")
                            ?: item.optNullableInt("runtime"),
                        rating = item.optJSONObject("rating")?.optNullableDouble("average"),
                        weight = item.optInt("weight", 0),
                        summary = stripHtml(item.optString("summary")),
                        imageUrl = item.optJSONObject("image")?.optString("medium")
                            ?.takeIf { it.isNotBlank() },
                        imdbId = item.optJSONObject("externals")?.optString("imdb")
                            ?.takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }
    }

    private fun buildRecommendations(
        shows: List<TvmazeShow>,
        description: String
    ): List<MovieRecommendation> {
        val state = _uiState.value
        val descriptionGenres = genresFromDescription(description)
        val avoidedGenres = avoidedGenresFromDescription(description)
        val targetMoodGenres = state.selectedMoods
            .flatMap { MovieAdvisorDefaults.moodGenres[it].orEmpty() }
            .toSet()
        val desiredGenres = state.selectedGenres + targetMoodGenres + descriptionGenres

        val hardFiltered = shows
            .asSequence()
            .filter { it.name.isNotBlank() }
            .filter { it.matchesContentType(state.contentType) }
            .filter { it.matchesYear(state.selectedYearRange) }
            .filter { it.matchesRating(state.selectedRating) }
            .filter { show ->
                state.selectedGenres.isEmpty() ||
                    show.genres.any { genre ->
                        state.selectedGenres.any { it.equals(genre, ignoreCase = true) }
                    }
            }
            .filter { show ->
                avoidedGenres.none { avoided ->
                    show.genres.any { it.equals(avoided, ignoreCase = true) }
                }
            }
            .toList()

        return hardFiltered
            .map { show ->
                val genreMatches = show.matchCount(state.selectedGenres)
                val moodMatches = show.matchCount(targetMoodGenres)
                val descriptionMatches = show.matchCount(descriptionGenres)
                val ratingScore = ((show.rating ?: 0.0) / 10.0).coerceIn(0.0, 1.0)
                val popularityScore = (show.weight / 100.0).coerceIn(0.0, 1.0)
                val yearScore = show.releaseYear?.let { year ->
                    when {
                        year >= 2020 -> 1.0
                        year >= 2010 -> 0.82
                        year >= 2000 -> 0.68
                        else -> 0.5
                    }
                } ?: 0.4

                val genreScore = if (state.selectedGenres.isEmpty()) {
                    0.65
                } else {
                    genreMatches.toDouble() / state.selectedGenres.size.coerceAtLeast(1)
                }
                val moodScore = if (targetMoodGenres.isEmpty()) {
                    0.55
                } else {
                    moodMatches.toDouble() / targetMoodGenres.size.coerceAtLeast(1)
                }
                val descriptionScore = if (descriptionGenres.isEmpty()) {
                    0.5
                } else {
                    descriptionMatches.toDouble() / descriptionGenres.size.coerceAtLeast(1)
                }

                val score = genreScore * 35.0 +
                    moodScore * 20.0 +
                    ratingScore * 20.0 +
                    descriptionScore * 10.0 +
                    popularityScore * 10.0 +
                    yearScore * 5.0
                val normalized = score.coerceIn(0.0, 100.0).roundToInt()

                MovieRecommendation(
                    show = show,
                    matchPercent = normalized,
                    reason = buildReason(show, desiredGenres),
                    score = score
                )
            }
            .sortedWith(
                compareByDescending<MovieRecommendation> { it.score }
                    .thenByDescending { it.show.rating ?: 0.0 }
                    .thenByDescending { it.show.weight }
            )
            .take(RECOMMENDATION_LIMIT)
    }

    private fun TvmazeShow.matchesContentType(type: MovieAdvisorContentType): Boolean {
        return when (type) {
            MovieAdvisorContentType.ALL -> true
            MovieAdvisorContentType.MOVIES -> isMovieLike
            MovieAdvisorContentType.SERIES -> !isMovieLike
        }
    }

    private fun TvmazeShow.matchesYear(range: MovieAdvisorYearRange): Boolean {
        val year = releaseYear ?: return range.startYear == null && range.endYear == null
        val afterStart = range.startYear?.let { year >= it } ?: true
        val beforeEnd = range.endYear?.let { year <= it } ?: true
        return afterStart && beforeEnd
    }

    private fun TvmazeShow.matchesRating(option: MovieAdvisorRatingOption): Boolean {
        val minimum = option.minimum ?: return true
        return (rating ?: 0.0) >= minimum
    }

    private fun TvmazeShow.matchCount(targetGenres: Set<String>): Int {
        return genres.count { genre ->
            targetGenres.any { it.equals(genre, ignoreCase = true) }
        }
    }

    private fun genresFromDescription(description: String): Set<String> {
        val text = description.lowercase()
        val genres = mutableSetOf<String>()
        if (text.containsAny("family", "kids", "children", "gia dinh", "tre em")) {
            genres += listOf("Family", "Animation")
        }
        if (text.containsAny("funny", "comedy", "vui", "hai huoc")) genres += "Comedy"
        if (text.containsAny("scary", "horror", "dang so", "kinh di")) genres += "Horror"
        if (text.containsAny("gentle", "light", "nhe nhang")) genres += listOf("Romance", "Family")
        if (text.containsAny("touching", "emotional", "cam dong")) genres += "Drama"
        if (text.containsAny("mystery", "mysterious", "bi an")) genres += listOf("Mystery", "Thriller")
        if (text.containsAny("action", "hanh dong")) genres += "Action"
        return genres
    }

    private fun avoidedGenresFromDescription(description: String): Set<String> {
        val text = description.lowercase()
        return if (text.containsAny("no violence", "non violent", "khong bao luc")) {
            setOf("Action", "War", "Crime")
        } else {
            emptySet()
        }
    }

    private fun buildReason(show: TvmazeShow, desiredGenres: Set<String>): String {
        val matchedGenres = show.genres.filter { genre ->
            desiredGenres.any { it.equals(genre, ignoreCase = true) }
        }

        return when {
            matchedGenres.isNotEmpty() && show.rating != null ->
                "Matches ${matchedGenres.joinToString(", ")} and has a ${show.rating} rating."

            matchedGenres.isNotEmpty() ->
                "Matches ${matchedGenres.joinToString(", ")} from your filters."

            show.rating != null ->
                "Strong TVmaze rating (${show.rating}) with similar recommendation signals."

            else ->
                "Selected because it fits the broad filters and popularity signals."
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        return if (has(name) && !isNull(name)) optDouble(name) else null
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it) }
    }

    private fun stripHtml(value: String): String {
        return value
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    companion object {
        private const val TVMAZE_BASE_URL = "https://api.tvmaze.com"
        private const val TVMAZE_PAGE_COUNT = 6
        private const val RECOMMENDATION_LIMIT = 10
    }
}
