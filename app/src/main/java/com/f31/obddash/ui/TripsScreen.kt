package com.f31.obddash.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import com.f31.obddash.data.DashViewModel
import com.f31.obddash.data.TripSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripsScreen(vm: DashViewModel) {
    val trips by vm.trips.collectAsState()
    val recording by vm.recording.collectAsState()
    val ctx = LocalContext.current
    var toDelete by remember { mutableStateOf<TripSummary?>(null) }

    fun share(t: TripSummary) {
        val file = vm.tripCsv(t.name)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "F31 Dash " + t.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share trip").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(u(6))) {
        // ---- totals
        Panel(Modifier.weight(0.3f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(u(12)), verticalArrangement = Arrangement.spacedBy(u(10))) {
                Text("TRIPS", color = C.Dim, fontSize = t(10))
                Text(
                    if (recording) "● Recording this drive" else "Recording starts by itself when the engine runs.",
                    color = if (recording) C.Red else C.Text, fontSize = t(10)
                )
                val km = trips.sumOf { it.distanceKm }
                val fuel = trips.sumOf { it.fuelL }
                Stat("Total distance", String.format(Locale.US, "%.0f km", km))
                Stat("Total fuel", String.format(Locale.US, "%.1f L", fuel))
                Stat("Average", if (km > 1) String.format(Locale.US, "%.1f L/100 km", fuel / km * 100) else "--")
                Spacer(Modifier.weight(1f))
                Text("Fuel is calculated from air mass and AFR – compare with the pump to judge accuracy.",
                    color = C.Dim, fontSize = t(8))
            }
        }
        // ---- list
        Panel(Modifier.weight(0.7f).fillMaxHeight()) {
            if (trips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No trips yet. Drive with the app open and connected.", color = C.Dim, fontSize = t(12))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(u(8)), verticalArrangement = Arrangement.spacedBy(u(6))) {
                    items(trips, key = { it.name }) { tr -> TripRow(tr, onShare = { share(tr) }, onDelete = { toDelete = tr }) }
                }
            }
        }
    }

    toDelete?.let { tr ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete trip?") },
            text = { Text(dateText(tr.startMs)) },
            confirmButton = { TextButton(onClick = { vm.deleteTrip(tr.name); toDelete = null }) { Text("DELETE", color = C.Red) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = C.Dim, fontSize = t(9))
        Text(value, color = C.Text, fontSize = t(18), fontWeight = FontWeight.Bold)
    }
}

private fun dateText(ms: Long): String = SimpleDateFormat("EEE dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun TripRow(tr: TripSummary, onShare: () -> Unit, onDelete: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = u(10), vertical = u(8)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dateText(tr.startMs), color = C.Text, fontSize = t(11), fontWeight = FontWeight.Bold)
                Text(
                    String.format(
                        Locale.US, "%d:%02d  ·  %.1f km  ·  %s  ·  max %.0f rpm  ·  max %.2f bar  ·  %.0f km/h",
                        tr.durationS / 60, tr.durationS % 60, tr.distanceKm,
                        tr.avgL100?.let { String.format(Locale.US, "%.1f L/100", it) } ?: String.format(Locale.US, "%.2f L", tr.fuelL),
                        tr.maxRpm, tr.maxBoost, tr.maxSpeed
                    ),
                    color = C.Dim, fontSize = t(9), maxLines = 1
                )
            }
            Icon(Icons.Outlined.Share, "Share CSV", tint = C.Text,
                modifier = Modifier.size(u(22)).clickable(onClick = onShare))
            Spacer(Modifier.width(u(14)))
            Icon(Icons.Outlined.Delete, "Delete", tint = C.Dim,
                modifier = Modifier.size(u(22)).clickable(onClick = onDelete))
        }
    }
}
