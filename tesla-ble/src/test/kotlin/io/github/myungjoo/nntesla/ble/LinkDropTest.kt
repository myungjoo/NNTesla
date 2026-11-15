package io.github.myungjoo.nntesla.ble

import io.github.myungjoo.nntesla.protocol.StateCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 측정 중(요청 처리 중) BLE 링크가 끊길 때의 동작을 검증한다.
 *
 * 예전에는 대기 요청을 실패 처리하는 시점에 클라이언트가 아직 READY여서, 스케줄러 콜백이 닫힌 링크에
 * 곧바로 재요청하다 `IllegalStateException("link not ready")`로 앱이 죽었다.
 */
class LinkDropTest {
    @Test
    fun linkDropWhileRequestInFlightFailsCleanly() {
        val states = ArrayList<TeslaVehicleClient.State>()
        val client = Fixtures.client(states)
        client.setField("state", TeslaVehicleClient.State.READY)
        client.setField("session", Fixtures.session())
        client.link.setField("ready", true)

        val outcomes = ArrayList<TeslaVehicleClient.Outcome>()
        val scheduler = PollingScheduler(client, PollingScheduler.Config(slowEnabled = false)) { _, result ->
            outcomes += result.outcome
        }
        scheduler.setField("running", true)
        PollingScheduler::class.java.getDeclaredMethod("fill").apply { isAccessible = true }.invoke(scheduler)
        assertEquals(1, client.inFlight)

        // BleLink.fail()과 같은 순서: 링크를 닫은 뒤 리스너에 알린다.
        client.link.setField("ready", false)
        client.link.listener.onLinkClosed("disconnected (status=8)")

        assertEquals(listOf(TeslaVehicleClient.State.FAILED), states)
        assertEquals(0, client.inFlight)
        assertTrue(outcomes.isNotEmpty())
        assertTrue(outcomes.all { it is TeslaVehicleClient.Outcome.Failed })
        assertFalse(scheduler.field("running") as Boolean)
    }

    @Test
    fun requestOnClosedLinkReportsNotReady() {
        val client = Fixtures.client()
        client.setField("state", TeslaVehicleClient.State.READY)
        client.setField("session", Fixtures.session())
        // 링크는 준비되지 않은 상태(기본값).

        var outcome: TeslaVehicleClient.Outcome? = null
        client.requestState(setOf(StateCategory.DRIVE), 1_000) { outcome = it.outcome }

        val failed = outcome as TeslaVehicleClient.Outcome.Failed
        assertEquals("not ready", failed.reason)
        assertEquals(0, client.inFlight)
    }
}
