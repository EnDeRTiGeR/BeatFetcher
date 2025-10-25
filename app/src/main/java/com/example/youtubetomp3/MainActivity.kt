package com.example.youtubetomp3

import android.util.Log
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.lifecycleScope
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.saveable.rememberSaveable
import dagger.hilt.android.AndroidEntryPoint
import com.example.youtubetomp3.ui.MainViewModel
import com.example.youtubetomp3.data.DownloadItem
import com.example.youtubetomp3.ui.YouTubePlayerPreview
import com.example.youtubetomp3.ui.MediaPlayerScreen
import com.example.youtubetomp3.util.UpdateManager
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        // GitHub owner/repo for in-app update release checks
        private const val GITHUB_OWNER = "EnDeRTiGeR"
        private const val GITHUB_REPO = "BeatFetcher"
    }
    
    @Inject
    lateinit var githubUpdateManager: UpdateManager
    @Inject
    lateinit var dataStore: DataStore<Preferences>
    
    private val mainViewModel: MainViewModel by viewModels()
    
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selected ->
            try {
                // Trigger local video -> audio conversion via ViewModel
                mainViewModel.convertLocalFile(selected)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start local conversion", e)
            }
        }
    }

@Composable
private fun SplashScreen(progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.mipmap.mp3_foreground),
            contentDescription = "App Icon",
            modifier = Modifier
                .size(96.dp)
                .padding(bottom = 24.dp)
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth(0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "${(progress * 100).toInt()}%")
    }
}

    // Permission launcher for storage permissions
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        
        if (allGranted) {
            // All requested permissions are granted
            Log.d("MainActivity", "All permissions granted")
        } else {
            // Some permissions are denied
            val deniedPermissions = permissions.filter { !it.value }.keys.joinToString(", ")
            Log.w("MainActivity", "Some permissions were denied: $deniedPermissions")
            
            // Check if we should show a rationale for any of the denied permissions
            val shouldShowRationale = permissions.any {
                !it.value && shouldShowRequestPermissionRationale(it.key)
            }
            
            if (shouldShowRationale) {
                // Show an explanation to the user why the permissions are needed
                showPermissionRationale()
            } else {
                // User checked "Don't ask again" or device policy prohibits the app from having that permission
                showPermissionDeniedDialog()
            }
        }
    }
    
    @androidx.media3.common.util.UnstableApi
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            var useDarkTheme by rememberSaveable { mutableStateOf(false) }
            MaterialTheme(colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()) {
                var showSplash by remember { mutableStateOf(true) }
                var progress by remember { mutableStateOf(0f) }
                var selected by rememberSaveable { mutableStateOf(0) }
                val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    // Load persisted theme and last section
                    try {
                        val prefs = dataStore.data.first()
                        useDarkTheme = prefs[booleanPreferencesKey("theme_dark")] ?: false
                        selected = prefs[intPreferencesKey("last_section")] ?: 0
                    } catch (_: Exception) { }
                    val steps = 50
                    val intervalMs = 100L
                    for (i in 0..steps) {
                        progress = i / steps.toFloat()
                        kotlinx.coroutines.delay(intervalMs)
                    }
                    showSplash = false
                }
                LaunchedEffect(showSplash) {
                    if (!showSplash) {
                        requestStoragePermissions()
                        try {
                            val hasNet = isInternetAvailable()
                            val ownerRepoSet = (GITHUB_OWNER != "YOUR_GITHUB_USERNAME" && GITHUB_REPO != "YOUR_REPOSITORY_NAME")
                            if (hasNet && ownerRepoSet) {
                                githubUpdateManager.checkAndInstallIfAvailable(
                                    this@MainActivity,
                                    GITHUB_OWNER,
                                    GITHUB_REPO
                                )
                            } else {
                                Log.d("MainActivity", "Update check skipped: internet=" + hasNet + ", ownerRepoSet=" + ownerRepoSet)
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Update check failed", e)
                        }
                    }
                }

                if (showSplash) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SplashScreen(progress = progress)
                    }
                } else {
                    androidx.compose.material3.ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            androidx.compose.material3.ModalDrawerSheet {
                                Spacer(Modifier.height(12.dp))
                                androidx.compose.material3.NavigationDrawerItem(
                                    label = { Text("Fetcher") },
                                    selected = selected == 0,
                                    onClick = {
                                        selected = 0
                                        scope.launch { drawerState.close() }
                                        scope.launch { dataStore.edit { it[intPreferencesKey("last_section")] = 0 } }
                                    },
                                    icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) }
                                )
                                androidx.compose.material3.NavigationDrawerItem(
                                    label = { Text("Player") },
                                    selected = selected == 1,
                                    onClick = {
                                        selected = 1
                                        scope.launch { drawerState.close() }
                                        scope.launch { dataStore.edit { it[intPreferencesKey("last_section")] = 1 } }
                                    },
                                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Text(
                                    "Theme",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                androidx.compose.material3.NavigationDrawerItem(
                                    label = { Text("Light Theme") },
                                    selected = !useDarkTheme,
                                    onClick = {
                                        useDarkTheme = false
                                        scope.launch { dataStore.edit { it[booleanPreferencesKey("theme_dark")] = false } }
                                    },
                                    icon = { Icon(Icons.Default.LightMode, contentDescription = null) }
                                )
                                androidx.compose.material3.NavigationDrawerItem(
                                    label = { Text("Dark Theme") },
                                    selected = useDarkTheme,
                                    onClick = {
                                        useDarkTheme = true
                                        scope.launch { dataStore.edit { it[booleanPreferencesKey("theme_dark")] = true } }
                                    },
                                    icon = { Icon(Icons.Default.DarkMode, contentDescription = null) }
                                )
                            }
                        }
                    ) {
                        androidx.compose.material3.Scaffold(
                            topBar = {
                                androidx.compose.material3.TopAppBar(
                                    title = {},
                                    navigationIcon = {
                                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                            Icon(Icons.Default.Menu, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                if (selected == 0) {
                                    YouTubeToMP3Screen(
                                        onUrlEntered = { url -> handleUrlInput(url) },
                                        onFileSelected = { getContent.launch("*/*") },
                                        sharedUrl = extractYouTubeUrl(intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""),
                                        onRequestPermissions = { requestStoragePermissions() }
                                    )
                                } else {
                                    MediaPlayerScreen()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (com.example.youtubetomp3.util.UpdateManager.shouldRetryAfterPermission) {
            com.example.youtubetomp3.util.UpdateManager.shouldRetryAfterPermission = false
            if (isInternetAvailable() &&
                GITHUB_OWNER != "EnDeRTiGeR" &&
                GITHUB_REPO != "BeatFetcher") {
                lifecycleScope.launch {
                    try {
                        githubUpdateManager.checkAndInstallIfAvailable(
                            this@MainActivity,
                            GITHUB_OWNER,
                            GITHUB_REPO
                        )
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Update retry failed", e)
                    }
                }
            }
        }
    }

    private fun requestStoragePermissions() {
        try {
            val permissionsToRequest = mutableListOf<String>()
            
            // For Android 13+ (API 33+), we need READ_MEDIA_AUDIO for accessing audio files
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
                }
            } 
            // For Android 10-12 (API 29-32), we need READ_EXTERNAL_STORAGE
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            // Also request POST_NOTIFICATIONS for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            
            if (permissionsToRequest.isNotEmpty()) {
                storagePermissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting permissions", e)
            // Continue without permissions, the app will handle this gracefully
        }
    }
    
    fun isInternetAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) return false
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
                
                when {
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> true
                    else -> false
                }
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            // If there's any error checking connectivity, assume internet is available
            // to prevent app from crashing
            true
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val extracted = sharedText?.let { extractYouTubeUrl(it) }
                if (!extracted.isNullOrBlank()) {
                    // Emit to ViewModel so the composable can update immediately
                    mainViewModel.onSharedUrlReceived(extracted)
                }
            }
        }
    }
    
    private fun handleSharedContent(content: String) {
        // Pass the shared content to the Composable via intent
        // No need to show a toast or process here
    }
    
    private fun extractYouTubeUrl(content: String): String? {
        val youtubeRegex = Regex("(https?://)?(www\\.)?(youtube\\.com|youtu\\.be)/[\\w\\-\\?=&]+")
        return youtubeRegex.find(content)?.value
    }
    
    private fun handleUrlInput(url: String) {
        // Validate and normalize input to a proper YouTube URL
        val extracted = extractYouTubeUrl(url)?.trim()
        if (extracted.isNullOrBlank()) {
            Toast.makeText(this, "Please paste a valid YouTube link", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isInternetAvailable()) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
            return
        }
        // Forward to ViewModel after validation
        mainViewModel.onUrlInputChanged(extracted)
        mainViewModel.convertToMP3(extracted)
    }
    
    private fun showPermissionRationale() {
        // You can show a dialog or a Snackbar explaining why the permissions are needed
        // For now, we'll just log it
        Log.i("MainActivity", "Showing permission rationale")
    }
    
    private fun showPermissionDeniedDialog() {
        // Show a dialog explaining that the user needs to grant permissions in app settings
        // For now, we'll just log it
        Log.w("MainActivity", "Permission denied and user selected 'Don't ask again'")
    }
}

