package com.boardlens.eval

import com.boardlens.pipeline.Pt
import com.boardlens.pipeline.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalFormatTest {

    private val board = Quad(Pt(400f, 300f), Pt(1400f, 320f), Pt(1380f, 900f), Pt(420f, 880f))

    @Test
    fun cropCoversBoardWithMargin() {
        val c = cropAround(board, 1920, 1440, margin = 0.1f)
        // Board spans x 400..1400 (w=1000), y 300..900 (h=600).
        assertEquals(CropRect(300, 240, 1500, 960), c)
        val rel = board.relativeTo(c)
        assertEquals(Pt(100f, 60f), rel.tl)
        assertTrue(rel.points().all { it.x in 0f..c.width.toFloat() && it.y in 0f..c.height.toFloat() })
    }

    @Test
    fun cropClampsToFrame() {
        val nearEdge = Quad(Pt(-5f, 10f), Pt(500f, 0f), Pt(510f, 400f), Pt(0f, 410f))
        val c = cropAround(nearEdge, 640, 480, margin = 0.2f)
        assertEquals(0, c.left)
        assertEquals(0, c.top)
        assertTrue(c.right <= 640 && c.bottom <= 480)
        assertTrue(c.width > 0 && c.height > 0)
    }

    @Test
    fun quadFlatListRoundTrip() {
        val flat = board.toFlatList()
        assertEquals(listOf(400f, 300f, 1400f, 320f, 1380f, 900f, 420f, 880f), flat)
        assertEquals(board, quadFromFlatList(flat))
    }

    @Test
    fun jsonLineEncodesTypesAndEscapes() {
        val line = JsonLine.encode(
            linkedMapOf(
                "id" to "s1/f001",
                "distance_m" to 6.5,
                "n" to 3,
                "ok" to true,
                "note" to "row 12, \"left\"\nside",
                "quad" to listOf(1.0f, 2.5f),
                "missing" to null,
                "nan" to Double.NaN,
                "timings" to linkedMapOf("ocr" to 12.25),
            ),
        )
        assertEquals(
            "{\"id\":\"s1/f001\",\"distance_m\":6.5,\"n\":3,\"ok\":true," +
                "\"note\":\"row 12, \\\"left\\\"\\nside\",\"quad\":[1,2.5],\"missing\":null,\"nan\":null," +
                "\"timings\":{\"ocr\":12.25}}",
            line,
        )
    }

    @Test
    fun distanceParsing() {
        assertEquals(6.0, parseDistanceMeters(" 6 "))
        assertEquals(6.5, parseDistanceMeters("6,5"))
        assertEquals(null, parseDistanceMeters("abc"))
        assertEquals(null, parseDistanceMeters("0"))
        assertEquals(null, parseDistanceMeters("500"))
    }

    @Test
    fun sessionNames() {
        assertEquals("20261021-153400_d6m", sessionName("20261021-153400", 6.0))
        assertEquals("20261021-153400_d6.5m", sessionName("20261021-153400", 6.5))
    }
}
