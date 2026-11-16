package com.example.sirena

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Пастельная палитра Sentinel + оттенки для Glassmorphism
object SentinelColors {
    val Milk = Color(0xFFFAF9FE)
    val SoftLavenderBg = Color(0xFFF2ECFF)
    val SoftMintBg = Color(0xFFEAF8F3)
    val Cloud = Color(0xFFF7F8FA)
    val Line = Color(0xFFEAE6F3)
    val Ink = Color(0xFF1E242B)
    val Slate = Color(0xFF2D3436)
    val Muted = Color(0xFF7F8C8D)
    val Sage = Color(0xFFA8D5BA)
    val Mint = Color(0xFFB8E0D2)
    val Lavender = Color(0xFFD4C4FB)
    val LavenderDeep = Color(0xFF9B85EC)
    val Sky = Color(0xFFBCE0FD)
    val Coral = Color(0xFFFF9AA2)
    val Rose = Color(0xFFFF758F)
}

enum class Phase { IDLE, COUNTDOWN, ARMED, TRIGGERED }
enum class Tab { GUARD, SOUNDS, SETTINGS }

data class CustomTrack(val id: String, val name: String, val path: String, val sizeMb: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.parseColor("#F6F3FC")
        window.navigationBarColor = android.graphics.Color.parseColor("#F2ECFF")

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = SentinelColors.Milk)) {
                SentinelApp()
            }
        }
    }
}

