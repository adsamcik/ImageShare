package com.imageshare.core.io

import android.content.ContentResolver
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersistableUriRegistryTest {
    @Test
    fun addEmitsNewUriAtFront() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver)
        val uri = Uri.parse("content://images/one")

        registry.add(uri)

        assertEquals(listOf(uri), registry.observe().first())
        assertEquals(listOf(uri), resolver.taken)
    }

    @Test
    fun duplicateAddMovesUriToFrontWithoutGrowingList() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver)
        val first = Uri.parse("content://images/one")
        val second = Uri.parse("content://images/two")

        registry.add(first)
        registry.add(second)
        registry.add(first)

        assertEquals(listOf(first, second), registry.observe().first())
    }

    @Test
    fun maxEntriesDropsOldestAndReleasesGrant() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver, maxEntries = 3)
        val uris = (1..4).map { Uri.parse("content://images/$it") }

        uris.forEach { registry.add(it) }

        assertEquals(listOf(uris[3], uris[2], uris[1]), registry.observe().first())
        assertEquals(listOf(uris[0]), resolver.released)
    }

    @Test
    fun removeReleasesGrantAndRemovesEntry() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver)
        val first = Uri.parse("content://images/one")
        val second = Uri.parse("content://images/two")
        registry.add(first)
        registry.add(second)

        registry.remove(first)

        assertEquals(listOf(second), registry.observe().first())
        assertEquals(first, resolver.released.last())
    }

    @Test
    fun reconcileDropsEntriesWhoseSystemGrantIsGone() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver)
        val first = Uri.parse("content://images/one")
        val second = Uri.parse("content://images/two")
        val third = Uri.parse("content://images/three")
        registry.add(first)
        registry.add(second)
        registry.add(third)
        resolver.persisted.remove(third)

        val removed = registry.reconcile()

        assertEquals(1, removed)
        assertEquals(listOf(second, first), registry.observe().first())
    }

    private fun registry(
        resolver: FakeContentResolver,
        maxEntries: Int = PersistableUriRegistry.DEFAULT_MAX_ENTRIES,
    ): PersistableUriRegistry {
        val dataStore = InMemoryPreferencesDataStore()
        return PersistableUriRegistry(dataStore, resolver, maxEntries)
    }
}

private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    override val data = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}

private class FakeContentResolver : ContentResolver(null) {
    val taken = mutableListOf<Uri>()
    val released = mutableListOf<Uri>()
    val persisted = linkedSetOf<Uri>()

    override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
        check(modeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        taken += uri
        persisted += uri
    }

    override fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) {
        released += uri
        persisted -= uri
    }

    override fun getPersistedUriPermissions(): List<UriPermission> =
        persisted.map { uri -> uriPermission(uri) }

    private fun uriPermission(uri: Uri): UriPermission {
        val constructor = UriPermission::class.java.getDeclaredConstructor(
            Uri::class.java,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        constructor.isAccessible = true
        return constructor.newInstance(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, 0L)
    }
}
