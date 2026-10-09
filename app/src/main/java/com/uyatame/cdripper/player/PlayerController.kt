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
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uyatame.cdripper.T
import com.uyatame.cdripper.usb.TocTrack
import com.uyatame.cdripper.usb.UsbScsiDrive
import com.uyatame.cdripper.player.usb.UsbDac
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
 * ファイル(OS内蔵デコーダで PCM に変換)と CD(READ CD で読んだ PCM)の両方を、
 * AudioOutput(イコライザー / ビットパーフェクト)を通して再生する。
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
        override fun onReceive(c: Context, i: Intent) {
            // USB DAC を直接使うときは、Android から DAC が消えたように見えるため無視する
            if (UsbDac.active || android.os.SystemClock.elapsedRealtime() < UsbDac.ignoreNoisyUntil) return
            pause()
        }
    }
    private var noisyRegistered = false

    val current: PlayItem? get() = queue.getOrNull(index)
    val isCd: Boolean get() = current?.cdTrack != null
    /** 再生中の曲が DSD か */
    val isDsd: Boolean get() = current?.uri?.let { isDsdUri(it) } == true

    private fun isDsdUri(uri: Uri): Boolean {
        val s = Uri.decode(uri.toString())
        if (com.uyatame.cdripper.player.dsd.DsdFile.isDsd(s)) return true
        // 拡張子が分かるなら、ファイル名の問い合わせはしない
        if (s.substringAfterLast('/').contains('.')) return false
        val name = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.name }.getOrNull()
        return name != null && com.uyatame.cdripper.player.dsd.DsdFile.isDsd(name)
    }

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

    private var file: PlayStream? = null
    private var cd: CdStream? = null
    private var drive: UsbScsiDrive? = null
    private var leadOut = 0
    private var cdSpeedX = 4

    private var volProvider: android.media.VolumeProvider? = null

    /** USB DAC 直接出力中は、音量キーで DAC の音量を変える */
    private fun setVolumeRouting(active: Boolean) {
        if (active) {
            val vp = object : android.media.VolumeProvider(android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, UsbDac.volume()) {
                override fun onSetVolumeTo(volume: Int) { setUsbVolume(volume, true) }
                override fun onAdjustVolume(direction: Int) { setUsbVolume(UsbDac.volume() + direction * 4, true) }
            }
            volProvider = vp
            runCatching { session.setPlaybackToRemote(vp) }
        } else {
            volProvider = null
            runCatching { session.setPlaybackToLocal(attrs) }
        }
    }

    /** DAC の音量(0〜100)を変える。fromKeys なら設定にも保存する */
    fun setUsbVolume(v: Int, fromKeys: Boolean = false) {
        // -1 は自動(DAC に合わせた初期値)
        val x = if (v < 0) -1 else v.coerceIn(0, 100)
        UsbDac.setVolume(x)
        volProvider?.currentVolume = UsbDac.volume()
        if (fromKeys) AudioEngine.onUsbVolume?.invoke(x)
    }

    init {
        UsbDac.init(ctx)
        UsbDac.onChanged = { main.post { if (AudioEngine.wantUsbDirect && current != null) reloadOutput() } }
        UsbDac.onDetached = {
            main.post {
                if (current != null) {
                    pause()
                    reloadOutput()
                }
            }
        }
        UsbDac.onActiveChanged = { a -> setVolumeRouting(a) }
        scope.launch {
            while (true) {
                delay(400)
                val c = cd
                val f = file
                if (c != null) positionMs = c.positionMs()
                else if (f != null) positionMs = f.positionMs()
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
        val f: PlayStream = if (isDsdUri(uri)) DsdStream(uri, fromMs, !isPlaying)
        else FileStream(uri, fromMs, !isPlaying)
        file = f
        f.start()
    }

    /** 音響設定(ビットパーフェクトの切り替えなど)を反映するため、今の位置から再生し直す */
    fun reloadOutput() {
        if (current == null) {
            AudioEngine.releaseUnused(am, attrs)
            return
        }
        AudioEngine.releaseUnused(am, attrs)
        startCurrent(positionMs, isPlaying)
    }

    fun toggle() { if (isPlaying) pause() else resume() }

    fun pause() {
        if (!isPlaying) return
        file?.pauseAudio()
        cd?.pauseAudio()
        isPlaying = false
        changed()
    }

    fun resume() {
        if (current == null) return
        am.requestAudioFocus(focus)
        val f = file
        val c = cd
        when {
            f != null && f.isAlive -> f.resumeAudio()
            c != null && c.isAlive -> c.resumeAudio()
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
        AudioEngine.release(am, attrs)
        changed()
    }

    fun release() {
        stop()
        runCatching { session.release() }
    }

    private fun halt() {
        file?.halt()
        file = null
        cd?.halt()
        cd = null
    }

    /** 端末内のファイルを、OS内蔵のデコーダで PCM にして再生する */
    /** ファイル再生のスレッド(PCM と DSD で共通の操作) */
    private abstract inner class PlayStream(name: String) : Thread(name) {
        abstract fun positionMs(): Long
        abstract fun pauseAudio()
        abstract fun resumeAudio()
        abstract fun halt()
    }

    /** DSD(DSF / DFF)の再生。ネイティブ DSD → DoP → PCM 変換の順に使える方法で出力する */
    private inner class DsdStream(
        val uri: Uri,
        val fromMs: Long,
        startPaused: Boolean,
    ) : PlayStream("dsd-play") {
        @Volatile private var stopFlag = false
        @Volatile private var sink: com.uyatame.cdripper.player.dsd.DsdSink? = null
        @Volatile private var paused = startPaused
        @Volatile private var baseMs = fromMs

        override fun positionMs(): Long {
            val s = sink ?: return baseMs
            return baseMs + s.headFrames() * 1000L / s.sampleRate.coerceAtLeast(1)
        }

        override fun pauseAudio() { paused = true; sink?.pause() }
        override fun resumeAudio() { paused = false; sink?.play() }

        override fun halt() {
            stopFlag = true
            sink?.pause()
        }

        private fun openSink(info: com.uyatame.cdripper.player.dsd.DsdInfo, ch: Int): com.uyatame.cdripper.player.dsd.DsdSink {
            val mode = AudioEngine.dsdMode
            // 1) ネイティブ DSD(USB DAC 直接出力のとき)
            if (mode == 0 && AudioEngine.wantUsbDirect) {
                val f = runCatching { UsbDac.prepareDsd(info.rate, ch) }.getOrNull()
                if (f != null) return com.uyatame.cdripper.player.dsd.NativeDsdSink(f, ch, AudioEngine.dsdSwap)
            }
            // 2) DoP(加工なしで出せる出力先があるとき)
            if (mode != 2 && AudioEngine.wantUsbDirect && info.rate % 16 == 0) {
                val o = runCatching { AudioOutput(ctx, attrs, info.rate / 16, ch, 24, exact = true) }.getOrNull()
                if (o != null && o.exactOk) return com.uyatame.cdripper.player.dsd.DopSink(o, ch)
                o?.release()
                AudioEngine.log("dsd: DoP not available at ${info.rate / 16} Hz")
            }
            // 3) PCM に変換
            val conv = com.uyatame.cdripper.player.dsd.DsdToPcm(info.rate, ch)
            val o = AudioOutput(ctx, attrs, conv.outRate, ch, 24)
            return com.uyatame.cdripper.player.dsd.PcmSink(o, conv, ch, info.rate)
        }

        override fun run() {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            val rf = com.uyatame.cdripper.player.dsd.RandomFile.open(ctx, uri)
            if (rf == null) {
                AudioEngine.log("dsd: cannot open file")
                if (!stopFlag) main.post { if (file === this) next(true) }
                return
            }
            try {
                val info = com.uyatame.cdripper.player.dsd.DsdFile.parse(rf)
                if (info.compressed) throw java.io.IOException(T("DST 圧縮の DSDIFF には対応していません", "DST-compressed DSDIFF is not supported"))
                val d = info.durationMs
                main.post { if (file === this) { durationMs = d; publish() } }
                // 多チャンネルの場合は前方の左右だけを使う
                val ch = info.channels.coerceAtMost(2)
                val reader = com.uyatame.cdripper.player.dsd.DsdReader(rf, info)
                val start = (fromMs * info.rate / 8000L) and 7L.inv()
                reader.seek(start)
                baseMs = start * 8000L / info.rate
                val s = openSink(info, ch)
                sink = s
                if (stopFlag) return
                if (!paused) s.play()
                val how = when (s.label) {
                    "Native DSD" -> T("ネイティブ DSD", "Native DSD")
                    "DoP" -> "DoP"
                    else -> T("PCM に変換", "Converted to PCM") + " (${AudioEngine.khz(s.sampleRate)})"
                }
                AudioEngine.report(s.bitPerfect, info.label() + " · " + how)
                AudioEngine.log("dsd: ${info.label()} ${info.channels}ch ${if (info.dff) "DFF" else "DSF"} -> $how")
                val chunk = 8192
                val bufs = Array(info.channels) { ByteArray(chunk) }
                val use = Array(ch) { bufs[it] }
                while (!stopFlag) {
                    val n = reader.read(bufs, chunk)
                    if (n <= 0) break
                    s.write(use, n) { stopFlag }
                }
                while (!stopFlag && s.headFrames() < s.framesWritten) Thread.sleep(50)
                if (!stopFlag) main.post { if (file === this) next(true) }
            } catch (e: Exception) {
                AudioEngine.log("dsd: playback error: ${e.javaClass.simpleName}: ${e.message}")
                if (!stopFlag) main.post { if (file === this) next(true) }
            } finally {
                sink?.release()
                rf.close()
            }
        }
    }

    private inner class FileStream(
        val uri: Uri,
        val fromMs: Long,
        val startPaused: Boolean,
    ) : PlayStream("file-play") {
        @Volatile private var stopFlag = false
        @Volatile private var out: AudioOutput? = null
        @Volatile private var paused = startPaused
        @Volatile private var baseUs = fromMs * 1000

        override fun positionMs(): Long {
            val o = out ?: return baseUs / 1000
            return baseUs / 1000 + o.headFrames() * 1000L / o.sampleRate
        }

        override fun pauseAudio() { paused = true; out?.pause() }
        override fun resumeAudio() { paused = false; out?.play() }

        override fun halt() {
            stopFlag = true
            out?.pause()
        }

        /** WAV を直接読んで再生する。WAV として扱えなければ false(通常のデコードに任せる) */
        private fun playWav(): Boolean {
            val name = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, uri)?.name }.getOrNull()
            val looksWav = name?.lowercase()?.endsWith(".wav") == true ||
                runCatching { ctx.contentResolver.getType(uri) }.getOrNull()?.contains("wav") == true
            if (!looksWav) return false
            val raw = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull() ?: return false
            val inp = java.io.BufferedInputStream(raw, 1 shl 16)
            val w = runCatching { parseWav(inp) }.getOrNull()
            if (w == null) {
                runCatching { inp.close() }
                return false
            }
            try {
                val total = if (w.dataLen in 1 until 0xFFFFFFFFL) w.dataLen / w.align else Long.MAX_VALUE
                if (total != Long.MAX_VALUE) {
                    val d = total * 1000L / w.rate
                    main.post { if (file === this) { durationMs = d; publish() } }
                }
                AudioEngine.log("wav: ${w.rate} Hz ${w.channels}ch ${AudioEngine.encName(w.enc)} (direct)")
                val start = (fromMs * w.rate / 1000).coerceIn(0L, if (total == Long.MAX_VALUE) Long.MAX_VALUE else total)
                skipFully(inp, start * w.align)
                baseUs = start * 1_000_000L / w.rate
                val o = AudioOutput(ctx, attrs, w.rate, w.channels, w.bits)
                out = o
                if (!paused) o.play()
                val buf = ByteArray(w.align * (w.rate / 20).coerceAtLeast(256))
                val bb = java.nio.ByteBuffer.wrap(buf)
                var left = if (total == Long.MAX_VALUE) Long.MAX_VALUE else (total - start) * w.align
                while (!stopFlag && left > 0) {
                    val want = minOf(buf.size.toLong(), left).toInt()
                    val n = readFully(inp, buf, want)
                    val usable = n - n % w.align
                    if (usable <= 0) break
                    bb.clear()
                    bb.limit(usable)
                    o.write(bb, w.enc) { stopFlag }
                    if (left != Long.MAX_VALUE) left -= usable
                    if (n < want) break
                }
                while (!stopFlag && o.headFrames() < o.framesWritten) Thread.sleep(50)
                if (!stopFlag) main.post { if (file === this) next(true) }
            } catch (e: Exception) {
                AudioEngine.log("playback error: ${e.javaClass.simpleName}: ${e.message}")
                if (!stopFlag) main.post { if (file === this) next(true) }
            } finally {
                runCatching { inp.close() }
                out?.release()
            }
            return true
        }

        override fun run() {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            // WAV はデコーダーを通すと 16bit に丸められることがあるため、自前で読んでそのまま出力する
            if (playWav()) return
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                ex.setDataSource(ctx, uri, null)
                val ti = (0 until ex.trackCount).first {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ex.selectTrack(ti)
                val fmt = ex.getTrackFormat(ti)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    val d = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
                    main.post { if (file === this) { durationMs = d; publish() } }
                }
                val srcBits = AudioInfo.bitsOf(ctx, uri)
                if (fromMs > 0) ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // 24bit などの音源は、精度を落とさないよう float で受け取る
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                // audio/raw(WAV など)では KEY_PCM_ENCODING は「入力データの形式」を表すので書き換えない
                val raw = mime == MediaFormat.MIMETYPE_AUDIO_RAW
                val wantFloat = !raw && srcBits != 16 && !fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                if (wantFloat) fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AFormat.ENCODING_PCM_FLOAT)
                AudioEngine.log(
                    "decode: $mime ${runCatching { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(0)} Hz" +
                        " srcBits=$srcBits" + (if (wantFloat) " (float requested)" else ""),
                )
                try {
                    c.configure(fmt, null, null, 0)
                } catch (e: Exception) {
                    if (!wantFloat) throw e
                    fmt.removeKey(MediaFormat.KEY_PCM_ENCODING)
                    c.configure(fmt, null, null, 0)
                }
                c.start()
                AudioEngine.log("decoder: ${c.name}")
                val info = MediaCodec.BufferInfo()
                var inEos = false
                var outEos = false
                var outFmt: MediaFormat? = null
                var first = true
                while (!stopFlag && !outEos) {
                    if (!inEos) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val b = c.getInputBuffer(i)!!
                            val n = ex.readSampleData(b, 0)
                            if (n < 0) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inEos = true
                            } else {
                                c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFmt = c.outputFormat
                    } else if (o >= 0) {
                        val f = outFmt ?: c.outputFormat
                        if (out == null) {
                            val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val pe = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AFormat.ENCODING_PCM_16BIT
                            AudioEngine.log("decoded: $sr Hz ${ch}ch ${AudioEngine.encName(pe)}")
                            val created = AudioOutput(ctx, attrs, sr, ch, srcBits)
                            out = created
                            if (!paused) created.play()
                        }
                        if (first) {
                            baseUs = info.presentationTimeUs.coerceAtLeast(0)
                            first = false
                        }
                        val enc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AFormat.ENCODING_PCM_16BIT
                        if (info.size > 0) {
                            val bb = c.getOutputBuffer(o)!!
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            out?.write(bb, enc) { stopFlag }
                        }
                        c.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                    }
                }
                val o = out
                if (o != null) {
                    while (!stopFlag && o.headFrames() < o.framesWritten) Thread.sleep(50)
                }
                if (!stopFlag) main.post { if (file === this) next(true) }
            } catch (e: Exception) {
                AudioEngine.log("playback error: ${e.javaClass.simpleName}: ${e.message}")
                if (!stopFlag) main.post { if (file === this) next(true) }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { ex.release() }
                out?.release()
            }
        }
    }

    private class WavInfo(val enc: Int, val channels: Int, val rate: Int, val bits: Int, val align: Int, val dataLen: Long)

    private fun readFully(inp: java.io.InputStream, b: ByteArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun skipFully(inp: java.io.InputStream, n0: Long) {
        var n = n0
        val tmp = ByteArray(8192)
        while (n > 0) {
            val s = inp.skip(n)
            if (s > 0) { n -= s; continue }
            val r = inp.read(tmp, 0, minOf(tmp.size.toLong(), n).toInt())
            if (r < 0) return
            n -= r
        }
    }

    /** WAV のヘッダーを読み、data チャンクの先頭まで進める。扱えない形式なら null */
    private fun parseWav(inp: java.io.InputStream): WavInfo? {
        val iso = Charsets.ISO_8859_1
        fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, i: Int): Long = (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)
        val h = ByteArray(12)
        if (readFully(inp, h, 12) < 12) return null
        if (String(h, 0, 4, iso) != "RIFF" || String(h, 8, 4, iso) != "WAVE") return null
        var tag = 0; var ch = 0; var sr = 0; var bits = 0; var align = 0
        var haveFmt = false
        val ck = ByteArray(8)
        while (true) {
            if (readFully(inp, ck, 8) < 8) return null
            val id = String(ck, 0, 4, iso)
            val len = le32(ck, 4)
            when (id) {
                "fmt " -> {
                    if (len < 16 || len > 4096) return null
                    val f = ByteArray(len.toInt())
                    if (readFully(inp, f, f.size) < f.size) return null
                    if (len % 2 == 1L) skipFully(inp, 1)
                    tag = le16(f, 0); ch = le16(f, 2); sr = le32(f, 4).toInt(); align = le16(f, 12); bits = le16(f, 14)
                    if (tag == 0xFFFE && len >= 26) tag = le16(f, 24)
                    haveFmt = true
                }
                "data" -> {
                    if (!haveFmt) return null
                    val enc = when {
                        tag == 1 && bits == 8 -> AFormat.ENCODING_PCM_8BIT
                        tag == 1 && bits == 16 -> AFormat.ENCODING_PCM_16BIT
                        tag == 1 && bits == 24 -> AFormat.ENCODING_PCM_24BIT_PACKED
                        tag == 1 && bits == 32 -> AFormat.ENCODING_PCM_32BIT
                        tag == 3 && bits == 32 -> AFormat.ENCODING_PCM_FLOAT
                        else -> return null
                    }
                    if (ch !in 1..2 || sr <= 0 || align != ch * bits / 8) return null
                    return WavInfo(enc, ch, sr, bits, align, len)
                }
                else -> skipFully(inp, len + (len and 1L))
            }
        }
    }

    private inner class CdStream(
        val d: UsbScsiDrive,
        val track: TocTrack,
        val baseSector: Int,
        val startPaused: Boolean,
    ) : Thread("cd-play") {
        @Volatile private var stopFlag = false
        // 出力はスレッドの中で作る(USB DAC の準備で画面が止まらないように)
        @Volatile private var out: AudioOutput? = null
        @Volatile private var paused = startPaused

        fun positionMs(): Long = (baseSector * 588L + (out?.headFrames() ?: 0L)) * 1000L / 44100L

        fun pauseAudio() { paused = true; out?.pause() }
        fun resumeAudio() { paused = false; out?.play() }

        fun halt() {
            stopFlag = true
            out?.pause()
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
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            runCatching { d.setSpeed(cdSpeedX * 176) }
            val end = if (leadOut > 0) minOf(track.endLba, leadOut) else track.endLba
            var lba = track.startLba + baseSector
            try {
                val o = AudioOutput(ctx, attrs, 44100, 2, 16)
                out = o
                if (stopFlag) return
                if (!paused) o.play()
                while (!stopFlag && lba < end) {
                    val n = minOf(27, end - lba)
                    val data = readSafe(lba, n)
                    o.write(java.nio.ByteBuffer.wrap(data, 0, n * 2352), AFormat.ENCODING_PCM_16BIT) { stopFlag }
                    lba += n
                }
                while (!stopFlag && o.headFrames() < o.framesWritten) Thread.sleep(50)
                if (!stopFlag) main.post { if (cd === this) next(true) }
            } catch (e: Exception) {
                // 停止・切断時
            } finally {
                out?.release()
            }
        }
    }
}