// Звуковой движок с отдачей живой амплитуды для эквалайзера
class SentinelAudioEngine(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var synthActive = false
    private var synthThread: Thread? = null
    @Volatile var currentEnergy: Float = 0f
        private set

    fun isPlaying(): Boolean = synthActive || (mediaPlayer?.isPlaying == true)

    fun play(
        soundId: String,
        customTracks: List<CustomTrack>,
        loop: Boolean,
        forceMax: Boolean,
        onEnd: (() -> Unit)? = null
    ) {
        stop()
        if (forceMax) {
            forceMaxSystemVolume()
        }

        val custom = customTracks.find { it.id == soundId }
        if (custom != null && File(custom.path).exists()) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(custom.path)
                    isLooping = loop
                    setVolume(1f, 1f)
                    setOnCompletionListener {
                        currentEnergy = 0f
                        if (!loop) onEnd?.invoke()
                    }
                    prepare()
                    start()
                }
                currentEnergy = 0.85f
                return
            } catch (_: Exception) {}
        }

        synthActive = true
        synthThread = thread(start = true) {
            val sr = 22050
            val minBuf = AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (forceMax) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .build()

            val buf = ShortArray(1024)
            var sampleIdx = 0L
            val maxSamples = if (loop) Long.MAX_VALUE else (sr * 4L)

            try {
                track.play()
                while (synthActive && sampleIdx < maxSamples) {
                    var sumAbs = 0.0
                    for (i in buf.indices) {
                        val t = sampleIdx.toDouble() / sr
                        val sampleVal = if (soundId == "alert") {
                            val step = (t / 0.13).toInt() % 8
                            if (step == 7) {
                                0.0
                            } else {
                                val f = if (step % 2 == 0) 2100.0 else 1500.0
                                val s = sin(2.0 * PI * f * t)
                                (if (s >= 0.0) 0.7 else -0.7) + 0.3 * s
                            }
                        } else {
                            val period = 2.0
                            val ph = 2.0 * PI * (950.0 * t + (420.0 * period) / (2.0 * PI) * (1.0 - cos((2.0 * PI * t) / period)))
                            0.62 * sin(ph) + 0.25 * sin(2.0 * ph) + 0.13 * sin(3.0 * ph)
                        }
                        sumAbs += abs(sampleVal)
                        buf[i] = (sampleVal.coerceIn(-1.0, 1.0) * 32767.0 * 0.9).toInt().toShort()
                        sampleIdx++
                    }
                    currentEnergy = (sumAbs / buf.size).toFloat().coerceIn(0.15f, 1f)
                    track.write(buf, 0, buf.size)
                }
                currentEnergy = 0f
                if (!loop && synthActive) onEnd?.invoke()
            } catch (_: Exception) {
            } finally {
                currentEnergy = 0f
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {}
            }
        }
    }

    fun forceMaxSystemVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
    }

    fun playBeep(freq: Double, durationMs: Int) {
        thread(start = true) {
            try {
                val sr = 22050
                val count = (sr * (durationMs / 1000.0)).toInt()
                val buf = ShortArray(count)
                for (i in 0 until count) {
                    val t = i.toDouble() / sr
                    val env = if (i < 300) i / 300.0 else ((count - i).toDouble() / count).coerceIn(0.0, 1.0)
                    buf[i] = (sin(2.0 * PI * freq * t) * 32767.0 * 0.25 * env).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 40L)
                track.release()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        synthActive = false
        synthThread = null
        currentEnergy = 0f
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}

fun hashPin(pin: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("sentinel::$pin::guard".toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun vibratePulse(context: Context, ms: Long = 35L) {
    try {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(ms)
        }
    } catch (_: Exception) {}
}

fun setTorchLight(context: Context, enabled: Boolean) {
    try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull() ?: return
        cm.setTorchMode(camId, enabled)
    } catch (_: Exception) {}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SentinelApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("sentinel_store", Context.MODE_PRIVATE) }
    val audio = remember { SentinelAudioEngine(context) }
    val coroutineScope = rememberCoroutineScope()

    var pinHash by remember { mutableStateOf(prefs.getString("pinHash", null)) }
    var changingPin by remember { mutableStateOf(false) }

    var motionOn by remember { mutableStateOf(prefs.getBoolean("motion", true)) }
    var chargerOn by remember { mutableStateOf(prefs.getBoolean("charger", false)) }
    var sensitivity by remember { mutableStateOf(prefs.getInt("sens", 1)) }
    var soundId by remember { mutableStateOf(prefs.getString("soundId", "classic") ?: "classic") }
    var flashOn by remember { mutableStateOf(prefs.getBoolean("flash", true)) }
    var vibrateOn by remember { mutableStateOf(prefs.getBoolean("vibrate", true)) }

    var customTracks by remember {
        mutableStateOf<List<CustomTrack>>(
            try {
                val arr = JSONArray(prefs.getString("tracks", "[]") ?: "[]")
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    CustomTrack(o.getString("id"), o.getString("name"), o.getString("path"), o.optString("sizeMb", "1.0 MB"))
                }.filter { File(it.path).exists() }
            } catch (_: Exception) { emptyList() }
        )
    }

    var phase by remember { mutableStateOf(Phase.IDLE) }
    var disarmSheetOpen by remember { mutableStateOf(false) }
    var countdownProgress by remember { mutableStateOf(0f) }
    var secondsLeft by remember { mutableStateOf(5) }
    var alarmReason by remember { mutableStateOf("") }
    var toastMsg by remember { mutableStateOf("") }

    var isCharging by remember { mutableStateOf(false) }
    var batteryPct by remember { mutableStateOf(100) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val currentTab = remember(pagerState.currentPage) { Tab.values()[pagerState.currentPage] }

    // Останавливаем предпросмотр музыки, если свайпнули со вкладки Sounds
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 1 && phase != Phase.TRIGGERED) {
            audio.stop()
        }
    }

    fun saveAll() {
        val arr = JSONArray()
        customTracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("path", t.path)
                put("sizeMb", t.sizeMb)
            })
        }
        prefs.edit()
            .putString("pinHash", pinHash)
            .putBoolean("motion", motionOn)
            .putBoolean("charger", chargerOn)
            .putInt("sens", sensitivity)
            .putString("soundId", soundId)
            .putBoolean("flash", flashOn)
            .putBoolean("vibrate", vibrateOn)
            .putString("tracks", arr.toString())
            .apply()
    }

    fun showToast(msg: String) {
        toastMsg = msg
    }

    LaunchedEffect(toastMsg) {
        if (toastMsg.isNotEmpty()) {
            delay(2600L)
            toastMsg = ""
        }
    }

    val locked = phase != Phase.IDLE
    BackHandler(enabled = locked) {
        if (phase == Phase.ARMED) disarmSheetOpen = true
    }

    DisposableEffect(phase, chargerOn) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val lv = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (lv >= 0 && sc > 0) batteryPct = (lv * 100) / sc
                val st = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val nowChg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                if (phase == Phase.ARMED && chargerOn && isCharging && !nowChg) {
                    alarmReason = "Charger unplugged"
                    phase = Phase.TRIGGERED
                }
                isCharging = nowChg
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    LaunchedEffect(phase) {
        if (phase == Phase.COUNTDOWN) {
            val start = System.currentTimeMillis()
            var lastSec = -1
            while (phase == Phase.COUNTDOWN) {
                val el = System.currentTimeMillis() - start
                countdownProgress = (el / 5000f).coerceIn(0f, 1f)
                secondsLeft = ((5000L - el + 999L) / 1000L).toInt().coerceIn(1, 5)
                val sec = (el / 1000L).toInt()
                if (sec != lastSec && sec < 5) {
                    lastSec = sec
                    audio.playBeep(if (sec == 4) 880.0 else 620.0, 180)
                    vibratePulse(context, 25L)
                }
                if (el >= 5000L) {
                    audio.playBeep(784.0, 350)
                    vibratePulse(context, 90L)
                    phase = Phase.ARMED
                    break
                }
                delay(25L)
            }
        } else if (phase == Phase.TRIGGERED) {
            disarmSheetOpen = false
            audio.play(soundId, customTracks, loop = true, forceMax = true)
            var torchState = false
            while (phase == Phase.TRIGGERED) {
                audio.forceMaxSystemVolume()
                if (vibrateOn) vibratePulse(context, 420L)
                if (flashOn) {
                    torchState = !torchState
                    setTorchLight(context, torchState)
                }
                delay(240L)
            }
            setTorchLight(context, false)
        } else {
            setTorchLight(context, false)
        }
    }

    DisposableEffect(phase, motionOn, sensitivity) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val accelThr = floatArrayOf(3.6f, 1.7f, 0.75f)[sensitivity.coerceIn(0, 2)]
        val gyroThr = floatArrayOf(1.9f, 0.95f, 0.42f)[sensitivity.coerceIn(0, 2)]
        val tiltThr = floatArrayOf(26f, 14f, 7f)[sensitivity.coerceIn(0, 2)]

        val settleUntil = System.currentTimeMillis() + 1500L
        var base: FloatArray? = null
        var refAcc = FloatArray(3)
        var refCount = 0
        var refPose: FloatArray? = null
        val hits = mutableListOf<Long>()

        fun recordHit() {
            val now = System.currentTimeMillis()
            hits.removeAll { now - it >= 700L }
            hits.add(now)
            if (hits.size >= 2 && phase == Phase.ARMED) {
                alarmReason = "Motion detected"
                phase = Phase.TRIGGERED
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent?) {
                if (e == null || phase != Phase.ARMED || !motionOn) return
                val now = System.currentTimeMillis()
                if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val vx = e.values[0]; val vy = e.values[1]; val vz = e.values[2]
                    if (base == null) base = floatArrayOf(vx, vy, vz)
                    val b = base!!
                    val dev = sqrt((vx - b[0]) * (vx - b[0]) + (vy - b[1]) * (vy - b[1]) + (vz - b[2]) * (vz - b[2]))
                    b[0] = b[0] * 0.92f + vx * 0.08f
                    b[1] = b[1] * 0.92f + vy * 0.08f
                    b[2] = b[2] * 0.92f + vz * 0.08f

                    if (now < settleUntil) {
                        refAcc[0] += vx; refAcc[1] += vy; refAcc[2] += vz
                        refCount++
                        return
                    }
                    if (refPose == null && refCount > 0) {
                        refPose = floatArrayOf(refAcc[0] / refCount, refAcc[1] / refCount, refAcc[2] / refCount)
                    }
                    var triggered = dev > accelThr
                    refPose?.let { rp ->
                        val m = sqrt(vx * vx + vy * vy + vz * vz) * sqrt(rp[0] * rp[0] + rp[1] * rp[1] + rp[2] * rp[2])
                        if (m > 0f) {
                            val dot = ((vx * rp[0] + vy * rp[1] + vz * rp[2]) / m).coerceIn(-1f, 1f)
                            val deg = (acos(dot.toDouble()) * 180.0 / PI).toFloat()
                            if (deg > tiltThr) triggered = true
                        }
                    }
                    if (triggered) recordHit()
                } else if (e.sensor.type == Sensor.TYPE_GYROSCOPE && now >= settleUntil) {
                    val rot = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    if (rot > gyroThr) recordHit()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (phase == Phase.ARMED && motionOn) {
            if (accel != null) sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            if (gyro != null) sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sm.unregisterListener(listener) }
    }

    // Дрейфующий пастельный фон (перелив из молочного в нежно-лавандовый и мятный)
    val bgInf = rememberInfiniteTransition(label = "pastelBg")
    val drift1 by bgInf.animateFloat(
        initialValue = -35f,
        targetValue = 45f,
        animationSpec = infiniteRepeatable(tween(6500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift1"
    )
    val drift2 by bgInf.animateFloat(
        initialValue = 40f,
        targetValue = -35f,
        animationSpec = infiniteRepeatable(tween(7800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift2"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        SentinelColors.Milk,
                        SentinelColors.SoftLavenderBg,
                        SentinelColors.SoftMintBg.copy(alpha = 0.7f),
                        SentinelColors.Milk
                    )
                )
            )
    ) {
        // Живые размытые пастельные сферы под матовым стеклом
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SentinelColors.Lavender.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(size.width * 0.85f + drift1, size.height * 0.12f - drift2),
                    radius = size.minDimension * 0.58f
                ),
                center = Offset(size.width * 0.85f + drift1, size.height * 0.12f - drift2),
                radius = size.minDimension * 0.58f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SentinelColors.Mint.copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(size.width * 0.15f - drift2, size.height * 0.48f + drift1),
                    radius = size.minDimension * 0.62f
                ),
                center = Offset(size.width * 0.15f - drift2, size.height * 0.48f + drift1),
                radius = size.minDimension * 0.62f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SentinelColors.Sky.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(size.width * 0.75f + drift2, size.height * 0.85f - drift1),
                    radius = size.minDimension * 0.52f
                ),
                center = Offset(size.width * 0.75f + drift2, size.height * 0.85f - drift1),
                radius = size.minDimension * 0.52f
            )
        }

        if (pinHash == null || changingPin) {
            SetupScreen(
                changing = changingPin,
                onCancel = if (changingPin) ({ changingPin = false }) else null,
                onDone = { newPin ->
                    val wasChanging = changingPin
                    pinHash = hashPin(newPin)
                    changingPin = false
                    saveAll()
                    showToast(if (wasChanging) "PIN updated" else "You're all set")
                }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // Плавное переключение между вкладками свайпом по экрану!
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !locked,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    when (pageIndex) {
                        0 -> GuardScreen(
                            phase = phase,
                            progress = countdownProgress,
                            secondsLeft = secondsLeft,
                            motionOn = motionOn,
                            chargerOn = chargerOn,
                            charging = isCharging,
                            batteryPct = batteryPct,
                            sensitivity = sensitivity,
                            onArm = {
                                if (!motionOn && !chargerOn) {
                                    showToast("Turn on Motion Guard or Charger Unplug first")
                                } else if (chargerOn && !motionOn && !isCharging) {
                                    showToast("Plug in the charger to use Charger Unplug")
                                } else {
                                    if (chargerOn && !isCharging) showToast("Charger not plugged in — watching Motion")
                                    phase = Phase.COUNTDOWN
                                }
                            },
                            onCancel = { if (phase == Phase.COUNTDOWN) phase = Phase.IDLE },
                            onDisarmRequest = { disarmSheetOpen = true },
                            onToggleMotion = { motionOn = it; saveAll() },
                            onToggleCharger = { chargerOn = it; saveAll() },
                            onSensitivityChange = { sensitivity = it; saveAll() }
                        )

                        1 -> SoundsScreen(
                            selectedId = soundId,
                            customTracks = customTracks,
                            audio = audio,
                            onSelect = { soundId = it; saveAll() },
                            onAddTrack = { track ->
                                customTracks = customTracks + track
                                soundId = track.id
                                saveAll()
                            },
                            onRemoveTrack = { track ->
                                try { File(track.path).delete() } catch (_: Exception) {}
                                customTracks = customTracks.filterNot { it.id == track.id }
                                if (soundId == track.id) soundId = "classic"
                                saveAll()
                                showToast("Removed “${track.name}”")
                            },
                            onToast = ::showToast
                        )

                        else -> SettingsScreen(
                            flashOn = flashOn,
                            vibrateOn = vibrateOn,
                            onToggleFlash = { flashOn = it; saveAll() },
                            onToggleVibrate = { vibrateOn = it; saveAll() },
                            onChangePin = { changingPin = true },
                            onTestAlarm = {
                                coroutineScope.launch { pagerState.animateScrollToPage(0) }
                                alarmReason = "Test alarm"
                                phase = Phase.TRIGGERED
                            },
                            onResetApp = {
                                audio.stop()
                                customTracks.forEach { try { File(it.path).delete() } catch (_: Exception) {} }
                                customTracks = emptyList()
                                pinHash = null
                                motionOn = true
                                chargerOn = false
                                sensitivity = 1
                                soundId = "classic"
                                prefs.edit().clear().apply()
                                coroutineScope.launch { pagerState.scrollToPage(0) }
                            }
                        )
                    }
                }

                // Стеклянная нижняя капсула навигации
                AnimatedVisibility(
                    visible = !locked,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(200)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 22.dp)
                ) {
                    SentinelBottomNav(
                        current = currentTab,
                        onSelect = { targetTab ->
                            vibratePulse(context, 15L)
                            audio.stop()
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(
                                    page = targetTab.ordinal,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    )
                }

                if (disarmSheetOpen && phase == Phase.ARMED) {
                    DisarmBottomSheet(
                        expectedPinHash = pinHash!!,
                        onClose = { disarmSheetOpen = false },
                        onSuccess = {
                            disarmSheetOpen = false
                            phase = Phase.IDLE
                            audio.stop()
                            showToast("Disarmed")
                        }
                    )
                }

                if (phase == Phase.TRIGGERED) {
                    AlarmTriggeredScreen(
                        reason = alarmReason,
                        expectedPinHash = pinHash!!,
                        strobe = flashOn,
                        onUnlock = {
                            phase = Phase.IDLE
                            audio.stop()
                            showToast("Disarmed")
                        }
                    )
                }
            }
        }

        // Всплывающее уведомление сверху
        AnimatedVisibility(
            visible = toastMsg.isNotEmpty(),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(220)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp, start = 24.dp, end = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .shadow(14.dp, CircleShape, spotColor = Color(0x331E242B))
                    .clip(CircleShape)
                    .background(SentinelColors.Ink.copy(alpha = 0.92f))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(toastMsg, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

/* ───────────────────────── ЖИВОЙ ПАСТЕЛЬНЫЙ ЭКВАЛАЙЗЕР ───────────────────────── */
@Composable
fun PastelEqualizerVisualizer(
    playing: Boolean,
    trackTitle: String,
    audio: SentinelAudioEngine
) {
    var tick by remember { mutableStateOf(0f) }
    var energy by remember { mutableStateOf(0f) }

    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                tick += 0.24f
                energy = audio.currentEnergy.coerceIn(0.25f, 1f)
                delay(28L)
            }
        } else {
            energy = 0f
        }
    }

    val barColors = listOf(
        SentinelColors.Mint,
        SentinelColors.Sage,
        SentinelColors.Sky,
        SentinelColors.Lavender,
        SentinelColors.LavenderDeep,
        SentinelColors.Coral,
        SentinelColors.Rose
    )

    SentinelGlassCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = playing
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (playing) SentinelColors.LavenderDeep else SentinelColors.Muted.copy(alpha = 0.35f))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (playing) "Now playing · $trackTitle" else "Pastel Equalizer · Tap ▶ on any sound",
                        color = SentinelColors.Slate,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (playing) "LIVE" else "IDLE",
                    color = if (playing) SentinelColors.LavenderDeep else SentinelColors.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 22 плавных пастельных столбика эквалайзера
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                val count = 22
                val spacing = 5.dp.toPx()
                val totalSpacing = spacing * (count - 1)
                val barW = ((size.width - totalSpacing) / count).coerceAtLeast(4f)

                for (i in 0 until count) {
                    val norm = if (playing) {
                        val w1 = sin(tick * 1.9f + i * 0.55f)
                        val w2 = cos(tick * 1.25f - i * 0.85f)
                        val w3 = sin(tick * 2.7f + i * 0.3f)
                        val raw = ((w1 * 0.42f + w2 * 0.35f + w3 * 0.23f) + 1f) / 2f
                        // Придаем куполообразную форму по центру
                        val centerBoost = 1f - abs(i - count / 2f) / (count * 0.75f)
                        (0.16f + raw * 0.84f * energy * centerBoost).coerceIn(0.14f, 1f)
                    } else {
                        0.15f + 0.05f * sin(i * 0.6f)
                    }

                    val barH = (size.height * norm).coerceAtLeast(8.dp.toPx())
                    val x = i * (barW + spacing)
                    val y = (size.height - barH) / 2f

                    val c1 = barColors[i % barColors.size]
                    val c2 = barColors[(i + 2) % barColors.size]

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = if (playing) listOf(c1, c2) else listOf(
                                SentinelColors.Lavender.copy(alpha = 0.45f),
                                SentinelColors.Mint.copy(alpha = 0.45f)
                            ),
                            startY = y,
                            endY = y + barH
                        ),
                        topLeft = Offset(x, y),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                    )
                }
            }
        }
    }
}

