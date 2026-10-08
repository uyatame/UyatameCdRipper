package com.uyatame.cdripper.data

import com.uyatame.cdripper.T

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

enum class AudioFormat(val label: String, val ext: String, val mime: String) {
    WAV("WAV", "wav", "audio/x-wav"),
    FLAC("FLAC", "flac", "audio/flac"),
    AAC("AAC", "m4a", "audio/mp4"),
    MP3("MP3", "mp3", "audio/mpeg"),
}

val MP3_RATES = listOf(128, 160, 192, 224, 256, 320)

class QualityPreset(
    val title: String,
    val desc: String,
    val format: AudioFormat,
    val flacLevel: Int,
    val aacKbps: Int,
    val mp3Kbps: Int = 320,
)

val QUALITY_PRESETS: List<QualityPreset> get() = listOf(
    QualityPreset(T("ロスレス(FLAC)", "Lossless (FLAC)"), T("CDと同じ音質のまま容量を約半分に。迷ったらこれ", "Same quality as the CD at about half the size. Recommended"), AudioFormat.FLAC, 5, 256),
    QualityPreset(T("高音質(AAC 320kbps)", "High quality (AAC 320 kbps)"), T("聴き分けが難しいほどの高音質。1曲 約10MB", "Nearly indistinguishable from the CD. About 10 MB per song"), AudioFormat.AAC, 5, 320),
    QualityPreset(T("標準(AAC 256kbps)", "Standard (AAC 256 kbps)"), T("音質と容量のバランス型。1曲 約8MB", "Balanced quality and size. About 8 MB per song"), AudioFormat.AAC, 5, 256),
    QualityPreset(T("互換性重視(MP3 320kbps)", "Compatible (MP3 320 kbps)"), T("古いプレーヤーやカーオーディオでも再生しやすい形式。1曲 約10MB", "Plays on older players and car audio. About 10 MB per song"), AudioFormat.MP3, 5, 256, 320),
    QualityPreset(T("省容量(AAC 128kbps)", "Small (AAC 128 kbps)"), T("容量を優先。1曲 約4MB", "Prioritizes size. About 4 MB per song"), AudioFormat.AAC, 5, 128),
    QualityPreset(T("無圧縮(WAV)", "Uncompressed (WAV)"), T("編集向け。容量が大きく、曲情報は保存されません", "For editing. Large files, no track info"), AudioFormat.WAV, 5, 256),
)

data class AppSettings(
    val format: AudioFormat = AudioFormat.FLAC,
    val flacLevel: Int = 5,
    val aacKbps: Int = 256,
    val mp3Kbps: Int = 320,
    val mp3Quality: Int = 2,
    val speedX: Int = 0,
    val sectorsPerRead: Int = 13,
    val retries: Int = 5,
    val useC2: Boolean = false,
    val abortOnError: Boolean = false,
    val offsetSamples: Int = 0,
    val ejectAfter: Boolean = false,
    val outputUri: String? = null,
    val libraryFolders: Set<String> = emptySet(),
    val folderMode: Int = 0,
    val fileNameMode: Int = 0,
    val autoMeta: Boolean = true,
    val useGnudb: Boolean = true,
    val contactEmail: String = "",
    val autoOpenDisc: Boolean = true,
    val saveCover: Boolean = true,
    val embedCover: Boolean = true,
    val cdPlaySpeed: Int = 4,
    val keepScreenOn: Boolean = true,
    val themeMode: Int = 0,
    val dynamicColor: Boolean = true,
    val ambientPlayer: Boolean = true,
    val libraryList: Boolean = false,
    val verboseLog: Boolean = false,
)

fun AppSettings.presetIndex(): Int = QUALITY_PRESETS.indexOfFirst { p ->
    p.format == format && when (format) {
        AudioFormat.FLAC -> p.flacLevel == flacLevel
        AudioFormat.AAC -> p.aacKbps == aacKbps
        AudioFormat.MP3 -> p.mp3Kbps == mp3Kbps
        AudioFormat.WAV -> true
    }
}

fun AppSettings.qualityLabel(): String = when (format) {
    AudioFormat.FLAC -> T("FLAC ロスレス", "FLAC lossless")
    AudioFormat.AAC -> "AAC ${aacKbps}kbps"
    AudioFormat.MP3 -> "MP3 ${mp3Kbps}kbps"
    AudioFormat.WAV -> T("WAV 無圧縮", "WAV uncompressed")
}

