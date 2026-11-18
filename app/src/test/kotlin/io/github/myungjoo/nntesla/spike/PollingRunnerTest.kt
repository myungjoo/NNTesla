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
    private var now = 0L
    private val runner = PollingRunner(recorder) { now }

    private fun advanceSeconds(seconds: Double) {
        now += Math.round(seconds * 1e9)
    }

    private fun checkAt(seconds: Double): PollingRunner.Recovery {
        advanceSeconds(seconds)
        return runner.checkStall()
    }

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

    /**
     * 회귀(0.2.1-spike): 주행 중 끊겼다가 스스로 다시 연결(Connected)됐는데 값이 갱신되지 않아 앱을 재시작해야
     * 했다. READY인데 값이 오지 않으면 먼저 스케줄러를 새로 만들고, 그래도 안 되면 다시 연결을 요청한다.
     */
    @Test
    fun noDataAfterReconnectRestartsPollingThenReconnects() {
        val car = FakeClient("car")
        connect(car)
        runner.onData()
        state(car, State.FAILED)
        state(car, State.SCANNING)
        advanceSeconds(3.0)
        state(car, State.READY)
        recorder.events.clear()

        assertEquals(PollingRunner.Recovery.NONE, checkAt(3.9))
        assertEquals(PollingRunner.Recovery.RESTART_SCHEDULER, checkAt(0.1))
        assertEquals(listOf("stop car", "gap", "start car"), recorder.events)
        assertEquals(listOf(car), recorder.active)
        assertTrue("run continues", runner.running)

        assertEquals(PollingRunner.Recovery.NONE, checkAt(3.9))
        assertEquals(PollingRunner.Recovery.RECONNECT, checkAt(0.1))
        // 다시 연결이 늦어져도 같은 일을 되풀이하지 않는다.
        for (i in 1..30) assertEquals(PollingRunner.Recovery.NONE, checkAt(1.0))
    }

    /** 값이 계속 들어오면 아무것도 건드리지 않는다(정상 주행). */
    @Test
    fun steadyDataNeverTriggersRecovery() {
        val car = FakeClient("car")
        connect(car)
        recorder.events.clear()
        repeat(120) {
            advanceSeconds(0.5)
            runner.onData()
            assertEquals(PollingRunner.Recovery.NONE, runner.checkStall())
        }
        // 값 사이 간격이 길어도(느린 응답) 4초 안이면 그대로 둔다.
        repeat(10) {
            advanceSeconds(3.5)
            runner.onData()
            assertEquals(PollingRunner.Recovery.NONE, runner.checkStall())
        }
        assertTrue(recorder.events.isEmpty())
    }

    /** 끊겨 있는 동안은 클라이언트가 스스로 재연결하므로 기다린다. 측정을 멈춘 뒤에도 마찬가지다. */
    @Test
    fun noRecoveryWhileDisconnectedOrStopped() {
        val car = FakeClient("car")
        connect(car)
        state(car, State.FAILED)
        state(car, State.SCANNING)
        assertEquals(PollingRunner.Recovery.NONE, checkAt(60.0))

        state(car, State.READY)
        runner.stopRun()
        assertEquals(PollingRunner.Recovery.NONE, checkAt(60.0))
    }

    /** 연결된 채로 오래 있다가 측정을 다시 시작해도 곧바로 복구로 보지 않는다. */
    @Test
    fun restartingRunWhileConnectedStartsTheClockAgain() {
        val car = FakeClient("car")
        connect(car)
        runner.stopRun()
        advanceSeconds(60.0)
        runner.startRun(PollingScheduler.Config())
        assertEquals(PollingRunner.Recovery.NONE, checkAt(3.9))
    }

    /** 다시 연결 요청 뒤 새 클라이언트가 READY가 되면 처음부터 다시 센다. 값이 오면 단계도 처음으로 돌아간다. */
    @Test
    fun newConnectionAndNewDataRearmTheWatchdog() {
        val first = FakeClient("first")
        connect(first)
        assertEquals(PollingRunner.Recovery.RESTART_SCHEDULER, checkAt(4.0))
        assertEquals(PollingRunner.Recovery.RECONNECT, checkAt(4.0))

        val second = FakeClient("second")
        advanceSeconds(2.0)
        connect(second)
        assertEquals(listOf(second), recorder.active)
        assertEquals(PollingRunner.Recovery.NONE, checkAt(3.9))
        advanceSeconds(0.05)
        runner.onData()
        assertEquals(PollingRunner.Recovery.NONE, checkAt(3.9))
        assertEquals("first step again, not reconnect", PollingRunner.Recovery.RESTART_SCHEDULER, checkAt(0.2))
    }
}
