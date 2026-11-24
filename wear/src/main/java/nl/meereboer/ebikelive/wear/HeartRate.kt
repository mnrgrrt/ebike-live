package nl.meereboer.ebikelive.wear

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Permissions for the watch's own heart rate sensor (old and new Wear OS). */
val HEART_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= 36) arrayOf("android.permission.health.READ_HEART_RATE")
    else arrayOf(android.Manifest.permission.BODY_SENSORS)

private fun hasHeartPermission(ctx: Context) =
    HEART_PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Live heart rate from the watch sensor while the screen is shown.
 * Returns null without permission or before the first valid reading.
 */
@Composable
fun rememberHeartRate(): Int? {
    val ctx = LocalContext.current
    var bpm by remember { mutableStateOf<Int?>(null) }
    // Re-check permission now and then: the user may grant it after start.
    var allowed by remember { mutableStateOf(hasHeartPermission(ctx)) }
    LaunchedEffect(Unit) {
        while (!allowed) {
            kotlinx.coroutines.delay(2000)
            allowed = hasHeartPermission(ctx)
        }
    }
    DisposableEffect(allowed) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values.firstOrNull()?.toInt() ?: return
                if (v > 0) bpm = v
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (allowed && sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose { sm?.unregisterListener(listener) }
    }
    return bpm
}
