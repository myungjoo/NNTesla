package io.github.myungjoo.nntesla.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import io.github.myungjoo.nntesla.protocol.BleFraming
import io.github.myungjoo.nntesla.protocol.BleReassembler
import java.util.ArrayDeque
import java.util.UUID

/**
 * GATT link to the vehicle's vehicle-command service. Every callback and every public method
 * runs on [handler]'s thread, so no locking is needed.
 *
 * Latency choices: CONNECTION_PRIORITY_HIGH, the largest MTU the vehicle accepts (fewer chunks per
 * message), and inbound messages handed to the listener synchronously on the GATT thread.
 */
@SuppressLint("MissingPermission") // Callers obtain BLUETOOTH_CONNECT before connecting.
internal class BleLink(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onLinkReady(mtu: Int)
        fun onLinkMessage(message: ByteArray, firstChunkNanos: Long, completeNanos: Long)
        fun onLinkClosed(reason: String)
    }

    private class Chunk(val bytes: ByteArray, val onLastWritten: ((Long) -> Unit)?)

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var blockLength = DEFAULT_MTU - ATT_HEADER
    private var mtu = DEFAULT_MTU
    private val writeQueue = ArrayDeque<Chunk>()
    private var writeInFlight: Chunk? = null
    private val reassembler = BleReassembler()
    private var firstChunkNanos = 0L
    private var ready = false

    fun connect(device: BluetoothDevice) {
        check(gatt == null) { "already connected" }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
    }

    /**
     * Queues [message] (without length prefix) for transmission. [onWritten] fires with the
     * monotonic time the last chunk was acknowledged by the vehicle.
     */
    fun send(message: ByteArray, onWritten: ((Long) -> Unit)? = null) {
        check(ready) { "link not ready" }
        val chunks = BleFraming.encode(message, blockLength)
        chunks.forEachIndexed { i, bytes ->
            writeQueue.add(Chunk(bytes, if (i == chunks.lastIndex) onWritten else null))
        }
        pumpWrites()
    }

    fun close() {
        val g = gatt ?: return
        gatt = null
        ready = false
        writeQueue.clear()
        writeInFlight = null
        g.disconnect()
        g.close()
    }

    private fun pumpWrites() {
        if (writeInFlight != null) return
        val chunk = writeQueue.poll() ?: return
        val g = gatt ?: return
        val characteristic = tx ?: return
        writeInFlight = chunk
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, chunk.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = chunk.bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        if (!ok) fail("GATT write could not be started")
    }

    private fun fail(reason: String) {
        close()
        listener.onLinkClosed(reason)
    }

    private fun onChunk(value: ByteArray) {
        val now = System.nanoTime()
        if (reassembler.isIdle) firstChunkNanos = now
        for (message in reassembler.feed(value)) {
            listener.onLinkMessage(message, firstChunkNanos, now)
            firstChunkNanos = now
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(MAX_MTU)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                if (gatt != null) fail("disconnected (status=$status)")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            blockLength = minOf(mtu, MAX_MESSAGE_CHUNK) - ATT_HEADER
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(UUID.fromString(BleFraming.SERVICE_UUID))
            tx = service?.getCharacteristic(UUID.fromString(BleFraming.TO_VEHICLE_UUID))
            rx = service?.getCharacteristic(UUID.fromString(BleFraming.FROM_VEHICLE_UUID))
            val rxChar = rx
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null || rxChar == null) {
                fail("vehicle service not found (status=$status)")
                return
            }
            g.setCharacteristicNotification(rxChar, true)
            val cccd = rxChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                fail("RX characteristic has no CCCD")
                return
            }
            val enable = if (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!ok) fail("could not subscribe to RX")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("RX subscription failed (status=$status)")
                return
            }
            ready = true
            listener.onLinkReady(mtu)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val chunk = writeInFlight ?: return
            writeInFlight = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT write failed (status=$status)")
                return
            }
            chunk.onLastWritten?.invoke(System.nanoTime())
            pumpWrites()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onChunk(value)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onChunk(characteristic.value ?: return)
            }
        }
    }

    companion object {
        private const val DEFAULT_MTU = 23
        private const val MAX_MTU = 517
        private const val MAX_MESSAGE_CHUNK = 1024
        private const val ATT_HEADER = 3
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
