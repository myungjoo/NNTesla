package io.github.myungjoo.nntesla.spike

import kotlin.math.ceil
import kotlin.math.min

/**
 * 주행 정보 타일을 남은 영역에 빈칸 없이 채우는 격자 계산. 열 수를 바꿔 보며 숫자 글자가 가장 커지는
 * 배치를 고른다. 타일이 영역을 꽉 채우므로 글자 크기는 타일 크기에서 정해진다.
 */
object TileGrid {
    /** 값 글자 높이 / 타일 높이. 라벨과 여백을 빼고 남는 비율. */
    const val VALUE_HEIGHT_RATIO = 0.55f

    /**
     * 값 글자 높이 / 타일 안쪽 폭. 값은 대부분 3자리 이하(배터리 85, 거리 350, 온도 -5)이고 가장 긴 것이
     * 출력 "-120"이다. 숫자 하나가 글자 높이의 약 0.6이고 단위가 0.4em 남짓이라 네 자리와 단위가 들어가는 비율.
     */
    const val VALUE_WIDTH_RATIO = 0.34f

    /**
     * 나머지 타일은 라벨을 왼쪽, 값을 오른쪽에 한 줄로 둔다(높이를 아껴 낮은 타일에서도 값이 잘리지 않게).
     * 높이에서는 값만 들어가면 되고, 폭에는 라벨(약 1.5em)과 값·단위(약 2.9em)가 함께 들어가야 한다.
     */
    const val INLINE_HEIGHT_RATIO = 0.7f
    const val INLINE_WIDTH_RATIO = 0.21f

    /** 타일 좌우 안쪽 여백(한쪽). */
    const val TILE_PADDING = 12f

    /** 주요 정보(배터리, 주행 가능 거리) 글자가 나머지 글자보다 커야 하는 배수의 범위. */
    const val PRIMARY_MIN_RATIO = 2.5f
    const val PRIMARY_MAX_RATIO = 3f

    data class Layout(val columns: Int, val rows: Int, val tileWidth: Float, val tileHeight: Float, val valueSize: Float)

    /**
     * 주요 타일 영역(위)과 나머지 타일 영역(아래)으로 나눈 배치. [primaryValueSize]는 [primary]가 허용하는
     * 크기를 나머지의 [PRIMARY_MAX_RATIO]배로 자른 값이다.
     */
    data class Split(val primaryHeight: Float, val primary: Layout, val secondary: Layout, val primaryValueSize: Float)

    /**
     * [count]개 타일을 [width]×[height] 영역(같은 단위, 예: dp)에 [gap] 간격으로 놓을 때 값 글자가 가장
     * 커지는 배치. 같으면 열이 적은 쪽(타일이 넓은 쪽)을 고른다.
     */
    fun choose(
        count: Int,
        width: Float,
        height: Float,
        gap: Float,
        maxColumns: Int = 4,
        heightRatio: Float = VALUE_HEIGHT_RATIO,
        widthRatio: Float = VALUE_WIDTH_RATIO,
    ): Layout {
        require(count > 0)
        var best: Layout? = null
        for (columns in 1..min(maxColumns, count)) {
            val rows = ceil(count / columns.toFloat()).toInt()
            val tileWidth = (width - gap * (columns - 1)) / columns
            val tileHeight = (height - gap * (rows - 1)) / rows
            if (tileWidth <= 0f || tileHeight <= 0f) continue
            val valueSize = min(tileHeight * heightRatio, (tileWidth - TILE_PADDING * 2) * widthRatio)
            if (best == null || valueSize > best.valueSize) {
                best = Layout(columns, rows, tileWidth, tileHeight, valueSize)
            }
        }
        return best ?: Layout(1, count, width, height / count, 0f)
    }

    /**
     * 위쪽에 [primaryCount]개(라벨 위·값 아래), 아래쪽에 [secondaryCount]개(한 줄형) 타일을 둘 때의 높이 나누기. 주요 글자가 나머지의
     * [PRIMARY_MIN_RATIO]배 이상인 나누기 중 나머지 글자가 가장 큰 것을 고른다. 그런 나누기가 없으면
     * 배수가 가장 큰 것을 고른다.
     */
    fun split(primaryCount: Int, secondaryCount: Int, width: Float, height: Float, gap: Float): Split {
        val usable = height - gap
        var best: Split? = null
        var bestRatio = 0f
        var fallback: Split? = null
        for (step in 20..85) {
            val primaryHeight = usable * step / 100f
            val primary = choose(primaryCount, width, primaryHeight, gap)
            val secondary = inline(secondaryCount, width, usable - primaryHeight, gap)
            if (secondary.valueSize <= 0f) continue
            val ratio = primary.valueSize / secondary.valueSize
            val candidate = Split(
                primaryHeight,
                primary,
                secondary,
                min(primary.valueSize, secondary.valueSize * PRIMARY_MAX_RATIO),
            )
            if (ratio >= PRIMARY_MIN_RATIO) {
                if (best == null || secondary.valueSize > best.secondary.valueSize) best = candidate
            } else if (ratio > bestRatio) {
                bestRatio = ratio
                fallback = candidate
            }
        }
        return best ?: fallback ?: Split(usable / 2, choose(primaryCount, width, usable / 2, gap), inline(secondaryCount, width, usable / 2, gap), 0f)
    }

    private fun inline(count: Int, width: Float, height: Float, gap: Float) =
        choose(count, width, height, gap, heightRatio = INLINE_HEIGHT_RATIO, widthRatio = INLINE_WIDTH_RATIO)
}
