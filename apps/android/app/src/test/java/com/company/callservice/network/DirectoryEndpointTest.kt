package com.company.callservice.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectoryEndpointTest {
    @Test
    fun `appends contract path to HTTPS base`() {
        assertEquals(
            "https://directory.example.com/api/v1/directory",
            DirectoryEndpoint.build("https://directory.example.com/api/").toString(),
        )
    }

    @Test
    fun `does not append contract path twice`() {
        assertEquals(
            "https://directory.example.com/v1/directory",
            DirectoryEndpoint.build("https://directory.example.com/v1/directory").toString(),
        )
    }

    @Test
    fun `rejects HTTP credentials query and fragment`() {
        listOf(
            "http://directory.example.com",
            "https://user:secret@directory.example.com",
            "https://directory.example.com?token=secret",
            "https://directory.example.com/#fragment",
        ).forEach { unsafe ->
            assertThrows(DirectoryApiException::class.java) {
                DirectoryEndpoint.build(unsafe)
            }
        }
    }
}
