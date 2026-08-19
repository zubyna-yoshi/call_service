package com.company.callservice.settings

/** Shared client/server token length contract, measured after trimming in UTF-8 bytes. */
object BearerTokenPolicy {
    const val MIN_TOKEN_BYTES = 32
    const val MAX_TOKEN_BYTES = 16 * 1024

    fun validateAndNormalize(token: String): String {
        require('\r' !in token && '\n' !in token) {
            "토큰에 줄바꿈을 넣을 수 없습니다."
        }
        val cleanToken = token.trim()

        // Every UTF-8 representation is at least as large as this UTF-16 string for normal input.
        require(cleanToken.length <= MAX_TOKEN_BYTES) { "토큰이 너무 깁니다." }
        val byteCount = cleanToken.toByteArray(Charsets.UTF_8).size
        require(byteCount >= MIN_TOKEN_BYTES) {
            "토큰은 UTF-8 기준 최소 ${MIN_TOKEN_BYTES}바이트여야 합니다."
        }
        require(byteCount <= MAX_TOKEN_BYTES) {
            "토큰은 UTF-8 기준 최대 ${MAX_TOKEN_BYTES}바이트여야 합니다."
        }
        return cleanToken
    }
}
