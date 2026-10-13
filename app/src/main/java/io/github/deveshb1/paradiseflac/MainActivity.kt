package io.github.deveshb1.paradiseflac

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF0E0F14)
private val DefaultTint = Color(0xFF2A2F55)
private val Good = Color(0xFF5BD68A)
private val Warn = Color(0xFFFFB547)

data class UiState(
    val connected: Boolean = false,
    val wantsAudio: Boolean = false,
    val buffering: Boolean = false,
    val reconnecting: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: Int? = null,
    val path: Bundle = Bundle.EMPTY,
)

class MainActivity : ComponentActivity() {

    private var ui by mutableStateOf(UiState())
    private var artwork by mutableStateOf<ImageBitmap?>(null)
    private var tint by mutableStateOf(DefaultTint)
    private var bitPerfectWanted by mutableStateOf(false)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var artworkUri: Uri? = null
    private lateinit var tipJar: TipJar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-dark UI, so keep the status/navigation bar icons light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        bitPerfectWanted = AudioPath.bitPerfectWanted(this)
        tipJar = TipJar(this).also { it.start() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(
                    ui = ui,
                    artwork = artwork,
                    tint = tint,
                    bitPerfectWanted = bitPerfectWanted,
                    onToggle = ::togglePlayback,
                    onBitPerfect = ::setBitPerfect,
                    tipJar = tipJar,
                    onTip = { tipJar.tip(this, it) },
                    onSupportStation = {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://radioparadise.com/support")))
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    ui = ui.copy(path = Bundle(extras))
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh(c)
            })
            ui = ui.copy(path = Bundle(c.sessionExtras))
            refresh(c)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        tipJar.stop()
        super.onDestroy()
    }

