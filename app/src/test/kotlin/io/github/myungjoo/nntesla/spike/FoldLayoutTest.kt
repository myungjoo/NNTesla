package io.github.myungjoo.nntesla.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Galaxy Fold 8 가로 화면(접은 상태, 편 상태)의 실제 크기로 주행 정보 배치를 확인한다. 사용자가 보고했던
 * 배치 버그가 다시 생기지 않게 막는다.
 *
 * 크기 계산은 MainActivity를 그대로 따른다. 속도 판넬이 폭의 45%를 차지하고, 나머지에서 구분선(1dp),
 * 좌우 여백(GAP 8dp씩), 위쪽 연결 상태·설정 버튼 줄(48dp), 아래 여백(GAP)을 뺀 영역에 타일을 둔다.
 */
class FoldLayoutTest {
    private class Screen(val name: String, widthPx: Int, heightPx: Int) {
        private val density = 450f / 160f // Fold 8 화면 밀도 설정(450dpi)
        val panelWidth = widthPx / density * (1 - SPEED_FRACTION) - 1f - GAP * 2
        val panelHeight = heightPx / density - HEADER - GAP

        val small get() = panelHeight < TileGrid.SMALL_PANEL_HEIGHT

        // DrivePanel의 타일 구성: 일반 4개(출력, 타이어, 누적 거리, 방향) + 넓은 칸 2개(내비, 음악).
        // 작은 화면은 온도를 넓은 한 줄로, 아니면 실외·실내 두 칸으로.
        val secondaryCount get() = if (small) 4 else 6
        val wideCount get() = if (small) 3 else 2

        fun split() = TileGrid.split(2, secondaryCount, panelWidth, panelHeight, GAP, secondaryWide = wideCount)

        override fun toString() = name
    }

    private val folded = Screen("folded", 1972, 1248)
    private val unfolded = Screen("unfolded", 2448, 1848)

    /** 회귀: 접은 화면에서 실외·실내 온도가 합쳐지지 않았다. 편 화면은 따로 둔다. */
    @Test
    fun temperaturesMergeOnlyWhenFolded() {
        assertTrue(folded.small)
        assertTrue(!unfolded.small)
    }

    /** 회귀: 편 화면에서 배터리·주행 가능 거리가 좌우로 놓여 빈칸이 많았다. 접은 화면은 좌우가 맞다. */
    @Test
    fun primaryTilesStackWhenUnfoldedAndSitSideBySideWhenFolded() {
        assertEquals(1, unfolded.split().primary.columns)
        assertEquals(2, folded.split().primary.columns)
    }

    /** 회귀: 편 화면에서 실외·실내 온도가 같은 줄(같은 높이)에 오지 않았다. 두 칸이 맨 앞이라 2열 이상이면 된다. */
    @Test
    fun outsideAndInsideShareARowWhenUnfolded() {
        assertTrue(unfolded.split().secondary.columns >= 2)
    }

    /** 회귀: 배터리·주행 가능 거리가 나머지보다 2~3배 커야 한다. */
    @Test
    fun primaryIsTwoToThreeTimesSecondary() {
        for (screen in listOf(folded, unfolded)) {
            val split = screen.split()
            val ratio = split.primaryValueSize / split.secondary.valueSize
            assertTrue("$screen ratio $ratio", ratio in 2f..3f)
        }
    }

    /** 회귀: 주행 가능 거리의 "km"가 다음 줄로 넘어갔다. "600 km"가 칸 폭 안에 들어가야 한다. */
    @Test
    fun rangeWithUnitFitsPrimaryTile() {
        for (screen in listOf(folded, unfolded)) {
            val split = screen.split()
            val v = split.primaryValueSize
            // 값 세 자리 + 단위 두 글자(값의 0.35배 크기). MainActivity.InfoTile 참고.
            val textWidth = (3 + 2 * 0.35f) * TileGrid.CHAR_WIDTH_EM * v
            val inner = split.primary.tileWidth - TileGrid.TILE_PADDING * 2
            assertTrue("$screen text $textWidth > $inner", textWidth <= inner)
            // 라벨(값의 0.3배, 최소 12dp) + 값 + 위아래 여백(6dp씩)이 칸 높이 안에 들어가야 한다.
            val label = maxOf(v * 0.3f, MIN_LABEL)
            assertTrue("$screen primary height", label + v + 12f <= split.primary.tileHeight)
        }
    }

    /** 회귀: 접은 화면에서 나머지 값이 칸 높이를 넘어 잘렸다. 글자도 너무 작지 않아야 한다. */
    @Test
    fun secondaryValuesFitTheirTilesAndStayReadable() {
        for (screen in listOf(folded, unfolded)) {
            val s = screen.split().secondary
            assertTrue("$screen value ${s.valueSize} vs tile ${s.tileHeight}", s.valueSize <= s.tileHeight)
            assertTrue("$screen value ${s.valueSize} too small", s.valueSize >= MIN_LABEL * 1.5f)
        }
    }

    /** 회귀: 타일이 영역을 다 채우지 않아 빈칸이 많았다. */
    @Test
    fun tilesFillThePanelHeight() {
        for (screen in listOf(folded, unfolded)) {
            val split = screen.split()
            val p = split.primary
            val s = split.secondary
            val used = p.tileHeight * p.rows + GAP * (p.rows - 1) + GAP + s.tileHeight * s.rows + GAP * (s.rows - 1)
            assertEquals("$screen", screen.panelHeight, used, 0.5f)
        }
    }

    private companion object {
        const val SPEED_FRACTION = 0.45f
        const val GAP = 8f
        const val HEADER = 48f
        const val MIN_LABEL = 12f
    }
}
