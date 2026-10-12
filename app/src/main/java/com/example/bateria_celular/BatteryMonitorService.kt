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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

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
        private const val TAG = "AlertaBateria"
    }

    private var alertaAltaEmitida = false
    private var alertaBajaEmitida = false
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var ttsIniciando = false
    private var textoPendiente: String? = null   // mensaje que llegó antes de que la voz estuviera lista
    private var ultimoEstado = ""

    // Recibe ACTION_BATTERY_CHANGED cada vez que cambia el estado de la batería
    private val receptorBateria = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nivel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val escala = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (nivel < 0 || escala <= 0) return
            val porcentaje = nivel * 100 / escala
            val conectado = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            // Registra solo los cambios de nivel o de cargador, no cada aviso del sistema
            val estado = "nivel=$porcentaje conectado=$conectado"
            if (estado != ultimoEstado) {
                ultimoEstado = estado
                Log.d(TAG, estado)
            }
            evaluar(porcentaje, conectado)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Servicio iniciado")
        crearCanales()
        iniciarVoz()
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
        Log.d(TAG, "Servicio detenido")
        unregisterReceiver(receptorBateria)
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        tts?.shutdown()
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
                android.R.drawable.ic_lock_idle_charging
            )
            hablar("Batería al $porcentaje por ciento. Ya puedes desconectar el cargador.")
        }
        if (!conectado || porcentaje < UMBRAL_ALTO - MARGEN) alertaAltaEmitida = false

        // Alerta de batería baja: descargando y bajó a 20 %
        if (!conectado && porcentaje <= UMBRAL_BAJO && !alertaBajaEmitida) {
            alertaBajaEmitida = true
            emitirAlerta(
                "Batería baja: $porcentaje %",
                "Conecta el cargador.",
                android.R.drawable.ic_lock_idle_low_battery
            )
            reproducirSonido(RingtoneManager.TYPE_ALARM)
        }
        if (conectado || porcentaje > UMBRAL_BAJO + MARGEN) alertaBajaEmitida = false
    }

    private fun emitirAlerta(titulo: String, texto: String, icono: Int) {
        Log.d(TAG, "Alerta: $titulo")
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
    }

    // ----- Mensaje de voz -----
    private fun iniciarVoz() {
        ttsIniciando = true
        tts = TextToSpeech(this) { estado ->
            ttsIniciando = false
            if (estado == TextToSpeech.SUCCESS) {
                val resultado = tts?.setLanguage(Locale.forLanguageTag("es-PE"))
                    ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsListo = resultado >= TextToSpeech.LANG_AVAILABLE
                // Se escucha con el volumen de alarma, aunque el multimedia esté bajo
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // El idioma puede figurar como disponible y aun así fallar al hablar
                // (p. ej. voz no descargada): en ese caso se usa el tono de respaldo
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}

                    @Deprecated("Requerido por la clase base")
                    override fun onError(utteranceId: String?) = onError(utteranceId, -1)

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.w(TAG, "La voz falló (código=$errorCode), se reproduce el tono")
                        handler.post { reproducirSonido(RingtoneManager.TYPE_NOTIFICATION) }
                    }
                })
                Log.d(TAG, "Voz lista=$ttsListo (idioma=$resultado)")
            } else {
                Log.w(TAG, "No se pudo iniciar la voz (estado=$estado)")
            }
            textoPendiente?.let { texto ->
                textoPendiente = null
                hablar(texto)
            }
        }
    }

    private fun hablar(texto: String) {
        if (ttsIniciando) {
            textoPendiente = texto
        } else if (ttsListo) {
            handler.removeCallbacksAndMessages(null)
            ringtone?.stop()
            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "alerta_bateria")
        } else {
            // Sin voz en español disponible, se usa el tono como respaldo
            Log.w(TAG, "Voz no disponible, se reproduce el tono")
            reproducirSonido(RingtoneManager.TYPE_NOTIFICATION)
        }
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
