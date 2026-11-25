package io.github.agenga0320.podhelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings

object Permissions {
    val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun bluetoothGranted(c: Context) = BLUETOOTH.all { granted(c, it) }
    fun notificationsGranted(c: Context) = granted(c, Manifest.permission.POST_NOTIFICATIONS)
    fun overlayGranted(c: Context) = Settings.canDrawOverlays(c)
    fun batteryUnrestricted(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
}