/* ───────────────────────── ЭКРАН 1: НАСТРОЙКА PIN (Setup) ───────────────────────── */
@Composable
fun SetupScreen(changing: Boolean, onCancel: (() -> Unit)?, onDone: (String) -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(if (changing) "create" else "welcome") }
    var firstPin by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    fun onDigit(d: String) {
        if (currentPin.length >= 4) return
        vibratePulse(context, 20L)
        val next = currentPin + d
        currentPin = next
        if (next.length == 4) {
            if (step == "create") {
                firstPin = next
                currentPin = ""
                errorMsg = ""
                step = "confirm"
            } else if (step == "confirm") {
                if (next == firstPin) {
                    currentPin = ""
                    onDone(next)
                } else {
                    vibratePulse(context, 180L)
                    errorMsg = "PINs didn't match — try again"
                    currentPin = ""
                    step = "create"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (onCancel != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "← Cancel",
                    color = SentinelColors.Muted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onCancel() }.padding(vertical = 4.dp)
                )
            }
        }

        if (step == "welcome") {
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .shadow(24.dp, CircleShape, spotColor = SentinelColors.LavenderDeep)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(SentinelColors.Lavender, SentinelColors.Sky)))
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("🛡️", fontSize = 54.sp)
            }
            Spacer(modifier = Modifier.height(36.dp))
            Text(
                text = "Welcome to Sentinel",
                color = SentinelColors.Slate,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "A calm, quiet guardian for your phone. If someone moves it or pulls the charger, it sounds the alarm.",
                color = SentinelColors.Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.widthIn(max = 280.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(14.dp, CircleShape, spotColor = SentinelColors.Ink)
                    .clip(CircleShape)
                    .background(SentinelColors.Ink)
                    .clickable { step = "create" },
                contentAlignment = Alignment.Center
            ) {
                Text("Get started", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(SentinelColors.Mint, SentinelColors.Sage)))
                    .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🛡️", fontSize = 26.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (step == "create") (if (changing) "Choose a new PIN" else "Create your PIN") else "Confirm your PIN",
                color = SentinelColors.Slate,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMsg.ifEmpty {
                    if (step == "create") "4 digits — the only way to silence the alarm" else "Enter it once more"
                },
                color = if (errorMsg.isNotEmpty()) SentinelColors.Rose else SentinelColors.Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            PinDotsRow(filled = currentPin.length, light = false)
            Spacer(modifier = Modifier.weight(1f))
            SentinelKeypad(
                light = false,
                onDigit = ::onDigit,
                onDelete = { if (currentPin.isNotEmpty()) currentPin = currentPin.dropLast(1) }
            )
        }
    }
}

