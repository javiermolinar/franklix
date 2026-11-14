package com.shieldstream.tv.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueBoostControllerTest {

    @Test
    fun `enable applies conservative speech EQ and close releases it`() {
        val effect = equalizer(60_000, 910_000, 3_600_000, 14_000_000)
        val controller = controller(effectFactory = { effect })

        assertTrue(controller.setEnabled(true))

        verify { effect.setBandLevel(0, -150) }
        verify { effect.setBandLevel(1, 300) }
        verify { effect.setBandLevel(2, 300) }
        verify { effect.setBandLevel(3, 0) }
        verify { effect.setEnabled(true) }

        controller.close()
        verify { effect.setEnabled(false) }
        verify { effect.release() }
    }

    @Test
    fun `enable waits for an audio session and attaches when it changes`() {
        val listener = slot<Player.Listener>()
        val player = mockk<ExoPlayer>(relaxed = true) {
            every { audioSessionId } returns 0
            every { addListener(capture(listener)) } returns Unit
        }
        val effect = equalizer(1_000_000)
        var createdForSession: Int? = null
        val controller = DialogueBoostController(player, onUnavailable = {}) {
            createdForSession = it
            effect
        }

        assertTrue(controller.setEnabled(true))
        verify(exactly = 0) { effect.setEnabled(true) }

        listener.captured.onAudioSessionIdChanged(42)

        assertTrue(createdForSession == 42)
        verify { effect.setEnabled(true) }
    }

    @Test
    fun `unsupported output disables the request and reports failure`() {
        val effect = equalizer(1_000_000)
        every { effect.setEnabled(true) } returns AudioEffect.ERROR
        var unavailable = false
        val controller = DialogueBoostController(
            player = mockk(relaxed = true) { every { audioSessionId } returns 42 },
            onUnavailable = { unavailable = true },
            effectFactory = { effect },
        )

        assertFalse(controller.setEnabled(true))
        assertTrue(unavailable)
        verify { effect.release() }
    }

    private fun controller(effectFactory: (Int) -> Equalizer) = DialogueBoostController(
        player = mockk(relaxed = true) { every { audioSessionId } returns 42 },
        onUnavailable = {},
        effectFactory = effectFactory,
    )

    private fun equalizer(vararg centerFrequencies: Int): Equalizer = mockk(relaxed = true) {
        every { numberOfBands } returns centerFrequencies.size.toShort()
        every { bandLevelRange } returns shortArrayOf(-1_500, 1_500)
        every { getCenterFreq(any()) } answers { centerFrequencies[firstArg<Short>().toInt()] }
        every { setEnabled(true) } returns AudioEffect.SUCCESS
    }
}
