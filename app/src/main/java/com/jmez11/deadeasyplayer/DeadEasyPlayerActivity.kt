package com.jmez11.deadeasyplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

enum class ProjectionMode(val constantValue: Int) {
    MONO(0),
    SBS(1),
    OU(2)
}

private val SBS_REGEX = Regex("""(?:^|[._\- ])(?:h?sbs|hs?bs)(?:[._\- ]|$)""", RegexOption.IGNORE_CASE)
private val OU_REGEX = Regex("""(?:^|[._\- ])(?:h?ou|ho?u)(?:[._\- ]|$)""", RegexOption.IGNORE_CASE)

fun detectProjectionMode(filename: String): ProjectionMode {
    return when {
        SBS_REGEX.containsMatchIn(filename) -> ProjectionMode.SBS
        OU_REGEX.containsMatchIn(filename) -> ProjectionMode.OU
        else -> ProjectionMode.MONO
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}

class DeadEasyPlayerActivity : ComponentActivity(), IVLCVout.Callback, IVLCVout.OnNewVideoLayoutListener, SurfaceHolder.Callback {

    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private var surfaceView: SurfaceView? = null
    private var subtitleSurfaceView: SurfaceView? = null

    private val isPlaying = mutableStateOf(false)
    private val currentTimeString = mutableStateOf("0:00")
    private val durationString = mutableStateOf("0:00")
    private val progress = mutableFloatStateOf(0f)
    private val projectionMode = mutableStateOf(ProjectionMode.MONO)
    private val videoAspectRatio = mutableFloatStateOf(16f / 9f)

    private var videoTitle: String = "Video"
    private var currentUri: Uri? = null

    // For auto-fade
    private val lastInteractionTime = mutableLongStateOf(System.currentTimeMillis())

    // Volume
    private val volumeLevel = mutableIntStateOf(100)
    private val showVolumeIndicator = mutableStateOf(false)
    private var volumeHideJob: Job? = null

    // Buffering
    private val isBuffering = mutableStateOf(false)

    // Tracks
    private val hasMultipleAudio = mutableStateOf(false)
    private val hasSubs = mutableStateOf(false)

    // Error
    private val errorMessage = mutableStateOf<String?>(null)
    private val stereoError = mutableStateOf<String?>(null)

    // Launch mode: true = launched from app launcher (show file browser), false = launched from intent with video
    private val launchedStandalone = mutableStateOf(false)
    private val showPlayer = mutableStateOf(false)

    // About dialog
    private val showAboutDialog = mutableStateOf(false)

    // File picker
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Take persistable permission so VLC can read the file
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            loadVideo(uri, uri.lastPathSegment ?: uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val args = ArrayList<String>()
        args.add("-vvv")
        args.add("--vout=android_display")
        libVlc = LibVLC(this, args)
        player = MediaPlayer(libVlc)
        setupPlayerEvents()

        val intentUri: Uri? = intent.data
        if (intentUri != null) {
            // Launched from external intent
            launchedStandalone.value = false
            val startPos = intent.getIntExtra("position", -1)
            videoTitle = intent.getStringExtra("title") ?: "Video"
            val filename = intent.getStringExtra("filename") ?: intentUri.toString()
            loadVideo(intentUri, filename, startPos)
        } else {
            // Launched from app launcher
            launchedStandalone.value = true
        }

        setContent {
            if (showPlayer.value) {
                VRPlayerUI()
            } else {
                FileBrowserUI()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val intentUri: Uri? = intent.data
        if (intentUri != null) {
            launchedStandalone.value = false
            val startPos = intent.getIntExtra("position", -1)
            videoTitle = intent.getStringExtra("title") ?: ""
            val filename = intent.getStringExtra("filename") ?: (intentUri.lastPathSegment ?: intentUri.toString())
            loadVideo(intentUri, filename, startPos)
        }
    }

    private fun setupPlayerEvents() {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    isPlaying.value = true
                    isBuffering.value = false
                    hasMultipleAudio.value = (player.audioTracks?.filter { it.id != -1 }?.size ?: 0) > 1
                    hasSubs.value = (player.spuTracks?.filter { it.id != -1 }?.size ?: 0) > 0
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    isPlaying.value = false
                }
                MediaPlayer.Event.Buffering -> {
                    isBuffering.value = event.buffering < 100f
                }
                MediaPlayer.Event.TimeChanged -> {
                    currentTimeString.value = formatDuration(player.time)
                    if (player.length > 0) {
                        progress.floatValue = player.time.toFloat() / player.length.toFloat()
                    }
                }
                MediaPlayer.Event.LengthChanged -> {
                    durationString.value = formatDuration(player.length)
                }
                MediaPlayer.Event.EncounteredError -> {
                    errorMessage.value = "Playback error: Unable to play this media file."
                    isPlaying.value = false
                    isBuffering.value = false
                }
            }
        }
    }

    private fun loadVideo(uri: Uri, filename: String, startPos: Int = -1) {
        currentUri = uri
        if (videoTitle == "Video" || videoTitle.isBlank()) {
            videoTitle = filename.substringAfterLast("/").substringBeforeLast(".")
        }

        Log.i("DeadEasyPlayer", "Raw filename resolved for 3D detect: $filename")

        val detectedMode = detectProjectionMode(filename)
        projectionMode.value = detectedMode

        val media = try {
            if (uri.scheme == "content") {
                val fd = contentResolver.openFileDescriptor(uri, "r")
                if (fd != null) {
                    Media(libVlc, fd.fileDescriptor)
                } else {
                    Media(libVlc, uri)
                }
            } else {
                Media(libVlc, uri)
            }
        } catch (e: Exception) {
            Log.e("DeadEasyPlayer", "Failed to open FD for $uri", e)
            Media(libVlc, uri)
        }
        
        if (startPos > 0) {
            media.addOption(":start-time=${startPos / 1000f}")
        }
        player.media = media
        currentTimeString.value = "0:00"
        durationString.value = "0:00"
        progress.floatValue = 0f
        isBuffering.value = true
        showPlayer.value = true

        if (surfaceView != null) {
            updateStereoSurface(detectedMode)
            player.play()
        }
    }

    // --- Key Events (Joystick volume) ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
                    adjustVolume(10)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    adjustVolume(-10)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun adjustVolume(delta: Int) {
        if (::player.isInitialized) {
            val current = player.volume
            val newVol = (current + delta).coerceIn(0, 200)
            player.volume = newVol
            volumeLevel.intValue = (newVol / 2).coerceIn(0, 100)
            showVolumeIndicator.value = true

            volumeHideJob?.cancel()
            volumeHideJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                delay(1500L)
                showVolumeIndicator.value = false
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
            if (vScroll > 0) {
                adjustVolume(10)
                return true
            } else if (vScroll < 0) {
                adjustVolume(-10)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun setProjection(mode: ProjectionMode) {
        if (projectionMode.value == mode) return
        projectionMode.value = mode
        updateStereoSurface(mode)
    }

    private fun updateStereoSurface(mode: ProjectionMode) {
        val sv = surfaceView ?: run {
            Log.e("DeadEasyPlayer", "SurfaceView is null, cannot set stereo!")
            return
        }
        try {
            val clazz = Class.forName("metavr.view.SurfaceViewExt")
            val method = clazz.getMethod("setStereoComposition", SurfaceView::class.java, Int::class.javaPrimitiveType)

            val constantValue = if (mode == ProjectionMode.SBS) 1 else if (mode == ProjectionMode.OU) 2 else 0

            method.invoke(null, sv, constantValue)
            Log.i("DeadEasyPlayer", "Successfully set stereo mode to $mode with value $constantValue")

            // Also apply stereo mode to subtitle surface
            subtitleSurfaceView?.let { subSv ->
                method.invoke(null, subSv, constantValue)
                subSv.post {
                    subSv.requestLayout()
                    subSv.invalidate()
                }
            }

            sv.post {
                sv.requestLayout()
                sv.invalidate()
            }
            stereoError.value = null
        } catch (e: Exception) {
            Log.e("DeadEasyPlayer", "Failed to set stereo mode", e)
            if (mode != ProjectionMode.MONO) {
                stereoError.value = "Stereo 3D mode unavailable; falling back to 2D mono."
            } else {
                stereoError.value = null
            }
        }
    }

    private fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        lastInteractionTime.longValue = System.currentTimeMillis()
    }

    private fun seekToFraction(fraction: Float) {
        if (player.length > 0) {
            player.time = (fraction * player.length).toLong()
        }
        lastInteractionTime.longValue = System.currentTimeMillis()
    }

    // ==================== FILE BROWSER UI ====================
    @Composable
    fun FileBrowserUI() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(48.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .size(256.dp)
                        .padding(bottom = 16.dp)
                )
                Text(
                    "DeadEasy Player",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "Select a video file to play in VR",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 48.dp)
                )

                Button(
                    onClick = {
                        filePickerLauncher.launch(arrayOf("video/*"))
                    },
                    modifier = Modifier
                        .width(300.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text("Browse Files", color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // About button
                IconButton(onClick = { showAboutDialog.value = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "About",
                        tint = Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // About dialog
            if (showAboutDialog.value) {
                AboutDialog { showAboutDialog.value = false }
            }
        }
    }

    // ==================== PLAYER UI ====================
    @Composable
    fun VRPlayerUI() {
        var showControls by remember { mutableStateOf(true) }
        var showDialog by remember { mutableStateOf(false) }
        var showAudioDialog by remember { mutableStateOf(false) }
        var showSubDialog by remember { mutableStateOf(false) }

        LaunchedEffect(lastInteractionTime.longValue, showDialog, showAudioDialog, showSubDialog) {
            if (showDialog || showAudioDialog || showSubDialog) {
                showControls = true
            } else {
                showControls = true
                delay(5000L)
                showControls = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            lastInteractionTime.longValue = System.currentTimeMillis()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(videoAspectRatio.floatValue, matchHeightConstraintsFirst = false)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            holder.addCallback(this@DeadEasyPlayerActivity)
                            surfaceView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                            setZOrderMediaOverlay(true)
                            subtitleSurfaceView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Tap to toggle controls (disabled when dialog open)
            if (!showDialog && !showAudioDialog && !showSubDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showControls = !showControls
                            lastInteractionTime.longValue = System.currentTimeMillis()
                        }
                )
            }

            // Buffering indicator
            if (isBuffering.value) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(64.dp)
                )
            }

            // Stereo fallback error banner
            stereoError.value?.let { errorText ->
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 80.dp, start = 24.dp, end = 24.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCCB00020))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = errorText,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(
                            onClick = { stereoError.value = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Dismiss", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Top bar (Matches Plezy style)
            AnimatedVisibility(
                visible = showControls || showVolumeIndicator.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    // Left side: Title and Back button
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showControls,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (launchedStandalone.value) {
                                    player.stop()
                                    showPlayer.value = false
                                } else {
                                    finish()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = videoTitle,
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = durationString.value,
                                    color = Color.LightGray,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    // Center: Volume Indicator
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showVolumeIndicator.value,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🔈", color = Color.White, fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Volume ${volumeLevel.intValue}%",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Right side: Clock
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showControls,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text(
                            text = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date()),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Bottom controls bar
            AnimatedVisibility(
                visible = showControls && !showDialog && !showAudioDialog && !showSubDialog,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Top row: Times and Slider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = currentTimeString.value,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(16.dp))
                            Slider(
                                value = progress.floatValue,
                                onValueChange = { seekToFraction(it) },
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )
                            Spacer(Modifier.width(16.dp))
                            val remaining = player.length - player.time
                            val remainingText = if (remaining > 0) "-" + formatDuration(remaining) else "0:00"
                            Text(
                                text = remainingText,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Bottom row: Buttons
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left side: Playback controls
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = { player.time = 0 }) {
                                    Text("|<", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                IconButton(onClick = { player.time = (player.time - 10000).coerceAtLeast(0) }) {
                                    Text("<<", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                IconButton(onClick = { togglePlayPause() }) {
                                    Icon(
                                        imageVector = if (isPlaying.value) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                IconButton(onClick = { player.time = (player.time + 30000).coerceAtMost(player.length) }) {
                                    Text(">>", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                IconButton(onClick = { player.time = player.length }) {
                                    Text(">|", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                                
                                Spacer(Modifier.width(16.dp))
                                
                                val remainingMs = player.length - player.time
                                val endsAtTime = System.currentTimeMillis() + if (remainingMs > 0) remainingMs else 0
                                val endsAtText = "Ends at " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(endsAtTime))
                                Text(
                                    text = endsAtText,
                                    color = Color.LightGray,
                                    fontSize = 14.sp
                                )
                            }

                            // Right side: Settings
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                TextButton(onClick = { showDialog = true }) {
                                    Text("3D", color = Color.White, fontSize = 16.sp)
                                }
                                TextButton(onClick = { showAudioDialog = true }, enabled = hasMultipleAudio.value) {
                                    Text("Audio", color = if (hasMultipleAudio.value) Color.White else Color.Gray, fontSize = 16.sp)
                                }
                                TextButton(onClick = { showSubDialog = true }, enabled = hasSubs.value) {
                                    Text("Subs", color = if (hasSubs.value) Color.White else Color.Gray, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }



            // 3D Mode dialog
            if (showDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showDialog = false },
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .width(350.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* absorb */ },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Display mode", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            Text("Find the right format when viewing 3D and immersive content.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 24.dp))
                            ModeRadioButton("2D Non VR", ProjectionMode.MONO)
                            ModeRadioButton("3D Left/Right", ProjectionMode.SBS)
                            ModeRadioButton("3D Top/Bottom", ProjectionMode.OU)
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { showDialog = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                            ) {
                                Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Audio dialog
            if (showAudioDialog) {
                TrackSelectionDialog("Audio Tracks", player.audioTracks?.filter { it.id != -1 }?.toTypedArray(), player.audioTrack, { id ->
                    player.setAudioTrack(id)
                    showAudioDialog = false
                }) { showAudioDialog = false }
            }

            // Subtitle dialog
            if (showSubDialog) {
                TrackSelectionDialog("Subtitles", player.spuTracks, player.spuTrack, { id ->
                    player.setSpuTrack(id)
                    showSubDialog = false
                }) { showSubDialog = false }
            }

            // Error dialog
            errorMessage.value?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.width(400.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("Playback Error", color = Color(0xFFFF6B6B), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                            Text(msg, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(bottom = 24.dp))
                            Button(
                                onClick = {
                                    errorMessage.value = null
                                    if (launchedStandalone.value) {
                                        showPlayer.value = false
                                    } else {
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                            ) {
                                Text("OK", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // About dialog (also accessible from player)
            if (showAboutDialog.value) {
                AboutDialog { showAboutDialog.value = false }
            }
        }
    }

    // ==================== DIALOGS ====================
    @Composable
    fun AboutDialog(onDismiss: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x88000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .width(500.dp)
                    .heightIn(max = 500.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* absorb */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("DeadEasy Player", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    Text("Version 1.0", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp))

                    HorizontalDivider(color = Color(0xFF444444), modifier = Modifier.padding(bottom = 16.dp))

                    Text("Open Source Licenses", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        LicenseItem("libVLC (VLC for Android)", "LGPLv2.1+", "VideoLAN", "https://www.videolan.org/vlc/libvlc.html")
                        LicenseItem("Meta Spatial SDK", "Meta Platform Technologies SDK License", "Meta Platforms, Inc.", "https://developer.meta.com")
                        LicenseItem("Jetpack Compose", "Apache License 2.0", "Google / AOSP", "https://developer.android.com/jetpack/compose")
                        LicenseItem("AndroidX Libraries", "Apache License 2.0", "Google / AOSP", "https://developer.android.com/jetpack/androidx")
                        LicenseItem("Material Design 3", "Apache License 2.0", "Google", "https://m3.material.io")
                        LicenseItem("Kotlin", "Apache License 2.0", "JetBrains", "https://kotlinlang.org")
                        LicenseItem("Kotlin Coroutines", "Apache License 2.0", "JetBrains", "https://github.com/Kotlin/kotlinx.coroutines")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) {
                        Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun LicenseItem(name: String, license: String, author: String, url: String) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("$license — $author", color = Color.Gray, fontSize = 12.sp)
            Text(url, color = Color(0xFF6EAAFF), fontSize = 11.sp)
        }
    }

    @Composable
    fun TrackSelectionDialog(
        title: String,
        tracks: Array<MediaPlayer.TrackDescription>?,
        currentTrackId: Int,
        onTrackSelected: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x88000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .width(400.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* absorb */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A))
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        val trackList = tracks ?: emptyArray()
                        if (trackList.isEmpty()) {
                            item { Text("No tracks available", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
                        }
                        items(trackList.size) { index ->
                            val track = trackList[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTrackSelected(track.id) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(track.name ?: "Track ${track.id}", color = Color.White, fontSize = 16.sp)
                                RadioButton(
                                    selected = track.id == currentTrackId,
                                    onClick = { onTrackSelected(track.id) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Color.Gray)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                    ) {
                        Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    fun ModeRadioButton(label: String, mode: ProjectionMode) {
        val selected = projectionMode.value == mode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { setProjection(mode) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White, fontSize = 16.sp)
            RadioButton(
                selected = selected,
                onClick = { setProjection(mode) },
                colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Color.Gray)
            )
        }
    }

    override fun onDestroy() {
        volumeHideJob?.cancel()
        if (::player.isInitialized) {
            player.stop()
            player.vlcVout.detachViews()
            player.release()
            libVlc.release()
        }
        super.onDestroy()
    }

    // --- SurfaceHolder.Callback ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        player.vlcVout.setVideoSurface(holder.surface, holder)
        subtitleSurfaceView?.let { subSv ->
            player.vlcVout.setSubtitlesSurface(subSv.holder.surface, subSv.holder)
        }
        player.vlcVout.attachViews(this)

        updateStereoSurface(projectionMode.value)

        if (!player.isPlaying) {
            player.play()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player.vlcVout.detachViews()
    }

    // --- IVLCVout.OnNewVideoLayoutListener ---
    override fun onNewVideoLayout(
        vlcVout: IVLCVout?,
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int
    ) {
        if (width > 0 && height > 0) {
            var aspect = width.toFloat() / height.toFloat()
            if (sarNum > 0 && sarDen > 0) {
                aspect *= (sarNum.toFloat() / sarDen.toFloat())
            }
            videoAspectRatio.floatValue = aspect

            surfaceView?.holder?.setFixedSize(width, height)
        }
    }

    override fun onSurfacesCreated(vlcVout: IVLCVout?) {}
    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {}
}
