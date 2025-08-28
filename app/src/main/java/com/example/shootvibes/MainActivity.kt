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
import androidx.lifecycle.lifecycleScope
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager


class MainActivity : ComponentActivity() {
    private lateinit var soundPool: SoundPool
    private var singleId by mutableStateOf(0)
    private var burstId by mutableStateOf(0)
    private var autoId by mutableStateOf(0)
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionCallback?.invoke(granted)
        permissionCallback = null
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
                        onAuto = { playShot(ShotType.AUTO) },
                        onToggleAudioReactive = { enabled -> toggleAudioService(enabled) },
                        // perfiles eliminados
                        onProfileChange = { },
                        currentProfile = "",
                        onRequestPlaybackCapture = { requestPlaybackCapture() }
                    )
                }
            }
        }
    }

    // perfiles eliminados
    private fun saveProfile(id: String) { }

    private fun toggleAudioService(start: Boolean) {
        val intent = Intent(this, AudioVibeService::class.java)
        if (start) startService(intent) else stopService(intent)
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val svcIntent = Intent(this, AudioVibeService::class.java).apply {
                action = AudioVibeService.ACTION_PROJECTION_RESULT
                putExtra(AudioVibeService.EXTRA_RESULT_CODE, res.resultCode)
                putExtra(AudioVibeService.EXTRA_DATA_INTENT, res.data)
            }
            startService(svcIntent)
        }
    }

    private fun requestPlaybackCapture() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpm.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }

    private fun playShot(type: ShotType) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        fun vibrate(pattern: LongArray, repeat: Int = -1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, repeat)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, repeat)
            }
        }

        fun vibrateWithAmplitudes(timings: LongArray, amplitudes: IntArray) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            } else {
                // Fallback: ignore amplitudes
                vibrate(timings)
            }
        }

        when (type) {
            ShotType.SINGLE_SOFT -> {
                vibrate(longArrayOf(0, 35)) // disparo suave
            }
            ShotType.BURST_SOFT -> {
                vibrate(longArrayOf(0, 30, 40, 30, 40, 30)) // ráfaga suave 3 golpes
            }
            ShotType.SINGLE_STRONG -> {
                // Pulso más largo + rebote para sensación contundente
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0, 70, 25, 25)
                    val amps = intArrayOf(0, 255, 0, 180)
                    val effect = VibrationEffect.createWaveform(timings, amps, -1)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(70)
                }
            }
            ShotType.BURST_STRONG -> {
                // 3 pulsos más contundentes (duraciones mayores) + amplitud alta
                val timings = longArrayOf(0, 55, 40, 55, 40, 65) // 3 vibraciones
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrateWithAmplitudes(timings, amplitudes)
            }
            ShotType.AUTO -> {
                // Simulate short automatic spray
                val rounds = 8
                val pattern = LongArray(rounds * 2) { i -> if (i % 2 == 0) 0L else 28L }
                vibrate(pattern)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool.release()
    }

    fun ensureMicPermission(cb: (Boolean) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) { cb(true); return }
    permissionCallback = cb
    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

enum class ShotType { SINGLE_SOFT, BURST_SOFT, SINGLE_STRONG, BURST_STRONG, AUTO }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FireScreen(
    onSingleSoft: () -> Unit,
    onBurstSoft: () -> Unit,
    onSingleStrong: () -> Unit,
    onBurstStrong: () -> Unit,
    onAuto: () -> Unit,
    onToggleAudioReactive: (Boolean) -> Unit,
    onProfileChange: (String) -> Unit,
    currentProfile: String,
    onRequestPlaybackCapture: () -> Unit
) {
    var reactive by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var source by remember { mutableStateOf(AudioVibeService.lastSource) }

    // Receiver para fuente
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(p0: android.content.Context?, p1: Intent?) {
                val s = p1?.getStringExtra(AudioVibeService.EXTRA_SOURCE)
                if (s != null) source = s
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(AudioVibeService.BROADCAST_SOURCE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(AudioVibeService.BROADCAST_SOURCE))
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
    Button(onClick = onSingleSoft) { Text(text = "Disparo suave") }
    Button(onClick = onBurstSoft) { Text(text = "Ráfaga suave") }
    Button(onClick = onSingleStrong) { Text(text = "Disparo fuerte") }
    Button(onClick = onBurstStrong) { Text(text = "Ráfaga fuerte") }
        Button(onClick = onAuto) { Text(text = "Automático") }
    Divider()
    Text("Detección audio (ráfagas & explosiones)")
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Audio reactivo")
            Switch(checked = reactive, onCheckedChange = {
                reactive = it
                (context as? MainActivity)?.let { act ->
                    if (it) act.ensureMicPermission { granted ->
                        if (granted) onToggleAudioReactive(true) else reactive = false
                    } else onToggleAudioReactive(false)
                }
            }, colors = SwitchDefaults.colors())
        }
    Text("Fuente actual: $source")
    Button(onClick = onRequestPlaybackCapture) { Text("Capturar audio sistema (API29+)") }
    }
}

private const val REQ_MIC = 2001

