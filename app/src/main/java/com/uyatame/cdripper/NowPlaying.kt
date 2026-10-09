@file:OptIn(ExperimentalMaterial3Api::class)

package com.uyatame.cdripper

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.uyatame.cdripper.library.ArtLoader
import com.uyatame.cdripper.data.AppSettings
import com.uyatame.cdripper.data.outputMode
import com.uyatame.cdripper.player.AudioEngine
import com.uyatame.cdripper.player.EqBands
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.uyatame.cdripper.player.AudioInfo
import com.uyatame.cdripper.player.PlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.LocalContentColor

/** 再生中の曲のジャケット画像 */
@Composable
private fun rememberArt(vm: MainViewModel, item: PlayItem): ImageBitmap? {
    if (item.cdTrack != null) return vm.cdCover
    val ctx = LocalContext.current
    val gen = ArtLoader.generation
    val img by produceState<ImageBitmap?>(ArtLoader.peek(item.artKey), item.artKey, item.artTrackUri, gen) {
        val k = item.artKey
        value = ArtLoader.peek(k)
        if (value == null && k != null) {
            value = withContext(Dispatchers.IO) { ArtLoader.load(ctx, k, item.artTrackUri, item.artCoverUri) }
        }
    }
    return img
}

/**
 * 全画面のプレイヤー。アンビエントモードでは、ジャケット画像をぼかして背景に敷き、
 * 画面全体をジャケットの色合いに染める。
 */
