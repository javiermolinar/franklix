package com.shieldstream.tv.ui.component

import com.shieldstream.tv.model.Lang
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Date formatters for one interface language. Building a [SimpleDateFormat] loads locale data and
 * is expensive on TV boxes; poster captions format a date for every card composed while scrolling.
 */
private class EpisodeDateFormats(val locale: Locale, val english: Boolean) {
    val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    val short = SimpleDateFormat(if (english) "MMM d" else "d MMM", locale)
    val withYear = SimpleDateFormat(if (english) "MMM d, yyyy" else "d MMM yyyy", locale)
}

// SimpleDateFormat is not thread-safe, so each thread keeps its own set.
private val episodeDateFormats = ThreadLocal<EpisodeDateFormats>()

fun formatEpisodeDate(isoDate: String?, includeYear: Boolean = false): String {
    if (isoDate.isNullOrBlank()) return ""
    val locale = Lang.locale
    val english = Lang.isEnglish
    val formats = episodeDateFormats.get()?.takeIf { it.locale == locale && it.english == english }
        ?: EpisodeDateFormats(locale, english).also(episodeDateFormats::set)
    return runCatching {
        (if (includeYear) formats.withYear else formats.short).format(formats.parser.parse(isoDate)!!)
    }.getOrDefault(isoDate)
}
