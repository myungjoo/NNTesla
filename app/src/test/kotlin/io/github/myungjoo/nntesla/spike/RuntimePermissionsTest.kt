package io.github.myungjoo.nntesla.spike

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 회귀(4934561): 차량이 iBeacon으로 광고해서 위치 권한이 없으면 스캔되지 않는다. 매니페스트에 선언하는
 * 것(tesla-ble ScanPermissionTest)에 더해 실행 중에 실제로 요청해야 한다. Android 12부터는 COARSE를
 * 함께 요청해야 FINE을 받을 수 있다.
 */
class RuntimePermissionsTest {
    @Test
    fun requestsScanConnectAndLocation() {
        val requested = SpikeViewModel.REQUIRED_PERMISSIONS.toSet()
        for (p in listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )) {
            assertTrue(p, p in requested)
        }
    }
}
