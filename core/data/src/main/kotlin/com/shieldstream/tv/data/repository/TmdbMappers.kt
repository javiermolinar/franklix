package com.shieldstream.tv.data.repository

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.CastMember
import com.shieldstream.tv.model.MediaType
import com.shieldstream.tv.model.PersonDetails
import com.shieldstream.tv.network.dto.TmdbCastDto
import com.shieldstream.tv.network.dto.TmdbImageDto
import com.shieldstream.tv.network.dto.TmdbImagesDto
import com.shieldstream.tv.network.dto.TmdbMovieDto
import com.shieldstream.tv.network.dto.TmdbPersonDto
import com.shieldstream.tv.network.dto.TmdbSearchResultDto
import com.shieldstream.tv.network.dto.TmdbTvDto

internal object TmdbImages {
    private const val ROOT = "https://image.tmdb.org/t/p"

    fun poster(path: String) = "$ROOT/w342$path"
    fun backdrop(path: String) = "$ROOT/w1280$path"
    fun still(path: String) = "$ROOT/w300$path"
    fun profile(path: String) = "$ROOT/w185$path"

    fun logo(images: TmdbImagesDto?, languageCode: String): String? {
        val best = images?.logos.orEmpty()
            .filter { it.file_path.isNotBlank() && !it.file_path.endsWith(".svg") }
            .sortedWith(
                compareBy<TmdbImageDto> {
                    when (it.iso_639_1) {
                        languageCode -> 0
                        "en" -> 1
                        null -> 2
                        else -> 3
                    }
                }.thenByDescending { it.vote_average },
            )
            .firstOrNull()
        return best?.let { "$ROOT/w500${it.file_path}" }
    }
}

internal fun TmdbCastDto.toCastMember() = CastMember(
    id = id,
    name = name,
    character = character,
    profileUrl = profile_path?.let(TmdbImages::profile),
)

internal fun TmdbPersonDto.toPersonDetails(): PersonDetails {
    val filmography = combined_credits?.cast.orEmpty()
        .mapNotNull { it.toCatalogItemOrNull() }
        .distinctBy { it.key }
        .sortedWith(compareByDescending<CatalogItem> { it.year ?: 0 }.thenBy { it.title })
        .take(MAX_FILMOGRAPHY_ITEMS)
    return PersonDetails(
        id = id,
        name = name,
        biography = biography,
        profileUrl = profile_path?.let(TmdbImages::profile),
        filmography = filmography,
    )
}

private const val MAX_FILMOGRAPHY_ITEMS = 80

internal fun TmdbMovieDto.toCatalogItem() = CatalogItem(
    id = id.toString(),
    imdbId = null,
    type = MediaType.MOVIE,
    title = title,
    overview = overview,
    posterUrl = poster_path?.let(TmdbImages::poster),
    backdropUrl = backdrop_path?.let(TmdbImages::backdrop),
    year = release_date?.take(4)?.toIntOrNull(),
    rating = vote_average.toFloat(),
    releaseDate = release_date,
)

internal fun TmdbTvDto.toCatalogItem() = CatalogItem(
    id = id.toString(),
    imdbId = null,
    type = MediaType.TV,
    title = name,
    overview = overview,
    posterUrl = poster_path?.let(TmdbImages::poster),
    backdropUrl = backdrop_path?.let(TmdbImages::backdrop),
    year = first_air_date?.take(4)?.toIntOrNull(),
    rating = vote_average.toFloat(),
    releaseDate = first_air_date,
)

internal fun TmdbSearchResultDto.toCatalogItemOrNull(): CatalogItem? {
    val type = when (media_type) {
        "movie" -> MediaType.MOVIE
        "tv" -> MediaType.TV
        else -> return null
    }
    return CatalogItem(
        id = id.toString(),
        imdbId = null,
        type = type,
        title = title ?: name ?: "",
        overview = overview,
        posterUrl = poster_path?.let(TmdbImages::poster),
        backdropUrl = backdrop_path?.let(TmdbImages::backdrop),
        year = (release_date ?: first_air_date)?.take(4)?.toIntOrNull(),
        rating = vote_average.toFloat(),
        releaseDate = release_date ?: first_air_date,
    )
}