/* ───────────────────────── ЭКРАН 2: ГЛАВНЫЙ ЭКРАН (Guard) ───────────────────────── */
@Composable
fun GuardScreen(
    phase: Phase,
    progress: Float,
    secondsLeft: Int,
    motionOn: Boolean,
    chargerOn: Boolean,
    charging: Boolean,
    batteryPct: Int,
    sensitivity: Int,
    onArm: () -> Unit,
    onCancel: () -> Unit,
    onDisarmRequest: () -> Unit,
    onToggleMotion: (Boolean) -> Unit,
    onToggleCharger: (Boolean) -> Unit,
    onSensitivityChange: (Int) -> Unit
) {
    val locked = phase != Phase.IDLE
    val sensLabels = listOf("Low", "Medium", "High")
    val sensHints = listOf(
        "Only firm shakes or a clear pickup",
        "Balanced — picks up lifting & tilting",
        "Reacts to the slightest nudge"
    )

    val inf = rememberInfiniteTransition(label = "breathe")
    val breatheScale by inf.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.13f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val breatheAlpha by inf.animateFloat(
        initialValue = 0.52f,
        targetValue = 0.18f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alpha"
    )

    val (pillText, pillBg, pillDot) = when (phase) {
        Phase.ARMED -> Triple("Armed", SentinelColors.Lavender.copy(alpha = 0.72f), Color(0xFF8D72E8))
        Phase.COUNTDOWN -> Triple("Arming…", SentinelColors.Sky.copy(alpha = 0.72f), Color(0xFF4FA3E0))
        else -> Triple("System Ready", SentinelColors.Sage.copy(alpha = 0.62f), Color(0xFF4AA56F))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 130.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Anti-theft alarm", color = SentinelColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Sentinel", color = SentinelColors.Slate, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(pillBg)
                    .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(pillDot))
                Spacer(modifier = Modifier.width(8.dp))
                Text(pillText, color = SentinelColors.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
            }
        }

        // Центральная сфера со стеклянным бликом
        Box(
            modifier = Modifier
                .padding(top = 24.dp)
                .size(290.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            if (phase == Phase.ARMED) {
                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .scale(breatheScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    SentinelColors.Lavender.copy(alpha = breatheAlpha),
                                    SentinelColors.Sky.copy(alpha = breatheAlpha * 0.5f)
                                )
                            )
                        )
                )
            } else if (phase == Phase.IDLE) {
                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .scale(breatheScale * 0.96f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    SentinelColors.Mint.copy(alpha = 0.42f),
                                    SentinelColors.Lavender.copy(alpha = 0.18f)
                                )
                            )
                        )
                )
            }

            if (phase == Phase.COUNTDOWN) {
                Canvas(modifier = Modifier.size(270.dp)) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.7f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = SentinelColors.LavenderDeep,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            val btnGradient = if (phase == Phase.IDLE) {
                Brush.linearGradient(listOf(SentinelColors.Sage, SentinelColors.Mint, SentinelColors.Sky.copy(alpha = 0.85f)))
            } else {
                Brush.linearGradient(listOf(SentinelColors.Lavender, SentinelColors.Sky, SentinelColors.Mint.copy(alpha = 0.85f)))
            }

            Box(
                modifier = Modifier
                    .size(230.dp)
                    .shadow(26.dp, CircleShape, spotColor = if (phase == Phase.IDLE) Color(0xFF78BE96) else Color(0xFF9682EB))
                    .clip(CircleShape)
                    .background(btnGradient)
                    .border(2.5.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        when (phase) {
                            Phase.IDLE -> onArm()
                            Phase.COUNTDOWN -> onCancel()
                            Phase.ARMED -> onDisarmRequest()
                            else -> {}
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (phase == Phase.COUNTDOWN) {
                        Text(
                            text = "$secondsLeft",
                            color = SentinelColors.Ink,
                            fontSize = 74.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Set it down · tap to cancel",
                            color = SentinelColors.Ink.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(if (phase == Phase.ARMED) "🔒" else "🛡️", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (phase == Phase.ARMED) "System Armed" else "Tap to Arm",
                            color = SentinelColors.Ink,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (phase == Phase.ARMED) "Tap to disarm with PIN" else "5 second countdown",
                            color = SentinelColors.Ink.copy(alpha = 0.65f),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Стеклянные карточки режимов
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SentinelModeCard(
                modifier = Modifier.weight(1f),
                emoji = "📱",
                tint = SentinelColors.Lavender,
                accent = Color(0xFFBFA9F7),
                title = "Motion Guard",
                sub = "Moves, tilts, pickup",
                checked = motionOn,
                disabled = locked,
                onToggle = onToggleMotion
            )
            SentinelModeCard(
                modifier = Modifier.weight(1f),
                emoji = "🔌",
                tint = SentinelColors.Sky,
                accent = Color(0xFF9ACFF8),
                title = "Charger Unplug",
                sub = if (charging) "Charging · $batteryPct%" else "Not plugged in",
                checked = chargerOn,
                disabled = locked,
                onToggle = onToggleCharger
            )
        }

        // Стеклянная карточка чувствительности
        SentinelGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sensitivity", color = SentinelColors.Slate, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SentinelColors.Lavender.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(sensLabels[sensitivity.coerceIn(0, 2)], color = SentinelColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sensLabels.forEachIndexed { idx, label ->
                        val active = sensitivity == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (active) Brush.linearGradient(
                                        listOf(SentinelColors.Lavender, SentinelColors.Sky.copy(alpha = 0.8f))
                                    ) else Brush.linearGradient(
                                        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.35f))
                                    )
                                )
                                .border(
                                    1.dp,
                                    if (active) SentinelColors.LavenderDeep else Color.White.copy(alpha = 0.8f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable(enabled = !locked && motionOn) { onSensitivityChange(idx) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (active) SentinelColors.Ink else SentinelColors.Muted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(sensHints[sensitivity.coerceIn(0, 2)], color = SentinelColors.Muted, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
fun SentinelModeCard(
    modifier: Modifier,
    emoji: String,
    tint: Color,
    accent: Color,
    title: String,
    sub: String,
    checked: Boolean,
    disabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    SentinelGlassCard(modifier = modifier, highlighted = checked) {
        Column(
            modifier = Modifier
                .clickable(enabled = !disabled) { onToggle(!checked) }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(tint.copy(alpha = 0.85f))
                        .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 20.sp)
                }
                SentinelSwitch(checked = checked, color = accent, onCheckedChange = { if (!disabled) onToggle(it) })
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, color = SentinelColors.Slate, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = SentinelColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ───────────────────────── ЭКРАН 3: ЗВУКИ + ЭКВАЛАЙЗЕР (Sounds) ───────────────────────── */
@Composable
fun SoundsScreen(
    selectedId: String,
    customTracks: List<CustomTrack>,
    audio: SentinelAudioEngine,
    onSelect: (String) -> Unit,
    onAddTrack: (CustomTrack) -> Unit,
    onRemoveTrack: (CustomTrack) -> Unit,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    var playingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { audio.stop() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            if (customTracks.size >= 5) {
                onToast("Limit reached — up to 5 custom sounds")
                return@rememberLauncherForActivityResult
            }
            try {
                var rawName = "Custom track"
                var sizeBytes = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nIdx >= 0) rawName = c.getString(nIdx)
                        if (sIdx >= 0) sizeBytes = c.getLong(sIdx)
                    }
                }
                if (sizeBytes > 25L * 1024L * 1024L) {
                    onToast("File is too large (max 25 MB)")
                    return@rememberLauncherForActivityResult
                }
                val id = "c_" + System.currentTimeMillis()
                val destDir = File(context.filesDir, "sounds").apply { mkdirs() }
                val destFile = File(destDir, "$id.mp3")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
                val clean = rawName.substringBeforeLast(".").replace(Regex("[_\\-.]+"), " ").trim().ifEmpty { "Custom track" }
                val sizeMb = String.format("%.1f MB · imported", sizeBytes / (1024.0 * 1024.0))
                onAddTrack(CustomTrack(id, clean.take(28), destFile.absolutePath, sizeMb))
                onToast("Imported “${clean.take(20)}”")
            } catch (_: Exception) {
                onToast("Couldn't import that file")
            }
        }
    }

    fun togglePreview(id: String) {
        vibratePulse(context, 18L)
        if (playingId == id) {
            audio.stop()
            playingId = null
        } else {
            playingId = id
            audio.play(id, customTracks, loop = false, forceMax = false) {
                playingId = null
            }
        }
    }

    val playingTitle = remember(playingId, customTracks) {
        when (playingId) {
            null -> ""
            "classic" -> "Classic Siren"
            "alert" -> "High-Alert Tone"
            else -> customTracks.find { it.id == playingId }?.name ?: "Custom track"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 130.dp)
    ) {
        Text("Audio manager", color = SentinelColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Sounds", color = SentinelColors.Slate, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "The selected sound loops at full volume until the correct PIN is entered.",
            color = SentinelColors.Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Живой пастельный эквалайзер!
        PastelEqualizerVisualizer(
            playing = playingId != null,
            trackTitle = playingTitle,
            audio = audio
        )

        Spacer(modifier = Modifier.height(22.dp))
        Text("BUILT-IN", color = SentinelColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(modifier = Modifier.height(10.dp))

        SentinelSoundRow(
            name = "Classic Siren",
            sub = "Rising & falling wail",
            emoji = "🔔",
            tint = SentinelColors.Coral,
            selected = selectedId == "classic",
            playing = playingId == "classic",
            onSelect = { onSelect("classic") },
            onPlay = { togglePreview("classic") }
        )
        Spacer(modifier = Modifier.height(10.dp))
        SentinelSoundRow(
            name = "High-Alert Tone",
            sub = "Piercing two-tone pulse",
            emoji = "🔔",
            tint = SentinelColors.Coral,
            selected = selectedId == "alert",
            playing = playingId == "alert",
            onSelect = { onSelect("alert") },
            onPlay = { togglePreview("alert") }
        )

        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("MY SOUNDS", color = SentinelColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text("${customTracks.size} / 5", color = SentinelColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(10.dp))

        customTracks.forEach { t ->
            SentinelSoundRow(
                name = t.name,
                sub = t.sizeMb,
                emoji = "🎵",
                tint = SentinelColors.Lavender,
                selected = selectedId == t.id,
                playing = playingId == t.id,
                onSelect = { onSelect(t.id) },
                onPlay = { togglePreview(t.id) },
                onDelete = {
                    if (playingId == t.id) {
                        audio.stop()
                        playingId = null
                    }
                    onRemoveTrack(t)
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Стеклянная кнопка загрузки своего трека
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.78f),
                            SentinelColors.SoftLavenderBg.copy(alpha = 0.65f)
                        )
                    )
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(24.dp))
                .clickable { picker.launch("audio/*") }
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(SentinelColors.Mint),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = SentinelColors.Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("Import from phone", color = SentinelColors.Slate, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "MP3, WAV or OGG · saved privately inside the app",
            color = SentinelColors.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SentinelSoundRow(
    name: String,
    sub: String,
    emoji: String,
    tint: Color,
    selected: Boolean,
    playing: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val inf = rememberInfiniteTransition(label = "miniEq")
    val b1 by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(340), RepeatMode.Reverse), label = "b1")
    val b2 by inf.animateFloat(1f, 0.25f, infiniteRepeatable(tween(280), RepeatMode.Reverse), label = "b2")
    val b3 by inf.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(410), RepeatMode.Reverse), label = "b3")

    SentinelGlassCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = selected || playing
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) SentinelColors.Lavender else Color.Transparent)
                    .border(2.dp, if (selected) SentinelColors.LavenderDeep else Color(0xFFD9DDE5), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Text("✓", color = SentinelColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(tint.copy(alpha = 0.65f))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (playing) {
                    // Мини-индикатор танцующих полосок прямо на иконке трека
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(18.dp)
                    ) {
                        Box(Modifier.width(3.dp).fillMaxHeight(b1).clip(CircleShape).background(SentinelColors.Ink))
                        Box(Modifier.width(3.dp).fillMaxHeight(b2).clip(CircleShape).background(SentinelColors.Ink))
                        Box(Modifier.width(3.dp).fillMaxHeight(b3).clip(CircleShape).background(SentinelColors.Ink))
                    }
                } else {
                    Text(emoji, fontSize = 18.sp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = SentinelColors.Slate, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = SentinelColors.Muted, fontSize = 12.sp, maxLines = 1)
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (playing) Brush.linearGradient(listOf(SentinelColors.Lavender, SentinelColors.Sky))
                        else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f), SentinelColors.Cloud))
                    )
                    .border(1.dp, Color.White, CircleShape)
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (playing) "⏸" else "▶", color = SentinelColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (onDelete != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "🗑",
                    fontSize = 17.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onDelete() }
                        .padding(6.dp)
                )
            }
        }
    }
}

