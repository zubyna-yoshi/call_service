package com.company.callservice.data

data class DirectoryEntryInput(
    val phoneNumber: String,
    val label: String,
    val name: String,
    val organization: String,
    val numberType: String,
)

data class ParsedDirectory(
    val version: String,
    val generatedAt: String,
    val entries: List<DirectoryEntryInput>,
)

data class DirectoryEntry(
    val phoneNumber: String,
    val label: String,
    val name: String,
    val organization: String,
    val numberType: String,
) {
    val displayLabel: String
        get() = label.ifBlank {
            listOf(organization, name).filter(String::isNotBlank).joinToString(" · ")
        }.ifBlank { phoneNumber }
}

data class DirectorySnapshot(
    val version: String,
    val generatedAt: String,
    val etag: String?,
    val checkedAtEpochMillis: Long,
    val entries: List<DirectoryEntry>,
)

data class PreparationResult(
    val entries: List<DirectoryEntry>,
    val duplicateCount: Int,
)

data class SnapshotInfo(
    val version: String,
    val generatedAt: String,
    val checkedAtEpochMillis: Long,
    val entryCount: Int,
    val etag: String?,
)

fun DirectorySnapshot.toInfo(): SnapshotInfo = SnapshotInfo(
    version = version,
    generatedAt = generatedAt,
    checkedAtEpochMillis = checkedAtEpochMillis,
    entryCount = entries.size,
    etag = etag,
)
