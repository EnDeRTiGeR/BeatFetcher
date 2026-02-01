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
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clipToBounds
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import dagger.hilt.android.AndroidEntryPoint
import com.example.youtubetomp3.ui.MainViewModel
import com.example.youtubetomp3.ui.MediaPlayerScreen
import com.example.youtubetomp3.data.appDataStore
import com.example.youtubetomp3.util.formatTime
import com.example.youtubetomp3.data.DownloadItem
import com.example.youtubetomp3.ui.YouTubePlayerPreview
import com.example.youtubetomp3.util.UpdateManager
import com.example.youtubetomp3.util.AppearanceBridge
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        // GitHub owner/repo for in-app update release checks
        private const val GITHUB_OWNER = "EnDeRTiGeR"
        private const val GITHUB_REPO = "BeatFetcher"
    }

private enum class BeatFetcherMode {
    Extractor,
    MediaSession
}

private data class AppModeTransition(
    val from: BeatFetcherMode,
    val to: BeatFetcherMode
)

private data class ThemeVariantTransition(
    val fromDark: Boolean,
    val toDark: Boolean
)

private fun lerpScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    val f = t.coerceIn(0f, 1f)
    return a.copy(
        primary = androidx.compose.ui.graphics.lerp(a.primary, b.primary, f),
        onPrimary = androidx.compose.ui.graphics.lerp(a.onPrimary, b.onPrimary, f),
        secondary = androidx.compose.ui.graphics.lerp(a.secondary, b.secondary, f),
        onSecondary = androidx.compose.ui.graphics.lerp(a.onSecondary, b.onSecondary, f),
        background = androidx.compose.ui.graphics.lerp(a.background, b.background, f),
        onBackground = androidx.compose.ui.graphics.lerp(a.onBackground, b.onBackground, f),
        surface = androidx.compose.ui.graphics.lerp(a.surface, b.surface, f),
        onSurface = androidx.compose.ui.graphics.lerp(a.onSurface, b.onSurface, f),
        surfaceVariant = androidx.compose.ui.graphics.lerp(a.surfaceVariant, b.surfaceVariant, f),
        onSurfaceVariant = androidx.compose.ui.graphics.lerp(a.onSurfaceVariant, b.onSurfaceVariant, f),
        outline = androidx.compose.ui.graphics.lerp(a.outline, b.outline, f),
        error = androidx.compose.ui.graphics.lerp(a.error, b.error, f),
        onError = androidx.compose.ui.graphics.lerp(a.onError, b.onError, f),
        errorContainer = androidx.compose.ui.graphics.lerp(a.errorContainer, b.errorContainer, f),
        onErrorContainer = androidx.compose.ui.graphics.lerp(a.onErrorContainer, b.onErrorContainer, f)
    )
}

private fun contentColorFor(bg: Color): Color {
    return if (bg.luminance() > 0.42f) Color(0xFF0F172A) else Color(0xFFE2E8F0)
}

private fun schemeFromModeColor(modeColor: Color, dark: Boolean): ColorScheme {
    // No pure white/black identities: everything is tinted off the mode color.
    val lightBase = Color(0xFFF3F5FA)
    val darkBase = Color(0xFF0B1020)

    val background = if (dark) androidx.compose.ui.graphics.lerp(darkBase, modeColor, 0.12f) else androidx.compose.ui.graphics.lerp(lightBase, modeColor, 0.14f)
    val surface = if (dark) androidx.compose.ui.graphics.lerp(darkBase, modeColor, 0.18f) else androidx.compose.ui.graphics.lerp(lightBase, modeColor, 0.10f)
    val surfaceVariant = if (dark) androidx.compose.ui.graphics.lerp(darkBase, modeColor, 0.28f) else androidx.compose.ui.graphics.lerp(lightBase, modeColor, 0.22f)

    val onBackground = contentColorFor(background)
    val onSurface = contentColorFor(surface)
    val onSurfaceVariant = contentColorFor(surfaceVariant)

    val primary = if (dark) androidx.compose.ui.graphics.lerp(modeColor, Color.White, 0.18f) else modeColor
    val secondary = if (dark) androidx.compose.ui.graphics.lerp(modeColor, Color.White, 0.10f) else androidx.compose.ui.graphics.lerp(modeColor, Color(0xFF0F172A), 0.08f)

    val outline = if (dark) androidx.compose.ui.graphics.lerp(surfaceVariant, Color.White, 0.18f) else androidx.compose.ui.graphics.lerp(surfaceVariant, Color(0xFF0F172A), 0.18f)

    val error = if (dark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
    val errorContainer = if (dark) Color(0xFF7F1D1D) else Color(0xFFFEE2E2)
    val onError = contentColorFor(error)
    val onErrorContainer = contentColorFor(errorContainer)

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = contentColorFor(primary),
            secondary = secondary,
            onSecondary = contentColorFor(secondary),
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = contentColorFor(primary),
            secondary = secondary,
            onSecondary = contentColorFor(secondary),
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer
        )
    }
}

