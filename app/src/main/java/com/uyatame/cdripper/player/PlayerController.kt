package com.uyatame.cdripper.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.cdripper.usb.TocTrack
import com.uyatame.cdripper.usb.UsbScsiDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.media.AudioFormat as AFormat

data class PlayItem(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri? = null,
    val cdTrack: TocTrack? = null,
    val artKey: String? = null,
    val artTrackUri: String? = null,
    val artCoverUri: String? = null,
)

/**
 * ファイル(MediaPlayer = OS内蔵デコーダ)とCD(READ CDで読んだPCMをAudioTrackへ)の両方を再生する。
 */
class PlayerController(private val ctx: Context, scope: CoroutineScope) {
    var queue by mutableStateOf<List<PlayItem>>(emptyList())
        private set
    var index by mutableIntStateOf(-1)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** 0 = リピートなし, 1 = 全曲リピート, 2 = 1曲リピート */
    var repeatMode by mutableIntStateOf(0)
        private set
    var shuffle by mutableStateOf(false)
        private set
    private var original: List<PlayItem> = emptyList()

    /** ロック画面・通知・Bluetooth機器からの操作用 */
    val session = MediaSession(ctx, "UCRT").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = this@PlayerController.resume()
            override fun onPause() = this@PlayerController.pause()
            override fun onSkipToNext() = this@PlayerController.next()
            override fun onSkipToPrevious() = this@PlayerController.prev()
            override fun onSeekTo(pos: Long) = this@PlayerController.seek(pos)
            override fun onStop() = this@PlayerController.stop()
        })
    }
    private var art: Bitmap? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { pause() }
    }
    private var noisyRegistered = false

    val current: PlayItem? get() = queue.getOrNull(index)
    val isCd: Boolean get() = current?.cdTrack != null

    var onStateChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener(
            AudioManager.OnAudioFocusChangeListener { ch ->
                if (ch == AudioManager.AUDIOFOCUS_LOSS || ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            },
            main,
        )
        .build()

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var cd: CdStream? = null
    private var drive: UsbScsiDrive? = null
    private var leadOut = 0
    private var cdSpeedX = 4

    init {
        scope.launch {
            while (true) {
                delay(400)
                val c = cd
                val m = mp
                if (c != null) {
                    positionMs = c.positionMs()
                } else if (m != null && prepared) {
                    positionMs = runCatching { m.currentPosition.toLong() }.getOrDefault(positionMs)
                }
            }
        }
    }

    fun playFiles(items: List<PlayItem>, start: Int) {
        if (items.isEmpty()) return
        halt()
        drive = null
        setQueue(items, start.coerceIn(0, items.lastIndex))
        startCurrent(0, true)
    }

    private fun setQueue(items: List<PlayItem>, start: Int) {
        original = items
        if (shuffle) {
            val first = items[start]
            queue = listOf(first) + (items - first).shuffled()
            index = 0
        } else {
            queue = items
            index = start
        }
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        publish()
    }

    fun toggleShuffle() {
        val cur = current
        shuffle = !shuffle
        if (cur != null && original.isNotEmpty()) {
            if (shuffle) {
                queue = listOf(cur) + (original - cur).shuffled()
                index = 0
            } else {
                queue = original
                index = original.indexOf(cur).coerceAtLeast(0)
            }
        }
        publish()
    }

    /** ジャケット画像(通知・ロック画面用) */
    fun setArt(b: Bitmap?) {
        art = b
        publish()
    }

    private fun publish() {
        val cur = current
        if (cur == null) {
            runCatching { session.isActive = false }
            return
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs, if (isPlaying) 1f else 0f,
                )
                .build(),
        )
        val mb = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, cur.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, cur.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, cur.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        art?.let { mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(mb.build())
        runCatching { session.isActive = true }
    }

    private fun changed() {
        if (isPlaying && !noisyRegistered) {
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    ctx, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            noisyRegistered = true
        } else if (!isPlaying && noisyRegistered) {
            runCatching { ctx.unregisterReceiver(noisy) }
            noisyRegistered = false
        }
        publish()
        onStateChanged?.invoke()
    }

    fun playCd(d: UsbScsiDrive, lo: Int, items: List<PlayItem>, start: Int, speedX: Int) {
        if (items.isEmpty()) return
        halt()
        drive = d
        leadOut = lo
        cdSpeedX = speedX.coerceIn(1, 52)
        setQueue(items, start.coerceIn(0, items.lastIndex))
        startCurrent(0, true)
    }

    private fun startCurrent(fromMs: Long, play: Boolean) {
        val item = current ?: return
        halt()
        if (play) am.requestAudioFocus(focus)
        durationMs = item.durationMs
        positionMs = fromMs
        isPlaying = play
        val t = item.cdTrack
        val d = drive
        if (t != null && d != null) {
            val c = CdStream(d, t, (fromMs * 75 / 1000).toInt(), !play)
            cd = c
            c.start()
        } else if (item.uri != null) {
            startFile(item.uri, fromMs)
        } else {
            isPlaying = false
        }
        changed()
    }

    private fun startFile(uri: Uri, fromMs: Long) {
        val p = MediaPlayer()
        prepared = false
        try {
            p.setAudioAttributes(attrs)
            p.setDataSource(ctx, uri)
            p.setOnPreparedListener {
                if (mp !== it) return@setOnPreparedListener
                prepared = true
                durationMs = it.duration.toLong()
                if (fromMs > 0) it.seekTo(fromMs.toInt())
                if (isPlaying) it.start()
                publish()
            }
            p.setOnCompletionListener { if (mp === it) next(true) }
            p.setOnErrorListener { mpx, _, _ ->
                if (mp === mpx) main.post { next(true) }
                true
            }
            mp = p
            p.prepareAsync()
        } catch (e: Exception) {
            runCatching { p.release() }
            mp = null
            main.post { next(true) }
        }
    }

    fun toggle() { if (isPlaying) pause() else resume() }

    fun pause() {
        if (!isPlaying) return
        mp?.let { if (prepared) runCatching { it.pause() } }
        cd?.pauseAudio()
        isPlaying = false
        changed()
    }

    fun resume() {
        if (current == null) return
        am.requestAudioFocus(focus)
        val m = mp
        val c = cd
        when {
            m != null -> { if (prepared) runCatching { m.start() } }
            c != null -> c.resumeAudio()
            else -> { startCurrent(positionMs, true); return }
        }
        isPlaying = true
        changed()
    }

    fun next(auto: Boolean = false) {
        if (auto && repeatMode == 2) {
            startCurrent(0, true)
        } else if (index < queue.lastIndex) {
            val play = isPlaying || auto
            index++
            startCurrent(0, play)
        } else if (repeatMode == 1 && queue.isNotEmpty()) {
            val play = isPlaying || auto
            index = 0
            startCurrent(0, play)
        } else if (auto) {
            halt()
            isPlaying = false
            positionMs = 0
            changed()
        }
    }

    fun prev() {
        if (positionMs > 3000 || index <= 0) { seek(0); return }
        val play = isPlaying
        index--
        startCurrent(0, play)
    }

    fun seek(ms: Long) {
        val m = mp
        if (m != null) {
            if (prepared) runCatching { m.seekTo(ms.toInt()) }
            positionMs = ms
            publish()
            return
        }
        if (current != null) startCurrent(ms.coerceAtLeast(0), isPlaying)
    }

    fun stop() {
        halt()
        queue = emptyList()
        original = emptyList()
        index = -1
        isPlaying = false
        positionMs = 0
        durationMs = 0
        runCatching { am.abandonAudioFocusRequest(focus) }
        changed()
    }

    fun release() {
        stop()
        runCatching { session.release() }
    }

    private fun halt() {
        mp?.let { runCatching { it.release() } }
        mp = null
        prepared = false
        cd?.halt()
        cd = null
    }

    private inner class CdStream(
        val d: UsbScsiDrive,
        val track: TocTrack,
        val baseSector: Int,
        val startPaused: Boolean,
    ) : Thread("cd-play") {
        @Volatile private var stopFlag = false
        private val at: AudioTrack

        init {
            val min = AudioTrack.getMinBufferSize(44100, AFormat.CHANNEL_OUT_STEREO, AFormat.ENCODING_PCM_16BIT)
            at = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AFormat.Builder()
                        .setSampleRate(44100)
                        .setChannelMask(AFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, 2352 * 75))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }

        fun positionMs(): Long =
            runCatching { (baseSector * 588L + at.playbackHeadPosition.toLong()) * 1000L / 44100L }
                .getOrDefault(baseSector * 1000L / 75)

        fun pauseAudio() { runCatching { at.pause() } }
        fun resumeAudio() { runCatching { at.play() } }

        fun halt() {
            stopFlag = true
            runCatching { at.pause() }
            runCatching { at.flush() }
        }

        private fun readSafe(lba: Int, n: Int): ByteArray {
            for (a in 0 until 2) {
                try {
                    val r = d.readCd(lba, n, false)
                    if (r.size >= n * 2352) return r
                } catch (e: Exception) {
                    if (stopFlag) break
                }
            }
            return ByteArray(n * 2352)
        }

        override fun run() {
            runCatching { d.setSpeed(cdSpeedX * 176) }
            val end = if (leadOut > 0) minOf(track.endLba, leadOut) else track.endLba
            var lba = track.startLba + baseSector
            var written = 0L
            try {
                if (!startPaused) at.play()
                while (!stopFlag && lba < end) {
                    val n = minOf(27, end - lba)
                    val data = readSafe(lba, n)
                    var off = 0
                    while (!stopFlag && off < data.size) {
                        val w = at.write(data, off, data.size - off, AudioTrack.WRITE_NON_BLOCKING)
                        if (w < 0) { stopFlag = true; break }
                        if (w == 0) Thread.sleep(10) else off += w
                    }
                    written += n * 588L
                    lba += n
                }
                while (!stopFlag && at.playbackHeadPosition.toLong() < written) Thread.sleep(50)
                if (!stopFlag) main.post { if (cd === this) next(true) }
            } catch (e: Exception) {
                // 停止・切断時
            } finally {
                runCatching { at.stop() }
                runCatching { at.release() }
            }
        }
    }
}
