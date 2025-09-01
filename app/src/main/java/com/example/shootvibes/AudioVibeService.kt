package com.example.shootvibes

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Unified clean implementation with vibration patterns, adaptive continuous fire, explosion detection (FFT low ratio)
 * and instrumentation stats + optional verbose logging.
 */
class AudioVibeService : Service() {

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    // Audio capture
    private var record: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var usePlayback = false
    private var inForeground = false

    // Config
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val bufSize by lazy {
        val m = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        (m * 2).coerceAtLeast(4096)
    }

    // Vibrator
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
    }

    // Detection state (simplified per-shot only; no loop/burst mode)
    private var lastVibe = 0L
    private var lastShotTime = 0L
    private var rapidShotsCount = 0
    private var lastShotInterval = 0L
    @Volatile private var lastStrength = 0

    // Stats instrumentation (explosions removed for now)
    private var shotsDetected = 0
    private var strongShotsDetected = 0
    private var ultraShotsDetected = 0
    private var lastStatsLog = 0L

    // Parameters
    private data class SimpleParams(
        val minShotDb: Double = -22.0,
        val minMediumDb: Double = -32.0,
        val shotOffsetDb: Double = 11.0,
        val mediumOffsetDb: Double = 5.0,
        val minDerivativeShot: Double = 0.48,
        val minDerivativeMedium: Double = 0.26,
        val refractoryShotMs: Long = 18,   // minimal guard; near-zero to allow very high cadence
        val refractoryMediumMs: Long = 140,
    val vibShotSoftMs: Int = 20,       // simplified shorter pulses
    val vibShotStrongMs: Int = 42,     // increased strong pulse length
    val vibShotUltraMs: Int = 65,      // new ultra shot pulse
        val vibBurstTapMs: Int = 14,
        val vibExplosionMs: Int = 0,        // disabled (no explosion pattern)
        val ultraExcessDb: Double = 11.0,    // threshold for ultra shot
        val ultraDerivativeMul: Double = 3.0,  // derivative multiplier for ultra shot
        // Adaptive shortening for extreme cadence
        val shortenThresholdMs: Long = 55,     // if interval between shots < this, shorten duration
        val minPulseMs: Int = 12,              // minimum pulse duration when shortened
    val shortenFactor: Double = 0.55,      // scale factor when shortening
    // Micro-shot fallback for very fast automatic weapons (AK, SMG) when derivative spikes blur
    val microShotDbDelta: Double = 2.0,    // within this below dynShot still accept as micro
    val microShotDerivative: Double = 0.15, // minimal derivative for micro shot
    val microShotMinGapMs: Long = 10,      // minimal gap between micro shots
    val rapidIntervalFastMs: Long = 50,    // threshold to consider intervals "very fast"
    val fastRefractoryMs: Long = 10        // dynamic refractory when in very fast mode
    )
    private val params = SimpleParams()

    // Signal processing
    private val hpState = HighPassState()
    private val ringBuffer = FloatArray(4410)
    private var ringIndex = 0
    private var lastRms = 0.0
    private var noiseFloorDb = -45.0
    private var accumFloor = 0.0
    private var floorSamples = 0
    private val fftSize = 512
    private val fftReal = DoubleArray(fftSize)
    private val fftImag = DoubleArray(fftSize)
    private val window = DoubleArray(fftSize) { i -> 0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1)) }
    private var blocksSinceFft = 0
    private var lastLowRatio = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate() }

    // Notification / foreground
    private fun ensureForeground() {
        if (!inForeground) {
            startForeground(NOTIF_ID, buildNotification())
            inForeground = true
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Audio Vibes", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, AudioVibeService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShootVibes activo")
            .setContentText("Analizando audio para vibraciones")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPi)
            .build()
    }

    // Capture control
    private fun startCapture(playback: Boolean) {
        if (DEBUG_VERBOSE) Log.d(TAG, "startCapture playback=$playback")
        stopCapture()
        usePlayback = playback
        record = if (playback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val proj = projection ?: run {
                Log.e(TAG, "No MediaProjection")
                return
            }
            val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setChannelMask(channelConfig)
                        .setSampleRate(sampleRate)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufSize
            )
        }
        try { record?.startRecording() } catch (e: Exception) { Log.e(TAG, "AudioRecord start fail: ${e.message}"); record = null; return }
        scope.launch {
            val buf = ShortArray(bufSize / 2)
            var reads = 0
            while (isActive) {
                val r = record?.read(buf, 0, buf.size) ?: break
                if (r > 0) processAudio(buf, r)
                if (DEBUG_VERBOSE && ++reads % 200 == 0) Log.d(TAG, "reads=$reads")
            }
        }
        broadcastSource()
    }

    private fun stopCapture() {
        try { record?.stop() } catch (_: Exception) {}
        record?.release(); record = null
    }

    // Core processing
    private fun processAudio(data: ShortArray, len: Int) {
        var sum = 0.0
        for (i in 0 until len) {
            val filtered = hpState.process(data[i].toDouble())
            sum += filtered * filtered
            ringBuffer[ringIndex] = filtered.toFloat()
            ringIndex = (ringIndex + 1) % ringBuffer.size
        }
        val rms = sqrt(sum / len)
        val db = 20 * log10(rms + 1e-9)
        if (db < params.minMediumDb) { // update noise floor slowly
            accumFloor += db; floorSamples++
            if (floorSamples >= 25) {
                val avg = accumFloor / floorSamples
                noiseFloorDb = 0.8 * noiseFloorDb + 0.2 * avg
                accumFloor = 0.0; floorSamples = 0
            }
        }
        val derivative = if (lastRms > 1e-9) (rms / lastRms) - 1.0 else 0.0
        lastRms = rms
        val dynShot = noiseFloorDb + params.shotOffsetDb
        val dynMedium = noiseFloorDb + params.mediumOffsetDb
        val now = System.currentTimeMillis()
    val shotCond = (db > params.minShotDb || db > dynShot) && derivative > params.minDerivativeShot
        val mediumCond = (db > params.minMediumDb || db > dynMedium) && derivative > params.minDerivativeMedium
    // Dynamic refractory: if weapon firing very fast (previous interval < rapidIntervalFastMs) shrink refractory further
    val dynamicRefractory = if (lastShotInterval in 1 until params.rapidIntervalFastMs) params.fastRefractoryMs else params.refractoryShotMs
    // Micro-shot condition: allows triggering when peaks smear together
    val microShotCond = (db > dynShot - params.microShotDbDelta && derivative > params.microShotDerivative)

        // (Explosion detection removed temporarily)
        blocksSinceFft++
        if (blocksSinceFft >= 4) {
            blocksSinceFft = 0
            lastLowRatio = computeLowFreqRatio() // still track for future tuning
        }

        if (shotCond && now - lastVibe > dynamicRefractory) {
            // Pure per-shot vibration
            lastShotInterval = now - lastShotTime
            rapidShotsCount = if (lastShotInterval < 160) (rapidShotsCount + 1).coerceAtMost(1000) else 1
            lastShotTime = now
            val strength = classifyShot(db, derivative)
            vibrateShot(strength, adaptiveInterval = lastShotInterval)
            shotsDetected++
            if (strength >= 1) strongShotsDetected++
            if (strength == 2) ultraShotsDetected++
            lastVibe = now
            lastStrength = strength
        } else if (!shotCond && microShotCond && now - lastVibe > params.microShotMinGapMs) {
            // Fallback micro shot (treat as soft or upgrade based on last strength)
            lastShotInterval = now - lastShotTime
            rapidShotsCount = if (lastShotInterval < 160) (rapidShotsCount + 1).coerceAtMost(1000) else 1
            lastShotTime = now
            val strength = if (lastStrength > 0) lastStrength else 0
            vibrateShot(strength, adaptiveInterval = lastShotInterval)
            shotsDetected++
            if (strength >= 1) strongShotsDetected++
            if (strength == 2) ultraShotsDetected++
            lastVibe = now
        } else if (mediumCond && now - lastVibe > params.refractoryMediumMs) {
            vibrateTap(params.vibBurstTapMs)
            lastVibe = now
        }

        if (now - lastStatsLog > 5000) {
            lastStatsLog = now
            Log.i(TAG, "Stats shots=$shotsDetected strong=$strongShotsDetected ultra=$ultraShotsDetected rapidSeq=$rapidShotsCount low=${"%.2f".format(lastLowRatio)} floor=${"%.1f".format(noiseFloorDb)}")
        }

        if (DEBUG_VERBOSE) {
            Log.d(TAG, "db=${"%.1f".format(db)} der=${"%.2f".format(derivative)} floor=${"%.1f".format(noiseFloorDb)} low=${"%.2f".format(lastLowRatio)} shot=$shotCond med=$mediumCond rapid=$rapidShotsCount interval=$lastShotInterval")
        }
    }

    // Support
    private fun broadcastSource() {
        lastSource = if (usePlayback) "PLAYBACK" else "MIC"
        sendBroadcast(Intent(BROADCAST_SOURCE).putExtra(EXTRA_SOURCE, lastSource))
    }

    private fun computeLowFreqRatio(): Double {
        if (ringBuffer.size < fftSize) return 0.0
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
        val nyquist = fftSize / 2
        val binHz = sampleRate.toDouble() / fftSize
        val lowMaxBin = (300.0 / binHz).toInt().coerceAtMost(nyquist)
        var low = 0.0
        var total = 0.0
        for (i in 1 until nyquist) { // skip DC
            val mag2 = fftReal[i]*fftReal[i] + fftImag[i]*fftImag[i]
            total += mag2
            if (i <= lowMaxBin) low += mag2
        }
        return if (total > 0) low / total else 0.0
    }

    private fun vibrateTap(ms: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(
            VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
        ) else @Suppress("DEPRECATION") vibrator.vibrate(ms.toLong())
    }

    private fun vibrateShot(strength: Int, adaptiveInterval: Long) {
        var baseDur = when (strength) {
            2 -> params.vibShotUltraMs
            1 -> params.vibShotStrongMs
            else -> params.vibShotSoftMs
        }
        if (adaptiveInterval in 1 until params.shortenThresholdMs) {
            val shortened = (baseDur * params.shortenFactor).roundToInt().coerceAtLeast(params.minPulseMs)
            baseDur = shortened
        }
        val dur = baseDur
        val supportsAmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.hasAmplitudeControl() else false
        val amp = if (supportsAmp) when (strength) { 2 -> 255; 1 -> 235; else -> 200 } else VibrationEffect.DEFAULT_AMPLITUDE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(dur.toLong(), amp))
        } else @Suppress("DEPRECATION") vibrator.vibrate(dur.toLong())
    }

    private fun classifyShot(db: Double, derivative: Double): Int {
        val dynShot = noiseFloorDb + params.shotOffsetDb
        val excess = db - dynShot
        return when {
            excess > params.ultraExcessDb || derivative > (params.minDerivativeShot * params.ultraDerivativeMul) -> 2
            excess > 6.0 || derivative > (params.minDerivativeShot * 2.2) -> 1
            else -> 0
        }
    }

    // Lifecycle
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        stopCapture()
        projection?.stop(); projection = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PROJECTION_RESULT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA_INTENT)
                if (code == Activity.RESULT_OK && data != null) {
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
        @Volatile var DEBUG_VERBOSE = false
        const val ACTION_STOP = "action_stop"
        const val ACTION_PROJECTION_RESULT = "action_projection_result"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"
        private const val CHANNEL_ID = "audio_vibe"
        private const val NOTIF_ID = 1
        const val BROADCAST_SOURCE = "com.example.shootvibes.SOURCE"
        const val EXTRA_SOURCE = "extra_source"
        @Volatile var lastSource: String = "MIC"
    }
}

// Simple 1st order high-pass to attenuate very low freq energy
private class HighPassState {
    private var prevIn = 0.0
    private var prevOut = 0.0
    private val rc = 0.05
    private val dt = 1.0 / 44100.0
    private val alpha = rc / (rc + dt)
    fun process(x: Double): Double {
        val y = alpha * (prevOut + x - prevIn)
        prevIn = x
        prevOut = y
        return y
    }
}
