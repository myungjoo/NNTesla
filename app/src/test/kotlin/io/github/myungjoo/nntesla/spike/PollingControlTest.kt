package io.github.myungjoo.nntesla.spike

import io.github.myungjoo.nntesla.ble.TeslaVehicleClient.State
import io.github.myungjoo.nntesla.spike.PollingControl.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class PollingControlTest {
    /** 0.1.0-spike 회귀: 연결만 되고 값을 요청하지 않아 모든 칸이 "-"였다. */
    @Test
    fun readyWithoutRunStartsPolling() {
        assertEquals(Action.START_RUN, PollingControl.onState(State.READY, running = false, schedulerActive = false))
    }

    @Test
    fun reconnectResumesRun() {
        assertEquals(Action.RESUME, PollingControl.onState(State.READY, running = true, schedulerActive = false))
    }

    @Test
    fun readyWhilePollingDoesNothing() {
        assertEquals(Action.NONE, PollingControl.onState(State.READY, running = true, schedulerActive = true))
    }

    @Test
    fun linkDownPausesPolling() {
        for (state in State.entries.filter { it != State.READY }) {
            assertEquals(state.name, Action.PAUSE, PollingControl.onState(state, running = true, schedulerActive = true))
            assertEquals(state.name, Action.NONE, PollingControl.onState(state, running = true, schedulerActive = false))
            assertEquals(state.name, Action.NONE, PollingControl.onState(state, running = false, schedulerActive = false))
        }
    }

    /** 연결 → 끊김 → 재연결 전체 흐름에서 READY일 때는 항상 폴링이 돌고 있어야 한다. */
    @Test
    fun pollingRunsWheneverReady() {
        var running = false
        var schedulerActive = false
        val sequence = listOf(
            State.SCANNING, State.CONNECTING, State.HANDSHAKING, State.READY,
            State.FAILED, State.SCANNING, State.CONNECTING, State.HANDSHAKING, State.READY,
            State.NEEDS_PAIRING, State.HANDSHAKING, State.READY,
        )
        for (state in sequence) {
            when (PollingControl.onState(state, running, schedulerActive)) {
                Action.START_RUN -> { running = true; schedulerActive = true }
                Action.RESUME -> schedulerActive = true
                Action.PAUSE -> schedulerActive = false
                Action.NONE -> Unit
            }
            assertEquals("after $state", state == State.READY, schedulerActive)
        }
    }
}
