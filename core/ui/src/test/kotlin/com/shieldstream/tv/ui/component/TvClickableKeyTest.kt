package com.shieldstream.tv.ui.component

import android.content.ComponentName
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvClickableKeyTest {
    private val composeRule = createComposeRule()
    private val registerActivity = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
                shadowOf(context.packageManager).addActivityIfNotPresent(
                    ComponentName(context, ComponentActivity::class.java),
                )
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerActivity).around(composeRule)

    @Test
    fun `short OK press triggers onClick and not onLongClick`() {
        var clicks = 0
        var longClicks = 0
        lateinit var hostView: android.view.View

        composeRule.setContent {
            hostView = LocalView.current
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                requester.requestFocus()
            }
            Box(
                Modifier
                    .size(100.dp)
                    .focusRequester(requester)
                    .tvClickable(
                        onLongClick = { longClicks++ },
                        onClick = { clicks++ },
                    ),
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            hostView.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0))
            hostView.dispatchKeyEvent(AndroidKeyEvent(0L, 50L, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        composeRule.waitForIdle()

        assertEquals(1, clicks)
        assertEquals(0, longClicks)
    }

    @Test
    fun `holding OK to open menu does not click newly focused menu button on repeats or release`() {
        var cardClicks = 0
        var menuOpenCount = 0
        var menuOptionClicks = 0
        lateinit var hostView: android.view.View

        composeRule.setContent {
            hostView = LocalView.current
            var menuOpen by remember { mutableStateOf(false) }
            val cardRequester = remember { FocusRequester() }
            val menuRequester = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                withFrameNanos { }
                cardRequester.requestFocus()
            }

            Column {
                Box(
                    Modifier
                        .size(100.dp)
                        .focusRequester(cardRequester)
                        .tvClickable(
                            onLongClick = {
                                menuOpenCount++
                                menuOpen = true
                            },
                            onClick = { cardClicks++ },
                        ),
                )
                if (menuOpen) {
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        menuRequester.requestFocus()
                    }
                    Box(
                        Modifier
                            .size(100.dp)
                            .focusRequester(menuRequester)
                            .tvClickable(onClick = { menuOptionClicks++ }),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. Initial OK press on card.
        composeRule.runOnUiThread {
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0),
            )
        }

        // 2. Long-press repeat arrives while user still holds OK on Fire Stick.
        composeRule.runOnUiThread {
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(
                    0L,
                    500L,
                    AndroidKeyEvent.ACTION_DOWN,
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    1,
                    0,
                    0,
                    0,
                    AndroidKeyEvent.FLAG_LONG_PRESS,
                ),
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, menuOpenCount)
        assertEquals(0, cardClicks)
        assertEquals(0, menuOptionClicks)

        // 3. Menu button now has focus while user is still holding OK (repeatCount = 2, 3) and then releases OK.
        composeRule.runOnUiThread {
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(0L, 550L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 2),
            )
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(0L, 600L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 3),
            )
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(0L, 650L, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0),
            )
        }
        composeRule.waitForIdle()

        // Neither the card nor the newly focused menu button should have been clicked.
        assertEquals(1, menuOpenCount)
        assertEquals(0, cardClicks)
        assertEquals(0, menuOptionClicks)

        // 4. Subsequent normal OK tap on the focused menu button triggers its action.
        composeRule.runOnUiThread {
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(700L, 700L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0),
            )
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(700L, 750L, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0),
            )
        }
        composeRule.waitForIdle()

        assertEquals(1, menuOptionClicks)
    }

    @Test
    fun `holding OK on item that stays focused triggers onLongClick once and ignores release`() {
        var clicks = 0
        var longClicks = 0
        lateinit var hostView: android.view.View

        composeRule.setContent {
            hostView = LocalView.current
            val requester = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                requester.requestFocus()
            }
            Box(
                Modifier
                    .size(100.dp)
                    .focusRequester(requester)
                    .tvClickable(
                        onLongClick = { longClicks++ },
                        onClick = { clicks++ },
                    ),
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(0L, 0L, AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0),
            )
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(
                    0L,
                    500L,
                    AndroidKeyEvent.ACTION_DOWN,
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    1,
                    0,
                    0,
                    0,
                    AndroidKeyEvent.FLAG_LONG_PRESS,
                ),
            )
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(
                    0L,
                    550L,
                    AndroidKeyEvent.ACTION_DOWN,
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    2,
                    0,
                    0,
                    0,
                    AndroidKeyEvent.FLAG_LONG_PRESS,
                ),
            )
            hostView.dispatchKeyEvent(
                AndroidKeyEvent(0L, 600L, AndroidKeyEvent.ACTION_UP, AndroidKeyEvent.KEYCODE_DPAD_CENTER, 0),
            )
        }
        composeRule.waitForIdle()

        assertEquals(0, clicks)
        assertEquals(1, longClicks)
    }
}