@Composable
private fun BeatFetcherTwoModeHost(
    mode: BeatFetcherMode,
    transition: AppModeTransition?,
    transitionProgress: Float,
    inputLocked: Boolean,
    onRequestMode: (BeatFetcherMode) -> Unit,
    isDarkVariant: Boolean,
    onRequestThemeVariant: (Boolean) -> Boolean,
    themeIndicatorDark: Boolean?,
    extractor: @Composable () -> Unit,
    mediaSession: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                PersistentWaveHeader(
                    mode = mode,
                    transition = transition,
                    transitionProgress = transitionProgress,
                    inputLocked = inputLocked,
                    isDarkVariant = isDarkVariant,
                    onRequestThemeVariant = onRequestThemeVariant,
                    themeIndicatorDark = themeIndicatorDark,
                    onSingleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRequestMode(BeatFetcherMode.MediaSession)
                    },
                    onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRequestMode(BeatFetcherMode.Extractor)
                    }
                )
                Box(modifier = Modifier.weight(1f)) {
                    val fusionOverlap = 18.dp
                    when (mode) {
                        BeatFetcherMode.Extractor -> {
                            Box(modifier = Modifier.offset(y = -fusionOverlap)) {
                                extractor()
                            }
                        }
                        BeatFetcherMode.MediaSession -> {
                            Box(modifier = Modifier.offset(y = -(fusionOverlap + 12.dp))) {
                                mediaSession()
                            }
                        }
                    }
                }
            }

            if (inputLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(pass = PointerEventPass.Initial)
                                while (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) { }
                            }
                        }
                )
            }
        }
    }
}

