package com.arcrictor.hermes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheFilenameTest {
    @Test fun `stored ids map to safe sibling files`() {
        // Mirror of SessionCache.transcriptFile sanitising (no Context needed).
        fun fileFor(storedId: String) =
            "transcript-" + storedId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json"
        assertEquals("transcript-20261011_233258_cf1f51.json", fileFor("20261011_233258_cf1f51"))
        val evil = fileFor("../../etc/passwd")
        assertTrue(!evil.contains("/") && !evil.contains(".."))
        assertTrue(File(evil).name == evil)
    }
}