    override fun onStop() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        ui = ui.copy(connected = false)
        super.onStop()
    }

    private fun refresh(c: MediaController) {
        val md = c.mediaMetadata
        val hasTrack = md.artist != null
        ui = ui.copy(
            connected = true,
            wantsAudio = c.playWhenReady,
            buffering = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING,
            reconnecting = c.playWhenReady && c.playerError != null,
            title = if (hasTrack) md.title?.toString().orEmpty() else "",
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            year = md.releaseYear,
        )
        loadArtwork(md.artworkUri)
    }

    private fun togglePlayback() {
        val c = controller ?: return
        if (c.playWhenReady) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    private fun setBitPerfect(on: Boolean) {
        bitPerfectWanted = on
        AudioPath.setBitPerfectWanted(this, on)
        // The mixer preference only applies to a freshly opened audio track.
        controller?.takeIf { it.playWhenReady }?.run {
            stop()
            prepare()
            play()
        }
    }

    private fun loadArtwork(uri: Uri?) {
        if (uri == artworkUri) return
        artworkUri = uri
        lifecycleScope.launch {
            val bitmap = uri?.let { withContext(Dispatchers.IO) { fetchBitmap(it) } }
            if (uri != artworkUri) return@launch
            artwork = bitmap?.asImageBitmap()
            tint = bitmap?.let(::tintOf) ?: DefaultTint
        }
    }

    private fun fetchBitmap(uri: Uri): Bitmap? = runCatching {
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", Station.USER_AGENT)
        try {
            conn.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** The cover's average colour, pulled into a range that stays readable under white text. */
    private fun tintOf(bitmap: Bitmap): Color {
        val pixel = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(pixel, hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(0.85f)
        hsv[2] = hsv[2].coerceIn(0.22f, 0.42f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}

@Composable
private fun PlayerScreen(
    ui: UiState,
    artwork: ImageBitmap?,
    tint: Color,
    bitPerfectWanted: Boolean,
    onToggle: () -> Unit,
    onBitPerfect: (Boolean) -> Unit,
    tipJar: TipJar,
    onTip: (TipJar.Tip) -> Unit,
    onSupportStation: () -> Unit,
) {
    val top by animateColorAsState(tint, tween(900), label = "tint")
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to top, 0.75f to Background)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RADIO PARADISE",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "FLAC",
                    color = Background,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Cover(artwork, Modifier.widthIn(max = 420.dp).fillMaxWidth())
            Spacer(Modifier.height(24.dp))

            Text(
                ui.title.ifEmpty { "Radio Paradise" },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                ui.artist.ifEmpty { "Main Mix" },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val albumLine = listOfNotNull(ui.album.ifEmpty { null }, ui.year?.toString()).joinToString(" · ")
            if (albumLine.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    albumLine,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(28.dp))
            PlayButton(playing = ui.wantsAudio, enabled = ui.connected, onClick = onToggle)
            Spacer(Modifier.height(12.dp))
            StatusLine(ui)

            Spacer(Modifier.height(28.dp))
            SignalPath(ui.path, bitPerfectWanted, onBitPerfect)

            Spacer(Modifier.height(16.dp))
            SupportCard(tipJar, onTip, onSupportStation)

            Spacer(Modifier.height(16.dp))
            Text(
                "Unofficial player · not affiliated with Radio Paradise",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Cover(artwork: ImageBitmap?, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(artwork, contentDescription = "Album cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            SunMark(Modifier.size(120.dp))
        }
    }
}

/** Placeholder that matches the launcher icon. */
@Composable
private fun SunMark(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawArc(
            color = Color(0xFFFFB547),
            startAngle = 180f, sweepAngle = 180f, useCenter = true,
            topLeft = Offset(w * 0.2f, w * 0.2f), size = Size(w * 0.6f, w * 0.6f),
        )
        listOf(Color(0xFFFF7A59), Color(0xFFE85D8C), Color(0xFF8E7CFF)).forEachIndexed { i, c ->
            val y = w * (0.6f + i * 0.13f)
            val inset = w * (0.12f + i * 0.08f)
            val path = Path().apply {
                moveTo(inset, y)
                var x = inset
                val step = w * 0.1f
                var up = true
                while (x < w - inset - 1f) {
                    val nx = (x + step).coerceAtMost(w - inset)
                    quadraticTo((x + nx) / 2, if (up) y - w * 0.05f else y + w * 0.05f, nx, y)
                    x = nx
                    up = !up
                }
            }
            drawPath(path, c, style = Stroke(width = w * 0.05f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (playing) "Stop" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val s = size.width
            if (playing) {
                drawRoundRect(Background, topLeft = Offset(s * 0.1f, s * 0.1f), size = Size(s * 0.8f, s * 0.8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f))
            } else {
                drawPath(
                    Path().apply {
                        moveTo(s * 0.22f, s * 0.08f)
                        lineTo(s * 0.95f, s * 0.5f)
                        lineTo(s * 0.22f, s * 0.92f)
                        close()
                    },
                    Background,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(ui: UiState) {
    val (text, dot) = when {
        !ui.connected -> "Connecting…" to null
        ui.reconnecting -> "Reconnecting…" to Warn
        ui.buffering -> "Buffering…" to Warn
        ui.wantsAudio -> "Live" to Color(0xFFFF5A5A)
        else -> "Tap to listen" to null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}

@Composable
private fun SignalPath(path: Bundle, bitPerfectWanted: Boolean, onBitPerfect: (Boolean) -> Unit) {
    val rate = path.getInt(PathInfo.SAMPLE_RATE)
    val bits = path.getInt(PathInfo.BITS)
    val outRate = path.getInt(PathInfo.OUT_SAMPLE_RATE)
    val kind = runCatching { AudioPath.Kind.valueOf(path.getString(PathInfo.DEVICE_KIND).orEmpty()) }
        .getOrDefault(AudioPath.Kind.OTHER)
    val deviceName = path.getString(PathInfo.DEVICE_NAME).orEmpty()
    val bitPerfect = path.getBoolean(PathInfo.BIT_PERFECT)

    val source = buildList {
        add(path.getString(PathInfo.CODEC) ?: "FLAC")
        if (rate > 0) add("%.1f kHz".format(rate / 1000f))
        if (bits > 0) add("$bits-bit")
    }.joinToString(" · ")

    val kindLabel = when (kind) {
        AudioPath.Kind.BLUETOOTH -> "Bluetooth"
        AudioPath.Kind.USB -> "USB"
        AudioPath.Kind.WIRED -> "Wired"
        AudioPath.Kind.SPEAKER -> "Speaker"
        AudioPath.Kind.OTHER -> "Output"
    }
    val (verdictColor, verdict) = when {
        kind == AudioPath.Kind.USB && bitPerfect -> Good to "Bit-perfect to your DAC — nothing in between."
        kind == AudioPath.Kind.USB -> Warn to "Android's mixer may resample before the DAC. Turn on bit-perfect below."
        kind == AudioPath.Kind.BLUETOOTH -> Warn to
            "Re-encoded by the Bluetooth codec, so lossy — unless your phone and earbuds negotiate aptX Lossless (Snapdragon Sound)."
        kind == AudioPath.Kind.WIRED -> Warn to "Lossless into the phone's DAC; Android's mixer may resample to 48 kHz."
        kind == AudioPath.Kind.SPEAKER -> Warn to "Phone speaker — the stream is lossless, the speaker is the limit."
        else -> Warn to "Lossless stream."
    }

    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("SIGNAL PATH", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, letterSpacing = 2.sp)
        PathRow("Stream", source, Good)
        PathRow(
            "Decoder",
            if (outRate > 0) "PCM · %.1f kHz".format(outRate / 1000f) else "PCM",
            if (outRate == 0 || outRate == rate) Good else Warn,
        )
        PathRow(kindLabel, deviceName.ifEmpty { "—" }, verdictColor)
        Text(verdict, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, lineHeight = 18.sp)

        if (AudioPath.bitPerfectSupported) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bit-perfect USB", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Sends 44.1 kHz/16-bit straight to a USB DAC. Other app sounds are silenced while it plays.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Switch(
                    checked = bitPerfectWanted,
                    onCheckedChange = onBitPerfect,
                    colors = SwitchDefaults.colors(checkedTrackColor = Good, checkedThumbColor = Background),
                )
            }
        }
    }
}

@Composable
private fun PathRow(label: String, value: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(10.dp))
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, modifier = Modifier.widthIn(min = 76.dp))
        Text(value, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SupportCard(tipJar: TipJar, onTip: (TipJar.Tip) -> Unit, onSupportStation: () -> Unit) {
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SUPPORT", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, letterSpacing = 2.sp)

        if (tipJar.status == TipJar.Status.READY) {
            Text(
                "Enjoying Paradise FLAC? Leave a tip to keep it going — it unlocks nothing, it's just a thank-you.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tipJar.tips.forEachIndexed { i, tip ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = if (i == 1) 0.16f else 0.1f))
                            .clickable { onTip(tip) }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(listOf("☕", "🍕", "🎧").getOrElse(i) { "💛" }, fontSize = 20.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(tip.price, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            tipJar.message?.let {
                Text(it, color = Good, fontSize = 13.sp)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onSupportStation)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Support Radio Paradise", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "The station is ad-free and listener-supported.",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
            Text("↗", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
        }
    }
}
