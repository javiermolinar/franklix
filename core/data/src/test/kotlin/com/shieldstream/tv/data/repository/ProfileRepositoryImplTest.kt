package com.shieldstream.tv.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shieldstream.tv.model.AppLanguage
import com.shieldstream.tv.model.Lang
import com.shieldstream.tv.model.Profile
import com.shieldstream.tv.model.SubtitleAppearance
import com.shieldstream.tv.model.SubtitleColor
import com.shieldstream.tv.strings.Texts
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var previousLanguage: AppLanguage

    @Before
    fun clearProfiles() {
        context = ApplicationProvider.getApplicationContext()
        previousLanguage = Lang.current
        context.getSharedPreferences("profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(AppLanguage.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun restoreLanguage() {
        Lang.current = previousLanguage
    }

    @Test
    fun `language override persists and selection applies the effective language`() {
        context.getSharedPreferences(AppLanguage.PREFS, Context.MODE_PRIVATE).edit()
            .putString(AppLanguage.PREF_KEY, AppLanguage.EN.name).commit()
        val cleanup = mockk<ProfileDataCleaner>(relaxed = true)
        val repository = ProfileRepositoryImpl(context, cleanup, mockk<Texts>(relaxed = true))
        val kids = Profile(id = "kids", name = "Kids", avatar = 1, kids = true, languageOverride = AppLanguage.ES)
        repository.save(kids)

        repository.select(kids.id)
        assertEquals(AppLanguage.ES, Lang.current)

        val reloaded = ProfileRepositoryImpl(context, cleanup, mockk<Texts>(relaxed = true))
        assertEquals(AppLanguage.ES, reloaded.active.value.languageOverride)

        reloaded.select(Profile.DEFAULT_ID)
        assertEquals(AppLanguage.EN, Lang.current)

        reloaded.save(reloaded.active.value.copy(languageOverride = AppLanguage.ES))
        assertEquals(AppLanguage.ES, Lang.current)
    }

    @Test
    fun `experimental sync preference persists across repository reloads`() {
        val cleanup = mockk<ProfileDataCleaner>(relaxed = true)
        val texts = mockk<Texts>(relaxed = true)
        val repository = ProfileRepositoryImpl(context, cleanup, texts)
        val profile = Profile(id = "sync", name = "Sync", avatar = 0, experimentalSubtitleSync = false)
        repository.save(profile)
        repository.select(profile.id)

        val reloaded = ProfileRepositoryImpl(context, cleanup, texts)
        assertFalse(reloaded.active.value.experimentalSubtitleSync)
        assertTrue(Profile(id = "default", name = "Default", avatar = 0).experimentalSubtitleSync)
    }

    @Test
    fun `frame rate matching preference persists across repository reloads`() {
        val cleanup = mockk<ProfileDataCleaner>(relaxed = true)
        val texts = mockk<Texts>(relaxed = true)
        val repository = ProfileRepositoryImpl(context, cleanup, texts)
        val profile = Profile(id = "frames", name = "Frames", avatar = 0, matchContentFrameRate = false)
        repository.save(profile)
        repository.select(profile.id)

        val reloaded = ProfileRepositoryImpl(context, cleanup, texts)
        assertFalse(reloaded.active.value.matchContentFrameRate)
        assertTrue(Profile(id = "default", name = "Default", avatar = 0).matchContentFrameRate)
    }

    @Test
    fun `subtitle appearance persists across repository reloads`() {
        val cleanup = mockk<ProfileDataCleaner>(relaxed = true)
        val texts = mockk<Texts>(relaxed = true)
        val repository = ProfileRepositoryImpl(context, cleanup, texts)
        val appearance = SubtitleAppearance(
            sizePercent = 150,
            verticalPositionPercent = 20,
            textColor = SubtitleColor.YELLOW,
            backgroundColor = SubtitleColor.BLACK,
            outlineColor = SubtitleColor.TRANSPARENT,
            applyEmbeddedStyles = false,
        )
        val profile = Profile(id = "appearance", name = "Appearance", avatar = 0, subtitleAppearance = appearance)
        repository.save(profile)
        repository.select(profile.id)

        val reloaded = ProfileRepositoryImpl(context, cleanup, texts)

        assertEquals(appearance, reloaded.active.value.subtitleAppearance)
    }

    @Test
    fun `deleting a profile invokes centralized data cleanup`() {
        val cleanup = mockk<ProfileDataCleaner>(relaxed = true)
        val repository = ProfileRepositoryImpl(context, cleanup, mockk<Texts>(relaxed = true))
        val deleted = Profile(id = "deleted", name = "Deleted", avatar = 1)
        repository.save(deleted)

        repository.delete(deleted.id)

        assertFalse(repository.profiles.value.any { it.id == deleted.id })
        verify { cleanup.delete(deleted.id) }
    }
}