@Composable
private fun PersistentWaveHeader(
    mode: BeatFetcherMode,
    transition: AppModeTransition?,
    transitionProgress: Float,
    inputLocked: Boolean,
    isDarkVariant: Boolean,
    onRequestThemeVariant: (Boolean) -> Boolean,
    themeIndicatorDark: Boolean?,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val structureColor = MaterialTheme.colorScheme.primary
    val isTransitioning = transition != null
    val direction = remember(transition) {
        val tr = transition
        if (tr == null) 0f else if (tr.from == BeatFetcherMode.Extractor && tr.to == BeatFetcherMode.MediaSession) 1f else -1f
    }

    val infinite = rememberInfiniteTransition(label = "waves")
    val idlePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "idlePhase"
    )

    val inferredDark = MaterialTheme.colorScheme.background.luminance() < 0.36f
    val baseThemeBoost = if (inferredDark) 1.03f else 1.00f
    val waveBoost = if (isTransitioning) {
        val p = transitionProgress
        val peak = 1.0f + (0.35f * (1f - kotlin.math.abs(2f * p - 1f)))
        peak
    } else baseThemeBoost

    val sweep = if (isTransitioning) direction * (transitionProgress * (Math.PI * 2.0).toFloat()) else 0f
    val phase = idlePhase + sweep

    val headerHeight = 104.dp
    val mediaHeaderExtra = 16.dp
    val fusionHeight = 18.dp
    val resolvedHeaderHeight = (if (mode == BeatFetcherMode.MediaSession) headerHeight + mediaHeaderExtra else headerHeight) + fusionHeight
    val buttonSize = 34.dp
    val buttonOffsetY = 12.dp
    val indicator = themeIndicatorDark

    val density = LocalDensity.current
    val themeSwipeThresholdPx = remember(density) { with(density) { 22.dp.toPx() } }

    val indicatorAlpha = remember { Animatable(0f) }
    val indicatorScale = remember { Animatable(0.92f) }
    LaunchedEffect(indicator) {
        if (indicator != null) {
            indicatorAlpha.snapTo(0f)
            indicatorScale.snapTo(0.92f)
            indicatorAlpha.animateTo(1f, animationSpec = tween(durationMillis = 90, easing = LinearEasing))
            indicatorScale.animateTo(1f, animationSpec = tween(durationMillis = 140, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            kotlinx.coroutines.delay(220)
            indicatorAlpha.animateTo(0f, animationSpec = tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            indicatorScale.animateTo(0.92f, animationSpec = tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(resolvedHeaderHeight)
            .drawBehind {
                val bgAlpha = if (inferredDark) 0.12f else 0.10f
                drawRect(color = structureColor.copy(alpha = bgAlpha))
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val buttonCenterY = (h / 2f) + buttonOffsetY.toPx()
            val centerY = buttonCenterY - 12.dp.toPx()
            val gap = 18.dp.toPx()
            val buttonRadius = buttonSize.toPx() / 2f
            val cx = w / 2f

            val leftEnd = (cx - buttonRadius - gap).coerceAtLeast(0f)
            val rightStart = (cx + buttonRadius + gap).coerceAtMost(w)

            val baseAmp = h * 0.10f
            val amp = baseAmp * waveBoost
            val strokeBase = (h * 0.07f).coerceAtLeast(2.5f)

            fun drawWaveBand(x0: Float, x1: Float, mirror: Boolean) {
                if (x1 <= x0) return
                val span = (x1 - x0)
                val steps = 46
                val dx = span / steps

                val weights = floatArrayOf(1.00f, 0.72f, 0.46f)
                val freqs = floatArrayOf(1.45f, 1.15f, 0.92f)
                val yOffsets = floatArrayOf(-h * 0.06f, 0f, h * 0.06f)

                for (i in 0 until 3) {
                    val path = androidx.compose.ui.graphics.Path()
                    val a = amp * weights[i]
                    val f = freqs[i]
                    val y0 = centerY + yOffsets[i]
                    val phaseOffset = phase + (i * 0.85f)

                    val convergeTargetY = buttonCenterY + (i - 1) * 5.dp.toPx()
                    val convergeSpanPx = 42.dp.toPx()

                    for (s in 0..steps) {
                        val x = x0 + s * dx
                        val t = (x - x0) / span
                        val local = if (mirror) (1f - t) else t
                        val rawY = y0 + a * kotlin.math.sin((local * Math.PI.toFloat() * 2f * f) + phaseOffset)

                        val distToCircleEdge = if (!mirror) (x1 - x) else (x - x0)
                        val convergeT = ((convergeSpanPx - distToCircleEdge) / convergeSpanPx).coerceIn(0f, 1f)
                        val easedConverge = convergeT * convergeT * (3f - 2f * convergeT)
                        val y = rawY + (convergeTargetY - rawY) * easedConverge
                        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }

                    val alpha = 0.70f - (i * 0.16f)
                    drawPath(
                        path = path,
                        color = structureColor.copy(alpha = alpha),
                        style = Stroke(width = strokeBase * (1f - i * 0.18f), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    )
                }
            }

            drawWaveBand(0f, leftEnd, mirror = false)
            drawWaveBand(rightStart, w, mirror = true)
        }

        val headerBg = structureColor.copy(alpha = if (inferredDark) 0.12f else 0.10f)
        val bg = MaterialTheme.colorScheme.background
        val blurRadiusPx = remember(density) { with(density) { 5.dp.toPx() } }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(fusionHeight)
                .graphicsLayer {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        renderEffect = RenderEffect.createBlurEffect(
                            blurRadiusPx,
                            blurRadiusPx,
                            Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    }
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(headerBg, bg),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = buttonOffsetY)
                .size(buttonSize)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = {
                        if (!inputLocked && mode == BeatFetcherMode.Extractor) onSingleTap()
                    },
                    onDoubleClick = {
                        if (!inputLocked && mode == BeatFetcherMode.MediaSession) onDoubleTap()
                    }
                )
                .pointerInput(isDarkVariant, inputLocked) {
                    if (inputLocked) return@pointerInput
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dy ->
                            totalDy += dy
                        },
                        onDragEnd = {
                            val targetDark = when {
                                totalDy > themeSwipeThresholdPx -> true
                                totalDy < -themeSwipeThresholdPx -> false
                                else -> null
                            }
                            totalDy = 0f
                            if (targetDark != null && targetDark != isDarkVariant) {
                                val accepted = onRequestThemeVariant(targetDark)
                                if (accepted) {
                                    // Soft haptic confirmation on successful theme toggle.
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        }
                    )
                }
                .drawBehind {
                    drawCircle(color = structureColor.copy(alpha = 0.10f))
                    drawCircle(color = structureColor.copy(alpha = 0.55f), style = Stroke(width = 1.dp.toPx()))
                },
            contentAlignment = Alignment.Center
        ) {
            val dot = MaterialTheme.colorScheme.onSurface
            Canvas(modifier = Modifier.size(18.dp)) {
                drawCircle(color = dot.copy(alpha = 0.35f))
            }
        }

        if (indicator != null) {
            val text = if (indicator) "🌙" else "☀️"
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = buttonOffsetY - 34.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.graphicsLayer {
                        alpha = indicatorAlpha.value
                        scaleX = indicatorScale.value
                        scaleY = indicatorScale.value
                    }
                )
            }
        }
    }
}
    
    @Inject
    lateinit var githubUpdateManager: UpdateManager
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
    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = tween(durationMillis = 800, easing = LinearEasing))
    }
    val ring = rememberInfiniteTransition(label = "ring")
    val sweep by ring.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            // Capture theme color in composable scope; use inside Canvas draw scope
            val primaryColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                drawArc(
                    color = primaryColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = stroke)
                )
            }
            Image(
                painter = painterResource(id = R.mipmap.mp3_foreground),
                contentDescription = "App Icon",
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
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

        // Handle initial launch intent (share/open-with)
        handleIntent(intent)
        
        setContent {
            val context = LocalContext.current
            var isDarkVariant by rememberSaveable { mutableStateOf(false) }

            var mode by rememberSaveable { mutableStateOf(BeatFetcherMode.Extractor) }
            var transition by remember { mutableStateOf<AppModeTransition?>(null) }
            val transitionProgress = remember { Animatable(0f) }
            var inputLocked by remember { mutableStateOf(false) }

            var themeTransition by remember { mutableStateOf<ThemeVariantTransition?>(null) }
            val themeProgress = remember { Animatable(1f) }
            var themeLocked by remember { mutableStateOf(false) }
            var themeIndicatorDark by remember { mutableStateOf<Boolean?>(null) }

            val extractorModeColor = Color(0xFF7C3AED)
            val mediaModeColor = Color(0xFF2563EB)

            val fromModeColor = remember(transition, mode) {
                val tr = transition
                val from = tr?.from ?: mode
                if (from == BeatFetcherMode.Extractor) extractorModeColor else mediaModeColor
            }
            val toModeColor = remember(transition, mode) {
                val tr = transition
                val to = tr?.to ?: mode
                if (to == BeatFetcherMode.Extractor) extractorModeColor else mediaModeColor
            }
            val modeColor = if (transition != null) androidx.compose.ui.graphics.lerp(fromModeColor, toModeColor, transitionProgress.value) else fromModeColor

            val fromVariantDark = themeTransition?.fromDark ?: isDarkVariant
            val toVariantDark = themeTransition?.toDark ?: isDarkVariant
            val fromVariantScheme = schemeFromModeColor(modeColor, fromVariantDark)
            val toVariantScheme = schemeFromModeColor(modeColor, toVariantDark)
            val scheme = if (themeTransition != null) lerpScheme(fromVariantScheme, toVariantScheme, themeProgress.value) else fromVariantScheme

            LaunchedEffect(scheme) {
                AppearanceBridge.updateFromScheme(scheme)
            }

            MaterialTheme(colorScheme = scheme) {
                var showSplash by remember { mutableStateOf(true) }
                var progress by remember { mutableStateOf(0f) }

                // If a URL is shared while the app is on the Player screen, force navigation to Fetcher.
                LaunchedEffect(Unit) {
                    mainViewModel.sharedUrlEvents.collect { newUrl ->
                        if (!newUrl.isNullOrBlank()) {
                            mode = BeatFetcherMode.Extractor
                        }
                    }
                }

                fun requestMode(target: BeatFetcherMode) {
                    if (inputLocked) return
                    if (target == mode) return
                    transition = AppModeTransition(from = mode, to = target)
                    inputLocked = true
                }

                fun requestThemeVariant(targetDark: Boolean): Boolean {
                    if (inputLocked) return false
                    if (themeLocked) return false
                    if (targetDark == isDarkVariant) return false
                    themeLocked = true
                    themeTransition = ThemeVariantTransition(fromDark = isDarkVariant, toDark = targetDark)
                    themeIndicatorDark = targetDark
                    return true
                }

                LaunchedEffect(themeTransition) {
                    val tr = themeTransition ?: return@LaunchedEffect
                    try {
                        themeProgress.snapTo(0f)
                        themeProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 280, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                        )
                        isDarkVariant = tr.toDark
                        try {
                            (context.applicationContext as android.content.Context).appDataStore.edit {
                                it[booleanPreferencesKey("theme_dark")] = tr.toDark
                            }
                        } catch (_: Exception) { }
                        kotlinx.coroutines.delay(520)
                    } finally {
                        themeIndicatorDark = null
                        themeTransition = null
                        themeProgress.snapTo(1f)
                        themeLocked = false
                    }
                }

                LaunchedEffect(transition) {
                    val tr = transition ?: return@LaunchedEffect
                    try {
                        transitionProgress.snapTo(0f)
                        kotlinx.coroutines.delay(500)
                        transitionProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 1500, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                        )
                        mode = tr.to
                    } finally {
                        transition = null
                        transitionProgress.snapTo(0f)
                        inputLocked = false
                    }
                }

                LaunchedEffect(Unit) {
                    // Load persisted theme and last section
                    try {
                        val prefs = (context.applicationContext as android.content.Context).appDataStore.data.first()
                        isDarkVariant = prefs[booleanPreferencesKey("theme_dark")] ?: false
                    } catch (_: Exception) { }
                    val steps = 20
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
                            if (hasNet) {
                                githubUpdateManager.checkAndInstallIfAvailable(
                                    this@MainActivity,
                                    GITHUB_OWNER,
                                    GITHUB_REPO
                                )
                            } else {
                                Log.d("MainActivity", "Update check skipped")
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
                    BeatFetcherTwoModeHost(
                        mode = mode,
                        transition = transition,
                        transitionProgress = transitionProgress.value,
                        inputLocked = inputLocked,
                        onRequestMode = { target -> requestMode(target) },
                        isDarkVariant = isDarkVariant,
                        onRequestThemeVariant = { targetDark -> requestThemeVariant(targetDark) },
                        themeIndicatorDark = themeIndicatorDark,
                        extractor = {
                            YouTubeToMP3Screen(
                                onUrlEntered = { url -> handleUrlInput(url) },
                                onFileSelected = { getContent.launch("*/*") },
                                sharedUrl = extractYouTubeUrl(intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""),
                                _onRequestPermissions = { requestStoragePermissions() }
                            )
                        },
                        mediaSession = {
                            MediaPlayerScreen()
                        }
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (com.example.youtubetomp3.util.UpdateManager.shouldRetryAfterPermission) {
            com.example.youtubetomp3.util.UpdateManager.shouldRetryAfterPermission = false
            if (isInternetAvailable()) {
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
            Intent.ACTION_VIEW -> {
                val extracted = intent.dataString?.let { extractYouTubeUrl(it) }
                if (!extracted.isNullOrBlank()) {
                    mainViewModel.onSharedUrlReceived(extracted)
                }
            }
        }
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

    override fun onDestroy() {
        try {
            mainViewModel.stopAudio()
            mainViewModel.releasePlayer()
        } catch (_: Exception) { }
        super.onDestroy()
    }
}

@Composable
@androidx.media3.common.util.UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Suppress("UNUSED_PARAMETER")
fun YouTubeToMP3Screen(
    onUrlEntered: (String) -> Unit,
    onFileSelected: () -> Unit,
    sharedUrl: String? = null,
    viewModel: MainViewModel = hiltViewModel(),
    _onRequestPermissions: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    @Suppress("DEPRECATION")
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
                    RecentDownloadCard(
                        download = download,
                        isPlaying = uiState.isPlaying && isCurrent,
                        positionMs = if (isCurrent) uiState.playbackPositionMs else 0L,
                        durationMs = if (isCurrent) uiState.playbackDurationMs else 0L,
                        onPlay = { viewModel.playAudio(download.filePath) },
                        onPause = { viewModel.pauseAudio() },
                        onStop = { viewModel.stopAudio() }
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
fun RecentDownloadCard(
    download: DownloadItem,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
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
            Column(modifier = Modifier.weight(1f)) {
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

            Row(verticalAlignment = Alignment.CenterVertically) {
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

 

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
fun DownloadItemCard(
    download: DownloadItem,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onSetMood: (String?) -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var moodDialogOpen by remember { mutableStateOf(false) }
    var moodText by remember(download.id) { mutableStateOf(download.moodTag ?: "") }

    if (moodDialogOpen) {
        AlertDialog(
            onDismissRequest = { moodDialogOpen = false },
            title = { Text("Set Mood") },
            text = {
                Column {
                    OutlinedTextField(
                        value = moodText,
                        onValueChange = { moodText = it },
                        singleLine = true,
                        label = { Text("Mood (e.g. Party, Chill)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick picks
                    val picks = listOf("Party", "Happy", "Chill", "Sad", "Workout", "Romantic", "Focus", "Lofi", "Vibes", "Relax")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        picks.forEach { p ->
                            AssistChip(
                                onClick = { moodText = p },
                                label = { Text(p) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val v = moodText.trim().ifBlank { null }
                        onSetMood(v)
                        moodDialogOpen = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onSetMood(null)
                        moodText = ""
                        moodDialogOpen = false
                    }
                ) { Text("Clear") }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pointerInput(download.id) {
                // Detect long-press anywhere on the card without consuming events,
                // so IconButtons inside still receive clicks.
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        menuExpanded = true
                        // Wait for release/cancel before listening for next gesture
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    }
                }
            }
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

                download.moodTag?.takeIf { it.isNotBlank() }?.let { tag ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Mood: $tag",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

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
                // Primary play/pause affordance
                if (isPlaying) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                    }
                } else {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (!isPlaying) {
                            DropdownMenuItem(
                                text = { Text("Play") },
                                onClick = {
                                    menuExpanded = false
                                    onPlay()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Play Next") },
                            onClick = {
                                menuExpanded = false
                                onPlayNext()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Queue") },
                            onClick = {
                                menuExpanded = false
                                onAddToQueue()
                            }
                        )
                        if (isPlaying) {
                            DropdownMenuItem(
                                text = { Text("Stop") },
                                onClick = {
                                    menuExpanded = false
                                    onStop()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Set Mood") },
                            onClick = {
                                menuExpanded = false
                                moodText = download.moodTag ?: ""
                                moodDialogOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
} 