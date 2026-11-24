package nl.meereboer.ebikelive.wear

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import kotlinx.coroutines.flow.MutableStateFlow
import nl.meereboer.ebikelive.core.AssistMode
import nl.meereboer.ebikelive.core.LdiConnection
import nl.meereboer.ebikelive.core.LiveData
import nl.meereboer.ebikelive.core.LiveService

class MainActivity : ComponentActivity() {

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val bt = listOf(
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
            if (bt.all { result[it] == true || has(it) }) LiveService.start(this)
        }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun openSpotify() {
        val launch = packageManager.getLaunchIntentForPackage(SPOTIFY)
            ?: android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("market://details?id=$SPOTIFY"),
            )
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the display on while the app is in front: you want to glance at
        // your speed without tapping the watch.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Screen(
                    onStart = { permissionRequest.launch(LiveService.PERMISSIONS + HEART_PERMISSIONS) },
                    onStop = { LiveService.stop(this) },
                    onMusic = { openSpotify() },
                )
            }
        }
    }
}

private val emptyStatus = MutableStateFlow(LdiConnection.Status.STOPPED)
private val emptyData = MutableStateFlow(LiveData())
private val emptyMsg = MutableStateFlow("")

@Composable
private fun Screen(onStart: () -> Unit, onStop: () -> Unit, onMusic: () -> Unit) {
    val conn by LiveService.connection.collectAsState()
    val status by (conn?.status ?: emptyStatus).collectAsState()
    val d by (conn?.data ?: emptyData).collectAsState()
    val msg by (conn?.message ?: emptyMsg).collectAsState()

    if (conn == null) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("eBike live", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStart) { Text("Start") }
        }
        return
    }

    val heartRate = rememberHeartRate()
    val mode = d.assistMode ?: AssistMode.UNKNOWN
    val live = status == LdiConnection.Status.LIVE
    val soc = d.batterySoc
    // Split ring. 6 o'clock = 0, 12 o'clock = max.
    // Right half: rider power 0-200 W (fills counter-clockwise via 3 o'clock).
    // Left half: cadence 0-100 rpm (fills clockwise via 9 o'clock).
    val powerFrac = ((d.riderPower ?: 0) / MAX_POWER).coerceIn(0f, 1f)
    val cadenceFrac = ((d.cadence ?: 0).coerceAtLeast(0) / MAX_CADENCE).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize()) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
        val w = 5.dp.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Butt)
        // Outer ring: support level colour (grey until the bike reports it).
        val outerInset = w / 2
        drawCircle(
            Color(mode.color), radius = size.minDimension / 2 - outerInset,
            style = stroke,
        )
        // Inner split ring: power right, cadence left.
        val inset = w + 3.dp.toPx() + w / 2
        val tl = Offset(inset, inset)
        val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
        val gap = 2f
        val span = 180f - 2 * gap
        drawArc(TRACK, 90f - gap, -span, false, tl, sz, style = stroke)
        drawArc(TRACK, 90f + gap, span, false, tl, sz, style = stroke)
        val powerEnd = 90f - gap - span * powerFrac
        val cadenceEnd = 90f + gap + span * cadenceFrac
        if (powerFrac > 0f) drawArc(POWER, 90f - gap, -span * powerFrac, false, tl, sz, style = stroke)
        if (cadenceFrac > 0f) drawArc(CADENCE, 90f + gap, span * cadenceFrac, false, tl, sz, style = stroke)
        // Number sits just inside the ring, where the coloured line ends.
        val r = size.minDimension / 2 - inset - 14.dp.toPx()
        fun label(value: Int?, angle: Float, color: Color) {
            if (value == null || value <= 0) return
            val t = measurer.measure(
                value.toString(),
                TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            val rad = Math.toRadians(angle.toDouble())
            val x = center.x + r * cos(rad).toFloat() - t.size.width / 2f
            val y = center.y + r * sin(rad).toFloat() - t.size.height / 2f
            drawText(t, topLeft = Offset(x, y))
        }
        label(d.riderPower, powerEnd, POWER)
        label(d.cadence?.coerceAtLeast(0), cadenceEnd, CADENCE)
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            d.speedKmh?.let { "%.0f".format(it) } ?: "--",
            fontSize = 64.sp, fontWeight = FontWeight.Bold,
            color = if (live) Color.White else Color.Gray,
        )
        Text("km/u", fontSize = 14.sp, color = Color.LightGray)
        Spacer(Modifier.height(6.dp))
        Text(
            "${soc ?: "--"}%",
            fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            color = when {
                soc == null -> Color.Gray
                soc <= 15 -> Color(0xFFFF5252)
                soc <= 30 -> Color(0xFFFFB300)
                else -> Color(0xFF69F0AE)
            },
        )
        Text(
            "${heartRate ?: "--"}",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = HEART,
        )
        if (!live) {
            Spacer(Modifier.height(4.dp))
            Text(msg, fontSize = 11.sp, textAlign = TextAlign.Center, color = Color.LightGray)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(26.dp).background(MUSIC, CircleShape).clickable { onMusic() },
                contentAlignment = Alignment.Center,
            ) { Text("\u266A", fontSize = 15.sp, color = Color.Black) }
            Text(
                "stop", fontSize = 12.sp, color = Color.Gray,
                modifier = Modifier.clickable { onStop() }.padding(6.dp),
            )
        }
    }
}
}

private const val MAX_POWER = 200f
private const val MAX_CADENCE = 100f
private val TRACK = Color(0xFF2C2C2A)
private val POWER = Color(0xFFFF8A3D)
private val CADENCE = Color(0xFF29B6F6)
private val HEART = Color(0xFFFF5252)
private val MUSIC = Color(0xFF1ED760)
private const val SPOTIFY = "com.spotify.music"