/* ───────────────────────── ЭКРАН 4: НАСТРОЙКИ (Settings) ───────────────────────── */
@Composable
fun SettingsScreen(
    flashOn: Boolean,
    vibrateOn: Boolean,
    onToggleFlash: (Boolean) -> Unit,
    onToggleVibrate: (Boolean) -> Unit,
    onChangePin: () -> Unit,
    onTestAlarm: () -> Unit,
    onResetApp: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = 130.dp)
    ) {
        Text("Preferences", color = SentinelColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Settings", color = SentinelColors.Slate, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)

        Spacer(modifier = Modifier.height(24.dp))
        Text("SECURITY", color = SentinelColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(modifier = Modifier.height(10.dp))

        SentinelGlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChangePin() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SentinelColors.Mint)
                        .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🔑", fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Change PIN", color = SentinelColors.Slate, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Update your 4-digit code", color = SentinelColors.Muted, fontSize = 12.5.sp)
                }
                Text("›", color = Color(0xFFC3C9D2), fontSize = 22.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("ALARM", color = SentinelColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(modifier = Modifier.height(10.dp))

        SentinelGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFFE3A3))
                            .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚡", fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Flashlight strobe", color = SentinelColors.Slate, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Flash the camera light & screen", color = SentinelColors.Muted, fontSize = 12.5.sp)
                    }
                    SentinelSwitch(checked = flashOn, color = Color(0xFFF5CE70), onCheckedChange = onToggleFlash)
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SentinelColors.Lavender)
                            .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📳", fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vibration", color = SentinelColors.Slate, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Pulse the motor while ringing", color = SentinelColors.Muted, fontSize = 12.5.sp)
                    }
                    SentinelSwitch(checked = vibrateOn, color = Color(0xFFBFA9F7), onCheckedChange = onToggleVibrate)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(14.dp, CircleShape, spotColor = SentinelColors.Rose)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(SentinelColors.Coral, SentinelColors.Rose)))
                .border(1.5.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                .clickable { onTestAlarm() },
            contentAlignment = Alignment.Center
        ) {
            Text("⚠  Test alarm", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(28.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (confirmReset) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Erase PIN & sounds?", color = SentinelColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SentinelColors.Coral.copy(alpha = 0.35f))
                            .clickable { onResetApp() }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Erase", color = Color(0xFFD9485F), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.7f))
                            .clickable { confirmReset = false }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Keep", color = SentinelColors.Slate, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                Text(
                    text = "Reset app",
                    color = SentinelColors.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { confirmReset = true }.padding(8.dp)
                )
            }
        }
    }
}

