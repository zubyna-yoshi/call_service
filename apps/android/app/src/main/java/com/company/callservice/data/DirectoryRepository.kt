package com.company.callservice.data

import android.content.Context
import com.company.callservice.network.DirectoryApiClient
import com.company.callservice.network.DirectoryApiResult
import com.company.callservice.settings.SecretStore
import com.company.callservice.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

sealed interface SyncOutcome {
    data class Updated(
        val entryCount: Int,
        val duplicateCount: Int,
        val version: String,
    ) : SyncOutcome

    data class NotModified(val entryCount: Int, val version: String) : SyncOutcome
}

class DirectoryRepository(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    private val snapshotStore: DirectorySnapshotStore,
    private val apiClient: DirectoryApiClient,
) {
    companion object {
        private const val SAMPLE_ASSET = "sample_directory.json"
        private const val MAX_SAMPLE_BYTES = 2 * 1024 * 1024
    }

    suspend fun sync(): SyncOutcome = withContext(Dispatchers.IO) {
        val settings = settingsStore.read()
        val token = secretStore.readToken()
            ?: throw IOException("Bearer 토큰이 설정되지 않았습니다.")
        val previous = snapshotStore.snapshot()

        when (
            val result = apiClient.fetch(
                apiBaseUrl = settings.apiBaseUrl,
                bearerToken = token,
                previousEtag = previous?.etag,
            )
        ) {
            is DirectoryApiResult.Downloaded -> {
                val parsed = DirectoryPayloadParser.parse(result.body)
                val prepared = DirectoryEntryPreparer.prepare(
                    parsed = parsed,
                    defaultCountryCallingCode = settings.defaultCountryCallingCode,
                )
                val snapshot = DirectorySnapshot(
                    version = parsed.version,
                    generatedAt = parsed.generatedAt,
                    etag = result.etag,
                    checkedAtEpochMillis = System.currentTimeMillis(),
                    entries = prepared.entries,
                )
                snapshotStore.save(snapshot)
                SyncOutcome.Updated(
                    entryCount = prepared.entries.size,
                    duplicateCount = prepared.duplicateCount,
                    version = parsed.version,
                )
            }

            DirectoryApiResult.NotModified -> {
                val existing = previous
                    ?: throw IOException("로컬 snapshot 없이 서버가 304를 반환했습니다.")
                snapshotStore.markChecked(System.currentTimeMillis())
                SyncOutcome.NotModified(
                    entryCount = existing.entries.size,
                    version = existing.version,
                )
            }
        }
    }

    suspend fun importBundledSample(): SyncOutcome.Updated = withContext(Dispatchers.IO) {
        val raw = context.assets.open(SAMPLE_ASSET).use {
            readLimitedUtf8(it, MAX_SAMPLE_BYTES)
        }
        val parsed = DirectoryPayloadParser.parse(raw)
        val prepared = DirectoryEntryPreparer.prepare(
            parsed = parsed,
            defaultCountryCallingCode = settingsStore.read().defaultCountryCallingCode,
        )
        snapshotStore.save(
            DirectorySnapshot(
                version = parsed.version,
                generatedAt = parsed.generatedAt,
                etag = null,
                checkedAtEpochMillis = System.currentTimeMillis(),
                entries = prepared.entries,
            ),
        )
        SyncOutcome.Updated(
            entryCount = prepared.entries.size,
            duplicateCount = prepared.duplicateCount,
            version = parsed.version,
        )
    }

    fun snapshotInfo(): SnapshotInfo? = snapshotStore.info()

    fun clearSnapshot() = snapshotStore.clear()

    private fun readLimitedUtf8(input: InputStream, maximumBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) throw IOException("샘플 파일이 너무 큽니다.")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
