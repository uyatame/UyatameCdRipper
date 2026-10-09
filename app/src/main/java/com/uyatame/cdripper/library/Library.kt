package com.uyatame.cdripper.library

import com.uyatame.cdripper.T

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val track: Int,
    val disc: Int,
    val durationMs: Long,
    val folderId: String,
    val coverUri: String?,
    val modified: Long,
    val year: String = "",
)

data class LibAlbum(val key: String, val title: String, val artist: String, val tracks: List<LibTrack>) {
    val first: LibTrack get() = tracks.first()
}

val UNKNOWN_ALBUM: String get() = T("不明なアルバム", "Unknown album")
val UNKNOWN_ARTIST: String get() = T("不明なアーティスト", "Unknown artist")

private val AUDIO_EXT = setOf("flac", "wav", "m4a", "mp3", "ogg", "opus", "aac", "dsf", "dff")
private val COVER_NAMES = setOf("cover.jpg", "cover.png", "folder.jpg", "front.jpg")

/** 保存先フォルダ内の音楽ファイルを読み取り、アルバム単位にまとめる */
class Library(private val ctx: Context) {
    private val cacheFile = File(ctx.filesDir, "library.json")

    fun loadCache(): List<LibTrack> = try {
        if (!cacheFile.exists()) emptyList() else {
            val a = JSONArray(cacheFile.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LibTrack(
                    o.getString("u"), o.optString("t"), o.optString("a"), o.optString("al"), o.optString("aa"),
                    o.optInt("n"), o.optInt("d", 1), o.optLong("ms"), o.optString("f"),
                    if (o.has("c")) o.getString("c") else null, o.optLong("m"), o.optString("y"),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveCache(list: List<LibTrack>) {
        val a = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("u", t.uri); o.put("t", t.title); o.put("a", t.artist); o.put("al", t.album); o.put("aa", t.albumArtist)
            o.put("n", t.track); o.put("d", t.disc); o.put("ms", t.durationMs); o.put("f", t.folderId); o.put("m", t.modified)
            if (t.coverUri != null) o.put("c", t.coverUri)
            o.put("y", t.year)
            a.put(o)
        }
        runCatching { cacheFile.writeText(a.toString()) }
    }

    /** 同時に処理する数(フォルダの一覧取得・タグの読み込み) */
    private val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /**
     * フォルダ内の曲を一覧にする。読み込み済み(前回から変わっていない)の曲はそのまま使い、
     * 新しく入った曲・書き換えられた曲だけタグを読む。消えた曲は一覧から外れる。
     * フォルダの一覧取得とタグの読み込みは、複数のスレッドで同時に行う。
     * progress は「タグを読む曲」の件数で数える。
     */
    fun scan(tree: Uri, old: List<LibTrack>, progress: (Int, Int) -> Unit): List<LibTrack> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
        try {
            val found = java.util.Collections.synchronizedList(ArrayList<Triple<Uri, String, Long>>())
            val names = java.util.concurrent.ConcurrentHashMap<String, String>()
            val covers = java.util.concurrent.ConcurrentHashMap<String, String>()
            // フォルダを階層ごとにまとめて、同時に一覧を取る
            var level = listOf(DocumentsContract.getTreeDocumentId(tree))
            var depth = 0
            while (level.isNotEmpty() && depth <= 8) {
                val next = java.util.Collections.synchronizedList(ArrayList<String>())
                pool.invokeAll(level.map { id ->
                    java.util.concurrent.Callable { list(tree, id, found, names, covers, next) }
                })
                level = next.toList()
                depth++
            }
            val oldMap = old.associateBy { it.uri }
            val all = found.toList()
            val need = all.filter { (uri, _, mod) -> oldMap[uri.toString()]?.modified != mod }
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val read = java.util.concurrent.ConcurrentHashMap<String, LibTrack>()
            pool.invokeAll(need.map { (uri, folder, mod) ->
                java.util.concurrent.Callable {
                    val key = uri.toString()
                    read[key] = readTags(uri, names[key] ?: "", folder, covers[folder], mod)
                    val n = done.incrementAndGet()
                    // 表示の更新は間引く
                    if (n == need.size || n % 5 == 0) progress(n, need.size)
                    Unit
                }
            })
            return all.mapNotNull { (uri, folder, _) ->
                val key = uri.toString()
                read[key] ?: oldMap[key]?.copy(coverUri = covers[folder])
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 1 つのフォルダの中身を一覧にする(サブフォルダは next に入れる) */
    private fun list(
        tree: Uri,
        docId: String,
        found: MutableList<Triple<Uri, String, Long>>,
        names: MutableMap<String, String>,
        covers: MutableMap<String, String>,
        next: MutableList<String>,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        runCatching {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    val mod = if (c.isNull(3)) 0L else c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { next.add(id); continue }
                    val lower = name.lowercase()
                    if (lower.substringAfterLast('.', "") in AUDIO_EXT) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found.add(Triple(uri, docId, mod))
                        names[uri.toString()] = name
                    } else if (lower in COVER_NAMES) {
                        covers[docId] = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    }
                }
            }
        }
    }

    /** 足りない分を読み切る */
    private fun readFully(ins: java.io.InputStream, b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val r = ins.read(b, off, b.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /**
     * FLAC はファイルの先頭(STREAMINFO と VORBIS_COMMENT)だけを自前で読む。
     * Android 標準の読み取り機能より大幅に速い。読めなければ null。
     */
    private fun readFlacTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack? {
        val raw = runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        return runCatching {
            raw.use { r0 ->
                val ins = java.io.BufferedInputStream(r0, 1 shl 16)
                val head = ByteArray(4)
                if (readFully(ins, head) != 4 || String(head, Charsets.ISO_8859_1) != "fLaC") return null
                var rate = 0
                var samples = 0L
                val tags = HashMap<String, String>()
                while (true) {
                    val bh = ByteArray(4)
                    if (readFully(ins, bh) != 4) break
                    val last = (bh[0].toInt() and 0x80) != 0
                    val type = bh[0].toInt() and 0x7F
                    val len = ((bh[1].toInt() and 0xFF) shl 16) or ((bh[2].toInt() and 0xFF) shl 8) or (bh[3].toInt() and 0xFF)
                    if (type == 0 || type == 4) {
                        val b = ByteArray(len)
                        if (readFully(ins, b) != len) break
                        if (type == 0 && len >= 18) {
                            fun u(i: Int) = b[i].toLong() and 0xFF
                            rate = ((u(10) shl 12) or (u(11) shl 4) or (u(12) shr 4)).toInt()
                            samples = ((u(13) and 0x0F) shl 32) or (u(14) shl 24) or (u(15) shl 16) or (u(16) shl 8) or u(17)
                        } else if (type == 4) {
                            val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val vlen = bb.int
                            bb.position(bb.position() + vlen)
                            val n = bb.int
                            for (k in 0 until n) {
                                if (bb.remaining() < 4) break
                                val l = bb.int
                                if (l < 0 || l > bb.remaining()) break
                                val sArr = ByteArray(l)
                                bb.get(sArr)
                                val c = String(sArr, Charsets.UTF_8)
                                val eq = c.indexOf('=')
                                if (eq > 0) {
                                    val key = c.substring(0, eq).uppercase()
                                    if (key !in tags) tags[key] = c.substring(eq + 1).trim()
                                }
                            }
                        }
                    } else {
                        var left = len.toLong()
                        while (left > 0) {
                            val sk = ins.skip(left)
                            if (sk <= 0) break
                            left -= sk
                        }
                    }
                    if (last) break
                }
                val base = name.substringBeforeLast('.')
                fun t(k: String) = tags[k]?.takeIf { it.isNotEmpty() }
                LibTrack(
                    uri.toString(),
                    t("TITLE") ?: base,
                    t("ARTIST") ?: UNKNOWN_ARTIST,
                    t("ALBUM") ?: UNKNOWN_ALBUM,
                    t("ALBUMARTIST") ?: t("ALBUM ARTIST") ?: "",
                    t("TRACKNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: base.take(2).toIntOrNull() ?: 0,
                    t("DISCNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull() ?: 1,
                    if (rate > 0) samples * 1000 / rate else 0L,
                    folder, cover, mod,
                    (t("DATE") ?: t("YEAR"))?.take(4) ?: "",
                )
            }
        }.getOrNull()
    }

    /** DSD(DSF / DFF)は Android 標準の機能で読めないので、自前で読む */
    private fun readDsdTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        val base = name.substringBeforeLast('.')
        val r = com.uyatame.cdripper.player.dsd.DsdTags.read(ctx, uri)
        val t = r?.tags
        return LibTrack(
            uri.toString(),
            t?.title ?: base,
            t?.artist ?: UNKNOWN_ARTIST,
            t?.album ?: UNKNOWN_ALBUM,
            t?.albumArtist ?: "",
            t?.track ?: base.take(2).toIntOrNull() ?: 0,
            t?.disc ?: 1,
            r?.info?.durationMs ?: 0L,
            folder, cover, mod,
            t?.year ?: "",
        )
    }

    private fun readTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        if (com.uyatame.cdripper.player.dsd.DsdFile.isDsd(name)) return readDsdTags(uri, name, folder, cover, mod)
        if (name.lowercase().endsWith(".flac")) readFlacTags(uri, name, folder, cover, mod)?.let { return it }
        val base = name.substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        return try {
            // ファイル記述子を直接渡す方が速い
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) pfd.use { r.setDataSource(it.fileDescriptor) } else r.setDataSource(ctx, uri)
            fun m(k: Int): String? = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val tn = m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull()
                ?: base.take(2).toIntOrNull() ?: 0
            LibTrack(
                uri.toString(),
                m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: base,
                m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: UNKNOWN_ARTIST,
                m(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: UNKNOWN_ALBUM,
                m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "",
                tn,
                m(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull() ?: 1,
                m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                folder, cover, mod,
                m(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: m(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4) ?: "",
            )
        } catch (e: Exception) {
            LibTrack(uri.toString(), base, UNKNOWN_ARTIST, UNKNOWN_ALBUM, "", base.take(2).toIntOrNull() ?: 0, 1, 0L, folder, cover, mod)
        } finally {
            runCatching { r.release() }
        }
    }

    companion object {
        fun group(tracks: List<LibTrack>): List<LibAlbum> =
            tracks.groupBy { t ->
                if (t.album == UNKNOWN_ALBUM || t.album == "不明なアルバム" || t.album == "Unknown album") "f:" + t.folderId
                else "a:" + t.albumArtist.ifEmpty { t.artist } + "\u0000" + t.album
            }.map { (k, list) ->
                val sorted = list.sortedWith(compareBy({ it.disc }, { it.track }, { it.title }))
                val f = sorted.first()
                val artist = f.albumArtist.ifEmpty {
                    if (sorted.all { it.artist == f.artist }) f.artist else T("さまざまなアーティスト", "Various artists")
                }
                LibAlbum(k, f.album, artist, sorted)
            }.sortedBy { it.artist.lowercase() + "\u0000" + it.title.lowercase() }
    }
}

/** ジャケット画像の読み込み(埋め込み画像 → フォルダのcover.jpg の順) */
object ArtLoader {
    private val cache = LruCache<String, ImageBitmap>(60)
    private val misses = HashSet<String>()

    /** 画像が差し替わったことを画面に知らせるための番号(増えると表示中の画像を読み直す) */
    var generation by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun peek(key: String?): ImageBitmap? = key?.let { cache.get(it) }

    fun load(ctx: Context, key: String, trackUri: String?, coverUri: String?, size: Int = 512): ImageBitmap? {
        cache.get(key)?.let { return it }
        synchronized(misses) { if (key in misses) return null }
        var bytes: ByteArray? = null
        if (trackUri != null && com.uyatame.cdripper.player.dsd.DsdFile.isDsd(Uri.decode(trackUri))) {
            bytes = runCatching { com.uyatame.cdripper.player.dsd.DsdTags.picture(ctx, Uri.parse(trackUri)) }.getOrNull()
        } else if (trackUri != null) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, Uri.parse(trackUri))
                bytes = r.embeddedPicture
            } catch (e: Exception) {
            } finally {
                runCatching { r.release() }
            }
        }
        if (bytes == null && coverUri != null) {
            bytes = runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(coverUri))?.use { it.readBytes() }
            }.getOrNull()
        }
        val img = bytes?.let { decode(it, size) }?.asImageBitmap()
        if (img == null) {
            synchronized(misses) { misses.add(key) }
            return null
        }
        cache.put(key, img)
        return img
    }

    fun forget(key: String) {
        cache.remove(key)
        synchronized(misses) { misses.remove(key) }
        generation++
    }

    fun clearMisses() {
        synchronized(misses) { misses.clear() }
        generation++
    }

    fun decode(b: ByteArray, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
