package com.arcrictor.hermes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CacheFilenameTest {
    @Test fun `stored ids map to safe file names`() {
        assertEquals(
            "transcript-20261011_233258_cf1f51.json",
            SessionCache.fileNameFor("20261011_233258_cf1f51"),
        )
        val evil = SessionCache.fileNameFor("../../etc/passwd")
        assertFalse(evil.contains("/"))
        assertFalse(evil.removeSuffix(".json").contains("."))
    }
}
