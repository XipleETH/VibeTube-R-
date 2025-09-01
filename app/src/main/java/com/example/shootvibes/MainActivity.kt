package com.example.shootvibes

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
// removed unused styling imports
import androidx.compose.ui.input.pointer.pointerInteropFilter
import android.view.MotionEvent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var soundPool: SoundPool
    private var singleId by mutableStateOf(0)
    private var burstId by mutableStateOf(0)
    private var autoId by mutableStateOf(0)
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var capturing by mutableStateOf(false)

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val svcIntent = Intent(this, AudioVibeService::class.java).apply {
                action = AudioVibeService.ACTION_PROJECTION_RESULT
                putExtra(AudioVibeService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(AudioVibeService.EXTRA_DATA_INTENT, res.data)
            }
            startService(svcIntent)
            capturing = true
        } else capturing = false
    }

    private fun startProjection() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        startService(Intent(this, AudioVibeService::class.java).apply { action = AudioVibeService.ACTION_STOP })
        capturing = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setAudioAttributes(attrs)
            .setMaxStreams(4)
            .build()

        // TODO: Replace with real short gunshot samples placed in res/raw
        // For placeholder we will load the same sample names when added later
        // singleId = soundPool.load(this, R.raw.single_shot, 1)
        // burstId = soundPool.load(this, R.raw.burst_shot, 1)
        // autoId = soundPool.load(this, R.raw.auto_shot, 1)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FireScreen(
                        onSingleSoft = { playShot(ShotType.SINGLE_SOFT) },
                        onBurstSoft = { playShot(ShotType.BURST_SOFT) },
                        onSingleStrong = { playShot(ShotType.SINGLE_STRONG) },
                        onBurstStrong = { playShot(ShotType.BURST_STRONG) },
                        onSingleUltra = { playShot(ShotType.SINGLE_ULTRA) },
                        onBurstUltra = { playShot(ShotType.BURST_ULTRA) },
                        onExplosion = { playShot(ShotType.EXPLOSION) },
                        capturing = capturing,
                        onToggleCapture = { want -> if (want) startProjection() else stopCapture() }
                    )
                }
            }
        }
    }

    private fun playShot(type: ShotType) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else { @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator }

        fun vibrateWithAmplitudes(timings: LongArray, amplitudes: IntArray) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else { @Suppress("DEPRECATION") vibrator.vibrate(timings, -1) }
        }

        when (type) {
            ShotType.SINGLE_SOFT -> {
                vibrateWithAmplitudes(longArrayOf(0, 35), intArrayOf(0, 180))
            }
            ShotType.BURST_SOFT -> {
                // 4 pulsos suaves (antes eran 3). Patrón: vib 28 / pausa 40 repetido 4 veces.
                vibrateWithAmplitudes(
                    longArrayOf(0, 28, 40, 28, 40, 28, 40, 28),
                    intArrayOf(0, 170, 0, 170, 0, 170, 0, 170)
                )
            }
            ShotType.SINGLE_STRONG -> {
                vibrateWithAmplitudes(longArrayOf(0, 70), intArrayOf(0, 255))
            }
            ShotType.BURST_STRONG -> {
                // 4 pulsos fuertes (antes 3). Pulsos ~55 ms separados por 40 ms.
                vibrateWithAmplitudes(
                    longArrayOf(0, 55, 40, 55, 40, 55, 40, 55),
                    intArrayOf(0, 255, 0, 240, 0, 240, 0, 240)
                )
            }
            ShotType.SINGLE_ULTRA -> {
                // Ultra: longer + full amplitude + slight tail reinforcement
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(
                        longArrayOf(0, 90, 30, 35), intArrayOf(0, 255, 0, 220), -1
                    ))
                } else @Suppress("DEPRECATION") vibrator.vibrate(90)
            }
            ShotType.BURST_ULTRA -> {
                // Ultra burst: 4 heavy pulses
                vibrateWithAmplitudes(
                    longArrayOf(0, 70, 45, 70, 45, 70, 45, 85),
                    intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)
                )
            }
            ShotType.EXPLOSION -> {
                vibrateWithAmplitudes(
                    longArrayOf(0, 100, 40, 150, 50, 120),
                    intArrayOf(0, 255, 0, 230, 0, 160)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool.release()
    }

    fun ensureMicPermission(cb: (Boolean) -> Unit) { cb(true) }
}

enum class ShotType { SINGLE_SOFT, BURST_SOFT, SINGLE_STRONG, BURST_STRONG, SINGLE_ULTRA, BURST_ULTRA, EXPLOSION }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FireScreen(
    onSingleSoft: () -> Unit,
    onBurstSoft: () -> Unit,
    onSingleStrong: () -> Unit,
    onBurstStrong: () -> Unit,
    onSingleUltra: () -> Unit,
    onBurstUltra: () -> Unit,
    onExplosion: () -> Unit,
    capturing: Boolean,
    onToggleCapture: (Boolean) -> Unit
) {
    var lang by remember { mutableStateOf(Language.ES) }
    fun tr(es: String, en: String) = if (lang == Language.ES) es else en

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Button(onClick = { lang = if (lang == Language.ES) Language.EN else Language.ES }) { Text(tr("English", "Español")) }
        Button(onClick = onSingleSoft) { Text(tr("Disparo suave", "Soft Shot")) }
    HoldBurstButton(label = tr("Ráfaga suave", "Soft Burst")) { onBurstSoft() }
        Button(onClick = onSingleStrong) { Text(tr("Disparo fuerte", "Strong Shot")) }
    HoldBurstButton(label = tr("Ráfaga fuerte", "Strong Burst")) { onBurstStrong() }
        Button(onClick = onSingleUltra) { Text(tr("Disparo ULTRA", "ULTRA Shot")) }
    HoldBurstButton(label = tr("Ráfaga ULTRA", "ULTRA Burst")) { onBurstUltra() }
        Button(onClick = onExplosion) { Text(tr("Explosión", "Explosion")) }
        Divider()
        Button(onClick = { onToggleCapture(!capturing) }) { Text(tr(if (capturing) "Detener captura" else "Capturar audio", if (capturing) "Stop capture" else "Capture audio")) }
    }
}

enum class Language { ES, EN }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun HoldBurstButton(
    label: String,
    intervalMs: Long = 130L,
    onBurstTick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        while (pressed) {
            onBurstTick()
            delay(intervalMs)
        }
    }
    Button(
        onClick = { /* handled by press state */ },
        modifier = Modifier
            .fillMaxWidth()
            .pointerInteropFilter { e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { pressed = true; true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressed = false; true }
                    else -> false
                }
            }
    ) { Text(label) }
}

