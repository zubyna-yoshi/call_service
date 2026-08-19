package com.company.callservice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectoryPayloadParserTest {
    @Test
    fun `parses agreed directory contract`() {
        val parsed = DirectoryPayloadParser.parse(
            """
            {
              "version": "42",
              "generated_at": "2026-08-19T01:02:03Z",
              "entries": [{
                "phone_number": "+82-10-1234-5678",
                "label": "플랫폼팀 · 김민수",
                "name": "김민수",
                "organization": "플랫폼팀",
                "number_type": "mobile"
              }]
            }
            """.trimIndent(),
        )

        assertEquals("42", parsed.version)
        assertEquals(1, parsed.entries.size)
        assertEquals("김민수", parsed.entries.single().name)
    }

    @Test
    fun `missing entries rejects payload`() {
        assertThrows(DirectoryPayloadException::class.java) {
            DirectoryPayloadParser.parse("""{"version":"1","generated_at":"now"}""")
        }
    }

    @Test
    fun `non object entry rejects payload`() {
        assertThrows(DirectoryPayloadException::class.java) {
            DirectoryPayloadParser.parse(
                """{"version":"1","generated_at":"now","entries":["bad"]}""",
            )
        }
    }
}
