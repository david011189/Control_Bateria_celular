package com.example.bateria_celular

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Al reiniciar el teléfono o al actualizar la app, Android detiene el servicio
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("monitor_activo", false)) {
            ContextCompat.startForegroundService(
                context, Intent(context, BatteryMonitorService::class.java)
            )
        }
    }
}
