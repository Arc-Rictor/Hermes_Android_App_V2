package com.arcrictor.hermes.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleSizeTest {
    @Test fun `small images are not subsampled`() {
        assertEquals(1, ServerImages.sampleSize(longEdge = 1200, maxEdge = 1568))
        assertEquals(1, ServerImages.sampleSize(longEdge = 3000, maxEdge = 1568))
    }

    @Test fun `large photos subsample by powers of two but never below max edge`() {
        assertEquals(2, ServerImages.sampleSize(longEdge = 4000, maxEdge = 1568)) // → 2000
        assertEquals(4, ServerImages.sampleSize(longEdge = 8000, maxEdge = 1568)) // → 2000
        assertEquals(2, ServerImages.sampleSize(longEdge = 3136, maxEdge = 1568)) // → 1568 exactly
    }
}