@Composable
@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalFoundationApi::class)
fun YouTubeToMP3Screen(
    onUrlEntered: (String) -> Unit,
    onFileSelected: () -> Unit,
    sharedUrl: String? = null,
    viewModel: MainViewModel = hiltViewModel(),
    onRequestPermissions: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ytRegex = remember { Regex("(https?://)?(www\\.)?(youtube\\.com|youtu\\.be)/[\\w\\-\\?=&]+") }

    var urlInput by remember { mutableStateOf(TextFieldValue("")) }
    var hasHandledSharedUrl by remember { mutableStateOf(false) }
    var isInternetAvailable by remember { mutableStateOf(true) }

    // Consider any visible progress or indeterminate state as converting
    val isConverting = uiState.isLoading || uiState.progressIndeterminate ||
        (uiState.currentDownloadProgress > 0f && uiState.currentDownloadProgress < 0.999f)

    // Check internet connectivity periodically
    LaunchedEffect(Unit) {
        while (true) {
            isInternetAvailable = (context as? MainActivity)?.isInternetAvailable() ?: true
            kotlinx.coroutines.delay(5000) // Check every 5 seconds
        }
    }

    // Handle shared URL on first composition
    LaunchedEffect(sharedUrl) {
        if (!hasHandledSharedUrl && !sharedUrl.isNullOrBlank()) {
            urlInput = TextFieldValue(sharedUrl)
            viewModel.onSharedUrlReceived(sharedUrl)
            hasHandledSharedUrl = true
        }
    }

    // Handle subsequent shared URLs while app is already running
    LaunchedEffect(Unit) {
        viewModel.sharedUrlEvents.collect { newUrl ->
            if (!newUrl.isNullOrBlank()) {
                urlInput = TextFieldValue(newUrl) // replace existing input
                viewModel.onUrlInputChanged(newUrl)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isConverting) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedAccentBar(
                    modifier = Modifier
                        .padding(bottom = 16.dp),
                    height = 32.dp,
                    cornerRadius = 16.dp
                ) {
                    Text(
                        text = "Fetching.. Beats of your choice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Internet Connectivity Status
        if (!isInternetAvailable) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.WifiOff,
                        contentDescription = "No Internet",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No internet connection. Please check your network settings.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // URL Input
        OutlinedTextField(
            value = urlInput,
            onValueChange = {
                urlInput = it
                viewModel.onUrlInputChanged(it.text)
            },
            label = { Text("YouTube URL") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* no-op: prevent focus */ },
                    onDoubleClick = {
                        val clip = clipboard.getText()?.text?.toString().orEmpty()
                        if (clip.isNotBlank()) {
                            val extracted = ytRegex.find(clip)?.value
                            if (extracted != null) {
                                urlInput = TextFieldValue(extracted)
                                viewModel.onUrlInputChanged(extracted)
                            } else {
                                Toast.makeText(context, "Please paste a valid YouTube link", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ),
            placeholder = { Text("https://www.youtube.com/watch?v=...") },
            enabled = isInternetAvailable,
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                Row {
                    if (urlInput.text.isNotBlank()) {
                        IconButton(onClick = {
                            urlInput = TextFieldValue("")
                            viewModel.onUrlInputChanged("")
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear URL")
                        }
                    }
                    IconButton(onClick = {
                        val clip = clipboard.getText()?.text?.toString().orEmpty()
                        if (clip.isNotBlank()) {
                            val extracted = ytRegex.find(clip)?.value
                            if (extracted != null) {
                                urlInput = TextFieldValue(extracted)
                                viewModel.onUrlInputChanged(extracted)
                            } else {
                                Toast.makeText(context, "Please paste a valid YouTube link", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste URL")
                    }
                }
            }
        )

        // YouTube Video Preview
        if (uiState.currentVideoId != null && isInternetAvailable) {
            YouTubePlayerPreview(
                videoId = uiState.currentVideoId,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = 16.dp)
            )
        }

        // Convert Button (always visible). Disabled during loading, shows spinner when loading.
        Button(
            onClick = {
                if (urlInput.text.isNotBlank()) {
                    // Route through handler for validation + conversion
                    onUrlEntered(urlInput.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            enabled = urlInput.text.isNotBlank() && !uiState.isLoading && isInternetAvailable
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (uiState.isLoading) "Converting..." else "Start Conversion")
        }

        // Progress Bar
        if (uiState.isLoading && uiState.currentDownloadProgress > 0f) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                if (uiState.progressIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { uiState.currentDownloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.progressIndeterminate) {
                        uiState.currentProgressLabel.ifBlank { "Working..." }
                    } else {
                        val baseLabel = uiState.currentProgressLabel.ifBlank { "Downloading" }
                        if (uiState.showByteProgress && uiState.downloadedBytes > 0L) {
                            fun fmtMB(b: Long) = String.format(java.util.Locale.US, "%.2f MB", b / 1024.0 / 1024.0)
                            val d = fmtMB(uiState.downloadedBytes)
                            val t = uiState.totalBytes?.let { fmtMB(it) }
                            if (t != null) "$baseLabel $d / $t" else "$baseLabel $d"
                        } else {
                            "$baseLabel: ${(uiState.currentDownloadProgress * 100).toInt()}%"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Only show the alternate option when not converting, to avoid concurrent starts
        if (!uiState.isLoading) {
            // Or divider
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = "OR",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            // Select File Button
            OutlinedButton(
                onClick = onFileSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                enabled = !uiState.isLoading
            ) {
                Text("Select File")
            }
        }

        if (uiState.downloads.isNotEmpty()) {
            Text(
                text = "Recent Downloads",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 8.dp)
            )

            LazyColumn {
                items(uiState.downloads.take(3)) { download ->
                    val isCurrent = uiState.currentlyPlayingPath == download.filePath
                    DownloadItemCard(
                        download = download,
                        isPlaying = uiState.isPlaying && isCurrent,
                        positionMs = if (isCurrent) uiState.playbackPositionMs else 0L,
                        durationMs = if (isCurrent) uiState.playbackDurationMs else 0L,
                        onPlay = { viewModel.playAudio(download.filePath) },
                        onPause = { viewModel.pauseAudio() },
                        onStop = { viewModel.stopAudio() },
                        onDelete = { viewModel.deleteDownload(download.id) }
                    )
                }
            }
        }

        // Error message
        uiState.error?.let { error ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun AnimatedAccentBar(
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    cornerRadius: Dp = 16.dp,
    content: @Composable () -> Unit = {}
) {

    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    val transition = rememberInfiniteTransition(label = "accent")
    val sweep by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable<Float>(
            animation = tween<Float>(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .drawBehind {
                val w = size.width
                // Base subtle track
                drawRect(color = surfaceVariantColor.copy(alpha = 0.35f))
                // Moving glow
                val x = sweep * w
                val start = Offset(x - w, 0f)
                val end = Offset(x, 0f)
                val brush = Brush.linearGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0f),
                        primaryColor.copy(alpha = 0.6f),
                        primaryColor.copy(alpha = 0f)
                    ),
                    start = start,
                    end = end
                )
                drawRect(brush = brush)
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(java.util.Locale.US, "%d:%02d", m, s)
}

@Composable
fun DownloadItemCard(
    download: DownloadItem,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = download.artist ?: "Unknown Artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = download.duration ?: "Unknown Duration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isPlaying) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Row {
                if (isPlaying) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                    }
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
} 