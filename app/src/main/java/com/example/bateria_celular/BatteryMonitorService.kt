package com.example.bateria_celular

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class BatteryMonitorService : Service() {

    companion object {
        const val UMBRAL_ALTO = 80
        const val UMBRAL_BAJO = 20
        private const val MARGEN = 2              // histéresis para no repetir alertas
        private const val CANAL_SERVICIO = "canal_servicio"
        private const val CANAL_ALERTAS = "canal_alertas"
        private const val ID_NOTIF_SERVICIO = 1
        private const val ID_NOTIF_ALERTA = 2
        private const val DURACION_SONIDO_MS = 8000L
    }

    private var alertaAltaEmitida = false
    private var alertaBajaEmitida = false
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())

    // Recibe ACTION_BATTERY_CHANGED cada vez que cambia el estado de la batería
    private val receptorBateria = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nivel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val escala = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (nivel < 0 || escala <= 0) return
            val porcentaje = nivel * 100 / escala
            val conectado = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            evaluar(porcentaje, conectado)
        }
    }

    override fun onCreate() {
        super.onCreate()
        crearCanales()
        val notificacion = NotificationCompat.Builder(this, CANAL_SERVICIO)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Monitor de batería activo")
            .setContentText("Avisará al llegar a $UMBRAL_ALTO % y a $UMBRAL_BAJO %")
            .setOngoing(true)
            .build()
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID_NOTIF_SERVICIO, notificacion, tipo)
        ContextCompat.registerReceiver(
            this, receptorBateria,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(receptorBateria)
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        super.onDestroy()
    }

    // ----- Lógica de umbrales -----
    private fun evaluar(porcentaje: Int, conectado: Boolean) {
        // Alerta de carga alta: cargando y llegó a 80 %
        if (conectado && porcentaje >= UMBRAL_ALTO && !alertaAltaEmitida) {
            alertaAltaEmitida = true
            emitirAlerta(
                "Batería al $porcentaje %",
                "Ya puedes desconectar el cargador.",
                RingtoneManager.TYPE_NOTIFICATION,
                android.R.drawable.ic_lock_idle_charging
            )
        }
        if (!conectado || porcentaje < UMBRAL_ALTO - MARGEN) alertaAltaEmitida = false

        // Alerta de batería baja: descargando y bajó a 20 %
        if (!conectado && porcentaje <= UMBRAL_BAJO && !alertaBajaEmitida) {
            alertaBajaEmitida = true
            emitirAlerta(
                "Batería baja: $porcentaje %",
                "Conecta el cargador.",
                RingtoneManager.TYPE_ALARM,
                android.R.drawable.ic_lock_idle_low_battery
            )
        }
        if (conectado || porcentaje > UMBRAL_BAJO + MARGEN) alertaBajaEmitida = false
    }

    private fun emitirAlerta(titulo: String, texto: String, tipoSonido: Int, icono: Int) {
        val notif = NotificationCompat.Builder(this, CANAL_ALERTAS)
            .setSmallIcon(icono)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val puedeNotificar = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (puedeNotificar) NotificationManagerCompat.from(this).notify(ID_NOTIF_ALERTA, notif)
        reproducirSonido(tipoSonido)
    }

    private fun reproducirSonido(tipo: Int) {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        val uri = RingtoneManager.getDefaultUri(tipo)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            play()
        }
        handler.postDelayed({ ringtone?.stop() }, DURACION_SONIDO_MS)
    }

    private fun crearCanales() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_SERVICIO, "Monitor en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val alertas = NotificationChannel(
            CANAL_ALERTAS, "Alertas de batería",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)   // el sonido lo reproduce el propio servicio
        }
        nm.createNotificationChannel(alertas)
    }
}
