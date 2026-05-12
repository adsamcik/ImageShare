package com.imageshare.feature.preset

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PresetRepositoryTest {
    @Test
    fun defaultPresetsValidate() {
        assertEquals(5, DefaultPresets.ALL.size)
        DefaultPresets.ALL.forEach { preset ->
            assertNotNull(preset)
            assertTrue(preset.builtIn)
        }
    }

    @Test
    fun defaultPresetIdsAreUniqueAndExpected() {
        val ids = DefaultPresets.ALL.map(Preset::id)

        assertEquals(ids.toSet().size, ids.size)
        assertEquals(
            listOf("small-file", "best-quality", "social-upload", "email", "custom"),
            ids,
        )
    }

    @Test
    fun qualityBelowRangeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            Preset(
                id = "bad-quality-low",
                displayName = "Bad quality low",
                format = OutputFormat.JPEG,
                resize = ResizeMode.Original,
                quality = 0,
                metadata = MetadataPolicy.StripAll,
                alphaFallback = AlphaFallback.Error,
            )
        }
    }

    @Test
    fun qualityAboveRangeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            Preset(
                id = "bad-quality-high",
                displayName = "Bad quality high",
                format = OutputFormat.JPEG,
                resize = ResizeMode.Original,
                quality = 101,
                metadata = MetadataPolicy.StripAll,
                alphaFallback = AlphaFallback.Error,
            )
        }
    }

    @Test
    fun longEdgeBelowMinimumThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ResizeMode.LongEdge(15)
        }
    }

    @Test
    fun setUnknownDefaultPresetThrows() = runTest {
        val repository = DataStorePresetRepository(FakePreferencesDataStore())

        try {
            repository.setDefaultPresetId("nope")
            fail("Expected IllegalArgumentException for unknown preset id.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun setDefaultPresetRoundTripsThroughDataStore() = runTest {
        val repository = DataStorePresetRepository(FakePreferencesDataStore())

        repository.setDefaultPresetId("best-quality")

        assertEquals("best-quality", repository.observeDefaultPresetId().first())
    }

    @Test
    fun unsetDefaultPresetEmitsSmallFile() = runTest {
        val repository = DataStorePresetRepository(FakePreferencesDataStore())

        assertEquals("small-file", repository.observeDefaultPresetId().first())
    }
}

private class FakePreferencesDataStore(
    initialPreferences: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initialPreferences)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
