package com.shieldstream.tv.model

/** App-wide source attributes that should be hidden and excluded from automatic playback. */
data class SourceExclusions(
    val qualities: Set<Quality> = emptySet(),
    val hdrTypes: Set<HdrType> = emptySet(),
    val audioCodecs: Set<AudioCodec> = emptySet(),
    /** Null means no size limit. Sources with unknown sizes (zero) remain eligible. */
    val maxSizeBytes: Long? = null,
) {
    val isEmpty: Boolean get() = qualities.isEmpty() && hdrTypes.isEmpty() && audioCodecs.isEmpty() && maxSizeBytes == null

    fun excludes(source: StreamSource): Boolean =
        source.quality in qualities || source.hdrType in hdrTypes || source.audioCodecs.any { it in audioCodecs } ||
            (maxSizeBytes != null && source.sizeBytes > maxSizeBytes)

    fun filter(sources: List<StreamSource>): List<StreamSource> = sources.filterNot(::excludes)
}

object SourceSizeLimits {
    const val BYTES_PER_GB = 1024L * 1024 * 1024
    val OPTIONS: List<Long?> = listOf(1, 2, 4, 8, 20, null).map { it?.let(::gigabytes) }

    fun gigabytes(value: Int): Long = value * BYTES_PER_GB
}
