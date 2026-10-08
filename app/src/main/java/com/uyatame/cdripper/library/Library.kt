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

private val AUDIO_EXT = setOf("flac", "wav", "m4a", "mp3", "ogg", "opus", "aac")
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

    fun scan(tree: Uri, old: List<LibTrack>, progress: (Int, Int) -> Unit): List<LibTrack> {
        val found = ArrayList<Triple<Uri, String, Long>>()
        val names = HashMap<String, String>()
        val covers = HashMap<String, String>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), 0, found, names, covers)
        val oldMap = old.associateBy { it.uri }
        val out = ArrayList<LibTrack>()
        found.forEachIndexed { i, (uri, folder, mod) ->
            progress(i + 1, found.size)
            val key = uri.toString()
            val o = oldMap[key]
            if (o != null && o.modified == mod && mod != 0L) {
                out.add(o.copy(coverUri = covers[folder]))
            } else {
                out.add(readTags(uri, names[key] ?: "", folder, covers[folder], mod))
            }
        }
        return out
    }

    private fun walk(
        tree: Uri,
        docId: String,
        depth: Int,
        found: MutableList<Triple<Uri, String, Long>>,
        names: MutableMap<String, String>,
        covers: MutableMap<String, String>,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = ArrayList<String>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                val mod = if (c.isNull(3)) 0L else c.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { dirs.add(id); continue }
                val lower = name.lowercase()
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (lower.substringAfterLast('.', "") in AUDIO_EXT) {
                    found.add(Triple(uri, docId, mod))
                    names[uri.toString()] = name
                } else if (lower in COVER_NAMES) {
                    covers[docId] = uri.toString()
                }
            }
        }
        if (depth < 8) for (d in dirs) walk(tree, d, depth + 1, found, names, covers)
    }

    private fun readTags(uri: Uri, name: String, folder: String, cover: String?, mod: Long): LibTrack {
        val base = name.substringBeforeLast('.')
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
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
        if (trackUri != null) {
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
