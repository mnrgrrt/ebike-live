package nl.meereboer.ebikelive.wear

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the display on while the app is in front: you want to glance at
        // your speed without tapping the watch.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Screen(
                    onStart = { permissionRequest.launch(LiveService.PERMISSIONS) },
                    onStop = { LiveService.stop(this) },
                )
            }
        }
    }
}

private val emptyStatus = MutableStateFlow(LdiConnection.Status.STOPPED)
private val emptyData = MutableStateFlow(LiveData())
private val emptyMsg = MutableStateFlow("")

@Composable
private fun Screen(onStart: () -> Unit, onStop: () -> Unit) {
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

    val live = status == LdiConnection.Status.LIVE
    val soc = d.batterySoc
    val mode = d.assistMode ?: AssistMode.UNKNOWN
    Box(Modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
        drawArc(
            color = Color(mode.color),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            style = Stroke(width = 8.dp.toPx()),
        )
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
        if (!live) {
            Spacer(Modifier.height(4.dp))
            Text(msg, fontSize = 11.sp, textAlign = TextAlign.Center, color = Color.LightGray)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "stop", fontSize = 12.sp, color = Color.Gray,
            modifier = Modifier.clickable { onStop() }.padding(6.dp),
        )
    }
}
}
