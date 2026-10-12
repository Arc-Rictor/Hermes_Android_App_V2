package com.arcrictor.hermes.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPlanTest {
    @Test fun `11 MB boundary`() {
        assertTrue(AttachmentPlan.sizeOk(11L * 1024 * 1024))
        assertFalse(AttachmentPlan.sizeOk(11L * 1024 * 1024 + 1))
        assertTrue(AttachmentPlan.sizeOk(0))
    }

    @Test fun `downscale math keeps aspect under the cap`() {
        assertEquals(1, AttachmentPlan.sampleSize(1200, 800))
        assertEquals(1200 to 800, AttachmentPlan.targetSize(1200, 800))
        assertEquals(2, AttachmentPlan.sampleSize(4000, 3000))
        assertEquals(1568 to 1176, AttachmentPlan.targetSize(4000, 3000))
        // Panoramic: longest side is the cap.
        assertEquals(1568 to 200, AttachmentPlan.targetSize(6000, 766))
        // Square just over the cap.
        assertEquals(1568 to 1568, AttachmentPlan.targetSize(1600, 1600))
        assertEquals(1, AttachmentPlan.sampleSize(1568, 1568))
    }

    @Test fun `every image filename ends jpg`() {
        assertEquals("IMG_123.jpg", AttachmentPlan.jpgName("IMG_123.HEIC"))
        assertEquals("photo.jpg", AttachmentPlan.jpgName("photo.png"))
        assertEquals("image.jpg", AttachmentPlan.jpgName(null))
        assertEquals("image.jpg", AttachmentPlan.jpgName(""))
        assertEquals("noext.jpg", AttachmentPlan.jpgName("noext"))
    }

    @Test fun `ref text goes on its own line`() {
        assertEquals("@file:/a/b.zip", AttachmentPlan.appendRefText("", "@file:/a/b.zip"))
        assertEquals("hi\n@file:/a/b.zip", AttachmentPlan.appendRefText("hi", "@file:/a/b.zip"))
        assertEquals("hi\n@file:/a/b.zip", AttachmentPlan.appendRefText("hi\n", "@file:/a/b.zip"))
    }
}
