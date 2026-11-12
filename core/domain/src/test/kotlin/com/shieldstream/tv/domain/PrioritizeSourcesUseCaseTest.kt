package com.shieldstream.tv.domain

import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.Quality
import com.shieldstream.tv.model.StreamSource
import com.shieldstream.tv.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrioritizeSourcesUseCaseTest {
    private val prioritize = PrioritizeSourcesUseCase()

    @Test
    fun `hardware AV1 is not penalized or labelled as CPU decoding`() {
        val av1 = source(id = "av1", videoCodec = VideoCodec.AV1, softwareDecode = true)
        val hevc = source(id = "hevc", videoCodec = VideoCodec.HEVC)

        val result = prioritize(
            listOf(av1, hevc),
            supportedHdr = setOf(HdrType.SDR),
            hardwareVideoCodecs = setOf(VideoCodec.AV1, VideoCodec.HEVC),
        )

        assertEquals("av1", result.first().id)
        assertFalse(result.first().softwareDecode)
    }

    @Test
    fun `software AV1 remains behind an equivalent hardware source`() {
        val av1 = source(id = "av1", videoCodec = VideoCodec.AV1, softwareDecode = true)
        val hevc = source(id = "hevc", videoCodec = VideoCodec.HEVC)

        val result = prioritize(
            listOf(av1, hevc),
            supportedHdr = setOf(HdrType.SDR),
            hardwareVideoCodecs = setOf(VideoCodec.HEVC),
        )

        assertEquals("hevc", result.first().id)
        assertTrue(result.last().softwareDecode)
    }

    @Test
    fun `4K AV1 requires a hardware decoder`() {
        val av1 = source(
            id = "av1-4k",
            quality = Quality.UHD_2160,
            videoCodec = VideoCodec.AV1,
            softwareDecode = true,
        )
        val hevc = source(id = "hevc-4k", quality = Quality.UHD_2160, videoCodec = VideoCodec.HEVC)

        val softwareOnly = prioritize(
            listOf(av1, hevc),
            supportedHdr = setOf(HdrType.SDR),
            hardwareVideoCodecs = setOf(VideoCodec.HEVC),
        )
        val hardwareAv1 = prioritize(
            listOf(av1, hevc),
            supportedHdr = setOf(HdrType.SDR),
            hardwareVideoCodecs = setOf(VideoCodec.HEVC, VideoCodec.AV1),
        )

        assertEquals(listOf("hevc-4k"), softwareOnly.map { it.id })
        assertEquals(setOf("av1-4k", "hevc-4k"), hardwareAv1.map { it.id }.toSet())
    }

    @Test
    fun `upscaled 1080p DS4K is not mistaken for AV1 4K`() {
        val av1 = source(
            id = "av1-upscaled",
            title = "Movie.1080p.DS4K.AV1",
            quality = Quality.UHD_2160,
            videoCodec = VideoCodec.AV1,
            softwareDecode = true,
        )

        val result = prioritize(
            listOf(av1),
            supportedHdr = setOf(HdrType.SDR),
            hardwareVideoCodecs = emptySet(),
        )

        assertEquals(listOf("av1-upscaled"), result.map { it.id })
    }

    @Test
    fun `Spanish-first puts advertised Spanish audio ahead of other languages and cache status`() {
        val cached = source(id = "cached", isCached = true, seeders = 100, languages = listOf("EN"))
        val spanish = source(
            id = "spanish",
            title = "Movie.Castellano.1080p",
            isCached = false,
            seeders = 1,
            languages = listOf("ES"),
        )

        val spanishFirst = prioritize(
            listOf(cached, spanish),
            supportedHdr = setOf(HdrType.SDR),
            preferSpanish = true,
        )
        val languageNeutral = prioritize(
            listOf(cached, spanish),
            supportedHdr = setOf(HdrType.SDR),
            preferSpanish = false,
        )

        assertEquals(listOf("spanish", "cached"), spanishFirst.map { it.id })
        assertEquals(listOf("cached", "spanish"), languageNeutral.map { it.id })
    }

    @Test
    fun `any advertised language outranks an unspecified source without Spanish-first`() {
        val multilingual = source(
            id = "multilingual",
            isCached = false,
            languages = listOf("EN", "RU"),
        )
        val unspecified = source(id = "unspecified", isCached = true)

        val result = prioritize(
            listOf(unspecified, multilingual),
            supportedHdr = setOf(HdrType.SDR),
            preferSpanish = false,
        )

        assertEquals(listOf("multilingual", "unspecified"), result.map { it.id })
    }

    @Test
    fun `resolution outranks seed count within the same availability tier`() {
        val seeded = source(id = "seeded", quality = Quality.HD_720, seeders = 100)
        val higherQuality = source(id = "higher-quality", quality = Quality.UHD_2160, seeders = 1)

        val result = prioritize(
            listOf(higherQuality, seeded),
            supportedHdr = setOf(HdrType.SDR),
        )

        assertEquals(listOf("higher-quality", "seeded"), result.map { it.id })
    }

    @Test
    fun `non-cached zero-seed torrent is demoted below seeded lower-quality source`() {
        val zeroSeed4K = source(
            id = "zero-seed-4k",
            quality = Quality.UHD_2160,
            seeders = 0,
            isCached = false,
        )
        val seeded720 = source(
            id = "seeded-720",
            quality = Quality.HD_720,
            seeders = 2,
            isCached = false,
        )

        val result = prioritize(
            listOf(zeroSeed4K, seeded720),
            supportedHdr = setOf(HdrType.SDR),
        )

        assertEquals(listOf("seeded-720", "zero-seed-4k"), result.map { it.id })
    }

    @Test
    fun `cached and direct sources do not depend on seeders`() {
        val cached = source(id = "cached", seeders = 0, isCached = true, quality = Quality.UHD_2160)
        val direct = source(id = "direct", seeders = 0, isCached = false, directUrl = "https://video.test/direct")
        val seeded = source(id = "seeded", seeders = 100, isCached = false)

        val result = prioritize(
            listOf(seeded, direct, cached),
            supportedHdr = setOf(HdrType.SDR),
        )

        assertEquals(listOf("cached", "direct", "seeded"), result.map { it.id })
    }

    @Test
    fun `unknown seeder count stays above non-cached zero-seed torrent`() {
        val unknown = source(id = "unknown", seeders = null, isCached = false)
        val zero = source(id = "zero", seeders = 0, isCached = false)
        val seeded = source(id = "seeded", seeders = 1, isCached = false)

        val result = prioritize(
            listOf(zero, unknown, seeded),
            supportedHdr = setOf(HdrType.SDR),
        )

        assertEquals(listOf("seeded", "unknown", "zero"), result.map { it.id })
    }

    private fun source(
        id: String,
        title: String = id,
        quality: Quality = Quality.FHD_1080,
        videoCodec: VideoCodec? = null,
        softwareDecode: Boolean = false,
        seeders: Int? = 10,
        isCached: Boolean = true,
        languages: List<String> = emptyList(),
        directUrl: String? = null,
    ) = StreamSource(
        id = id,
        title = title,
        infoHash = id.padEnd(40, '0'),
        fileIdx = null,
        magnet = "magnet:?xt=urn:btih:$id",
        quality = quality,
        hdrType = HdrType.SDR,
        sizeBytes = 1_000_000_000,
        seeders = seeders,
        isCached = isCached,
        languages = languages,
        directUrl = directUrl,
        videoCodec = videoCodec,
        softwareDecode = softwareDecode,
    )
}
