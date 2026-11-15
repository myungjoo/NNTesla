package io.github.myungjoo.nntesla.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 연결 설정 타임아웃과 링크 종료 시 타이머 정리를 검증한다. */
class ConnectionTimersTest {
    private fun TeslaVehicleClient.connectTimeout() = field("connectTimeout") as Runnable

    @Test
    fun connectTimeoutFailsStuckConnection() {
        val states = ArrayList<TeslaVehicleClient.State>()
        val client = Fixtures.client(states)
        client.setField("state", TeslaVehicleClient.State.CONNECTING)

        client.connectTimeout().run()

        assertEquals(listOf(TeslaVehicleClient.State.FAILED), states)
        assertTrue(!client.link.isReady)
    }

    @Test
    fun connectTimeoutIgnoredOnceConnected() {
        val states = ArrayList<TeslaVehicleClient.State>()
        val client = Fixtures.client(states)
        // 타이머가 늦게 발화해도 이미 handshake 이후 단계라면 아무 일도 하지 않아야 한다.
        for (state in listOf(TeslaVehicleClient.State.HANDSHAKING, TeslaVehicleClient.State.READY)) {
            client.setField("state", state)
            client.connectTimeout().run()
        }
        assertEquals(emptyList<TeslaVehicleClient.State>(), states)
    }

    @Test
    fun linkCloseDropsPendingHandshake() {
        val client = Fixtures.client()
        client.setField("state", TeslaVehicleClient.State.HANDSHAKING)
        client.setField("handshakeUuid", ByteArray(16))

        client.link.listener.onLinkClosed("disconnected (status=8)")

        // 이전 링크의 handshake 응답·타이머가 다음 연결에 섞이지 않도록 정리돼야 한다.
        assertNull(client.field("handshakeUuid"))
        assertEquals(TeslaVehicleClient.State.FAILED, client.field("state"))
    }
}
