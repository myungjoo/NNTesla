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

    /**
     * 주요 타일(배터리, 주행 가능 거리) 값 글자 높이 / 타일 안쪽 폭. 값이 많아야 세 자리(100 %, 600 km)라
     * 일반 타일([VALUE_WIDTH_RATIO], 네 자리 기준)보다 크게 잡는다.
     */
    const val PRIMARY_WIDTH_RATIO = 0.4f

    /**
     * 남은 영역 높이(dp)가 이보다 작으면 "작은 화면"으로 보고 실외·실내 온도를 한 줄("Out: / In:")로 합친다.
     * 접은 Fold 가로(약 390dp)와 커버 화면 세로는 작은 화면, 편 Fold 가로(약 600dp)는 아니다.
     */
    const val SMALL_PANEL_HEIGHT = 480f

    /** 주요 타일은 좌우로 나란히 놓아 글자가 이 배수 넘게 커질 때만 나란히 두고, 아니면 위아래로 쌓는다. */
    const val PRIMARY_PREFER_STACKED = 1.15f

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
     * 커지는 배치. 같으면 열이 적은 쪽(타일이 넓은 쪽)을 고른다. [wide]개 타일은 한 줄을 통째로 쓴다
     * (내비게이션, 음악처럼 글이 긴 것). 폭 제한은 일반 타일 기준으로만 계산한다.
     */
    fun choose(
        count: Int,
        width: Float,
        height: Float,
        gap: Float,
        maxColumns: Int = 4,
        heightRatio: Float = VALUE_HEIGHT_RATIO,
        widthRatio: Float = VALUE_WIDTH_RATIO,
        wide: Int = 0,
        preferFewerColumns: Float = 1f,
    ): Layout {
        require(count > 0 || wide > 0)
        if (count == 0) {
            val tileHeight = (height - gap * (wide - 1)) / wide
            return Layout(1, wide, width, tileHeight, min(tileHeight * heightRatio, (width - TILE_PADDING * 2) * widthRatio))
        }
        var best: Layout? = null
        for (columns in 1..min(maxColumns, count)) {
            val rows = ceil(count / columns.toFloat()).toInt() + wide
            val tileWidth = (width - gap * (columns - 1)) / columns
            val tileHeight = (height - gap * (rows - 1)) / rows
            if (tileWidth <= 0f || tileHeight <= 0f) continue
            val valueSize = min(tileHeight * heightRatio, (tileWidth - TILE_PADDING * 2) * widthRatio)
            // 열을 늘리는 배치는 글자가 [preferFewerColumns]배 넘게 커질 때만 고른다(넓고 낮은 타일이 빈칸이 적다).
            if (best == null || valueSize > best.valueSize * preferFewerColumns) {
                best = Layout(columns, rows, tileWidth, tileHeight, valueSize)
            }
        }
        return best ?: Layout(1, count + wide, width, height / (count + wide), 0f)
    }

    /**
     * 한 줄형 타일에서 값 글자를 칸 폭에 맞춘다. [maxValueSize]를 넘지 않으면서 [label], [value], [unit]이
     * [innerWidth] 안에 들어가는 크기. 라벨·단위는 [labelSize]로, 글자 폭은 숫자·라틴 문자 평균
     * ([CHAR_WIDTH_EM]em)으로 어림한다.
     */
    fun fitValue(innerWidth: Float, label: String, value: String, unit: String, labelSize: Float, maxValueSize: Float): Float {
        val fixed = (label.length + unit.length + 2) * CHAR_WIDTH_EM * labelSize
        val perValueEm = value.length.coerceAtLeast(1) * CHAR_WIDTH_EM
        val fit = (innerWidth - fixed) / perValueEm
        return min(maxValueSize, fit).coerceAtLeast(0f)
    }

    /** 숫자·라틴 문자의 평균 폭(글자 크기 대비). */
    const val CHAR_WIDTH_EM = 0.58f

    /**
     * 위쪽에 [primaryCount]개(라벨 위·값 아래), 아래쪽에 [secondaryCount]개(한 줄형) 타일을 둘 때의 높이 나누기. 주요 글자가 나머지의
     * [PRIMARY_MIN_RATIO]배 이상인 나누기 중 나머지 글자가 가장 큰 것을 고른다. 그런 나누기가 없으면
     * 배수가 가장 큰 것을 고른다.
     */
    fun split(primaryCount: Int, secondaryCount: Int, width: Float, height: Float, gap: Float, secondaryWide: Int = 0): Split {
        val usable = height - gap
        var best: Split? = null
        var bestRatio = 0f
        var fallback: Split? = null
        for (step in 20..85) {
            val primaryHeight = usable * step / 100f
            val primary = choose(
                primaryCount,
                width,
                primaryHeight,
                gap,
                widthRatio = PRIMARY_WIDTH_RATIO,
                preferFewerColumns = PRIMARY_PREFER_STACKED,
            )
            val secondary = inline(secondaryCount, width, usable - primaryHeight, gap, secondaryWide)
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
        return best ?: fallback ?: Split(usable / 2, choose(primaryCount, width, usable / 2, gap, widthRatio = PRIMARY_WIDTH_RATIO), inline(secondaryCount, width, usable / 2, gap, secondaryWide), 0f)
    }

    private fun inline(count: Int, width: Float, height: Float, gap: Float, wide: Int) =
        choose(count, width, height, gap, heightRatio = INLINE_HEIGHT_RATIO, widthRatio = INLINE_WIDTH_RATIO, wide = wide)
}
