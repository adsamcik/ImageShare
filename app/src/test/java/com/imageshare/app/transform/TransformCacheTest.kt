package com.imageshare.app.transform

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransformCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun keyIsDeterministicAndIncludesAllInputs() {
        val cache = cache()
        val signature = "format=jpeg|quality=85|resize=original|aspectLock=false|metadata=StripAll"

        val first = cache.key(123, "content://source/image/1", signature)
        val second = cache.key(123, "content://source/image/1", signature)

        assertEquals(first, second)
        assertEquals(expectedKey(123, "content://source/image/1", signature), first)
        assertEquals(64, first.length)
    }

    @Test fun keyDiffersByCallerUid() {
        val cache = cache()
        val signature = "sig"

        assertNotEquals(
            cache.key(1000, "content://source/image/1", signature),
            cache.key(1001, "content://source/image/1", signature),
        )
    }

    @Test fun keyDiffersBySourceUri() {
        val cache = cache()
        val signature = "sig"

        assertNotEquals(
            cache.key(1000, "content://source/image/1", signature),
            cache.key(1000, "content://source/image/2", signature),
        )
    }

    @Test fun keyDiffersByParamsSignature() {
        val cache = cache()

        assertNotEquals(
            cache.key(1000, "content://source/image/1", "format=jpeg|quality=85"),
            cache.key(1000, "content://source/image/1", "format=png|quality=85"),
        )
    }

    @Test fun lookupReturnsNullWhenMissing() {
        assertNull(cache().lookup("missing", "jpg"))
    }

    @Test fun lookupReturnsFileWhenPresent() {
        val cache = cache()
        val key = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val temp = tempPayload("payload", byteArrayOf(1, 2, 3))

        val cached = cache.put(key, "jpg", temp, "content://source/image/1", 1000, "sig", "image/jpeg")

        assertNotNull(cached)
        assertFalse(temp.exists())
        assertEquals(cached, cache.lookup(key, "jpg"))
        assertArrayEquals(byteArrayOf(1, 2, 3), cached!!.readBytes())
    }

    @Test fun lookupReturnsNullForExpiredEntry() {
        var now = 1_000L
        val cache = cache(maxAgeMillis = 1_000L, clock = { now })
        val key = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val cached = cache.put(key, "jpg", tempPayload("expired", byteArrayOf(1)), "content://source", 1000, "sig", "image/jpeg")
        assertNotNull(cached)

        now = 2_001L

        assertNull(cache.lookup(key, "jpg"))
        assertFalse(File(tmp.root, "$key.jpg").exists())
        assertFalse(File(tmp.root, "$key.meta").exists())
    }

    @Test fun lookupReturnsNullForCorruptMeta() {
        val key = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        File(tmp.root, "$key.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(tmp.root, "$key.meta").writeText("not-json", Charsets.UTF_8)

        assertNull(cache().lookup(key, "jpg"))
        assertFalse(File(tmp.root, "$key.jpg").exists())
        assertFalse(File(tmp.root, "$key.meta").exists())
    }

    @Test fun putEvictsOldestWhenOverByteBudget() {
        var now = 10L
        val cache = cache(maxBytes = 10L, clock = { now })
        val oldest = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        val newest = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        assertNotNull(cache.put(oldest, "jpg", tempPayload("oldest", ByteArray(6) { 1 }), "content://old", 1000, "sig", "image/jpeg"))
        now = 20L

        assertNotNull(cache.put(newest, "jpg", tempPayload("newest", ByteArray(6) { 2 }), "content://new", 1000, "sig", "image/jpeg"))

        assertNull(cache.lookup(oldest, "jpg"))
        assertNotNull(cache.lookup(newest, "jpg"))
        assertFalse(File(tmp.root, "$oldest.meta").exists())
    }

    @Test fun putEvictsOldestWhenOverEntryCount() {
        var now = 10L
        val cache = cache(maxEntries = 1, clock = { now })
        val oldest = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        val newest = "1111111111111111111111111111111111111111111111111111111111111111"
        assertNotNull(cache.put(oldest, "jpg", tempPayload("count-old", byteArrayOf(1)), "content://old", 1000, "sig", "image/jpeg"))
        now = 20L

        assertNotNull(cache.put(newest, "jpg", tempPayload("count-new", byteArrayOf(2)), "content://new", 1000, "sig", "image/jpeg"))

        assertNull(cache.lookup(oldest, "jpg"))
        assertNotNull(cache.lookup(newest, "jpg"))
    }

    @Test fun sweepExpiredRemovesOldEntries() {
        var now = 0L
        val cache = cache(maxAgeMillis = 1_000L, clock = { now })
        val old = "2222222222222222222222222222222222222222222222222222222222222222"
        val fresh = "3333333333333333333333333333333333333333333333333333333333333333"
        assertNotNull(cache.put(old, "jpg", tempPayload("sweep-old", byteArrayOf(1)), "content://old", 1000, "old", "image/jpeg"))
        now = 500L
        assertNotNull(cache.put(fresh, "jpg", tempPayload("sweep-fresh", byteArrayOf(2)), "content://fresh", 1000, "fresh", "image/jpeg"))

        now = 1_001L
        cache.sweepExpired()

        assertNull(cache.lookup(old, "jpg"))
        assertNotNull(cache.lookup(fresh, "jpg"))
    }

    @Test fun sweepExpiredToleratesOrphanedPayloadFiles() {
        val key = "4444444444444444444444444444444444444444444444444444444444444444"
        val orphan = File(tmp.root, "$key.jpg")
        orphan.writeBytes(byteArrayOf(1, 2, 3))

        cache().sweepExpired()

        assertFalse(orphan.exists())
    }

    private fun cache(
        maxBytes: Long = TransformCache.DEFAULT_MAX_BYTES,
        maxEntries: Int = TransformCache.DEFAULT_MAX_ENTRIES,
        maxAgeMillis: Long = TransformCache.DEFAULT_MAX_AGE_MILLIS,
        clock: () -> Long = { 1_000L },
    ) = TransformCache(tmp.root, maxBytes, maxEntries, maxAgeMillis, clock)

    private fun tempPayload(name: String, bytes: ByteArray): File =
        tmp.newFile("$name.bin").apply { writeBytes(bytes) }

    private fun expectedKey(callerUid: Int, source: String, paramsSignature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(callerUid.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(source.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(paramsSignature.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun assertNotEquals(first: String, second: String) {
        assertFalse("Expected values to differ, but both were $first", first == second)
    }
}
