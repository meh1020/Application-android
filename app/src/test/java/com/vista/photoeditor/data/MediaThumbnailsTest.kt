package com.vista.photoeditor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaThumbnailsTest {

    @Test
    fun `vignette tournée comme la photo`() {
        assertEquals(true, MediaThumbnails.sameOrientation(4000, 3000, 540, 405))
        assertEquals(true, MediaThumbnails.sameOrientation(3000, 4000, 405, 540))
    }

    @Test
    fun `vignette pas redressée`() {
        assertEquals(false, MediaThumbnails.sameOrientation(3000, 4000, 540, 405))
        assertEquals(false, MediaThumbnails.sameOrientation(4000, 3000, 405, 540))
    }

    @Test
    fun `impossible d'en juger`() {
        assertEquals(true, MediaThumbnails.sameOrientation(0, 0, 540, 405))
        assertEquals(true, MediaThumbnails.sameOrientation(3000, 3000, 540, 405))
        assertEquals(true, MediaThumbnails.sameOrientation(4000, 3000, 540, 540))
    }
}
