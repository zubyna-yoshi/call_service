package com.company.callservice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectoryEntryPreparerTest {
    @Test
    fun `same normalized number and label collapses`() {
        val parsed = ParsedDirectory(
            version = "v1",
            generatedAt = "2026-08-19T00:00:00Z",
            entries = listOf(
                entry(phone = "010-1234-5678", label = "개발팀 · 김민수", type = "office"),
                entry(phone = "+82 10 1234 5678", label = "개발팀 · 김민수", type = "mobile"),
            ),
        )

        val result = DirectoryEntryPreparer.prepare(parsed, "82")

        assertEquals(1, result.entries.size)
        assertEquals(1, result.duplicateCount)
        assertEquals("mobile", result.entries.single().numberType)
    }

    @Test
    fun `same normalized number with conflicting label rejects whole payload`() {
        val parsed = ParsedDirectory(
            version = "v1",
            generatedAt = "now",
            entries = listOf(
                entry(phone = "010-1234-5678", label = "개발팀 · 김민수"),
                entry(phone = "+82 10 1234 5678", label = "재무팀 · 이서연"),
            ),
        )

        assertThrows(DirectoryPayloadException::class.java) {
            DirectoryEntryPreparer.prepare(parsed, "82")
        }
    }

    @Test
    fun `invalid row rejects whole payload instead of silently dropping it`() {
        val parsed = ParsedDirectory(
            version = "v1",
            generatedAt = "now",
            entries = listOf(
                entry(phone = "010-1234-5678", label = "정상"),
                entry(phone = "not-a-number", label = "오염 데이터"),
            ),
        )

        assertThrows(DirectoryPayloadException::class.java) {
            DirectoryEntryPreparer.prepare(parsed, "82")
        }
    }

    @Test
    fun `derives label from organization and name`() {
        val parsed = ParsedDirectory(
            version = "v1",
            generatedAt = "now",
            entries = listOf(
                DirectoryEntryInput(
                    phoneNumber = "02-555-0101",
                    label = "",
                    name = "이서연",
                    organization = "재무팀",
                    numberType = "office",
                ),
            ),
        )

        val result = DirectoryEntryPreparer.prepare(parsed, "82")

        assertEquals("재무팀 · 이서연", result.entries.single().label)
    }

    private fun entry(phone: String, label: String, type: String = "mobile") =
        DirectoryEntryInput(
            phoneNumber = phone,
            label = label,
            name = "",
            organization = "",
            numberType = type,
        )
}
