package nl.meereboer.ebikelive.phone

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Screen(
                        onStart = { permissionRequest.launch(LiveService.PERMISSIONS) },
                        onStop = { LiveService.stop(this) },
                    )
                }
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
    val running = conn != null

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        val mode = d.assistMode ?: AssistMode.UNKNOWN
        Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(
                    color = Color(mode.color),
                    startAngle = 135f, sweepAngle = 270f, useCenter = false,
                    style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    d.speedKmh?.let { "%.1f".format(it) } ?: "--",
                    fontSize = 104.sp, fontWeight = FontWeight.Bold,
                )
                Text("km/u", fontSize = 20.sp)
            }
            Text(
                mode.label, fontSize = 16.sp, color = Color(mode.color),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
        Text("Accu ${d.batterySoc ?: "--"}%", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Small("Cadans", d.cadence?.let { "$it rpm" })
            Small("Vermogen", d.riderPower?.let { "$it W" })
        }
        Small("Kilometerstand", d.odometerKm?.let { "%.1f km".format(it) })
        Spacer(Modifier.height(8.dp))
        Text(
            when (status) {
                LdiConnection.Status.STOPPED -> "Gestopt"
                LdiConnection.Status.WAITING_FOR_BIKE -> "Wacht op fiets"
                LdiConnection.Status.CONNECTING -> "Verbinden"
                LdiConnection.Status.LIVE -> "Live"
                LdiConnection.Status.ERROR -> "Fout"
            },
            fontWeight = FontWeight.SemiBold,
        )
        if (msg.isNotEmpty()) Text(msg)
        if (d.unknownFields.isNotEmpty()) Text(
            "Nieuwe velden van de fiets: " +
                d.unknownFields.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" },
            fontSize = 12.sp,
        )
        Button(onClick = if (running) onStop else onStart) {
            Text(if (running) "Stop" else "Start")
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Eerste keer koppelen: zet de fiets aan en druk hier op Start. " +
                "Open dan Bosch Flow, kies je fiets, tandwiel, Componenten, " +
                "Nieuw apparaat toevoegen. Kies dit toestel en bevestig op het display van de fiets.",
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Small(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 13.sp)
        Text(value ?: "--", fontSize = 20.sp)
    }
}
