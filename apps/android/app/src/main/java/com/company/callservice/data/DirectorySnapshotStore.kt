package com.company.callservice.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class DirectorySnapshotStore(context: Context) {
    companion object {
        private const val SNAPSHOT_FILE = "directory_snapshot.json"
        private const val MAX_SNAPSHOT_BYTES = 12 * 1024 * 1024
    }

    private val atomicFile = AtomicFile(File(context.noBackupFilesDir, SNAPSHOT_FILE))
    private val lock = Any()

    @Volatile
    private var cached: CachedSnapshot? = null

    fun lookup(
        rawPhoneNumber: String,
        defaultCountryCallingCode: String,
        nowEpochMillis: Long,
    ): DirectoryEntry? {
        val normalized = PhoneNumberNormalizer.normalize(rawPhoneNumber, defaultCountryCallingCode)
            ?: return null
        val current = loadCached() ?: return null
        if (
            !SnapshotFreshnessPolicy.isUsable(
                nowEpochMillis = nowEpochMillis,
                checkedAtEpochMillis = current.snapshot.checkedAtEpochMillis,
            )
        ) {
            return null
        }
        return current.byPhoneNumber[normalized]
    }

    fun snapshot(): DirectorySnapshot? = loadCached()?.snapshot

    fun info(): SnapshotInfo? = snapshot()?.toInfo()

    @Throws(IOException::class)
    fun save(snapshot: DirectorySnapshot) {
        val bytes = DirectorySnapshotCodec.encode(snapshot).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_SNAPSHOT_BYTES) {
            throw IOException("로컬 snapshot이 ${MAX_SNAPSHOT_BYTES}바이트 제한을 초과했습니다.")
        }

        synchronized(lock) {
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                atomicFile.finishWrite(output)
                cached = snapshot.toCached()
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
        }
    }

    @Throws(IOException::class)
    fun markChecked(nowEpochMillis: Long) {
        val current = snapshot() ?: return
        save(current.copy(checkedAtEpochMillis = nowEpochMillis))
    }

    fun clear() {
        synchronized(lock) {
            atomicFile.delete()
            cached = null
        }
    }

    private fun loadCached(): CachedSnapshot? {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: readFromDisk()?.toCached()?.also { cached = it }
        }
    }

    private fun readFromDisk(): DirectorySnapshot? {
        if (!atomicFile.baseFile.exists()) return null
        if (atomicFile.baseFile.length() > MAX_SNAPSHOT_BYTES) return null
        return try {
            val bytes = atomicFile.readFully()
            DirectorySnapshotCodec.decode(String(bytes, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            // A corrupt snapshot must never delay or break an incoming call.
            null
        }
    }

    private fun DirectorySnapshot.toCached(): CachedSnapshot = CachedSnapshot(
        snapshot = this,
        byPhoneNumber = entries.associateBy(DirectoryEntry::phoneNumber),
    )

    private data class CachedSnapshot(
        val snapshot: DirectorySnapshot,
        val byPhoneNumber: Map<String, DirectoryEntry>,
    )
}

internal object DirectorySnapshotCodec {
    fun encode(snapshot: DirectorySnapshot): String = JSONObject().apply {
        put("version", snapshot.version)
        put("generated_at", snapshot.generatedAt)
        put("etag", snapshot.etag ?: JSONObject.NULL)
        put("checked_at_epoch_millis", snapshot.checkedAtEpochMillis)
        put("entries", JSONArray().apply {
            snapshot.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("phone_number", entry.phoneNumber)
                    put("label", entry.label)
                    put("name", entry.name)
                    put("organization", entry.organization)
                    put("number_type", entry.numberType)
                })
            }
        })
    }.toString()

    fun decode(rawJson: String): DirectorySnapshot {
        val root = JSONObject(rawJson)
        val array = root.getJSONArray("entries")
        val entries = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    DirectoryEntry(
                        phoneNumber = item.getString("phone_number"),
                        label = item.optString("label", ""),
                        name = item.optString("name", ""),
                        organization = item.optString("organization", ""),
                        numberType = item.optString("number_type", ""),
                    ),
                )
            }
        }
        return DirectorySnapshot(
            version = root.getString("version"),
            generatedAt = root.getString("generated_at"),
            etag = if (root.isNull("etag")) null else root.optString("etag").takeIf(String::isNotBlank),
            checkedAtEpochMillis = root.optLong("checked_at_epoch_millis", 0L),
            entries = entries,
        )
    }
}
