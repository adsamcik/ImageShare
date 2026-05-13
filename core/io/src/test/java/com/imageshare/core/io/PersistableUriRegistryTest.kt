package com.imageshare.core.io

import android.content.ContentResolver
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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

        registry.add(uri, displayName = "one.jpg")

        assertEquals(listOf(RecentUriEntry(uri, "one.jpg")), registry.observe().first())
        assertEquals(listOf(uri), resolver.taken)
    }

    @Test
    fun duplicateAddMovesUriToFrontWithoutGrowingList() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver)
        val first = Uri.parse("content://images/one")
        val second = Uri.parse("content://images/two")

        registry.add(first, displayName = "one.jpg")
        registry.add(second, displayName = "two.jpg")
        registry.add(first, displayName = "one-updated.jpg")

        assertEquals(
            listOf(RecentUriEntry(first, "one-updated.jpg"), RecentUriEntry(second, "two.jpg")),
            registry.observe().first(),
        )
        assertEquals(emptyList<Uri>(), resolver.released)
    }

    @Test
    fun maxEntriesDropsOldestAndReleasesGrant() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver, maxEntries = 3)
        val uris = (1..4).map { Uri.parse("content://images/$it") }

        uris.forEachIndexed { index, uri -> registry.add(uri, displayName = "image-$index.jpg") }

        assertEquals(
            listOf(
                RecentUriEntry(uris[3], "image-3.jpg"),
                RecentUriEntry(uris[2], "image-2.jpg"),
                RecentUriEntry(uris[1], "image-1.jpg"),
            ),
            registry.observe().first(),
        )
        assertEquals(listOf(uris[0]), resolver.released)
    }

    @Test
    fun removeReleasesGrantAndRemovesEntry() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver)
        val first = Uri.parse("content://images/one")
        val second = Uri.parse("content://images/two")
        registry.add(first, displayName = "one.jpg")
        registry.add(second, displayName = "two.jpg")

        registry.remove(first)

        assertEquals(listOf(RecentUriEntry(second, "two.jpg")), registry.observe().first())
        assertEquals(first, resolver.released.last())
    }

    @Test
    fun reconcileDropsEntriesWhoseSystemGrantIsGone() = runTest {
        val resolver = FakeContentResolver()
        val registry = registry(resolver)
        val first = Uri.parse("content://images/one")
        val second = Uri.parse("content://images/two")
        val third = Uri.parse("content://images/three")
        registry.add(first, displayName = "one.jpg")
        registry.add(second, displayName = "two.jpg")
        registry.add(third, displayName = "three.jpg")
        resolver.persisted.remove(third)

        val removed = registry.reconcile()

        assertEquals(1, removed)
        assertEquals(
            listOf(RecentUriEntry(second, "two.jpg"), RecentUriEntry(first, "one.jpg")),
            registry.observe().first(),
        )
    }

    @Test
    fun legacyUriOnlyEntriesMigrateWithNullDisplayName() = runTest {
        val resolver = FakeContentResolver()
        val legacyUri = Uri.parse("content://images/legacy")
        val dataStore = InMemoryPreferencesDataStore(
            preferencesOf(stringPreferencesKey("persisted_uris") to legacyUri.toString()),
        )
        val registry = PersistableUriRegistry(dataStore, resolver)

        assertEquals(listOf(RecentUriEntry(legacyUri, null)), registry.observe().first())
    }

    @Test
    fun legacyEntryUpgradePreservesGrant() = runTest {
        val resolver = FakeContentResolver()
        val legacyUri = Uri.parse("content://example/legacy.jpg")
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("persisted_uris")] = legacyUri.toString()
        }
        val registry = PersistableUriRegistry(dataStore, resolver)

        registry.add(legacyUri, displayName = "legacy.jpg")

        assertEquals(emptyList<Uri>(), resolver.released)
        assertEquals(listOf(RecentUriEntry(legacyUri, "legacy.jpg")), registry.observe().first())
    }

    private fun registry(
        resolver: FakeContentResolver,
        maxEntries: Int = PersistableUriRegistry.DEFAULT_MAX_ENTRIES,
    ): PersistableUriRegistry {
        val dataStore = InMemoryPreferencesDataStore()
        return PersistableUriRegistry(dataStore, resolver, maxEntries)
    }
}

private class InMemoryPreferencesDataStore(
    initialPreferences: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initialPreferences)

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
