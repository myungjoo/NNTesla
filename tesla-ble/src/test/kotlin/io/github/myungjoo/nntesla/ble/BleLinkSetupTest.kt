package io.github.myungjoo.nntesla.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import io.github.myungjoo.nntesla.protocol.BleFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * GATT 연결 후 링크 준비(MTU → 서비스 탐색 → RX 구독) 순서를 검증한다.
 *
 * 회귀(8d58f79): Tesla 앱이 이미 차량과 BLE 링크를 잡고 있으면 스택이 MTU 콜백을 스스로 한 번 보내고
 * 우리 requestMtu 응답으로 한 번 더 보낸다. 콜백마다 서비스 탐색을 시작하던 탓에 CCCD 쓰기가 두 번
 * 일어났고, 두 번째가 "prior command is not finished"로 거부되어 링크가 닫혔다.
 *
 * 테스트 환경은 Build.VERSION.SDK_INT가 0이라 API 33 미만 경로(deprecated writeDescriptor)를 탄다.
 */
@Suppress("DEPRECATION")
class BleLinkSetupTest {
    private val gatt = mock(BluetoothGatt::class.java)
    private val service = mock(BluetoothGattService::class.java)
    private val tx = mock(BluetoothGattCharacteristic::class.java)
    private val rx = mock(BluetoothGattCharacteristic::class.java)
    private val cccd = mock(BluetoothGattDescriptor::class.java)

    private val readyCalls = ArrayList<Int>()
    private val closeReasons = ArrayList<String>()
    private val link = BleLink(mock(Context::class.java), mock(Handler::class.java), object : BleLink.Listener {
        override fun onLinkReady(mtu: Int) {
            readyCalls += mtu
        }

        override fun onLinkMessage(message: ByteArray, firstChunkNanos: Long, completeNanos: Long) = Unit

        override fun onLinkClosed(reason: String) {
            closeReasons += reason
        }
    })
    private val callback = link.field("callback") as BluetoothGattCallback

    init {
        `when`(gatt.requestMtu(anyInt())).thenReturn(true)
        `when`(gatt.discoverServices()).thenReturn(true)
        `when`(gatt.getService(UUID.fromString(BleFraming.SERVICE_UUID))).thenReturn(service)
        `when`(service.getCharacteristic(UUID.fromString(BleFraming.TO_VEHICLE_UUID))).thenReturn(tx)
        `when`(service.getCharacteristic(UUID.fromString(BleFraming.FROM_VEHICLE_UUID))).thenReturn(rx)
        `when`(rx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))).thenReturn(cccd)
        `when`(gatt.writeDescriptor(cccd)).thenReturn(true)
        // connect()가 하는 일: gatt를 잡아 둔다.
        link.setField("gatt", gatt)
    }

    private fun connected() = callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)

    @Test
    fun singleMtuCallbackSetsUpLink() {
        connected()
        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS)

        assertEquals(listOf(517), readyCalls)
        assertTrue(link.isReady)
        assertEquals(emptyList<String>(), closeReasons)
    }

    /** 이미 잡힌 링크에 붙을 때: MTU 콜백이 두 번 와도 탐색과 구독은 한 번씩만 한다. */
    @Test
    fun duplicateMtuCallbackDoesNotRepeatDiscoveryOrSubscription() {
        connected()
        callback.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS) // 스택이 스스로 보낸 것
        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS) // 우리 requestMtu 응답
        verify(gatt, times(1)).discoverServices()

        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        verify(gatt, times(1)).writeDescriptor(cccd)

        callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS)
        callback.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS)
        assertEquals("link ready reported once", 1, readyCalls.size)
        assertEquals(emptyList<String>(), closeReasons)
    }

    /** MTU 요청을 시작하지 못하면 MTU 콜백 없이 바로 탐색으로 넘어가고, 늦게 온 MTU 콜백이 탐색을 반복하지 않는다. */
    @Test
    fun mtuRequestRejectedStillDiscoversOnce() {
        `when`(gatt.requestMtu(anyInt())).thenReturn(false)
        connected()
        callback.onMtuChanged(gatt, 185, BluetoothGatt.GATT_SUCCESS)
        verify(gatt, times(1)).discoverServices()
    }

    @Test
    fun failedSubscriptionClosesWithStatus() {
        connected()
        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        callback.onDescriptorWrite(gatt, cccd, 133)
        assertEquals(listOf("RX subscription failed (status=133)"), closeReasons)
        assertEquals(emptyList<Int>(), readyCalls)
    }
}
