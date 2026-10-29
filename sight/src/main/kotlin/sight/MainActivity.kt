package sight

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update
import java.io.File

private object Ink {
    val bg = Color(0xFF0E1013)
    val card = Color(0xFF1A1E24)
    val text = Color(0xFFF2F4F7)
    val dim = Color(0xFFB8C0CC)
    val accent = Color(0xFFFFD45C) // high contrast yellow on near-black
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.bg)) {
                Surface(Modifier.fillMaxSize(), color = Ink.bg) { Screen() }
            }
        }
    }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val st by SightHub.flow.collectAsState()
    val settings = remember { SightSettings(ctx) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SightHub.command(ctx, SightService.ACTION_START)
    }

    fun start() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_SCAN
            need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) SightHub.command(ctx, SightService.ACTION_START) else permissions.launch(missing.toTypedArray())
    }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(ctx, uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "BrainOS Sight",
            color = Ink.text,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Reads text and describes what's in front of you. Everything stays on this phone.",
            color = Ink.dim,
            fontSize = 16.sp,
        )

        // Spoken by TalkBack whenever it changes.
        Text(
            "${st.status}. Camera ${st.camera}.",
            color = Ink.text,
            fontSize = 20.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        BigButton(if (st.running) "Stop Sight" else "Start Sight", primary = !st.running) {
            if (st.running) SightHub.command(ctx, SightService.ACTION_STOP) else start()
        }

        if (st.running) {
            BigButton("Read text", enabled = !st.busy) { SightHub.command(ctx, SightService.ACTION_READ) }
            BigButton("Describe what's in front of me", enabled = !st.busy) {
                SightHub.command(ctx, SightService.ACTION_DESCRIBE)
            }
            BigButton("Ask a question", enabled = !st.busy) { SightHub.command(ctx, SightService.ACTION_ASK) }
            BigButton("Stop talking", primary = false) { SightHub.command(ctx, SightService.ACTION_HUSH) }
        }

        if (st.lastSpoken.isNotBlank()) {
            Card {
                Text("Last answer", color = Ink.dim, fontSize = 14.sp, modifier = Modifier.semantics { heading() })
                Text(st.lastSpoken, color = Ink.text, fontSize = 18.sp, lineHeight = 25.sp)
            }
        }

        st.photo?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Last photo from the camera",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }

        Card {
            Text("Controls", color = Ink.text, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            Text(
                "Headset button: press once to read, twice to describe. Press while it talks to stop.\n" +
                    "Earbud tap: ask a question. Tap while it talks to stop.\n" +
                    "Earbud next track: describe. Previous track: read.\n" +
                    "Hold the headset button for 2 seconds to turn the camera off.",
                color = Ink.dim,
                fontSize = 16.sp,
                lineHeight = 23.sp,
            )
        }

        Card {
            Text("Scene descriptions", color = Ink.text, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            Text(
                if (st.model.isBlank()) "Scene model not installed" else st.model,
                color = Ink.dim,
                fontSize = 16.sp,
            )
            Text(
                "Uses Gemma 3n, a small open model that runs on this phone. Download the .task file once, then choose it here.",
                color = Ink.dim,
                fontSize = 14.sp,
            )
            if (st.importing >= 0) {
                Text(
                    "Copying the model: ${st.importing}%",
                    color = Ink.text,
                    fontSize = 16.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose model file", fontSize = 16.sp)
                }
            }
        }

        Card {
            Text("Settings", color = Ink.text, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            var rate by remember { mutableStateOf(settings.rate) }
            Text("Speaking speed", color = Ink.dim, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Slow" to 0.8f, "Normal" to 1.0f, "Fast" to 1.3f, "Faster" to 1.6f).forEach { (label, v) ->
                    FilterChip(
                        selected = rate == v,
                        onClick = {
                            rate = v
                            settings.rate = v
                            SightHub.command(ctx, SightService.ACTION_SETTINGS)
                        },
                        label = { Text(label, fontSize = 15.sp) },
                    )
                }
            }
            var flip by remember { mutableStateOf(settings.flip) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Camera is mounted upside down", color = Ink.dim, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = flip,
                    onCheckedChange = {
                        flip = it
                        settings.flip = it
                        SightHub.command(ctx, SightService.ACTION_SETTINGS)
                    },
                    modifier = Modifier.semantics { contentDescription = "Camera is mounted upside down" },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BigButton(label: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(containerColor = Ink.accent, contentColor = Color.Black)
        } else {
            ButtonDefaults.buttonColors(containerColor = Ink.card, contentColor = Ink.text)
        },
        modifier = Modifier.fillMaxWidth().height(76.dp),
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = Ink.card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Copies the chosen Gemma .task file into the app's private storage (several GB, so on a thread). */
private fun importModel(ctx: android.content.Context, uri: Uri) {
    val app = ctx.applicationContext
    Thread {
        val dest = File(app.filesDir, Eyes.MODEL_NAME)
        val tmp = File(app.filesDir, Eyes.MODEL_NAME + ".part")
        try {
            val total = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            SightHub.state.update { it.copy(importing = 0) }
            app.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                SightHub.state.update { it.copy(importing = pct) }
                            }
                        }
                    }
                }
            }
            dest.delete()
            tmp.renameTo(dest)
            SightHub.state.update { it.copy(importing = -1, model = "Scene model installed") }
        } catch (t: Throwable) {
            tmp.delete()
            SightHub.state.update { it.copy(importing = -1, model = "Couldn't copy the model file") }
        }
        SightHub.command(app, SightService.ACTION_MODEL)
    }.start()
}
