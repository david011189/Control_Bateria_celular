package com.example.bateria_celular

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.use

class MainActivity : AppCompatActivity() {

    private val pedirPermiso =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var tvNivel: TextView
    private lateinit var btnIniciar: Button
    private lateinit var colorTextoOriginal: ColorStateList
    private var colorFondoOriginal: ColorStateList? = null

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

        btnIniciar = findViewById(R.id.btnIniciar)
        colorTextoOriginal = btnIniciar.textColors
        // Gris por defecto de los botones del tema, para restaurarlo al detener
        colorFondoOriginal = obtainStyledAttributes(
            intArrayOf(androidx.appcompat.R.attr.colorButtonNormal)
        ).use { it.getColorStateList(0) }
        val monitorActivo = prefs.getBoolean("monitor_activo", false)
        // Si el sistema detuvo el servicio (p. ej. al actualizar la app), se vuelve a lanzar
        if (monitorActivo) ContextCompat.startForegroundService(this, intentServicio)
        mostrarEstado(monitorActivo)

        btnIniciar.setOnClickListener {
            ContextCompat.startForegroundService(this, intentServicio)
            prefs.edit { putBoolean("monitor_activo", true) }
            mostrarEstado(true)
        }
        findViewById<Button>(R.id.btnDetener).setOnClickListener {
            stopService(intentServicio)
            prefs.edit { putBoolean("monitor_activo", false) }
            mostrarEstado(false)
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

    // Botón verde con "Monitoreo iniciado" mientras el monitoreo está activo
    private fun mostrarEstado(activo: Boolean) {
        if (activo) {
            btnIniciar.text = "Monitoreo iniciado"
            btnIniciar.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.monitoreo_activo))
            btnIniciar.setTextColor(Color.WHITE)
        } else {
            btnIniciar.text = "Iniciar monitoreo"
            btnIniciar.backgroundTintList = colorFondoOriginal
            btnIniciar.setTextColor(colorTextoOriginal)
        }
    }
}
