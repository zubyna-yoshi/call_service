package com.company.callservice.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun `normalizes Korean domestic mobile and office numbers`() {
        assertEquals("+821012345678", PhoneNumberNormalizer.normalize("010-1234-5678", "82"))
        assertEquals("+8225550101", PhoneNumberNormalizer.normalize("02 555 0101", "82"))
    }

    @Test
    fun `keeps international number and converts 00 prefix`() {
        assertEquals("+12025550123", PhoneNumberNormalizer.normalize("+1 (202) 555-0123", "82"))
        assertEquals("+442071838750", PhoneNumberNormalizer.normalize("0044 20 7183 8750", "82"))
    }

    @Test
    fun `rejects ambiguous local number whose leading zero was lost`() {
        assertNull(PhoneNumberNormalizer.normalize("1012345678", "82"))
        assertNull(PhoneNumberNormalizer.normalize("5550101", "82"))
    }

    @Test
    fun `rejects default country code followed by a national trunk zero`() {
        assertNull(PhoneNumberNormalizer.normalize("+82 (0)10-1234-5678", "82"))
        assertNull(PhoneNumberNormalizer.normalize("8201012345678", "82"))
    }

    @Test
    fun `rejects non ASCII digits and unsafe characters`() {
        assertNull(PhoneNumberNormalizer.normalize("０１０-１２３４-５６７８", "82"))
        assertNull(PhoneNumberNormalizer.normalize("010-1234-ABCD", "82"))
        assertNull(PhoneNumberNormalizer.normalize("010;1234;5678", "82"))
        assertNull(PhoneNumberNormalizer.normalize("010-1234-5678", "８２"))
    }

    @Test
    fun `rejects impossible lookup key lengths`() {
        assertNull(PhoneNumberNormalizer.normalize("123", "82"))
        assertNull(PhoneNumberNormalizer.normalize("+0123456789", "82"))
    }
}
