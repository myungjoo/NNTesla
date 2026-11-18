package io.github.myungjoo.nntesla.spike

import io.github.myungjoo.nntesla.ble.PollingScheduler
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient

/**
 * 차량 클라이언트 교체와 연결 상태에 맞춰 폴링(측정)을 시작·일시정지·재개한다. Android에 의존하지 않아
 * JVM 테스트로 확인한다. [client]를 빼고는 메인 스레드에서만 호출한다.
 *
 * 지켜야 하는 것(모두 실제로 났던 버그):
 * - 연결(READY)되면 사용자 조작 없이 폴링을 시작한다(0.1.0-spike에서 모든 칸이 "-"였다).
 * - 클라이언트를 바꾸면 이전 클라이언트에 묶인 스케줄러를 멈춘다.
 * - 교체되거나 끊긴 클라이언트가 늦게 보내는 상태 이벤트는 무시한다.
 * - 주행 중 끊겼다가 다시 연결(READY)됐는데 값이 들어오지 않으면 스스로 복구한다(0.2.1-spike에서 앱을
 *   재시작해야 했다). [checkStall] 참고.
 */
class PollingRunner<C : Any>(
    private val listener: Listener<C>,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    interface Listener<C> {
        /** [client]에 묶인 스케줄러를 만들고 시작한다. */
        fun startScheduler(client: C, config: PollingScheduler.Config): Handle

        /** 새 측정이 시작될 때(스케줄러를 처음 돌리기 전). */
        fun onRunStarted()

        /** 측정이 끝났을 때. */
        fun onRunStopped()

        /** 측정 중 스케줄러가 멈췄을 때(링크 끊김, 클라이언트 교체). */
        fun onGap()

        /** 지금 차량 링크가 READY인가(측정을 바로 시작할지 정할 때 쓴다). */
        fun isReady(): Boolean
    }

    fun interface Handle {
        fun stop()
    }

    /** 현재 클라이언트. BLE 스레드의 리스너도 읽는다. */
    @Volatile
    var client: C? = null
        private set

    var running = false
        private set

    private var config: PollingScheduler.Config? = null
    private var scheduler: Handle? = null

    /** 현재 클라이언트가 READY인가(이 객체가 받은 상태 이벤트 기준). */
    private var ready = false

    /** 마지막으로 진행이 있었던 시각: READY가 됨, 측정 시작, 값 수신. 값 수신은 BLE 스레드에서 쓴다. */
    @Volatile
    private var lastProgressNanos = 0L

    /** [lastProgressNanos] 이후 이미 시도한 복구 단계. 진행이 생기면 처음부터 다시 센다. */
    private var recoveryStage = Recovery.NONE
    private var recoveryFor = 0L

    val schedulerActive: Boolean get() = scheduler != null

    fun isCurrent(c: C): Boolean = c === client

    /**
     * 현재 클라이언트를 [c]로 바꾼다(null이면 끊기). 이전 클라이언트의 스케줄러는 그 전에 [pause]로 멈춰야
     * 한다. 측정은 유지하고 새 클라이언트가 READY가 되면 이어 간다.
     */
    fun setClient(c: C?) {
        client = c
        ready = false
    }

    /** 클라이언트를 바꾼다: 이전 스케줄러를 멈추고 [c]로 교체. */
    fun replaceClient(c: C?) {
        pause()
        setClient(c)
    }

    /** [c]의 상태가 [state]로 바뀌었을 때. */
    fun onState(c: C, state: TeslaVehicleClient.State) {
        if (c !== client) return
        val wasReady = ready
        ready = state == TeslaVehicleClient.State.READY
        if (ready && !wasReady) markProgress()
        when (PollingControl.onState(state, running, schedulerActive)) {
            PollingControl.Action.START_RUN -> startRun(PollingScheduler.Config())
            PollingControl.Action.RESUME -> resume()
            PollingControl.Action.PAUSE -> pause()
            PollingControl.Action.NONE -> Unit
        }
    }

    /** 측정을 시작한다. 이미 측정 중이면 false. 링크가 끊기면 일시정지했다가 다시 연결되면 이어 간다. */
    fun startRun(config: PollingScheduler.Config): Boolean {
        if (running) return false
        this.config = config
        running = true
        markProgress()
        listener.onRunStarted()
        if (listener.isReady()) resume()
        return true
    }

    fun stopRun() {
        scheduler?.stop()
        scheduler = null
        config = null
        if (running) {
            running = false
            listener.onRunStopped()
        }
    }

    private fun resume() {
        if (scheduler != null) return
        val c = client ?: return
        val cfg = config ?: return
        scheduler = listener.startScheduler(c, cfg)
    }

    /** 차량에서 값을 받았을 때(BLE 스레드에서 불러도 된다). */
    fun onData() {
        markProgress()
    }

    private fun markProgress() {
        lastProgressNanos = nanoClock()
    }

    enum class Recovery {
        NONE,

        /** 스케줄러를 새로 만들었다. 링크는 그대로 둔다. */
        RESTART_SCHEDULER,

        /** 호출한 쪽이 클라이언트를 새로 만들어 다시 연결해야 한다(앱 재시작과 같은 효과). */
        RECONNECT,
    }

    /**
     * 주기적으로(메인 스레드, 1초마다) 부른다. 측정 중이고 READY인데 값이 [RESTART_AFTER_NANOS] 동안
     * 하나도 오지 않으면 스케줄러를 새로 만들고, [RECONNECT_AFTER_NANOS]가 지나도 그대로면 [Recovery.RECONNECT]를
     * 돌려준다. 각 단계는 진행(READY, 측정 시작, 값 수신)이 생길 때까지 한 번만 한다. READY가 아닐 때는
     * 클라이언트가 스스로 재연결하므로 건드리지 않는다.
     */
    fun checkStall(): Recovery {
        if (!running || !ready) return Recovery.NONE
        val progress = lastProgressNanos
        if (progress != recoveryFor) {
            recoveryFor = progress
            recoveryStage = Recovery.NONE
        }
        val idle = nanoClock() - progress
        return when {
            idle >= RECONNECT_AFTER_NANOS && recoveryStage != Recovery.RECONNECT -> {
                recoveryStage = Recovery.RECONNECT
                Recovery.RECONNECT
            }
            idle >= RESTART_AFTER_NANOS && recoveryStage == Recovery.NONE -> {
                recoveryStage = Recovery.RESTART_SCHEDULER
                pause()
                resume()
                Recovery.RESTART_SCHEDULER
            }
            else -> Recovery.NONE
        }
    }

    /** 스케줄러를 멈춘다(측정은 유지). */
    fun pause() {
        val s = scheduler ?: return
        s.stop()
        scheduler = null
        listener.onGap()
    }

    companion object {
        /** 정상이면 초당 여러 번 값이 온다(속도 요청 제한 시간 1초, 나머지 3초). */
        const val RESTART_AFTER_NANOS = 4_000_000_000L
        const val RECONNECT_AFTER_NANOS = 8_000_000_000L
    }
}
