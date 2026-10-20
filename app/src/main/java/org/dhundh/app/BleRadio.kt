package org.dhundh.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Sends and receives Dhundh packets as Bluetooth LE extended advertisements.
 *
 * Long range uses the LE Coded PHY (Bluetooth 5), which trades data rate for
 * roughly four times the range of ordinary Bluetooth LE. Phones without Coded
 * PHY fall back to the 1M PHY. Both sides scan on every PHY they support, so a
 * Coded phone still hears a 1M phone and the other way round.
 */
@SuppressLint("MissingPermission") // permissions are checked in MainActivity before the service starts
class BleRadio(
    private val adapter: BluetoothAdapter,
    private val onPacket: (bytes: ByteArray, rssi: Int, phy: Int) -> Unit,
) {
    companion object {
        val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("6f1d0a3e-8c2b-4c5e-9d7a-2b1e5f40d4a1"))
        private const val TAG = "DhundhRadio"
        private const val BURST_10MS = 300 // advertise each warning for 3 seconds
    }

    val codedPhy: Boolean = adapter.isLeCodedPhySupported
    val extended: Boolean = adapter.isLeExtendedAdvertisingSupported
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(SERVICE) ?: return
            onPacket(data, result.rssi, result.primaryPhy)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
        }
    }

    fun startScanning() {
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner ?: return
        // Matching on our service data keeps scanning alive with the screen off.
        val filter = ScanFilter.Builder()
            .setServiceData(SERVICE, byteArrayOf(1), byteArrayOf(0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    fun stopScanning() {
        if (!scanning) return
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    /** Broadcast one packet for a few seconds, then stop. */
    fun broadcast(packet: ByteArray) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        if (!extended) {
            Log.w(TAG, "extended advertising not supported; a ${packet.size}-byte warning cannot be sent")
            return
        }
        val phy = if (codedPhy) BluetoothDevice.PHY_LE_CODED else BluetoothDevice.PHY_LE_1M
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .setPrimaryPhy(phy)
            .setSecondaryPhy(phy)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE, packet)
            .build()
        advertiser.startAdvertisingSet(params, data, null, null, null, BURST_10MS, 0, object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) Log.w(TAG, "advertising failed: $status")
            }
        })
    }
}
