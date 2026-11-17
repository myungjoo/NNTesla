package io.github.myungjoo.nntesla.spike

import io.github.myungjoo.nntesla.ble.PollingScheduler
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingRunnerTest {
    private class FakeClient(val name: String) {
        override fun toString() = name
    }

    /** 스케줄러 시작·정지와 콜백을 기록한다. */
    private class Recorder : PollingRunner.Listener<FakeClient> {
        val events = ArrayList<String>()
        val active = ArrayList<FakeClient>()
        var ready = false

        override fun startScheduler(client: FakeClient, config: PollingScheduler.Config): PollingRunner.Handle {
            events += "start $client"
            active += client
            return PollingRunner.Handle {
                events += "stop $client"
                active -= client
            }
        }

        override fun onRunStarted() {
            events += "run started"
        }

        override fun onRunStopped() {
            events += "run stopped"
        }

        override fun onGap() {
            events += "gap"
        }

        override fun isReady() = ready
    }

    private val recorder = Recorder()
    private val runner = PollingRunner(recorder)

    /** VM과 같이 상태값(_status)을 먼저 바꾸고 runner에 알린다. 교체된 클라이언트의 이벤트는 상태값도 바꾸지 않는다. */
    private fun state(c: FakeClient, s: State) {
        if (runner.isCurrent(c)) recorder.ready = s == State.READY
        runner.onState(c, s)
    }

    private fun connect(c: FakeClient) {
        runner.replaceClient(c)
        for (s in listOf(State.SCANNING, State.CONNECTING, State.HANDSHAKING, State.READY)) state(c, s)
    }

    /** 0.1.0-spike 회귀: 연결(Connected)만 되고 값을 요청하지 않아 모든 칸이 "-"였다. */
    @Test
    fun connectingStartsPollingWithoutUserAction() {
        val car = FakeClient("car")
        connect(car)
        assertTrue(runner.running)
        assertEquals(listOf(car), recorder.active)
        assertEquals(listOf("run started", "start car"), recorder.events)
    }

    @Test
    fun linkDropPausesAndReconnectResumes() {
        val car = FakeClient("car")
        connect(car)
        state(car, State.FAILED)
        assertTrue(recorder.active.isEmpty())
        assertTrue("run continues across the outage", runner.running)
        state(car, State.SCANNING)
        state(car, State.READY)
        assertEquals(listOf(car), recorder.active)
        assertEquals(listOf("run started", "start car", "stop car", "gap", "start car"), recorder.events)
    }

    /** 회귀(015c64b): 다시 Connect하면 이전 클라이언트에 묶인 스케줄러가 계속 돌았다. */
    @Test
    fun replacingClientStopsOldSchedulerAndRestartsOnNewOne() {
        val first = FakeClient("first")
        val second = FakeClient("second")
        connect(first)
        runner.replaceClient(second)
        assertTrue("old scheduler stopped", recorder.active.isEmpty())
        state(second, State.READY)
        assertEquals(listOf(second), recorder.active)
    }

    /** 회귀(015c64b): 교체되거나 끊긴 클라이언트가 늦게 보낸 상태 이벤트가 새 연결을 흔들었다. */
    @Test
    fun lateEventsFromReplacedClientAreIgnored() {
        val first = FakeClient("first")
        val second = FakeClient("second")
        connect(first)
        runner.replaceClient(second)
        assertFalse(runner.isCurrent(first))

        state(first, State.READY)
        assertTrue("stale READY must not start polling on any client", recorder.active.isEmpty())

        connect(second)
        state(first, State.FAILED)
        assertEquals("stale FAILED must not pause the new client", listOf(second), recorder.active)
    }

    @Test
    fun disconnectStopsEverythingAndIgnoresShutdownEvents() {
        val car = FakeClient("car")
        connect(car)
        runner.stopRun()
        runner.replaceClient(null)
        assertFalse(runner.running)
        assertTrue(recorder.active.isEmpty())
        // shutdown()이 늦게 보내는 상태 이벤트.
        state(car, State.IDLE)
        state(car, State.READY)
        assertFalse(runner.running)
        assertTrue(recorder.active.isEmpty())
    }

    @Test
    fun userStartedRunBeforeConnectBeginsOnReady() {
        val car = FakeClient("car")
        runner.replaceClient(car)
        assertTrue(runner.startRun(PollingScheduler.Config(maxInFlight = 2)))
        assertTrue("not ready yet", recorder.active.isEmpty())
        state(car, State.READY)
        assertEquals(listOf(car), recorder.active)
        assertFalse("second start while running is refused", runner.startRun(PollingScheduler.Config()))
    }

    @Test
    fun stopThenStartWithNewConfigWhileConnected() {
        val car = FakeClient("car")
        connect(car)
        runner.stopRun()
        assertTrue(recorder.active.isEmpty())
        assertTrue(runner.startRun(PollingScheduler.Config(maxInFlight = 2)))
        assertEquals(listOf(car), recorder.active)
    }
}
