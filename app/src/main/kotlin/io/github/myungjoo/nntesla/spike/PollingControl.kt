package io.github.myungjoo.nntesla.spike

import io.github.myungjoo.nntesla.ble.TeslaVehicleClient

/**
 * 연결 상태가 바뀔 때 폴링을 어떻게 할지 정한다. 계기판이므로 연결(READY)되면 사용자 조작 없이 값을 받기
 * 시작해야 한다. 연결은 됐는데 값을 요청하지 않아 모든 칸이 "-"로 남던 버그(0.1.0-spike)를 막으려고
 * 판단을 이 순수 함수로 떼어 JVM 테스트로 확인한다.
 */
object PollingControl {
    enum class Action {
        /** 측정이 없으니 기본 설정으로 새로 시작한다. */
        START_RUN,

        /** 측정은 켜져 있고 연결이 돌아왔으니 스케줄러를 다시 돌린다. */
        RESUME,

        /** 연결이 끊겼으니 스케줄러를 멈춘다(측정 자체는 유지해서 다시 연결되면 이어 간다). */
        PAUSE,
        NONE,
    }

    fun onState(state: TeslaVehicleClient.State, running: Boolean, schedulerActive: Boolean): Action = when {
        state == TeslaVehicleClient.State.READY && !running -> Action.START_RUN
        state == TeslaVehicleClient.State.READY && !schedulerActive -> Action.RESUME
        state != TeslaVehicleClient.State.READY && schedulerActive -> Action.PAUSE
        else -> Action.NONE
    }
}
