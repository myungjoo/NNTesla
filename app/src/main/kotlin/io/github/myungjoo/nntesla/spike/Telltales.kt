package io.github.myungjoo.nntesla.spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.myungjoo.nntesla.vehicledata.Closures

/**
 * 90년대 계기판식 경고등. 평소에는 어둡게 깔려 있다가 경고가 필요할 때만 밝은 색으로 켜진다.
 * 차량이 아직 보고하지 않은 값(null)은 경고가 아닌 것으로 본다.
 */
object TelltaleColors {
    val Off = Color.White.copy(alpha = 0.13f)
    val Red = Color(0xFFFF3B30)
    val Amber = Color(0xFFFFB300)
}

/** 도어·트렁크, 창문, 잠금 경고등을 한 줄로. 높이는 [modifier]가 정한다(속도 판넬 아래쪽 10% 이내). */
@Composable
fun TelltaleRow(closures: Closures?, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Telltale.SPACING, Alignment.CenterHorizontally)) {
        ClosureTelltale(closures, Modifier.fillMaxHeight().aspectRatio(0.8f))
        WindowTelltale(closures?.anyWindowOpen == true, Modifier.fillMaxHeight().aspectRatio(1f))
        LockTelltale(unlocked = closures?.locked == false, Modifier.fillMaxHeight().aspectRatio(0.8f))
    }
}

private object Telltale {
    val SPACING = androidx.compose.ui.unit.Dp(18f)
}

/** 위에서 본 차. 열린 도어·프렁크·트렁크가 차체 밖으로 꺾여 나온다. 하나라도 열리면 빨간색. */
@Composable
private fun ClosureTelltale(c: Closures?, modifier: Modifier) {
    val lit = c != null && (c.anyDoorOpen || c.anyLidOpen)
    val color = if (lit) TelltaleColors.Red else TelltaleColors.Off
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
        val bodyLeft = w * 0.28f
        val bodyRight = w * 0.72f
        val bodyTop = h * 0.08f
        val bodyBottom = h * 0.92f
        drawRoundRect(
            color,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyRight - bodyLeft, bodyBottom - bodyTop),
            cornerRadius = CornerRadius(w * 0.14f),
            style = stroke,
        )
        if (c == null) return@Canvas
        val doorLen = w * 0.24f
        fun door(open: Boolean?, left: Boolean, hingeY: Float) {
            if (open != true) return
            val x = if (left) bodyLeft else bodyRight
            val dx = if (left) -doorLen else doorLen
            drawLine(color, Offset(x, hingeY), Offset(x + dx, hingeY + doorLen * 0.8f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        door(c.frontLeftDoorOpen, left = true, hingeY = h * 0.34f)
        door(c.frontRightDoorOpen, left = false, hingeY = h * 0.34f)
        door(c.rearLeftDoorOpen, left = true, hingeY = h * 0.56f)
        door(c.rearRightDoorOpen, left = false, hingeY = h * 0.56f)
        if (c.frunkOpen == true) {
            drawLine(color, Offset(bodyLeft, bodyTop + h * 0.08f), Offset(bodyRight, bodyTop - h * 0.06f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        if (c.trunkOpen == true) {
            drawLine(color, Offset(bodyLeft, bodyBottom - h * 0.08f), Offset(bodyRight, bodyBottom + h * 0.06f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

/** 옆에서 본 창문과 아래 화살표. 창문이 하나라도 열리면 주황색. */
@Composable
private fun WindowTelltale(lit: Boolean, modifier: Modifier) {
    val color = if (lit) TelltaleColors.Amber else TelltaleColors.Off
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        val frame = Path().apply {
            moveTo(w * 0.12f, h * 0.85f)
            lineTo(w * 0.12f, h * 0.45f)
            lineTo(w * 0.45f, h * 0.15f)
            lineTo(w * 0.88f, h * 0.15f)
            lineTo(w * 0.88f, h * 0.85f)
            close()
        }
        drawPath(frame, color, style = stroke)
        arrowDown(color, x = w * 0.58f, top = h * 0.34f, bottom = h * 0.72f, head = w * 0.13f, width = stroke.width)
    }
}

/** 자물쇠. 잠기지 않았으면 걸쇠가 열린 모양으로 주황색. */
@Composable
private fun LockTelltale(unlocked: Boolean, modifier: Modifier) {
    val color = if (unlocked) TelltaleColors.Amber else TelltaleColors.Off
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.15f, h * 0.45f),
            size = Size(w * 0.7f, h * 0.47f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = stroke,
        )
        // 걸쇠: 잠겼으면 양 끝이 몸통에 붙고, 열렸으면 오른쪽 다리가 들려 있다.
        val shackleLeft = w * 0.28f
        val shackleRight = w * 0.72f
        val top = if (unlocked) h * 0.02f else h * 0.1f
        val shackle = Path().apply {
            moveTo(shackleLeft, h * 0.45f)
            lineTo(shackleLeft, top + (shackleRight - shackleLeft) / 2)
            arcTo(
                androidx.compose.ui.geometry.Rect(shackleLeft, top, shackleRight, top + (shackleRight - shackleLeft)),
                180f,
                180f,
                false,
            )
            lineTo(shackleRight, if (unlocked) h * 0.3f else h * 0.45f)
        }
        drawPath(shackle, color, style = stroke)
    }
}

private fun DrawScope.arrowDown(color: Color, x: Float, top: Float, bottom: Float, head: Float, width: Float) {
    drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = width, cap = StrokeCap.Round)
    drawLine(color, Offset(x - head, bottom - head), Offset(x, bottom), strokeWidth = width, cap = StrokeCap.Round)
    drawLine(color, Offset(x + head, bottom - head), Offset(x, bottom), strokeWidth = width, cap = StrokeCap.Round)
}
