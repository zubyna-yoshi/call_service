package com.company.callservice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.company.callservice.data.DirectoryEntryPreparer
import com.company.callservice.data.DirectoryPayloadParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledSampleTest {
    @Test
    fun bundledSampleParsesAndNormalizesWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = context.assets.open("sample_directory.json").bufferedReader().use { it.readText() }

        val parsed = DirectoryPayloadParser.parse(raw)
        val prepared = DirectoryEntryPreparer.prepare(parsed, "82")

        assertEquals("sample-1", parsed.version)
        assertEquals(3, prepared.entries.size)
        assertEquals(
            "플랫폼개발팀 · 김민수",
            prepared.entries.single { it.phoneNumber == "+821012345678" }.label,
        )
    }
}