/* ───────────────────────── ЭКРАН 5: ТРЕВОГА (Alarm) ───────────────────────── */
@Composable
fun AlarmTriggeredScreen(reason: String, expectedPinHash: String, strobe: Boolean, onUnlock: () -> Unit) {
    val context = LocalContext.current
    var elapsed by remember { mutableStateOf(0) }
    var wrongCount by remember { mutableStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var strobeFlash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            elapsed++
        }
    }

    LaunchedEffect(strobe) {
        if (strobe) {
            while (true) {
                delay(220L)
                strobeFlash = !strobeFlash
            }
        }
    }

    val mm = String.format("%02d", elapsed / 60)
    val ss = String.format("%02d", elapsed % 60)
    val bgBrush = if (elapsed % 2 == 0) {
        Brush.linearGradient(listOf(SentinelColors.Coral, SentinelColors.Rose))
    } else {
        Brush.linearGradient(listOf(SentinelColors.Rose, SentinelColors.Coral))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
    ) {
        if (strobe && strobeFlash) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.45f)))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .shadow(14.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text("⚠️", fontSize = 36.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Alarm triggered", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("$reason · $mm:$ss", color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Medium)

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = if (wrongCount > 0) "Wrong PIN · $wrongCount attempt${if (wrongCount > 1) "s" else ""}" else "Enter your PIN to silence",
                color = Color.White.copy(alpha = 0.95f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(18.dp))
            PinDotsRow(filled = pin.length, light = true)

            Spacer(modifier = Modifier.weight(1f))
            SentinelKeypad(
                light = true,
                onDigit = { d ->
                    if (pin.length < 4) {
                        vibratePulse(context, 25L)
                        val next = pin + d
                        pin = next
                        if (next.length == 4) {
                            if (hashPin(next) == expectedPinHash) {
                                onUnlock()
                            } else {
                                wrongCount++
                                vibratePulse(context, 200L)
                                pin = ""
                            }
                        }
                    }
                },
                onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
            )
        }
    }
}

