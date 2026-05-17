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
            repeat((index % 10) + 1) {
                repository.recordSelection(component("target-$index"))
            }
        }

        val topTargets = repository.observeTopTargets(60).first()
        val components = topTargets.map { it.componentName }

        assertEquals(50, topTargets.size)
        assertEquals(component("target-49"), topTargets.first().componentName)
        assertEquals(component("target-10"), topTargets.last().componentName)
        assertEquals((5..54).map { component("target-$it") }.toSet(), components.toSet())
        assertEquals(10, topTargets.first().count)
        assertEquals(1, topTargets.last().count)
    }

    @Test
    fun recordingNewTargetAtCapEvictsOldestNotNewest() = runTest {
        val t0 = 1_000L
        val t1 = 2_000L
        var now = t0
        val repository = repository { now }
        val originalTargets = (0 until 50).map { component("target-$it") }
        val newTarget = component("target-50")

        originalTargets.forEach { target ->
            repeat(10) {
                repository.recordSelection(target)
            }
        }
        now = t1
        repository.recordSelection(newTarget)

        val topTargets = repository.observeTopTargets(60).first()
        val components = topTargets.map { it.componentName }

        assertEquals(50, topTargets.size)
        assertEquals(true, newTarget in components)
        assertEquals(49, originalTargets.count { it in components })
        assertEquals(1, originalTargets.count { it !in components })
        assertEquals(t1, topTargets.single { it.componentName == newTarget }.lastUsedAt)
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
