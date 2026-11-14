package com.shieldstream.tv.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Applies a mild speech EQ to the current ExoPlayer audio session. */
internal class DialogueBoostController(
    private val player: ExoPlayer,
    private val onUnavailable: () -> Unit,
    private val effectFactory: (audioSessionId: Int) -> Equalizer = { Equalizer(0, it) },
) {
    private var requested = false
    private var closed = false
    private var equalizer: Equalizer? = null

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (requested) attach(audioSessionId)
        }
    }

    init {
        player.addListener(listener)
    }

    /** Returns false when this audio output cannot provide an equalizer. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (closed) return false
        requested = enabled
        if (!enabled) {
            releaseEffect()
            return true
        }
        return attach(player.audioSessionId)
    }

    fun close() {
        if (closed) return
        closed = true
        requested = false
        player.removeListener(listener)
        releaseEffect()
    }

    private fun attach(audioSessionId: Int): Boolean {
        releaseEffect()
        // ExoPlayer may not have opened its audio track yet. The session listener will retry.
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return true

        return try {
            val effect = effectFactory(audioSessionId)
            equalizer = effect
            configure(effect)
            true
        } catch (error: RuntimeException) {
            requested = false
            releaseEffect()
            Log.w(TAG, "Dialogue boost is unavailable for this audio output", error)
            onUnavailable()
            false
        }
    }

    private fun configure(effect: Equalizer) {
        val range = effect.bandLevelRange
        val minimum = range[0].toInt()
        val maximum = range[1].toInt()
        for (band in 0 until effect.numberOfBands) {
            val bandIndex = band.toShort()
            val centerHz = effect.getCenterFreq(bandIndex) / 1_000
            val targetMb = when (centerHz) {
                in Int.MIN_VALUE..120 -> -150 // Reduce low-frequency rumble slightly.
                in 700..5_000 -> 300 // Lift the frequencies that carry speech intelligibility.
                else -> 0
            }
            effect.setBandLevel(bandIndex, targetMb.coerceIn(minimum, maximum).toShort())
        }
        check(effect.setEnabled(true) == AudioEffect.SUCCESS) { "Equalizer could not be enabled" }
    }

    private fun releaseEffect() {
        equalizer?.let { effect ->
            runCatching { effect.setEnabled(false) }
            runCatching { effect.release() }
        }
        equalizer = null
    }

    private companion object {
        const val TAG = "DialogueBoost"
    }
}
