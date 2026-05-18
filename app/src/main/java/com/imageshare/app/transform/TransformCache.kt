package com.imageshare.app.transform

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Disk-backed content-addressed FIFO cache for transform results.
 *
 * Key = SHA-256(callerUid + sourceUri + paramsSignature). Each entry is two files
 * in [root]: {key}.{ext} (the bytes) and {key}.meta (JSON sidecar).
 *
 * Capacity: [maxBytes] hard budget, [maxEntries] hard count, [maxAgeMillis] hard expiry.
 * Eviction policy: FIFO by createdAtMs.
 */
internal class TransformCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        root.mkdirs()
    }

    fun key(callerUid: Int, source: String, paramsSignature: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(callerUid.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(source.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(paramsSignature.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * Look up a cached entry. Returns null if missing, corrupt, or expired.
     * Does NOT enforce grant — caller must do that.
     */
    @Synchronized
    fun lookup(key: String, ext: String): File? {
        val payload = File(root, "$key.$ext")
        val meta = File(root, "$key.meta")
        if (!payload.isFile || !meta.isFile) return null
        return try {
            val json = JSONObject(meta.readText(Charsets.UTF_8))
            val createdAt = json.getLong("createdAtMs")
            if (clock() - createdAt > maxAgeMillis) {
                payload.delete()
                meta.delete()
                null
            } else {
                payload
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Deleting corrupt transform cache metadata for $key", error)
            payload.delete()
            meta.delete()
            null
        }
    }

    /**
     * Atomically place [tempPayload] into the cache as {key}.{ext} with the given metadata.
     * Evicts oldest entries (FIFO) until the cache satisfies maxBytes and maxEntries
     * after this new entry is in place.
     *
     * If the payload cannot fit within the hard budget or any IO operation fails,
     * [tempPayload] is left untouched and null is returned.
     */
    @Synchronized
    fun put(
        key: String,
        ext: String,
        tempPayload: File,
        sourceUri: String,
        callerUid: Int,
        paramsSignature: String,
        mime: String,
    ): File? {
        if (!tempPayload.isFile) return null
        val size = tempPayload.length()
        if (size > maxBytes || maxEntries < 1) return null

        val payload = File(root, "$key.$ext")
        val meta = File(root, "$key.meta")
        val stagedPayload = File(root, "$key.$ext.tmp-${System.nanoTime()}")
        val stagedMeta = File(root, "$key.meta.tmp-${System.nanoTime()}")
        val sidecar = JSONObject().apply {
            put("createdAtMs", clock())
            put("sourceUri", sourceUri)
            put("callerUid", callerUid)
            put("paramsSig", paramsSignature)
            put("sizeBytes", size)
            put("mime", mime)
        }

        return try {
            tempPayload.copyTo(stagedPayload, overwrite = true)
            stagedMeta.writeText(sidecar.toString(), Charsets.UTF_8)

            payload.delete()
            meta.delete()
            evictFor(addedBytes = size, addedEntries = 1)

            if (!stagedPayload.renameTo(payload)) {
                stagedPayload.copyTo(payload, overwrite = true)
                stagedPayload.delete()
            }
            if (!stagedMeta.renameTo(meta)) {
                stagedMeta.copyTo(meta, overwrite = true)
                stagedMeta.delete()
            }
            tempPayload.delete()
            payload
        } catch (_: Throwable) {
            stagedPayload.delete()
            stagedMeta.delete()
            payload.delete()
            meta.delete()
            null
        }
    }

    /** Sweep entries older than [maxAgeMillis]. Run on provider onCreate. */
    @Synchronized
    fun sweepExpired() {
        val now = clock()
        listEntries().forEach { entry ->
            if (now - entry.createdAtMs > maxAgeMillis) {
                entry.payload.delete()
                entry.meta.delete()
            }
        }
    }

    /**
     * Evict oldest entries (by createdAtMs) until adding [addedBytes] + [addedEntries] would
     * leave the cache at or below maxBytes and maxEntries.
     */
    private fun evictFor(addedBytes: Long, addedEntries: Int) {
        val entries = listEntries().sortedBy { it.createdAtMs }.toMutableList()
        var totalBytes = entries.sumOf { it.sizeBytes }
        var totalCount = entries.size
        while (
            (totalBytes + addedBytes > maxBytes || totalCount + addedEntries > maxEntries) &&
            entries.isNotEmpty()
        ) {
            val oldest = entries.removeAt(0)
            if (oldest.payload.delete()) totalBytes -= oldest.sizeBytes
            if (oldest.meta.delete()) totalCount -= 1
        }
    }

    private data class Entry(
        val payload: File,
        val meta: File,
        val createdAtMs: Long,
        val sizeBytes: Long,
    )

    private fun listEntries(): List<Entry> {
        val children = root.listFiles() ?: return emptyList()
        val metas = children.filter { it.name.endsWith(".meta") }.associateBy { it.nameWithoutExtension }
        val payloads = children.filter { file ->
            file.isFile && !file.name.endsWith(".meta") && !file.name.contains(".tmp-")
        }
        val entries = payloads.mapNotNull { payload ->
            val key = payload.nameWithoutExtension
            val meta = metas[key] ?: run {
                Log.w(TAG, "Deleting orphan transform cache payload ${payload.name}")
                payload.delete()
                return@mapNotNull null
            }
            try {
                val json = JSONObject(meta.readText(Charsets.UTF_8))
                Entry(
                    payload = payload,
                    meta = meta,
                    createdAtMs = json.getLong("createdAtMs"),
                    sizeBytes = payload.length(),
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Deleting corrupt transform cache entry ${payload.name}", error)
                payload.delete()
                meta.delete()
                null
            }
        }
        val payloadKeys = payloads.mapTo(mutableSetOf()) { it.nameWithoutExtension }
        metas.forEach { (key, meta) ->
            if (key !in payloadKeys) {
                Log.w(TAG, "Deleting orphan transform cache metadata ${meta.name}")
                meta.delete()
            }
        }
        return entries
    }

    companion object {
        private const val TAG = "TransformCache"
        const val DEFAULT_MAX_BYTES: Long = 209_715_200L
        const val DEFAULT_MAX_ENTRIES: Int = 2000
        const val DEFAULT_MAX_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
