package com.company.callservice.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BearerTokenPolicyTest {
    @Test
    fun `accepts inclusive ASCII byte boundaries`() {
        val minimum = "a".repeat(BearerTokenPolicy.MIN_TOKEN_BYTES)
        val maximum = "b".repeat(BearerTokenPolicy.MAX_TOKEN_BYTES)

        assertEquals(minimum, BearerTokenPolicy.validateAndNormalize(minimum))
        assertEquals(maximum, BearerTokenPolicy.validateAndNormalize(maximum))
    }

    @Test
    fun `rejects values outside byte boundaries`() {
        assertThrows(IllegalArgumentException::class.java) {
            BearerTokenPolicy.validateAndNormalize(
                "a".repeat(BearerTokenPolicy.MIN_TOKEN_BYTES - 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BearerTokenPolicy.validateAndNormalize(
                "a".repeat(BearerTokenPolicy.MAX_TOKEN_BYTES + 1),
            )
        }
    }

    @Test
    fun `measures multibyte token in UTF-8 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            BearerTokenPolicy.validateAndNormalize("가".repeat(10))
        }
        assertEquals(
            "가".repeat(11),
            BearerTokenPolicy.validateAndNormalize("가".repeat(11)),
        )
    }

    @Test
    fun `trims surrounding whitespace and rejects line breaks`() {
        val token = "a".repeat(BearerTokenPolicy.MIN_TOKEN_BYTES)
        assertEquals(token, BearerTokenPolicy.validateAndNormalize("  $token  "))
        assertThrows(IllegalArgumentException::class.java) {
            BearerTokenPolicy.validateAndNormalize("${token}\nextra")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BearerTokenPolicy.validateAndNormalize("${token}\n")
        }
    }
}
