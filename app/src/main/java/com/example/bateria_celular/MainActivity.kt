package com.example.bateria_celular

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var tvNivel: TextView

    // Actualiza el nivel en pantalla mientras la actividad está visible (RF01)
    private val receptorNivel = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nivel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val escala = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (nivel >= 0 && escala > 0) mostrarNivel(nivel * 100 / escala)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        tvNivel = findViewById(R.id.tvNivel)
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        mostrarNivel(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val intentServicio = Intent(this, BatteryMonitorService::class.java)

        findViewById<Button>(R.id.btnIniciar).setOnClickListener {
            ContextCompat.startForegroundService(this, intentServicio)
            prefs.edit { putBoolean("monitor_activo", true) }
        }
        findViewById<Button>(R.id.btnDetener).setOnClickListener {
            stopService(intentServicio)
            prefs.edit { putBoolean("monitor_activo", false) }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, receptorNivel,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(receptorNivel)
        super.onStop()
    }

    private fun mostrarNivel(porcentaje: Int) {
        tvNivel.text = "Nivel actual: $porcentaje %"
    }
}
