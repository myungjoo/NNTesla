package io.github.myungjoo.nntesla.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {
    @Test
    fun portraitPhoneUsesTwoColumns() {
        // 세로 폰의 속도 아래 영역(대략 390x400dp)에 타일 6개.
        val layout = TileGrid.choose(count = 6, width = 390f, height = 400f, gap = 8f)
        assertEquals(2, layout.columns)
        assertEquals(3, layout.rows)
    }

    @Test
    fun wideAreaSpreadsOut() {
        // 펼친 폴드·태블릿 가로 모드처럼 아주 넓고 낮으면 열을 늘려 행 높이를 키운다.
        val layout = TileGrid.choose(count = 6, width = 900f, height = 300f, gap = 8f)
        assertEquals(3, layout.columns)
        assertEquals(2, layout.rows)
    }

    @Test
    fun tilesFillTheArea() {
        val layout = TileGrid.choose(count = 7, width = 390f, height = 400f, gap = 8f)
        val usedHeight = layout.tileHeight * layout.rows + 8f * (layout.rows - 1)
        val usedWidth = layout.tileWidth * layout.columns + 8f * (layout.columns - 1)
        assertEquals(400f, usedHeight, 0.01f)
        assertEquals(390f, usedWidth, 0.01f)
        assertTrue(layout.valueSize > 0f)
    }

    @Test
    fun primaryTilesAreTwoToThreeTimesLarger() {
        // 세로 폰, 접은 Fold 가로, 편 Fold 가로에서 속도 밖 영역의 대략적인 크기(dp).
        for ((w, h) in listOf(390f to 400f, 380f to 380f, 470f to 590f)) {
            for (secondary in listOf(4, 5)) {
                val split = TileGrid.split(primaryCount = 2, secondaryCount = secondary, width = w, height = h, gap = 8f)
                val ratio = split.primaryValueSize / split.secondary.valueSize
                assertTrue("${w}x$h/$secondary ratio $ratio", ratio in 2f..3f)
                assertTrue(split.secondary.valueSize > 0f)
                val secondaryHeight = split.secondary.tileHeight * split.secondary.rows + 8f * (split.secondary.rows - 1)
                assertEquals(h - 8f, split.primaryHeight + secondaryHeight, 0.01f)
            }
        }
    }
}
