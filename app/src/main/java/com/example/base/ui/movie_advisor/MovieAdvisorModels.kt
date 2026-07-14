package com.example.base.ui.movie_advisor

enum class MovieAdvisorContentType {
    ALL,
    MOVIES,
    SERIES
}

enum class MovieAdvisorFilterType {
    GENRES,
    MOODS,
    RELEASE_YEAR,
    RATING
}

data class MovieAdvisorYearRange(
    val label: String,
    val startYear: Int? = null,
    val endYear: Int? = null
)

data class MovieAdvisorRatingOption(
    val label: String,
    val minimum: Double? = null
)

data class TvmazeShow(
    val id: Int,
    val name: String,
    val type: String,
    val genres: List<String>,
    val premiered: String?,
    val averageRuntime: Int?,
    val rating: Double?,
    val weight: Int,
    val summary: String,
    val imageUrl: String?,
    val imdbId: String?
) {
    val releaseYear: Int?
        get() = premiered?.take(4)?.toIntOrNull()

    val isMovieLike: Boolean
        get() = type.equals("Movie", ignoreCase = true) ||
            (type.equals("Scripted", ignoreCase = true) && (averageRuntime ?: 0) >= 70)
}

data class MovieRecommendation(
    val show: TvmazeShow,
    val matchPercent: Int,
    val reason: String,
    val score: Double
)

sealed interface MovieAdvisorResultState {
    data object Idle : MovieAdvisorResultState
    data object Loading : MovieAdvisorResultState
    data class Success(val items: List<MovieRecommendation>) : MovieAdvisorResultState
    data object Empty : MovieAdvisorResultState
    data class Error(val message: String) : MovieAdvisorResultState
}

data class MovieAdvisorUiState(
    val contentType: MovieAdvisorContentType = MovieAdvisorContentType.ALL,
    val selectedGenres: Set<String> = emptySet(),
    val selectedMoods: Set<String> = emptySet(),
    val selectedYearRange: MovieAdvisorYearRange = MovieAdvisorDefaults.yearRanges.first(),
    val selectedRating: MovieAdvisorRatingOption = MovieAdvisorDefaults.ratingOptions.first(),
    val canFindNext: Boolean = false,
    val resultState: MovieAdvisorResultState = MovieAdvisorResultState.Idle
)

object MovieAdvisorDefaults {
    val genres = listOf(
        "Action",
        "Adventure",
        "Animation",
        "Comedy",
        "Crime",
        "Documentary",
        "Drama",
        "Family",
        "Fantasy",
        "History",
        "Horror",
        "Music",
        "Mystery",
        "Romance",
        "Science-Fiction",
        "Thriller"
    )

    val moods = listOf(
        "Adventurous",
        "Chaotic",
        "Comedic",
        "Dark",
        "Dramatic",
        "Eerie",
        "Gritty",
        "Heart Warming",
        "Hopeful",
        "Inspirational",
        "Intense",
        "Lighthearted"
    )

    val yearRanges = listOf(
        MovieAdvisorYearRange("All Times"),
        MovieAdvisorYearRange("2020-Present", 2020, null),
        MovieAdvisorYearRange("2015-2020", 2015, 2020),
        MovieAdvisorYearRange("2010-2015", 2010, 2015),
        MovieAdvisorYearRange("2005-2010", 2005, 2010),
        MovieAdvisorYearRange("2000-2005", 2000, 2005),
        MovieAdvisorYearRange("1995-2000", 1995, 2000),
        MovieAdvisorYearRange("1990-1995", 1990, 1995),
        MovieAdvisorYearRange("Before 1990", null, 1989)
    )

    val ratingOptions = listOf(
        MovieAdvisorRatingOption("All Ratings"),
        MovieAdvisorRatingOption("9.0+", 9.0),
        MovieAdvisorRatingOption("8.0+", 8.0),
        MovieAdvisorRatingOption("7.0+", 7.0),
        MovieAdvisorRatingOption("6.0+", 6.0)
    )

    val moodGenres = mapOf(
        "Adventurous" to setOf("Adventure", "Action", "Fantasy"),
        "Chaotic" to setOf("Action", "Comedy", "Crime"),
        "Comedic" to setOf("Comedy"),
        "Dark" to setOf("Thriller", "Crime", "Horror"),
        "Dramatic" to setOf("Drama"),
        "Eerie" to setOf("Horror", "Mystery", "Thriller"),
        "Gritty" to setOf("Crime", "Drama", "Thriller"),
        "Heart Warming" to setOf("Family", "Romance", "Drama"),
        "Hopeful" to setOf("Drama", "Family"),
        "Inspirational" to setOf("Drama", "History"),
        "Intense" to setOf("Action", "Thriller", "Crime"),
        "Lighthearted" to setOf("Comedy", "Family", "Romance")
    )
}
