package com.ruuvi.station.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ruuvi.station.R
import com.ruuvi.station.feature.main.MainActivity
import com.ruuvi.station.feature.main.MainActivity.isBluetoothEnabled
import java.util.ArrayList

class Starter(val that: AppCompatActivity) {
    fun startScanning(): Boolean {
        if (!MainActivity.isLocationEnabled(that)) {
            val builder = AlertDialog.Builder(that)
            builder.setTitle(that.getString(R.string.locationServices))
            builder.setMessage(that.getString(R.string.enableLocationServices))
            builder.setPositiveButton(android.R.string.ok) { _, i ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                that.startActivity(intent)
            }
            builder.setNegativeButton(android.R.string.cancel) { _, i -> }
            builder.show()
            return false
        }
        return true
    }

    fun getThingsStarted() {
        requestPermissions()
    }

    fun getNeededPermissions(): List<String> {
        val permissionCoarseLocation = ContextCompat.checkSelfPermission(that,
                Manifest.permission.ACCESS_COARSE_LOCATION)

        val listPermissionsNeeded = ArrayList<String>()

        if (permissionCoarseLocation != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        return listPermissionsNeeded
    }

    private fun showPermissionDialog(activity: AppCompatActivity): Boolean {
        val listPermissionsNeeded = getNeededPermissions()

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(activity, listPermissionsNeeded.toTypedArray(), 10)
        }

        return !listPermissionsNeeded.isEmpty()
    }

    fun requestPermissions() {
        if (getNeededPermissions().isNotEmpty()) {
            val alertDialog = androidx.appcompat.app.AlertDialog.Builder(that).create()
            alertDialog.setTitle(that.getString(R.string.permission_dialog_title))
            alertDialog.setMessage(that.getString(R.string.permission_dialog_request_message))
            alertDialog.setButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL, that.getString(R.string.ok)
            ) { dialog, _ -> dialog.dismiss() }
            alertDialog.setOnDismissListener { showPermissionDialog(that) }
            alertDialog.show()
        } else {
            checkBluetooth()
        }
    }

    fun checkBluetooth(): Boolean {
        if (isBluetoothEnabled()) {
            return true
        }
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        that.startActivityForResult(enableBtIntent, 87)
        return false
    }
}