package com.company.callservice.data

/**
 * Converts server and Telecom phone numbers to one E.164-like key.
 *
 * The result always starts with '+' and contains 7..15 digits. This is a lookup key, not a
 * guarantee that the carrier verified the caller's identity.
 */
object PhoneNumberNormalizer {
    private val separators = setOf(' ', '-', '(', ')', '.', '\t', '\n', '\r')

    fun normalize(raw: String?, defaultCountryCallingCode: String): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > 64) return null

        val countryCode = defaultCountryCallingCode.trim().removePrefix("+")
        if (
            countryCode.length !in 1..3 ||
            countryCode.firstOrNull() == '0' ||
            !countryCode.all { it in '0'..'9' }
        ) {
            return null
        }

        var value = raw.trim()
        if (value.startsWith("tel:", ignoreCase = true)) {
            value = value.substring(4)
        }

        val compact = buildString(value.length) {
            value.forEachIndexed { index, character ->
                when {
                    character in '0'..'9' -> append(character)
                    character == '+' && index == 0 -> append(character)
                    character in separators -> Unit
                    else -> return null
                }
            }
        }

        if (compact.isBlank() || compact == "+") return null

        val canonical = when {
            compact.startsWith("+") -> compact
            compact.startsWith("00") -> "+${compact.drop(2)}"
            compact.startsWith("0") -> "+$countryCode${compact.drop(1)}"
            compact.startsWith(countryCode) -> "+$compact"
            else -> return null
        }

        val digits = canonical.drop(1)
        if (digits.length !in 7..15 || digits.firstOrNull() == '0' || !digits.all { it in '0'..'9' }) {
            return null
        }
        if (digits.startsWith("${countryCode}0")) return null
        return canonical
    }
}