/* ───────────────────────── СТЕКЛЯННАЯ ШТОРКА СНЯТИЯ С ОХРАНЫ ───────────────────────── */
@Composable
fun DisarmBottomSheet(expectedPinHash: String, onClose: () -> Unit, onSuccess: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SentinelColors.Ink.copy(alpha = 0.28f))
            .clickable { onClose() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.94f),
                            SentinelColors.SoftLavenderBg.copy(alpha = 0.95f)
                        )
                    )
                )
                .border(1.5.dp, Color.White, RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                .clickable(enabled = false) {}
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.width(40.dp).height(6.dp).clip(CircleShape).background(SentinelColors.Line))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Disarm Sentinel", color = SentinelColors.Slate, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                text = if (wrong) "Wrong PIN — try again" else "Enter your PIN",
                color = if (wrong) SentinelColors.Rose else SentinelColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 22.dp)
            )
            PinDotsRow(filled = pin.length, light = false)
            Spacer(modifier = Modifier.height(24.dp))
            SentinelKeypad(
                light = false,
                onDigit = { d ->
                    if (pin.length < 4) {
                        vibratePulse(context, 20L)
                        val next = pin + d
                        pin = next
                        if (next.length == 4) {
                            if (hashPin(next) == expectedPinHash) {
                                onSuccess()
                            } else {
                                wrong = true
                                vibratePulse(context, 180L)
                                pin = ""
                            }
                        } else {
                            wrong = false
                        }
                    }
                },
                onDelete = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
            )
        }
    }
}

