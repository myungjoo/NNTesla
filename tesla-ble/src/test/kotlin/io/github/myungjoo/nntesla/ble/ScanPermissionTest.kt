package io.github.myungjoo.nntesla.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 회귀(4934561): BLUETOOTH_SCAN에 neverForLocation을 붙이면 Android가 iBeacon 형식 광고를 스캔 결과에서
 * 걸러 낸다. Tesla 차량은 iBeacon으로 광고하므로 차량이 발견되지 않았다("vehicle not found").
 * 대신 위치 권한을 선언해야 한다. Gradle 단위 테스트는 모듈 디렉터리에서 돈다.
 */
class ScanPermissionTest {
    private val permissions: List<Element> = run {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName("uses-permission")
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun named(name: String) = permissions.filter { it.getAttributeNS(ANDROID, "name") == name }

    @Test
    fun scanPermissionIsNotNeverForLocation() {
        val scan = named("android.permission.BLUETOOTH_SCAN")
        assertTrue("BLUETOOTH_SCAN declared", scan.isNotEmpty())
        for (e in scan) {
            assertFalse(
                "neverForLocation filters the vehicle's iBeacon advertisement",
                e.getAttributeNS(ANDROID, "usesPermissionFlags").contains("neverForLocation"),
            )
        }
    }

    @Test
    fun locationPermissionsAreDeclared() {
        assertTrue(named("android.permission.ACCESS_FINE_LOCATION").isNotEmpty())
        assertTrue(named("android.permission.ACCESS_COARSE_LOCATION").isNotEmpty())
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
