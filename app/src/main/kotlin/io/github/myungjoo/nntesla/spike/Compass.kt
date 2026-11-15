package io.github.myungjoo.nntesla.spike

object Compass {
    private val NAMES = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** 북쪽 0도에서 시계 방향 각도를 8방위 이름으로. 경계는 각 방위의 ±22.5도. 값이 없으면 "-". */
    fun name(degrees: Int?): String {
        if (degrees == null) return "-"
        val d = ((degrees % 360) + 360) % 360
        return NAMES[((d * 2 + 45) / 90) % 8]
    }
}