object Keys {
    val format = intPreferencesKey("format")
    val flacLevel = intPreferencesKey("flacLevel")
    val aacKbps = intPreferencesKey("aacKbps")
    val mp3Kbps = intPreferencesKey("mp3Kbps")
    val mp3Quality = intPreferencesKey("mp3Quality")
    val speedX = intPreferencesKey("speedX")
    val sectorsPerRead = intPreferencesKey("sectorsPerRead")
    val retries = intPreferencesKey("retries")
    val useC2 = booleanPreferencesKey("useC2")
    val abortOnError = booleanPreferencesKey("abortOnError")
    val offsetSamples = intPreferencesKey("offsetSamples")
    val ejectAfter = booleanPreferencesKey("ejectAfter")
    val outputUri = stringPreferencesKey("outputUri")
    val libraryFolders = stringSetPreferencesKey("libraryFolders")
    val folderMode = intPreferencesKey("folderMode")
    val fileNameMode = intPreferencesKey("fileNameMode")
    val autoMeta = booleanPreferencesKey("autoMeta")
    val useGnudb = booleanPreferencesKey("useGnudb")
    val contactEmail = stringPreferencesKey("contactEmail")
    val autoOpenDisc = booleanPreferencesKey("autoOpenDisc")
    val saveCover = booleanPreferencesKey("saveCover")
    val embedCover = booleanPreferencesKey("embedCover")
    val cdPlaySpeed = intPreferencesKey("cdPlaySpeed")
    val keepScreenOn = booleanPreferencesKey("keepScreenOn")
    val themeMode = intPreferencesKey("themeMode")
    val dynamicColor = booleanPreferencesKey("dynamicColor")
    val ambientPlayer = booleanPreferencesKey("ambientPlayer")
    val libraryList = booleanPreferencesKey("libraryList")
    val verboseLog = booleanPreferencesKey("verboseLog")
}

class SettingsRepository(private val context: Context) {
    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            format = AudioFormat.entries.getOrElse(p[Keys.format] ?: d.format.ordinal) { d.format },
            flacLevel = p[Keys.flacLevel] ?: d.flacLevel,
            aacKbps = p[Keys.aacKbps] ?: d.aacKbps,
            mp3Kbps = p[Keys.mp3Kbps] ?: d.mp3Kbps,
            mp3Quality = p[Keys.mp3Quality] ?: d.mp3Quality,
            speedX = p[Keys.speedX] ?: d.speedX,
            sectorsPerRead = p[Keys.sectorsPerRead] ?: d.sectorsPerRead,
            retries = p[Keys.retries] ?: d.retries,
            useC2 = p[Keys.useC2] ?: d.useC2,
            abortOnError = p[Keys.abortOnError] ?: d.abortOnError,
            offsetSamples = p[Keys.offsetSamples] ?: d.offsetSamples,
            ejectAfter = p[Keys.ejectAfter] ?: d.ejectAfter,
            outputUri = p[Keys.outputUri],
            libraryFolders = p[Keys.libraryFolders] ?: emptySet(),
            folderMode = p[Keys.folderMode] ?: d.folderMode,
            fileNameMode = p[Keys.fileNameMode] ?: d.fileNameMode,
            autoMeta = p[Keys.autoMeta] ?: d.autoMeta,
            useGnudb = p[Keys.useGnudb] ?: d.useGnudb,
            contactEmail = p[Keys.contactEmail] ?: d.contactEmail,
            autoOpenDisc = p[Keys.autoOpenDisc] ?: d.autoOpenDisc,
            saveCover = p[Keys.saveCover] ?: d.saveCover,
            embedCover = p[Keys.embedCover] ?: d.embedCover,
            cdPlaySpeed = p[Keys.cdPlaySpeed] ?: d.cdPlaySpeed,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            themeMode = p[Keys.themeMode] ?: d.themeMode,
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
            ambientPlayer = p[Keys.ambientPlayer] ?: d.ambientPlayer,
            libraryList = p[Keys.libraryList] ?: d.libraryList,
            verboseLog = p[Keys.verboseLog] ?: d.verboseLog,
        )
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun resetDrive() {
        context.dataStore.edit {
            it.remove(Keys.speedX); it.remove(Keys.retries); it.remove(Keys.abortOnError)
            it.remove(Keys.offsetSamples); it.remove(Keys.sectorsPerRead); it.remove(Keys.useC2); it.remove(Keys.cdPlaySpeed)
        }
    }

    suspend fun updateFolders(f: (Set<String>) -> Set<String>): Set<String> {
        var out = emptySet<String>()
        context.dataStore.edit {
            out = f(it[Keys.libraryFolders] ?: emptySet())
            it[Keys.libraryFolders] = out
        }
        return out
    }

    suspend fun setPreset(p: QualityPreset) {
        context.dataStore.edit {
            it[Keys.format] = p.format.ordinal
            it[Keys.flacLevel] = p.flacLevel
            it[Keys.aacKbps] = p.aacKbps
            it[Keys.mp3Kbps] = p.mp3Kbps
        }
    }
}
