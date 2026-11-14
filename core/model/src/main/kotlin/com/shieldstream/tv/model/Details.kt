package com.shieldstream.tv.model

data class CatalogDetails(
    val item: CatalogItem,
    val originalTitle: String? = null,
    val logoUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val tagline: String? = null,
    val seasons: List<Season> = emptyList(),
    val latestEpisode: Episode? = null,
    val nextEpisode: Episode? = null,
    val cast: List<CastMember> = emptyList(),
    /** Recommended minimum age from TMDB certifications; 0 is all audiences, null unknown. */
    val minimumAge: Int? = null,
)

data class CastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profileUrl: String?,
)

data class PersonDetails(
    val id: Int,
    val name: String,
    val biography: String,
    val profileUrl: String?,
    val filmography: List<CatalogItem>,
)

data class Season(
    val number: Int,
    val name: String,
    val episodeCount: Int,
    /** Premiere date (`YYYY-MM-DD`), when TMDB knows it. */
    val airDate: String? = null,
)

data class Episode(
    val season: Int,
    val number: Int,
    val title: String,
    val overview: String,
    val stillUrl: String?,
    val runtimeMinutes: Int?,
    val airDate: String? = null,
)

enum class HomeFilter { ALL, MOVIES, TV }

enum class RowStyle { POSTER, TOP10, CONTINUE, NEW_EPISODES, UP_NEXT, MY_LIST }

data class ShowAiring(
    val item: CatalogItem,
    val latestEpisode: Episode? = null,
    val nextEpisode: Episode? = null,
    val seasons: List<Season> = emptyList(),
)

data class ShowEpisodeStatus(
    val item: CatalogItem,
    val availableEpisodes: List<Episode> = emptyList(),
    val upcomingEpisode: Episode? = null,
)