@Composable
fun NowPlayingScreen(vm: MainViewModel, s: AppSettings, onClose: () -> Unit) {
    val ambient = s.ambientPlayer
    var showSound by remember { mutableStateOf(false) }
    if (showSound) AudioSettingsSheet(vm, s) { showSound = false }
    val p = vm.player
    val cur = p.current ?: return
    val ctx = LocalContext.current
    val art = rememberArt(vm, cur)
    val info by produceState<String?>(null, cur) {
        value = withContext(Dispatchers.IO) { AudioInfo.describe(ctx, cur) }
    }
    BackHandler(onBack = onClose)

    val dark = ambient || MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }
    val view = LocalView.current
    DisposableEffect(dark) {
        val w = (view.context as? Activity)?.window
        val c = w?.let { WindowCompat.getInsetsController(it, view) }
        val before = c?.isAppearanceLightStatusBars
        c?.isAppearanceLightStatusBars = !dark
        onDispose { if (before != null) c?.isAppearanceLightStatusBars = before }
    }

    val content = if (ambient) Color.White else MaterialTheme.colorScheme.onSurface
    val sub = if (ambient) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accent = if (ambient) Color(0xFFF3D58C) else MaterialTheme.colorScheme.tertiary
    var drag by remember { mutableFloatStateOf(0f) }

    Surface(
        color = if (ambient) Color.Black else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { if (drag > 220f) onClose(); drag = 0f },
                onDragCancel = { drag = 0f },
            ) { _, d -> drag = (drag + d).coerceAtLeast(0f) }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (ambient) {
                if (art != null) {
                    Image(
                        art, null, Modifier.fillMaxSize().blur(70.dp),
                        contentScale = ContentScale.Crop, alpha = 0.9f,
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, Color.Black)),
                        ),
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.6f)),
                        ),
                    ),
                )
            }
            CompositionLocalProvider(LocalContentColor provides content) {
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, T("閉じる", "Close"), tint = content) }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (p.isCd) T("CDから再生", "Playing from CD") else T("再生中", "Now playing"),
                            style = MaterialTheme.typography.labelLarge, color = sub,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton({ showSound = true }) { Icon(AppIcons.Tune, T("音響設定", "Sound"), tint = content) }
                    }
                    Spacer(Modifier.weight(0.6f))
                    Box(
                        Modifier.fillMaxWidth().widthIn(max = 460.dp).aspectRatio(1f)
                            .shadow(24.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        CoverBox(art, Modifier.fillMaxSize(), 12.dp)
                    }
                    Spacer(Modifier.weight(0.5f))
                    Text(
                        info ?: " ", style = MaterialTheme.typography.titleSmall, color = accent,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 音響設定のクイック切り替え(タップで設定を開く)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chipColors = if (ambient) {
                            FilterChipDefaults.filterChipColors(
                                labelColor = sub, selectedLabelColor = Color.Black,
                                selectedContainerColor = accent,
                            )
                        } else FilterChipDefaults.filterChipColors()
                        val mode = s.outputMode
                        val bpSel = mode != 0
                        val eqSel = s.eqEnabled && !(mode != 0 && AudioEngine.bitPerfectActive)
                        FilterChip(
                            selected = bpSel,
                            onClick = { showSound = true },
                            label = {
                                Text(if (mode != 0 && AudioEngine.bitPerfectActive) "Bit-perfect ✓" else "Bit-perfect")
                            },
                            colors = chipColors,
                            border = if (ambient) BorderStroke(1.dp, sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = bpSel),
                        )
                        FilterChip(
                            selected = eqSel,
                            onClick = { showSound = true },
                            label = {
                                val name = EqBands.PRESETS.getOrNull(s.eqPreset)?.let { T(it.ja, it.en) } ?: T("カスタム", "Custom")
                                Text("EQ" + if (s.eqEnabled) " · $name" else " OFF")
                            },
                            colors = chipColors,
                            border = if (ambient) BorderStroke(1.dp, sub) else FilterChipDefaults.filterChipBorder(enabled = true, selected = eqSel),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        cur.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(cur.artist, style = MaterialTheme.typography.titleMedium, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (cur.album.isNotEmpty()) {
                        Text(cur.album, style = MaterialTheme.typography.bodyMedium, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(20.dp))

                    var seeking by remember(cur) { mutableStateOf<Float?>(null) }
                    val dur = p.durationMs.coerceAtLeast(1)
                    val frac = seeking ?: (p.positionMs.toFloat() / dur).coerceIn(0f, 1f)
                    Slider(
                        value = frac,
                        onValueChange = { seeking = it },
                        onValueChangeFinished = {
                            seeking?.let { p.seek((it * dur).toLong()) }
                            seeking = null
                        },
                        colors = if (ambient) {
                            SliderDefaults.colors(
                                thumbColor = Color.White, activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                            )
                        } else SliderDefaults.colors(),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtMs((frac * dur).toLong()), style = MaterialTheme.typography.labelMedium, color = sub)
                        Spacer(Modifier.weight(1f))
                        Text(fmtMs(p.durationMs), style = MaterialTheme.typography.labelMedium, color = sub)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton({ p.cycleRepeat() }) {
                            Icon(
                                if (p.repeatMode == 2) AppIcons.RepeatOne else AppIcons.Repeat,
                                when (p.repeatMode) { 1 -> T("全曲リピート", "Repeat all"); 2 -> T("1曲リピート", "Repeat one"); else -> T("リピートなし", "Repeat off") },
                                tint = if (p.repeatMode != 0) accent else sub,
                            )
                        }
                        OutlinedIconButton({ p.prev() }, Modifier.size(64.dp), border = BorderStroke(2.dp, content)) {
                            Icon(AppIcons.SkipPrevious, T("前の曲", "Previous track"), Modifier.size(30.dp), tint = content)
                        }
                        OutlinedIconButton({ p.toggle() }, Modifier.size(84.dp), shape = CircleShape, border = BorderStroke(2.5.dp, content)) {
                            Icon(
                                if (p.isPlaying) AppIcons.Pause else Icons.Filled.PlayArrow,
                                if (p.isPlaying) T("一時停止", "Pause") else T("再生", "Play"),
                                Modifier.size(44.dp), tint = content,
                            )
                        }
                        OutlinedIconButton({ p.next() }, Modifier.size(64.dp), border = BorderStroke(2.dp, content)) {
                            Icon(AppIcons.SkipNext, T("次の曲", "Next track"), Modifier.size(30.dp), tint = content)
                        }
                        IconButton({ p.toggleShuffle() }) {
                            Icon(AppIcons.Shuffle, T("シャッフル", "Shuffle"), tint = if (p.shuffle) accent else sub)
                        }
                    }
                    Spacer(Modifier.weight(0.6f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            T("${p.index + 1} / ${p.queue.size} 曲目", "Track ${p.index + 1} of ${p.queue.size}"),
                            style = MaterialTheme.typography.labelMedium, color = sub,
                        )
                        Spacer(Modifier.size(8.dp))
                        TextButton({ p.stop(); onClose() }) { Text(T("再生を終了", "Stop playback"), color = sub) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
