package com.example.shootvibes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Foreground service que captura audio del micro (no del mix global, para eso se requeriría MediaProjection)
 * y genera vibraciones proporcionales a picos de energía (simulación para disparos / explosiones).
 */
class AudioVibeService : Service() {    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var record: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var usePlayback = false
    private var inForeground = false

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private val bufSize by lazy {
        val min = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        (min * 2).coerceAtLeast(4096)
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // No iniciamos captura todavía; se hará tras recibir RESULT de MediaProjection.
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Audio Vibes", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AudioVibeService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ShootVibes activo")
            .setContentText("Analizando audio para vibraciones")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPi)
            .build()
    }

    private fun startCapture(playback: Boolean = false) {
        Log.d(TAG, "startCapture called with playback=$playback")
        stopCapture()
        usePlayback = playback
        if (playback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val proj = projection ?: run {
                Log.e(TAG, "No MediaProjection available for playback capture")
                return
            }
            Log.d(TAG, "Using MediaProjection for audio capture")
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setChannelMask(channelConfig)
                        .setSampleRate(sampleRate)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } else {
            Log.d(TAG, "Using microphone for audio capture (fallback)")
            // Fallback mic (no se usará si la intención del usuario es solo apps)
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufSize
            )
        }
        try { 
            Log.d(TAG, "Starting AudioRecord...")
            record?.startRecording() 
            Log.d(TAG, "AudioRecord started successfully")
        } catch (e: Exception) { 
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}")
            return 
        }
        scope.launch {
            val buf = ShortArray(bufSize / 2)
            Log.d(TAG, "Audio recording loop starting, buffer size: ${buf.size}")
            var readCount = 0
            while (isActive) {
                val r = record?.read(buf, 0, buf.size) ?: break
                readCount++
                if (r > 0) {
                    Log.d(TAG, "Audio read #$readCount: $r samples")
                    processAudio(buf, r)
                } else {
                    Log.w(TAG, "Audio read #$readCount returned: $r")
                }
                if (readCount % 100 == 0) {
                    Log.d(TAG, "Audio recording still active after $readCount reads")
                }
            }
            Log.d(TAG, "Audio recording loop ended after $readCount reads")
        }
        broadcastSource()
    }

    private fun ensureForeground() {
        if (!inForeground) {
            startForeground(NOTIF_ID, buildNotification())
            inForeground = true
        }
    }

    private fun stopCapture() {
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
    }

    private var lastVibe = 0L
    // Parámetros fijos simplificados
    private data class SimpleParams(
        val minShotDb: Double = -22.0,
        val minMediumDb: Double = -32.0,
        val shotOffsetDb: Double = 11.0,
        val mediumOffsetDb: Double = 5.5,
        val minDerivativeShot: Double = 0.50,
        val minDerivativeMedium: Double = 0.28,
        val refractoryShotMs: Long = 70,
        val refractoryMediumMs: Long = 150,
        val vibShotMs: Int = 32,
        val vibBurstTapMs: Int = 18,
        val vibExplosionMs: Int = 120
    )
    private val params = SimpleParams()
    private val hpState = HighPassState()
    private val ringBuffer = FloatArray(4410)
    private var ringIndex = 0
    private var lastRms = 0.0
    private var noiseFloorDb = -45.0
    private var accumFloor = 0.0
    private var floorSamples = 0
    // FFT / espectro
    private val fftSize = 512
    private val fftReal = DoubleArray(fftSize)
    private val fftImag = DoubleArray(fftSize)
    private val window = DoubleArray(fftSize) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1)) }
    private var blocksSinceFft = 0
    private var lastLowRatio = 0.0

    private fun processAudio(data: ShortArray, len: Int) {
        Log.d(TAG, "processAudio: len=$len, dataSize=${data.size}")
        
        var sum = 0.0
        for (i in 0 until len) {
            val filtered = hpState.process(data[i].toDouble())
            sum += filtered * filtered
            ringBuffer[ringIndex] = filtered.toFloat()
            ringIndex = (ringIndex + 1) % ringBuffer.size
        }
        val rms = sqrt(sum / len)
        val db = 20 * log10(rms + 1e-9)
        
        Log.d(TAG, "Audio processing: rms=$rms, db=$db, noiseFloor=$noiseFloorDb")

    if (db < params.minMediumDb) { // actualizar piso de ruido
            accumFloor += db
            floorSamples++
            if (floorSamples >= 25) {
                val avg = accumFloor / floorSamples
                noiseFloorDb = 0.8 * noiseFloorDb + 0.2 * avg
                accumFloor = 0.0
                floorSamples = 0
            }
        }

    val derivative = if (lastRms > 1e-9) (rms / lastRms) - 1.0 else 0.0
        lastRms = rms

    val dynShot = noiseFloorDb + params.shotOffsetDb
    val dynMedium = noiseFloorDb + params.mediumOffsetDb
        val now = System.currentTimeMillis()
    val shotCond = (db > params.minShotDb || db > dynShot) && derivative > params.minDerivativeShot
    val mediumCond = (db > params.minMediumDb || db > dynMedium) && derivative > params.minDerivativeMedium
        // FFT cada pocos bloques para clasificar espectro
        var heavyCond = false
        blocksSinceFft++
        if (blocksSinceFft >= 4) { // ajustar frecuencia de análisis
            blocksSinceFft = 0
            lastLowRatio = computeLowFreqRatio()
            // Evento pesado si hay fuerte componente grave y derivada moderada (no tan abrupta como disparo) o db alto
            heavyCond = lastLowRatio > 0.55 && (derivative > params.minDerivativeMedium * 0.5) && (db > params.minMediumDb || db > dynMedium)
        }

        if (heavyCond && now - lastVibe > params.refractoryMediumMs * 2) { // explosión
            vibrateTap(params.vibExplosionMs)
            lastVibe = now
        } else if (shotCond && now - lastVibe > params.refractoryShotMs) {
            // si es ráfaga detectaremos múltiples disparos sucesivos
            vibrateTap(params.vibShotMs)
            lastVibe = now
        } else if (mediumCond && now - lastVibe > params.refractoryMediumMs) {
            // vibración corta para pequeños impactos / transición de ráfaga
            vibrateTap(params.vibBurstTapMs)
            lastVibe = now
        }
    }

    // Emite broadcast con la fuente actual (MIC o PLAYBACK)
    private fun broadcastSource() {
        lastSource = if (usePlayback) "PLAYBACK" else "MIC"
        sendBroadcast(Intent(BROADCAST_SOURCE).putExtra(EXTRA_SOURCE, lastSource))
    }

    // Calcula la razón de energía de bajas frecuencias (< ~300Hz) respecto a energía total usando FFT real simple
    private fun computeLowFreqRatio(): Double {
        if (ringBuffer.size < fftSize) return 0.0
        // Extraer ventana más reciente (manejo circular)
        val start = (ringIndex - fftSize + ringBuffer.size) % ringBuffer.size
        if (start + fftSize <= ringBuffer.size) {
            for (i in 0 until fftSize) fftReal[i] = ringBuffer[start + i].toDouble() * window[i]
        } else {
            val first = ringBuffer.size - start
            var k = 0
            for (i in 0 until first) { fftReal[k] = ringBuffer[start + i].toDouble() * window[k]; k++ }
            for (i in 0 until (fftSize - first)) { fftReal[k] = ringBuffer[i].toDouble() * window[k]; k++ }
        }
        java.util.Arrays.fill(fftImag, 0.0)
        // Radix-2 Cooley-Tukey in-place FFT
        var n = fftSize
        var j = 0
        for (i in 1 until n - 1) {
            var bit = n shr 1
            while (j >= bit) { j -= bit; bit = bit shr 1 }
            j += bit
            if (i < j) {
                val tr = fftReal[j]; val ti = fftImag[j]
                fftReal[j] = fftReal[i]; fftImag[j] = fftImag[i]
                fftReal[i] = tr; fftImag[i] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenCos = cos(ang)
            val wlenSin = sin(ang)
            for (i in 0 until n step len) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until len / 2) {
                    val uReal = fftReal[i + k]
                    val uImag = fftImag[i + k]
                    val vReal = fftReal[i + k + len / 2] * wReal - fftImag[i + k + len / 2] * wImag
                    val vImag = fftReal[i + k + len / 2] * wImag + fftImag[i + k + len / 2] * wReal
                    fftReal[i + k] = uReal + vReal
                    fftImag[i + k] = uImag + vImag
                    fftReal[i + k + len / 2] = uReal - vReal
                    fftImag[i + k + len / 2] = uImag - vImag
                    val nextWReal = wReal * wlenCos - wImag * wlenSin
                    val nextWImag = wReal * wlenSin + wImag * wlenCos
                    wReal = nextWReal; wImag = nextWImag
                }
            }
            len = len shl 1
        }
        // Magnitudes y energía por bandas
        val nyquistBins = fftSize / 2
        var low = 0.0; var total = 0.0
        // Resolución ~ sampleRate/fftSize
        val binHz = sampleRate.toDouble() / fftSize
        val lowMaxHz = 300.0
        val lowMaxBin = (lowMaxHz / binHz).toInt().coerceAtMost(nyquistBins)
        for (i in 1 until nyquistBins) { // ignorar DC i=0
            val mag2 = fftReal[i]*fftReal[i] + fftImag[i]*fftImag[i]
            total += mag2
            if (i <= lowMaxBin) low += mag2
        }
        return if (total > 0) low / total else 0.0
    }

    private fun vibrateTap(ms: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } else @Suppress("DEPRECATION") vibrator.vibrate(ms.toLong())
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopCapture()
        projection?.stop(); projection = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PROFILE -> { /* perfiles eliminados */ }
            ACTION_PLAYBACK -> { /* deprecated path: ignore, use ACTION_PROJECTION_RESULT */ }
        ACTION_PROJECTION_RESULT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA_INTENT)
                if (code == Activity.RESULT_OK && data != null) {
                    // CRITICAL: Start foreground service BEFORE creating MediaProjection
                    ensureForeground()
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection?.stop()
                    projection = mpm.getMediaProjection(code, data)
                    startCapture(playback = true)
                }
            }
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "AudioVibeService"
        const val ACTION_STOP = "action_stop"
        const val ACTION_PROFILE = "action_profile"
        const val ACTION_PLAYBACK = "action_playback"
        const val ACTION_PROJECTION_RESULT = "action_projection_result"
        const val EXTRA_PROFILE = "extra_profile"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        private const val CHANNEL_ID = "audio_vibe"
        private const val NOTIF_ID = 1
    const val BROADCAST_SOURCE = "com.example.shootvibes.SOURCE"
    const val EXTRA_SOURCE = "extra_source"
    @Volatile
    var lastSource: String = "MIC"
    }
}

private class HighPassState {
    // Simple 1st order high-pass filter to reduce low freq (explosiones largas)
    private var prevInput = 0.0
    private var prevOutput = 0.0
    private val rc = 0.05 // time constant
    private val dt = 1.0/44100.0
    private val alpha = rc / (rc + dt)
    fun process(x: Double): Double {
        val y = alpha * (prevOutput + x - prevInput)
        prevInput = x
        prevOutput = y
        return y
    }
}