/* ───────────────────────── СТЕКЛОМОРФИЗМ И UI-КОМПОНЕНТЫ ───────────────────────── */
@Composable
fun SentinelGlassCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    val glassBrush = if (highlighted) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.88f),
                SentinelColors.SoftLavenderBg.copy(alpha = 0.78f),
                Color.White.copy(alpha = 0.72f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.78f),
                Color.White.copy(alpha = 0.56f)
            )
        )
    }

    val borderBrush = if (highlighted) {
        Brush.linearGradient(
            listOf(Color.White, SentinelColors.LavenderDeep.copy(alpha = 0.65f))
        )
    } else {
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.45f))
        )
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = if (highlighted) 16.dp else 10.dp,
                shape = RoundedCornerShape(26.dp),
                spotColor = if (highlighted) Color(0x389B85EC) else Color(0x182D3436)
            )
            .clip(RoundedCornerShape(26.dp))
            .background(glassBrush)
            .border(1.5.dp, borderBrush, RoundedCornerShape(26.dp))
    ) {
        content()
    }
}

@Composable
fun SentinelSwitch(checked: Boolean, color: Color, onCheckedChange: (Boolean) -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (checked) color else Color(0xFFE4E7EC),
        animationSpec = tween(250),
        label = "swBg"
    )
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun PinDotsRow(filled: Int, light: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(4) { i ->
            val on = i < filled
            val activeCol = if (light) Color.White else SentinelColors.Slate
            val borderCol = if (on) activeCol else if (light) Color.White.copy(alpha = 0.7f) else SentinelColors.Slate.copy(alpha = 0.3f)
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (on) activeCol else Color.Transparent)
                    .border(2.dp, borderCol, CircleShape)
            )
        }
    }
}

@Composable
fun SentinelKeypad(light: Boolean, onDigit: (String) -> Unit, onDelete: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )
    Column(
        modifier = Modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(modifier = Modifier.size(68.dp))
                    } else if (key == "⌫") {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .clickable { onDelete() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⌫", color = if (light) Color.White else SentinelColors.Muted, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .shadow(if (light) 0.dp else 8.dp, CircleShape, spotColor = Color(0x222D3436))
                                .clip(CircleShape)
                                .background(
                                    if (light) Color.White.copy(alpha = 0.35f)
                                    else Color.White.copy(alpha = 0.85f)
                                )
                                .border(1.5.dp, Color.White, CircleShape)
                                .clickable { onDigit(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                color = if (light) Color.White else SentinelColors.Slate,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SentinelBottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    val items = listOf(
        Triple(Tab.GUARD, "🛡️", "Guard"),
        Triple(Tab.SOUNDS, "🎵", "Sounds"),
        Triple(Tab.SETTINGS, "⚙️", "Settings")
    )
    Row(
        modifier = Modifier
            .shadow(20.dp, CircleShape, spotColor = Color(0x382D3436))
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.88f),
                        SentinelColors.SoftLavenderBg.copy(alpha = 0.82f)
                    )
                )
            )
            .border(1.5.dp, Color.White, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (tab, icon, label) ->
            val active = current == tab
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) Brush.linearGradient(
                            listOf(
                                SentinelColors.Lavender.copy(alpha = 0.85f),
                                SentinelColors.Sky.copy(alpha = 0.75f)
                            )
                        ) else Brush.linearGradient(
                            listOf(Color.Transparent, Color.Transparent)
                        )
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(icon, fontSize = 18.sp)
                AnimatedVisibility(visible = active) {
                    Text(label, color = SentinelColors.Ink, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                }
            }
        }
    }
}