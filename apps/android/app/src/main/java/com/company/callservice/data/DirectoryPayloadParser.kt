package com.company.callservice.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class DirectoryPayloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

object DirectoryPayloadParser {
    const val MAX_ENTRIES = 100_000

    @Throws(DirectoryPayloadException::class)
    fun parse(rawJson: String): ParsedDirectory {
        try {
            val root = JSONObject(rawJson)
            val version = root.requiredNonBlankString("version", maximumLength = 200)
            val generatedAt = root.requiredNonBlankString("generated_at", maximumLength = 100)
            val array = root.optJSONArray("entries")
                ?: throw DirectoryPayloadException("entries 배열이 없습니다.")

            if (array.length() > MAX_ENTRIES) {
                throw DirectoryPayloadException("entries가 최대 ${MAX_ENTRIES}개를 초과했습니다.")
            }

            return ParsedDirectory(
                version = version,
                generatedAt = generatedAt,
                entries = array.toEntries(),
            )
        } catch (error: DirectoryPayloadException) {
            throw error
        } catch (error: JSONException) {
            throw DirectoryPayloadException("디렉터리 JSON 형식이 올바르지 않습니다.", error)
        }
    }

    private fun JSONArray.toEntries(): List<DirectoryEntryInput> = buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index)
                ?: throw DirectoryPayloadException("entries[$index]가 객체가 아닙니다.")
            add(
                DirectoryEntryInput(
                    phoneNumber = item.optionalString("phone_number"),
                    label = item.optionalString("label"),
                    name = item.optionalString("name"),
                    organization = item.optionalString("organization"),
                    numberType = item.optionalString("number_type"),
                ),
            )
        }
    }

    private fun JSONObject.requiredNonBlankString(key: String, maximumLength: Int): String {
        val value = optionalString(key)
        if (value.isBlank()) throw DirectoryPayloadException("$key 값이 비어 있습니다.")
        if (value.length > maximumLength) {
            throw DirectoryPayloadException("$key 값이 최대 길이 $maximumLength 자를 초과했습니다.")
        }
        return value
    }

    private fun JSONObject.optionalString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = get(key)
        if (value !is String) throw DirectoryPayloadException("$key 값은 문자열이어야 합니다.")
        return value.trim()
    }
}

object DirectoryEntryPreparer {
    private val whitespace = Regex("\\s+")

    /**
     * Builds an all-or-nothing snapshot.
     *
     * An exact normalized-number/label duplicate is harmless and collapses to one row. A bad row
     * or a number mapped to different labels rejects the whole payload so a known-good snapshot is
     * never silently replaced with an incomplete or ambiguous one.
     */
    @Throws(DirectoryPayloadException::class)
    fun prepare(parsed: ParsedDirectory, defaultCountryCallingCode: String): PreparationResult {
        val byNumber = LinkedHashMap<String, DirectoryEntry>()
        var duplicates = 0

        parsed.entries.forEachIndexed { index, input ->
            val normalized = PhoneNumberNormalizer.normalize(
                raw = input.phoneNumber,
                defaultCountryCallingCode = defaultCountryCallingCode,
            )
            if (normalized == null) {
                throw DirectoryPayloadException("entries[$index]의 전화번호를 정규화할 수 없습니다.")
            }

            val name = sanitize(input.name, 100, index, "name")
            val organization = sanitize(input.organization, 120, index, "organization")
            val explicitLabel = sanitize(input.label, 180, index, "label")
            val label = explicitLabel.ifBlank {
                listOf(organization, name).filter(String::isNotBlank).joinToString(" · ")
            }
            if (label.isBlank()) {
                throw DirectoryPayloadException("entries[$index]의 label/name/organization이 모두 비어 있습니다.")
            }
            if (label.length > 180) {
                throw DirectoryPayloadException("entries[$index]의 표시 label이 최대 길이 180자를 초과했습니다.")
            }

            val entry = DirectoryEntry(
                phoneNumber = normalized,
                label = label,
                name = name,
                organization = organization,
                numberType = sanitize(input.numberType, 40, index, "number_type"),
            )
            val existing = byNumber[normalized]
            if (existing != null) {
                if (existing.label != entry.label) {
                    throw DirectoryPayloadException(
                        "entries[$index]가 같은 번호를 다른 label로 매핑합니다.",
                    )
                }
                duplicates += 1
            }
            byNumber[normalized] = entry
        }

        return PreparationResult(
            entries = byNumber.values.sortedBy(DirectoryEntry::phoneNumber),
            duplicateCount = duplicates,
        )
    }

    private fun sanitize(
        value: String,
        maximumLength: Int,
        index: Int,
        field: String,
    ): String {
        val sanitized = value.trim().replace(whitespace, " ")
        if (sanitized.length > maximumLength) {
            throw DirectoryPayloadException(
                "entries[$index].$field 값이 최대 길이 $maximumLength 자를 초과했습니다.",
            )
        }
        return sanitized
    }
}
