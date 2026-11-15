package io.github.myungjoo.nntesla.spike

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 차량 블루투스(오디오·핸즈프리)가 연결되면 앱을 자동으로 띄운다.
 *
 * Android 12부터 백그라운드에서 액티비티를 띄우는 것은 막혀 있지만, CompanionDeviceManager로
 * 기기와 연결(association)된 앱은 예외다. 그래서 사용자가 한 번 차량을 골라 연결해 두면,
 * [CarConnectedReceiver]가 그 기기의 ACL 연결 브로드캐스트를 받아 [MainActivity]를 띄울 수 있다.
 *
 * 신호로 BLE 근접 대신 클래식 블루투스 연결을 쓰는 이유: 차 옆을 지나가기만 해도 앱이 뜨면 안 되고,
 * 차량 오디오 연결은 실제로 차에 탔을 때 일어난다.
 */
object CarAutoLaunch {
    private const val TAG = "NNTesla.AutoLaunch"

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /** 이 앱과 연결된 기기의 MAC 주소(대문자). */
    fun associatedAddresses(context: Context): List<String> {
        val cdm = manager(context) ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations.map { it.uppercase() }
        }
    }

    fun isAssociated(context: Context, address: String): Boolean =
        address.uppercase() in associatedAddresses(context)

    /** 페어링된 블루투스 기기 하나. */
    data class BondedDevice(val name: String, val address: String) {
        val looksLikeTesla: Boolean get() = name.contains("Tesla", ignoreCase = true)
    }

    /**
     * 폰에 페어링된 클래식 블루투스 기기. 차량(이름에 Tesla가 들어간 것)을 앞에 둔다.
     * 시스템 선택 창은 주변 기기를 MAC 주소로만 보여 주기도 해서, 이름이 확실한 페어링 목록에서 고르게 한다.
     */
    @SuppressLint("MissingPermission") // 아래에서 BLUETOOTH_CONNECT를 확인한다.
    fun bondedDevices(context: Context): List<BondedDevice> {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
            .map { BondedDevice(it.name ?: it.address, it.address.uppercase()) }
            .sortedWith(compareByDescending<BondedDevice> { it.looksLikeTesla }.thenBy { it.name })
    }

    /** 연결해 둔 기기를 이름으로 보여 준다(페어링이 풀려 이름을 모르면 주소). */
    fun describeAssociated(context: Context): List<String> {
        val names = bondedDevices(context).associate { it.address to it.name }
        return associatedAddresses(context).map { names[it] ?: it }
    }

    /**
     * [address] 기기 하나만 지정해 연결을 요청한다. 시스템은 목록 대신 그 기기에 대한 확인 창만 띄우며,
     * 그 창을 띄울 [IntentSender]를 [onChooser]로 넘긴다. 결과는 창을 띄운 쪽의 액티비티 결과로 돌아온다.
     * 이 확인은 Android가 요구하는 사용자 동의라 건너뛸 수 없다.
     */
    fun requestAssociation(context: Context, address: String, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = manager(context) ?: return onError("Companion device setup is not supported on this phone")
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        @Suppress("DEPRECATION") // Executor 판은 API 33부터라 minSdk 31에서는 Handler 판을 쓴다.
        cdm.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                @Deprecated("API 33부터는 onAssociationPending이 기본 구현으로 이 메서드를 부른다")
                override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)

                override fun onFailure(error: CharSequence?) = onError("Association failed: $error")
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /** 연결을 모두 해제해 자동 실행을 끈다. */
    fun disassociateAll(context: Context) {
        val cdm = manager(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.forEach { cdm.disassociate(it.id) }
        } else {
            @Suppress("DEPRECATION")
            cdm.associations.forEach { cdm.disassociate(it) }
        }
        Log.i(TAG, "auto-launch disabled")
    }
}

/** 연결해 둔 차량의 블루투스가 붙으면 [MainActivity]를 띄운다. */
class CarConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        if (!CarAutoLaunch.isAssociated(context, address)) return
        Log.i("NNTesla.AutoLaunch", "car connected ($address), launching")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }
}
