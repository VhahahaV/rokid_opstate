package ai.opstate.rokid.phone

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.Build
import android.os.ParcelUuid
import ai.opstate.protocol.StudyWire
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Read-only discovery of the user-authorized glasses. No bonding, GATT writes or SDK authentication. */
@RunWith(AndroidJUnit4::class)
class RokidDiscoveryDeviceTest {
    @Test fun captureFreshSdpAndRokidAdvertisements()=runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter
        val target=adapter.getRemoteDevice("AC:86:D1:56:FC:AF")
        val answer=CompletableDeferred<List<String>>()
        val receiver=object : BroadcastReceiver() {
            override fun onReceive(context: Context?,intent: Intent?) {
                @Suppress("DEPRECATION")
                val device=intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if(intent?.action==BluetoothDevice.ACTION_UUID && device?.address==target.address) {
                    @Suppress("DEPRECATION")
                    val values=intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.filterIsInstance<ParcelUuid>()?.map {it.uuid.toString()}.orEmpty()
                    answer.complete(values)
                }
            }
        }
        val filter=IntentFilter(BluetoothDevice.ACTION_UUID)
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED) else context.registerReceiver(receiver,filter)
        var started=false
        val uuids=try {started=target.fetchUuidsWithSdp();withTimeoutOrNull(12_000) {answer.await()}} finally {context.unregisterReceiver(receiver)}
        val found=ConcurrentHashMap<String,JsonObject>()
        var error: Int?=null
        val callback=object : ScanCallback() {
            override fun onScanResult(callbackType: Int,result: ScanResult) {
                val name=result.scanRecord?.deviceName ?: result.device.name
                if(result.device.address==target.address || name?.endsWith("2804")==true) {
                    found[result.device.address]=StudyWire.payload("name" to name,"connectable" to result.isConnectable,
                        "rssi" to result.rssi,"service_uuids" to JsonArray(result.scanRecord?.serviceUuids.orEmpty().map {JsonPrimitive(it.uuid.toString())}))
                }
            }
            override fun onScanFailed(errorCode: Int) {error=errorCode}
        }
        val scanner=adapter.bluetoothLeScanner
        scanner.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
        try {delay(8000)} finally {scanner.stopScan(callback)}
        val report=StudyWire.payload("kind" to "READ_ONLY_ROKID_DISCOVERY","bluetooth_enabled" to adapter.isEnabled,
            "target_bond_state" to target.bondState,"sdp_request_accepted" to started,"fresh_sdp_received" to (uuids!=null),
            "sdp_uuids" to JsonArray(uuids.orEmpty().map(::JsonPrimitive)),"target_advertisements" to JsonArray(found.values.toList()),
            "ble_scan_error" to error,"sdk_authenticated" to false)
        File(context.filesDir,"validation").apply {mkdirs()}.resolve("rokid-discovery.json").writeText(report.toString())
    }
}
