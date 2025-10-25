package com.example.youtubetomp3.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.BitmapFactory
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youtubetomp3.data.DownloadItem
import com.example.youtubetomp3.data.LibrarySong
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip

@Composable
fun MediaPlayerScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.scanLibrary()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
        Text(
            text = "Player",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Search + Sort controls
        var query by remember { mutableStateOf("") }
        var sortByTitle by remember { mutableStateOf(true) }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            label = { Text("Search songs") },
            singleLine = true
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = sortByTitle,
                onClick = { sortByTitle = true },
                label = { Text("Sort: Title") }
            )
            FilterChip(
                selected = !sortByTitle,
                onClick = { sortByTitle = false },
                label = { Text("Sort: Artist") }
            )
        }

        when {
            uiState.libraryLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.libraryError != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Failed to load library: ${uiState.libraryError}")
                }
            }
            uiState.librarySongs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No audio found on device")
                }
            }
            else -> {
                val scope = rememberCoroutineScope()
                val listState = rememberLazyListState()

                val filteredSorted = remember(uiState.librarySongs, query, sortByTitle) {
                    val base = uiState.librarySongs.filter { s ->
                        val q = query.trim()
                        if (q.isEmpty()) true else {
                            s.title.contains(q, ignoreCase = true) || (s.artist?.contains(q, ignoreCase = true) == true)
                        }
                    }
                    if (sortByTitle) base.sortedBy { it.title.lowercase() } else base.sortedBy { (it.artist ?: "").lowercase() }
                }
                val groups = remember(filteredSorted, sortByTitle) {
                    filteredSorted.groupBy { item ->
                        val key = if (sortByTitle) item.title else (item.artist ?: "")
                        key.firstOrNull()?.uppercaseChar() ?: '#'
                    }.toSortedMap(compareBy { it })
                }
                val sectionIndexMap = remember(groups) {
                    var idx = 0
                    buildMap<Char, Int> {
                        groups.forEach { (ch, list) ->
                            this[ch] = idx
                            idx += list.size + 1 // +1 for header
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        groups.forEach { (ch, list) ->
                            item {
                                Surface(color = MaterialTheme.colorScheme.surface) {
                                    Text(
                                        text = ch.toString(),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                            items(list) { song ->
                                val isCurrent = uiState.currentlyPlayingPath == song.uri
                                LibrarySongRow(
                                    song = song,
                                    isPlaying = uiState.isPlaying && isCurrent,
                                    positionMs = if (isCurrent) uiState.playbackPositionMs else 0L,
                                    durationMs = if (isCurrent) uiState.playbackDurationMs else 0L,
                                    onPlay = { viewModel.playAudio(song.uri) },
                                    onPause = { viewModel.pauseAudio() },
                                    onStop = { viewModel.stopAudio() }
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }

                    // A–Z fast scroller for available groups
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        groups.keys.forEach { ch ->
                            Text(
                                text = ch.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clickable {
                                        sectionIndexMap[ch]?.let { target ->
                                            scope.launch { listState.animateScrollToItem(target.coerceAtLeast(0)) }
                                        }
                                    }
                                    .padding(1.dp)
                            )
                        }
                    }
                }
            }
        }

        }

        // Auto-show Now Playing when a track starts
        LaunchedEffect(uiState.currentlyPlayingPath) {
            if (uiState.currentlyPlayingPath != null) showNowPlaying = true
        }

        if (uiState.currentlyPlayingPath != null && showNowPlaying) {
            BackHandler(enabled = true) { showNowPlaying = false }
            Surface(
                modifier = Modifier
                    .fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                var dragY by remember { mutableStateOf(0f) }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .graphicsLayer { translationY = dragY }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragY > 200f) {
                                        showNowPlaying = false
                                    }
                                    dragY = 0f
                                },
                                onVerticalDrag = { _, dy ->
                                    dragY = (dragY + dy).coerceAtLeast(0f)
                                }
                            )
                        }
                ) {
                    // Now Playing header (close button removed)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Now Playing",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 0.dp)
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    // Center rotating disk
                    val artForDisk = uiState.artworkData
                    RotatingDisk(
                        artworkData = artForDisk,
                        isPlaying = uiState.isPlaying
                    )
                    Spacer(Modifier.height(8.dp))
                    val libMatch = uiState.librarySongs.find { it.uri == uiState.currentlyPlayingPath }
                    val dlMatch = uiState.downloads.find { it.filePath == uiState.currentlyPlayingPath }
                    Spacer(Modifier.weight(1f))
                    NowPlayingControls(
                        title = libMatch?.title ?: dlMatch?.title ?: "Playing",
                        artist = libMatch?.artist ?: dlMatch?.artist,
                        artworkData = uiState.artworkData,
                        isPlaying = uiState.isPlaying,
                        positionMs = uiState.playbackPositionMs,
                        durationMs = uiState.playbackDurationMs,
                        shuffleEnabled = uiState.shuffleEnabled,
                        repeatOne = uiState.repeatOne,
                        onToggleShuffle = {
                            val newState = !uiState.shuffleEnabled
                            viewModel.toggleShuffle()
                            android.widget.Toast.makeText(
                                context,
                                if (newState) "Shuffle On" else "Shuffle Off",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        onPrev = { viewModel.playPrevious() },
                        onPlay = { uiState.currentlyPlayingPath?.let { viewModel.playAudio(it) } },
                        onPause = { viewModel.pauseAudio() },
                        onNext = { viewModel.playNext() },
                        onToggleRepeatOne = {
                            val newState = !uiState.repeatOne
                            viewModel.toggleRepeatOne()
                            android.widget.Toast.makeText(
                                context,
                                if (newState) "Repeat One On" else "Repeat One Off",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        onSeekPercent = { p -> viewModel.seekToPercent(p) }
                    )
                }
            }
        }

        // Mini player bar at bottom when Now Playing is closed
        if (uiState.currentlyPlayingPath != null && !showNowPlaying) {
            val libMatch = uiState.librarySongs.find { it.uri == uiState.currentlyPlayingPath }
            val dlMatch = uiState.downloads.find { it.filePath == uiState.currentlyPlayingPath }
            val title = libMatch?.title ?: dlMatch?.title ?: "Playing"
            val artist = libMatch?.artist ?: dlMatch?.artist
            val progress = if (uiState.playbackDurationMs > 0L)
                (uiState.playbackPositionMs.toFloat() / uiState.playbackDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clickable { showNowPlaying = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork thumb
                        val bmp = remember(uiState.artworkData) {
                            uiState.artworkData?.let { data ->
                                try { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() } catch (_: Exception) { null }
                            }
                        }
                        if (bmp != null) {
                            Image(bmp, contentDescription = null, modifier = Modifier.size(48.dp))
                        } else {
                            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            if (!artist.isNullOrBlank()) {
                                Text(artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        IconButton(onClick = { if (uiState.isPlaying) viewModel.pauseAudio() else uiState.currentlyPlayingPath?.let { viewModel.playAudio(it) } }) {
                            if (uiState.isPlaying) Icon(Icons.Default.Pause, contentDescription = null) else Icon(Icons.Default.PlayArrow, contentDescription = null)
                        }
                        IconButton(onClick = { viewModel.playNext() }) { Icon(Icons.Default.SkipNext, contentDescription = null) }
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PlayerItemRow(
    item: DownloadItem,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, style = MaterialTheme.typography.titleSmall)
                Text(text = item.artist ?: "Unknown Artist", style = MaterialTheme.typography.bodySmall)
                if (isPlaying && durationMs > 0L) {
                    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) {
                    IconButton(onClick = onPause) { Icon(Icons.Default.Pause, contentDescription = null) }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = null) }
                } else {
                    IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                }
            }
        }
    }
}

@Composable
fun LibrarySongRow(
    song: LibrarySong,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, style = MaterialTheme.typography.titleSmall)
                Text(text = song.artist ?: "Unknown Artist", style = MaterialTheme.typography.bodySmall)
                if (isPlaying && durationMs > 0L) {
                    val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 6.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) {
                    IconButton(onClick = onPause) { Icon(Icons.Default.Pause, contentDescription = null) }
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = null) }
                } else {
                    IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingControls(
    title: String,
    artist: String?,
    artworkData: ByteArray?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatOne: Boolean,
    onToggleShuffle: () -> Unit,
    onPrev: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeatOne: () -> Unit,
    onSeekPercent: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            // Artwork with slight pulse when playing
            val artBitmap = remember(artworkData) {
                artworkData?.let { data ->
                    try { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() } catch (_: Exception) { null }
                }
            }
            val artScale by animateFloatAsState(targetValue = if (isPlaying) 1.03f else 1.0f, label = "artScale")
            if (artBitmap != null) {
                Image(
                    artBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .graphicsLayer { scaleX = artScale; scaleY = artScale }
                )
                Spacer(Modifier.height(8.dp))
            }
            // Title + artist
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (!artist.isNullOrBlank()) {
                Text(text = artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Progress slider with time labels
            var isSliding by remember { mutableStateOf(false) }
            var sliderPos by remember { mutableStateOf(0f) }
            LaunchedEffect(positionMs, durationMs, isSliding) {
                if (!isSliding && durationMs > 0L) {
                    sliderPos = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                }
            }
            if (durationMs > 0L) {
                Slider(
                    value = sliderPos,
                    onValueChange = { v ->
                        isSliding = true
                        sliderPos = v
                    },
                    onValueChangeFinished = {
                        onSeekPercent(sliderPos)
                        isSliding = false
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
                    Text("-${formatTime(remaining)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Control row
            val shuffleScale by animateFloatAsState(
                targetValue = if (shuffleEnabled) 1.1f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "shuffleScale"
            )
            val repeatScale by animateFloatAsState(
                targetValue = if (repeatOne) 1.1f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "repeatScale"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.scale(shuffleScale)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrev) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous") }
                    // Big play/pause
                    FilledIconButton(onClick = { if (isPlaying) onPause() else onPlay() }) {
                        if (isPlaying) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                        }
                    }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
                }

                IconButton(onClick = onToggleRepeatOne) {
                    if (repeatOne) {
                        BadgedBox(badge = { Badge { Text("1") } }) {
                            Icon(
                                Icons.Default.Repeat,
                                contentDescription = "Repeat One",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.scale(repeatScale)
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = "Repeat One",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.scale(repeatScale)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RotatingDisk(
    artworkData: ByteArray?,
    isPlaying: Boolean,
    sizeDp: androidx.compose.ui.unit.Dp = 220.dp
) {
    val bitmap = remember(artworkData) {
        artworkData?.let { data ->
            try { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() } catch (_: Exception) { null }
        }
    }
    // Capture theme colors outside of drawBehind (draw scope is not @Composable)
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.background
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                rotation.animateTo(
                    rotation.value + 360f,
                    animationSpec = tween(durationMillis = 8000, easing = LinearEasing)
                )
            }
        }
    }
    val rot = rotation.value % 360f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(sizeDp)
            .graphicsLayer { rotationZ = rot },
        contentAlignment = Alignment.Center
    ) {
        val diskModifier = Modifier
            .size(sizeDp)
            .clip(CircleShape)
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = null,
                modifier = diskModifier,
                contentScale = ContentScale.Crop
            )
        } else {
            // Stylized placeholder disk
            Box(
                modifier = diskModifier.drawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(color = surfaceVariantColor)
                    drawCircle(color = primaryColor.copy(alpha = 0.2f), radius = r * 0.85f)
                    drawCircle(color = primaryColor.copy(alpha = 0.15f), radius = r * 0.65f)
                }
            )
        }
        // Center hole
        Box(
            modifier = Modifier
                .size(sizeDp * 0.14f)
                .clip(CircleShape)
                .drawBehind { drawCircle(color = bgColor) }
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(java.util.Locale.US, "%d:%02d", m, s)
}
