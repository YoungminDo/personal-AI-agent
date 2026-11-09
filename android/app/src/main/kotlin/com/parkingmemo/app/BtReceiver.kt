package com.parkingmemo.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Car Bluetooth connect/disconnect is the primary parking signal (A01). */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val car = Prefs(ctx).carAddress ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = try {
            device?.address
        } catch (e: SecurityException) {
            null
        } ?: return
        if (!address.equals(car, ignoreCase = true)) return
        val action = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> TrackerService.ACTION_CAR_CONNECTED
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> TrackerService.ACTION_CAR_DISCONNECTED
            else -> return
        }
        TrackerService.send(ctx, action)
    }
}
