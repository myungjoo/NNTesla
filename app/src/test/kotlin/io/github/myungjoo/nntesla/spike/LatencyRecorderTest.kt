package io.github.myungjoo.nntesla.spike

import com.tesla.generated.carserver.vehicle.Vehicle
import io.github.myungjoo.nntesla.ble.PollingScheduler.Kind
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient
import io.github.myungjoo.nntesla.protocol.StateCategory
import io.github.myungjoo.nntesla.vehicledata.FastState
import io.github.myungjoo.nntesla.vehicledata.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 지연 상한(latency bound) 계산과, 기록 중에도 snapshot/exportCsv가 안전하게 동작하는지 검증한다. */
class LatencyRecorderTest {
    private val frame = ms(16)
    private val ok = TeslaVehicleClient.Outcome.Ok(Vehicle.VehicleData.getDefaultInstance())

    private fun ms(v: Long) = v * 1_000_000L

    private fun result(
        categories: Set<StateCategory>,
        sendMs: Long,
        decodedMs: Long,
        outcome: TeslaVehicleClient.Outcome = ok,
    ) = TeslaVehicleClient.Result(
        categories, ms(sendMs), ms(sendMs + 5), ms(decodedMs - 10), ms(decodedMs - 2), ms(decodedMs), 100, 200, outcome,
    )

    private fun fast(receivedMs: Long) = FastState(50f, ShiftState.D, 10, null, ms(receivedMs))

    @Test
    fun latencyBoundsMatchDefinition() {
        val r = LatencyRecorder(frame)
        val drive = setOf(StateCategory.DRIVE)

        // 시각 0은 recorder가 "이전 샘플 없음"으로 쓰므로 1000 ms부터 시작한다.
        // 첫 표시 샘플은 이전 샘플이 없어 상한을 계산하지 않는다.
        r.record(Kind.FAST, result(drive, sendMs = 1000, decodedMs = 1100), fast(1100))
        r.markDrawn(ms(1100), ms(1110)) // 표시 = 1110 + 16 = 1126 ms
        // 표시 = 1310 + 16 = 1326 ms, 상한 = 1326 - 1000(이전 표시 샘플의 송신 시각) = 326
        r.record(Kind.FAST, result(drive, sendMs = 1200, decodedMs = 1300), fast(1300))
        r.markDrawn(ms(1300), ms(1310))
        // UI가 record보다 먼저 그린 경우: 표시 = 1505 + 16 = 1521 ms, 상한 = 1521 - 1200 = 321
        r.markDrawn(ms(1500), ms(1505))
        r.record(Kind.FAST, result(drive, sendMs = 1400, decodedMs = 1500), fast(1500))
        r.record(Kind.FAST, result(drive, sendMs = 1600, decodedMs = 1700, TeslaVehicleClient.Outcome.Timeout), null)

        // slow 표시 = 디코드 + 2 프레임. 상한 = 3600 + 32 - 1000 = 2632
        val charge = setOf(StateCategory.CHARGE)
        r.record(Kind.SLOW, result(charge, sendMs = 1000, decodedMs = 1050), null)
        r.record(Kind.SLOW, result(charge, sendMs = 3500, decodedMs = 3600), null)

        val report = r.snapshot()
        assertEquals(4, report.fast.requests)
        assertEquals(3, report.fast.ok)
        assertEquals(1, report.fast.timeouts)
        assertEquals(3, report.fastDrawn)
        val fastBound = report.fast.latencyBoundMs!!
        assertEquals(2, fastBound.n)
        assertEquals(321.0, fastBound.p50, 1e-9)
        assertEquals(326.0, fastBound.max, 1e-9)
        assertEquals(true, report.fast.pass)

        val slowBound = report.slow.getValue("CHARGE").latencyBoundMs!!
        assertEquals(1, slowBound.n)
        assertEquals(2632.0, slowBound.max, 1e-9)
        assertEquals(true, report.slow.getValue("CHARGE").pass)
    }

    @Test
    fun snapshotAndExportWhileRecording() {
        val r = LatencyRecorder(frame)
        val drive = setOf(StateCategory.DRIVE)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                for (i in 1L..20_000L) {
                    r.record(Kind.FAST, result(drive, sendMs = i * 10, decodedMs = i * 10 + 5), fast(i * 10 + 5))
                    r.markDrawn(ms(i * 10 + 5), ms(i * 10 + 6))
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        writer.start()
        val csv = File.createTempFile("latency", ".csv").apply { deleteOnExit() }
        while (writer.isAlive) {
            r.snapshot()
            r.exportCsv(csv)
        }
        writer.join()
        failure.get()?.let { throw it }

        val report = r.snapshot()
        assertEquals(20_000, report.fast.requests)
        assertEquals(20_000, report.fastDrawn)
        r.exportCsv(csv)
        assertEquals(1 + 20_000, csv.readLines().size)
        assertTrue(report.fast.latencyBoundMs!!.n > 0)
    }
}
