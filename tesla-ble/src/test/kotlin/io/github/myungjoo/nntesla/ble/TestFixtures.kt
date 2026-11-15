package io.github.myungjoo.nntesla.ble

import android.content.Context
import android.content.ContextWrapper
import com.tesla.generated.universalmessage.UniversalMessage.Domain
import io.github.myungjoo.nntesla.protocol.JcaEcdhKey
import io.github.myungjoo.nntesla.protocol.Session

/**
 * tesla-ble JVM 단위 테스트 공용 픽스처.
 *
 * Android 프레임워크는 스텁(isReturnDefaultValues)이라 Handler.post/postDelayed는 실행되지 않는다.
 * 그래서 GATT 콜백이나 타이머 대신 내부 상태를 리플렉션으로 맞추고 해당 코드를 직접 호출한다.
 */
internal object Fixtures {
    const val VIN = "5YJ30123456789ABC"

    // upstream protocol.md의 공개 테스트 키. 실제 차량에 등록하지 말 것.
    val clientKey: JcaEcdhKey = JcaEcdhKey.fromScalar(
        hex("2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB"),
        hex(
            "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
                "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e",
        ),
    )

    fun session(): Session = Session.fromHandshake(
        Domain.DOMAIN_INFOTAINMENT, VIN, clientKey,
        hex("1588d5a30eabc6f8fc9a951b11f6fd11"),
        hex(
            "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a10" +
                "4c463f9cc0d3d26906e982ed224adde6255a0a0000",
        ),
        hex("996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002"),
    )

    /** 상태 변화를 기록하는 클라이언트. 실제 BLE·Handler는 동작하지 않는다. */
    fun client(states: MutableList<TeslaVehicleClient.State> = ArrayList()): TeslaVehicleClient =
        TeslaVehicleClient(StubContext(), VIN, clientKey, object : TeslaVehicleClient.Listener {
            override fun onStateChanged(state: TeslaVehicleClient.State, detail: String) {
                states += state
            }
        })

    fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class StubContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}

internal fun Any.field(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

internal fun Any.setField(name: String, value: Any?) {
    javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

internal val TeslaVehicleClient.link: BleLink get() = field("link") as BleLink

internal val BleLink.listener: BleLink.Listener get() = field("listener") as BleLink.Listener
