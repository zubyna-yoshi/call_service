package com.company.callservice.network

import com.company.callservice.settings.BearerTokenPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

sealed interface DirectoryApiResult {
    data class Downloaded(val body: String, val etag: String?) : DirectoryApiResult
    data object NotModified : DirectoryApiResult
}

class DirectoryApiException(message: String, cause: Throwable? = null) : IOException(message, cause)

object DirectoryEndpoint {
    private const val DIRECTORY_PATH = "v1/directory"

    @Throws(DirectoryApiException::class)
    fun build(apiBaseUrl: String): URL {
        val base = apiBaseUrl.trim()
        if (base.isEmpty()) throw DirectoryApiException("API 기본 URL을 입력하세요.")

        val uri = try {
            URI(base)
        } catch (error: Exception) {
            throw DirectoryApiException("API 기본 URL 형식이 올바르지 않습니다.", error)
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw DirectoryApiException("Bearer 토큰 보호를 위해 HTTPS URL만 허용합니다.")
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw DirectoryApiException("호스트만 포함한 안전한 API 기본 URL을 입력하세요.")
        }

        val currentPath = uri.path.orEmpty().trimEnd('/')
        val endpointPath = if (currentPath.endsWith("/$DIRECTORY_PATH")) {
            currentPath
        } else {
            "$currentPath/$DIRECTORY_PATH"
        }.replace(Regex("/{2,}"), "/")

        return try {
            URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                endpointPath,
                null,
                null,
            ).toURL()
        } catch (error: Exception) {
            throw DirectoryApiException("디렉터리 endpoint를 만들 수 없습니다.", error)
        }
    }
}

class DirectoryApiClient {
    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 12_000
        private const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
    }

    @Throws(IOException::class)
    fun fetch(
        apiBaseUrl: String,
        bearerToken: String,
        previousEtag: String?,
    ): DirectoryApiResult {
        val validatedBearerToken = try {
            BearerTokenPolicy.validateAndNormalize(bearerToken)
        } catch (error: IllegalArgumentException) {
            throw DirectoryApiException(error.message ?: "Bearer 토큰 형식이 올바르지 않습니다.", error)
        }
        val endpoint = DirectoryEndpoint.build(apiBaseUrl)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = false // Never forward a bearer token to another origin.
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $validatedBearerToken")
            previousEtag?.takeIf(String::isNotBlank)?.let {
                setRequestProperty("If-None-Match", it)
            }
        }

        return try {
            when (val statusCode = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> DirectoryApiResult.Downloaded(
                    body = connection.inputStream.use(::readLimitedUtf8),
                    etag = connection.getHeaderField("ETag")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && it.length <= 512 && '\r' !in it && '\n' !in it },
                )

                HttpURLConnection.HTTP_NOT_MODIFIED -> DirectoryApiResult.NotModified
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN ->
                    throw DirectoryApiException("API 인증에 실패했습니다. 토큰과 접근 권한을 확인하세요. ($statusCode)")

                in 300..399 -> throw DirectoryApiException("보안을 위해 API redirect를 허용하지 않습니다. ($statusCode)")
                else -> throw DirectoryApiException("디렉터리 API 요청이 실패했습니다. (HTTP $statusCode)")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedUtf8(input: InputStream): String {
        val buffer = ByteArray(16 * 1024)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw DirectoryApiException("API 응답이 ${MAX_RESPONSE_BYTES}바이트 제한을 초과했습니다.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}
