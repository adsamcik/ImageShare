package com.imageshare.app.sharing

import android.content.ComponentName
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharingTargetsRepositoryTest {
    @Test
    fun recordsSelectionsAndOrdersTopThreeByCountThenRecency() = runTest {
        var now = 1L
        val repository = repository { now++ }
        val alpha = component("alpha")
        val beta = component("beta")
        val gamma = component("gamma")
        val delta = component("delta")

        repository.recordSelection(alpha)
        repository.recordSelection(beta)
        repository.recordSelection(beta)
        repository.recordSelection(gamma)
        repository.recordSelection(delta)

        assertEquals(
            listOf(beta, delta, gamma),
            repository.observeTopTargets().first().map { it.componentName },
        )
    }

    @Test
    fun keepsOnlyTopFiftyTargets() = runTest {
        var now = 1L
        val repository = repository { now++ }
        repeat(55) { index ->
            repository.recordSelection(component("target-$index"))
        }

        val topTargets = repository.observeTopTargets(60).first()

        assertEquals(50, topTargets.size)
        assertEquals(component("target-54"), topTargets.first().componentName)
        assertEquals(component("target-5"), topTargets.last().componentName)
    }

    private fun repository(clock: () -> Long = { System.currentTimeMillis() }): DataStoreSharingTargetsRepository {
        return DataStoreSharingTargetsRepository(FakePreferencesDataStore(), clock)
    }

    private fun component(name: String) = ComponentName("com.example.$name", "com.example.$name.ShareActivity")
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
