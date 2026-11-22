package com.example.trackernotifier

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class VibrationPattern(
    val label: String,
    val followUpDelaysMs: List<Long>
) {
    SINGLE("Single", emptyList()),
    DOUBLE("Double", listOf(650L)),
    TRIPLE("Triple", listOf(650L, 1300L)),
    URGENT("Urgent", listOf(550L, 1100L, 1650L)),
    LONG("Long", listOf(450L, 900L));

    companion object {
        fun fromName(name: String?): VibrationPattern =
            entries.firstOrNull { it.name == name } ?: SINGLE
    }
}

object BleTrackerManager {
    // Common Qwatch Pro / JYouPro / KeepHealth GATT Service & Write UUIDs
    private val QWATCH_SERVICE_UUIDS = listOf(
        UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb"), // Primary Qwatch / KeepHealth service
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), // Nordic UART variant used on newer Qwatch bands
        UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")  // Legacy JYouPro variant
    )

    private val QWATCH_WRITE_UUIDS = listOf(
        UUID.fromString("0000fec7-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    )

    // Qwatch Pro / JYouPro short vibration pulse command (Find Band / Haptic Ping)
    private val QWATCH_BUZZ_PULSE_FRAME = byteArrayOf(
        0xAB.toByte(),
        0x00.toByte(),
        0x01.toByte(),
        0x71.toByte()
    )

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionStatus = MutableStateFlow("Not connected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private fun emitStatus(status: String, onStatus: (String) -> Unit) {
        _connectionStatus.value = status
        onStatus(status)
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, deviceNameFilter: String = "", onStatus: (String) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        val scanner = adapter?.bluetoothLeScanner ?: run {
            emitStatus("Bluetooth is off", onStatus)
            return
        }

        // First check if the tracker is already paired/bonded in Android Bluetooth settings
        val bondedMatch = adapter.bondedDevices?.firstOrNull { device ->
            val name = device.name.orEmpty()
            if (deviceNameFilter.isNotBlank()) {
                name.contains(deviceNameFilter, ignoreCase = true)
            } else {
                name.startsWith("Q", ignoreCase = true) ||
                    name.contains("Watch", ignoreCase = true) ||
                    name.contains("Band", ignoreCase = true)
            }
        }

        if (bondedMatch != null) {
            emitStatus("Connecting to paired ${bondedMatch.name}...", onStatus)
            connectToDevice(context, bondedMatch, onStatus)
            return
        }

        emitStatus("Scanning for Qwatch tracker...", onStatus)
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val advertisedUuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()

                val matchesUuid = advertisedUuids.any { it in QWATCH_SERVICE_UUIDS }
                val matchesName = deviceNameFilter.isNotBlank() &&
                    name.contains(deviceNameFilter, ignoreCase = true)

                if (matchesUuid || matchesName) {
                    scanner.stopScan(this)
                    emitStatus("Found $name. Connecting...", onStatus)
                    connectToDevice(context, result.device, onStatus)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(context: Context, device: BluetoothDevice, onStatus: (String) -> Unit) {
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, true, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    emitStatus("Connected to ${device.name}. Discovering services...", onStatus)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    emitStatus("Disconnected (will auto-reconnect)", onStatus)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                for (service in gatt.services) {
                    if (service.uuid in QWATCH_SERVICE_UUIDS) {
                        val match = service.characteristics.firstOrNull { it.uuid in QWATCH_WRITE_UUIDS }
                            ?: service.characteristics.firstOrNull {
                                (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            }
                        if (match != null) {
                            writeCharacteristic = match
                            emitStatus("Ready! (${device.name})", onStatus)
                            return
                        }
                    }
                }
                emitStatus("Connected, but Qwatch write service not found", onStatus)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        _connectionStatus.value = "Disconnected"
    }

    @SuppressLint("MissingPermission")
    fun sendNotificationAlert(
        appName: String,
        title: String,
        text: String,
        pattern: VibrationPattern = VibrationPattern.SINGLE
    ) {
        val gatt = bluetoothGatt ?: return
        val char = writeCharacteristic ?: return

        val messageBytes = "$appName: $title - $text"
            .take(80)
            .toByteArray(Charsets.UTF_8)

        // Qwatch Pro / JYouPro Notification Push Frame:
        // [0xAB] Magic header | [0x00] Version/Flags | [Length] | [0x05] Notification Cmd | [0x01] Generic App Type | [UTF-8 Text...]
        val packetLength = messageBytes.size + 2
        val frame = ByteArray(messageBytes.size + 5)
        frame[0] = 0xAB.toByte()
        frame[1] = 0x00.toByte()
        frame[2] = (packetLength and 0xFF).toByte()
        frame[3] = 0x05.toByte() // Message push command ID
        frame[4] = 0x01.toByte() // App icon ID (0x01 = generic message / SMS)
        System.arraycopy(messageBytes, 0, frame, 5, messageBytes.size)

        // Default BLE MTU is 23 bytes (20 bytes payload max per packet), so chunk the frame safely
        val chunks = frame.toList().chunked(20).map { it.toByteArray() }
        chunks.forEachIndexed { index, chunk ->
            mainHandler.postDelayed({
                writeChunk(gatt, char, chunk)
            }, index * 40L)
        }

        // Schedule follow-up haptic pulses after the notification chunks finish transmitting
        val frameDurationMs = chunks.size * 40L
        pattern.followUpDelaysMs.forEach { offsetMs ->
            mainHandler.postDelayed({
                val activeGatt = bluetoothGatt ?: return@postDelayed
                val activeChar = writeCharacteristic ?: return@postDelayed
                writeChunk(activeGatt, activeChar, QWATCH_BUZZ_PULSE_FRAME)
            }, frameDurationMs + offsetMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChunk(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, data: ByteArray) {
        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }
}
