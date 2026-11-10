package local.papernotes.editor

import local.papernotes.model.ImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageUtilTest {
    @Test
    fun extensionIsDetectedFromMagicBytes() {
        assertEquals("png", ImageUtil.extensionOf(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1)))
        assertEquals("jpg", ImageUtil.extensionOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("gif", ImageUtil.extensionOf("GIF89a".toByteArray()))
        assertEquals("webp", ImageUtil.extensionOf("RIFF1234WEBPVP8 ".toByteArray()))
        assertEquals("img", ImageUtil.extensionOf(ByteArray(2)))
    }

    @Test
    fun initialSizeKeepsAspectAndLimitsHeight() {
        val (w, h) = ImageUtil.initialSize(2000, 1000, 1000f, 1500f)
        assertEquals(500f, w, 0.001f)
        assertEquals(250f, h, 0.001f)
        val (w2, h2) = ImageUtil.initialSize(1000, 4000, 1000f, 1000f)
        assertEquals(600f, h2, 0.001f)
        assertEquals(150f, w2, 0.001f)
    }

    @Test
    fun sampleSizeIsPowerOfTwo() {
        assertEquals(1, ImageUtil.sampleSize(1000, 800, 2048))
        assertEquals(2, ImageUtil.sampleSize(4000, 3000, 2048))
        assertEquals(4, ImageUtil.sampleSize(8000, 6000, 2048))
    }

    @Test
    fun fractionsOrderCornersAndClamp() {
        val f = ImageUtil.fractions(150f, 80f, -20f, 20f, 200f, 100f)!!
        assertEquals(0f, f.left, 0.001f)
        assertEquals(0.75f, f.right, 0.001f)
        assertEquals(0.2f, f.top, 0.001f)
        assertEquals(0.8f, f.bottom, 0.001f)
    }

    @Test
    fun tinySelectionIsRejected() {
        assertNull(ImageUtil.fractions(10f, 10f, 12f, 60f, 200f, 100f))
        assertNull(ImageUtil.fractions(0f, 0f, 10f, 10f, 0f, 0f))
    }

    @Test
    fun pixelRectStaysInsideImage() {
        val r = ImageUtil.pixelRect(ImageUtil.Fractions(0.1f, 0.2f, 1f, 1f), 100, 50)
        assertEquals(ImageUtil.PixelRect(10, 10, 100, 50), r)
        assertEquals(90, r.width)
        assertEquals(40, r.height)
        assertNotNull(ImageUtil.pixelRect(ImageUtil.Fractions(0.99f, 0.99f, 1f, 1f), 10, 10))
    }

    @Test
    fun croppedImageKeepsPlaceOfSelection() {
        val img = ImageAttachment("a", "old.png", 100f, 200f, 400f, 300f)
        val c = ImageUtil.cropped(img, ImageUtil.Fractions(0.25f, 0.5f, 0.75f, 1f), "new.png")
        assertEquals(ImageAttachment("a", "new.png", 200f, 350f, 200f, 150f), c)
    }
}
