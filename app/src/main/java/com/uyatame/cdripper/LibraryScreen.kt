@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.uyatame.cdripper

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uyatame.cdripper.data.AppSettings
import com.uyatame.cdripper.library.LibAlbum
import com.uyatame.cdripper.tags.TagEditor
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.uyatame.cdripper.data.Keys
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.input.ImeAction
import com.uyatame.cdripper.library.UNKNOWN_ALBUM
import com.uyatame.cdripper.library.UNKNOWN_ARTIST
import com.uyatame.cdripper.meta.ReleaseCandidate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun LibraryScreen(vm: MainViewModel, s: AppSettings, albumKey: String?, onOpen: (String?) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addLibraryFolder(uri)
    }
    val hasFolders = s.outputUri != null || s.libraryFolders.isNotEmpty()
    // 曲情報を変えるとアルバムのまとまりが変わるので、新しい名前で開き直す
    LaunchedEffect(vm.albums, vm.pendingAlbumTitle, vm.tagSaving, vm.libScanning) {
        val t = vm.pendingAlbumTitle ?: return@LaunchedEffect
        if (vm.tagSaving || vm.libScanning) return@LaunchedEffect
        vm.albums.firstOrNull { it.title == t }?.let { onOpen(it.key) }
        vm.pendingAlbumTitle = null
    }
    val album = albumKey?.let { k -> vm.albums.firstOrNull { it.key == k } }
    if (album != null) {
        AlbumDetail(vm, album) { onOpen(null) }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val listView = s.libraryList
    val shown = remember(vm.albums, query, sort) {
        val q = query.trim().lowercase()
        val list = if (q.isEmpty()) vm.albums else vm.albums.filter { a ->
            a.title.lowercase().contains(q) || a.artist.lowercase().contains(q) ||
                a.tracks.any { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        }
        when (sort) {
            1 -> list.sortedBy { it.title.lowercase() }
            2 -> list.sortedByDescending { a -> a.tracks.maxOf { it.modified } }
            3 -> list.sortedWith(compareBy({ folderGroup(it).lowercase() }, { folderName(it).lowercase() }))
            else -> list
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("ライブラリ", "Library")) {
            IconButton({ vm.refreshLibrary() }, enabled = !vm.libScanning && hasFolders) {
                Icon(Icons.Filled.Refresh, T("ライブラリを更新", "Refresh library"))
            }
        }
        if (vm.libScanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text(
                T("曲を読み込み中 ${vm.libProgress}", "Loading songs ${vm.libProgress}"), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        when {
            vm.albums.isEmpty() && !vm.libScanning -> EmptyState(
                AppIcons.Library, T("まだ曲がありません", "No songs yet"),
                T(
                    "CDを取り込むと、ここに表示されます。端末にある音楽フォルダを追加して、その曲を再生することもできます",
                    "Ripped CDs appear here. You can also add a music folder from your device to play its songs",
                ),
                T("音楽フォルダを追加", "Add a music folder"),
            ) { picker.launch(null) }
            else -> {
                // 一覧に並べる項目(見出しとアルバム)
                val groups: List<Pair<String?, List<LibAlbum>>> =
                    if (sort == 3) shown.groupBy { folderGroup(it) }.toList() else listOf(null to shown)
                val labelOf: (LibAlbum) -> String = { a ->
                    when (sort) {
                        1 -> initialOf(a.title)
                        2 -> java.text.SimpleDateFormat("yyyy/MM", java.util.Locale.getDefault())
                            .format(java.util.Date(a.tracks.maxOf { it.modified }))
                        3 -> folderGroup(a).substringAfterLast('/').take(2)
                        else -> initialOf(a.artist)
                    }
                }
                // 高速スクロール用:各項目(先頭の検索欄=0番を含む)の頭文字
                val labels = remember(groups, sort) {
                    val l = ArrayList<String>()
                    l.add(groups.firstOrNull()?.second?.firstOrNull()?.let(labelOf) ?: "")
                    groups.forEach { (folder, list) ->
                        if (folder != null) l.add(list.firstOrNull()?.let(labelOf) ?: "")
                        list.forEach { l.add(labelOf(it)) }
                    }
                    l
                }
                val headerContent: @Composable () -> Unit = {
                    LibraryControls(query, { query = it }, sort, { sort = it }, listView, { vm.set(Keys.libraryList, it) }, shown)
                }
                Box(Modifier.fillMaxSize()) {
                    if (listView) {
                        val st = rememberLazyListState()
                        LazyColumn(
                            state = st,
                            contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            item(key = "controls") { headerContent() }
                            groups.forEach { (folder, list) ->
                                if (folder != null) item(key = "folder:$folder") { FolderHeader(folder) }
                                items(list, key = { it.key }) { a -> AlbumRow(vm, a) { onOpen(a.key) } }
                            }
                        }
                        FastScroller(
                            total = st.layoutInfo.totalItemsCount,
                            first = st.firstVisibleItemIndex,
                            visible = st.layoutInfo.visibleItemsInfo.size,
                            scrolling = st.isScrollInProgress,
                            label = { labels.getOrElse(it) { "" } },
                        ) { st.scrollToItem(it) }
                    } else {
                        val st = rememberLazyGridState()
                        LazyVerticalGrid(
                            state = st,
                            columns = GridCells.Adaptive(156.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { headerContent() }
                            groups.forEach { (folder, list) ->
                                if (folder != null) {
                                    item(key = "folder:$folder", span = { GridItemSpan(maxLineSpan) }) { FolderHeader(folder) }
                                }
                                items(list, key = { it.key }) { a -> AlbumCard(vm, a) { onOpen(a.key) } }
                            }
                        }
                        FastScroller(
                            total = st.layoutInfo.totalItemsCount,
                            first = st.firstVisibleItemIndex,
                            visible = st.layoutInfo.visibleItemsInfo.size,
                            scrolling = st.isScrollInProgress,
                            label = { labels.getOrElse(it) { "" } },
                        ) { st.scrollToItem(it) }
                    }
                }
            }
        }
    }
}

/** 検索欄・並べ替え・表示切り替え */
@Composable
private fun LibraryControls(
    query: String,
    onQuery: (String) -> Unit,
    sort: Int,
    onSort: (Int) -> Unit,
    listView: Boolean,
    onListView: (Boolean) -> Unit,
    shown: List<LibAlbum>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        OutlinedTextField(
            query, onQuery, Modifier.fillMaxWidth(),
            placeholder = { Text(T("アルバム・アーティスト・曲名で探す", "Search albums, artists, songs")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Filled.Clear, T("消去", "Clear")) }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(T("アーティスト", "Artist"), T("アルバム名", "Album"), T("最近追加", "Recent"), T("フォルダ", "Folder"))
                .forEachIndexed { i, l -> FilterChip(sort == i, { onSort(i) }, label = { Text(l) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                T("${shown.size}枚 ・ ${shown.sumOf { it.tracks.size }}曲", "${shown.size} albums · ${shown.sumOf { it.tracks.size }} songs"),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !listView, onClick = { onListView(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = {},
                ) { Icon(AppIcons.Grid, T("グリッド表示", "Grid view"), Modifier.size(18.dp)) }
                SegmentedButton(
                    selected = listView, onClick = { onListView(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = {},
                ) { Icon(AppIcons.ListView, T("リスト表示", "List view"), Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun FolderHeader(folder: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Icon(AppIcons.Library, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            folder, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AlbumCard(vm: MainViewModel, a: LibAlbum, onOpen: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen)) {
        Box {
            ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.fillMaxWidth().aspectRatio(1f), 12.dp)
            FilledIconButton(
                { vm.playAlbum(a, 0) },
                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp),
            ) { Icon(Icons.Filled.PlayArrow, T("再生", "Play")) }
        }
        Text(
            a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Text(
            a.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun AlbumRow(vm: MainViewModel, a: LibAlbum, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(56.dp), 8.dp) },
        headlineContent = { Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                a.artist + T(" ・ ${a.tracks.size}曲", " · ${a.tracks.size} tracks"),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton({ vm.playAlbum(a, 0) }) { Icon(Icons.Filled.PlayArrow, T("再生", "Play")) }
        },
    )
}

@Composable
private fun AlbumDetail(vm: MainViewModel, a: LibAlbum, onBack: () -> Unit) {
    val playingUri = vm.player.current?.uri?.toString()
    var editing by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf(false) }
    if (editing) EditTagsDialog(vm, a) { editing = false }
    if (lookup) LookupSheet(vm, a) { lookup = false }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, T("戻る", "Back")) }
                Spacer(Modifier.weight(1f))
                if (vm.tagSaving) {
                    Text(T("保存中…", "Saving…"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
                } else {
                    IconButton({ lookup = true; vm.libLookup(a) }) { Icon(Icons.Filled.Search, T("曲情報を取得", "Get track info")) }
                    IconButton({ editing = true }) { Icon(Icons.Filled.Edit, T("曲情報を編集", "Edit track info")) }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.fillMaxWidth(0.66f).aspectRatio(1f), 16.dp)
                Spacer(Modifier.height(20.dp))
                Text(a.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(a.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    T("${a.tracks.size}曲 ・ ${fmtMs(a.tracks.sumOf { it.durationMs })}", "${a.tracks.size} tracks · ${fmtMs(a.tracks.sumOf { it.durationMs })}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ vm.playAlbum(a, 0) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(T("再生", "Play"))
                    }
                    FilledTonalButton({ vm.playAlbum(a, 0, shuffle = true) }) {
                        Icon(AppIcons.Shuffle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(T("シャッフル", "Shuffle"))
                    }
                }
            }
        }
        itemsIndexed(a.tracks, key = { _, t -> t.uri }) { i, t ->
            val playing = playingUri == t.uri
            ListItem(
                modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.playAlbum(a, i) },
                colors = ListItemDefaults.colors(
                    containerColor = if (playing) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
                leadingContent = {
                    Text(
                        if (t.track > 0) "${t.track}" else "${i + 1}", modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(
                        t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (playing) FontWeight.Bold else null,
                    )
                },
                supportingContent = if (t.artist != a.artist) {
                    { Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else null,
                trailingContent = { Text(fmtMs(t.durationMs), style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

@Composable
private fun EditTagsDialog(vm: MainViewModel, a: LibAlbum, onDismiss: () -> Unit) {
    var album by remember { mutableStateOf(a.title) }
    var albumArtist by remember { mutableStateOf(a.artist) }
    var year by remember { mutableStateOf(a.first.year) }
    val titles = remember { mutableStateListOf<String>().apply { addAll(a.tracks.map { it.title }) } }
    val artists = remember { mutableStateListOf<String>().apply { addAll(a.tracks.map { it.artist }) } }
    var cover by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) cover = it }
    val unsupported = a.tracks.count { !TagEditor.supported(it.uri) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                vm.saveAlbumTags(a, album, albumArtist, year, titles.toList(), artists.toList(), cover)
                onDismiss()
            }) { Text(T("保存", "Save")) }
        },
        dismissButton = { TextButton(onDismiss) { Text(T("キャンセル", "Cancel")) } },
        title = { Text(T("曲情報を編集", "Edit track info")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtImage(a.key, a.first.uri, a.first.coverUri, Modifier.size(64.dp), 8.dp)
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton({
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(if (cover == null) T("ジャケットを変更", "Change cover") else T("新しい画像を選択済み", "New image selected")) }
                    }
                }
                item { OutlinedTextField(album, { album = it }, Modifier.fillMaxWidth(), label = { Text(T("アルバム名", "Album")) }, singleLine = true) }
                item {
                    OutlinedTextField(albumArtist, { albumArtist = it }, Modifier.fillMaxWidth(), label = { Text(T("アルバムアーティスト", "Album artist")) }, singleLine = true)
                }
                item { OutlinedTextField(year, { year = it }, Modifier.fillMaxWidth(), label = { Text(T("発売年", "Year")) }, singleLine = true) }
                if (unsupported > 0) {
                    item {
                        Text(
                            T("WAVなど${unsupported}曲は、曲情報を保存できない形式のため変更されません", "${unsupported} tracks (such as WAV) use a format that cannot store tags and will not be changed"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(titles.size) { i ->
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val n = a.tracks[i].track
                        OutlinedTextField(
                            titles[i], { titles[i] = it }, Modifier.fillMaxWidth(),
                            label = { Text(if (n > 0) T("${n}曲目 曲名", "Track ${n} title") else T("曲名", "Title")) }, singleLine = true,
                        )
                        OutlinedTextField(
                            artists[i], { artists[i] = it }, Modifier.fillMaxWidth(),
                            label = { Text(T("アーティスト", "Artist")) }, singleLine = true,
                        )
                    }
                }
            }
        },
    )
}

/** 取り込み済みアルバムの曲情報を、インターネットから探して書き込む */
@Composable
private fun LookupSheet(vm: MainViewModel, a: LibAlbum, onDismiss: () -> Unit) {
    var album by remember { mutableStateOf(a.title.takeIf { it != UNKNOWN_ALBUM }.orEmpty()) }
    var artist by remember { mutableStateOf(a.artist.takeIf { it != UNKNOWN_ARTIST }.orEmpty()) }
    var pick by remember { mutableStateOf<ReleaseCandidate?>(null) }
    val n = a.tracks.size
    val search = { if (album.isNotBlank() || artist.isNotBlank()) vm.libSearch(a, album, artist) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(T("曲情報を取得", "Get track info"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onDismiss) { Text(T("閉じる", "Close")) }
                }
                Text(
                    T(
                        "曲の長さからCDを推定して探します。選んだ情報は、このアルバムの${n}曲のファイルに書き込まれます。",
                        "Matches are estimated from song lengths. The chosen info is written to the $n files of this album.",
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    album, { album = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text(T("アルバム名", "Album")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            item {
                OutlinedTextField(
                    artist, { artist = it }, Modifier.fillMaxWidth(),
                    label = { Text(T("アーティスト", "Artist")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(search, enabled = !vm.libSearching && (album.isNotBlank() || artist.isNotBlank())) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(T("名前で検索", "Search by name"))
                    }
                    TextButton({ vm.libLookup(a) }, enabled = !vm.libSearching) { Text(T("曲の長さで探す", "Match by length")) }
                }
                if (vm.libSearching || vm.libApplying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                if (vm.libMessage.isNotEmpty()) {
                    Text(vm.libMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            items(vm.libCandidates) { c ->
                CandidateRow(c, n, !vm.libApplying && !vm.tagSaving, false) { pick = c }
            }
        }
    }
    val c = pick
    if (c != null) {
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(T("この曲情報で上書きしますか?", "Overwrite with this info?")) },
            text = {
                Text(
                    "${c.meta.title}\n${c.meta.artist}\n\n" + T(
                        "${n}曲のファイルの曲名・アーティスト・アルバム名・年を書き換えます。ジャケット画像が見つかれば差し替えます。WAVファイルは対象外です。",
                        "Titles, artists, album and year of the $n files will be rewritten. Cover art is replaced if found. WAV files are skipped.",
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    pick = null
                    vm.libApply(a, c) { onDismiss() }
                }) { Text(T("上書きする", "Overwrite")) }
            },
            dismissButton = { TextButton({ pick = null }) { Text(T("キャンセル", "Cancel")) } },
        )
    }
}

/** アルバムが入っているフォルダの名前(例: Music/Artist/Album の Album) */
private fun folderPath(a: LibAlbum): String = Uri.decode(a.first.folderId).substringAfter(':', "")

private fun folderName(a: LibAlbum): String = folderPath(a).substringAfterLast('/')

/** フォルダ別表示の見出し(アルバムのフォルダの1つ上。例: Music/Artist) */
private fun folderGroup(a: LibAlbum): String {
    val p = folderPath(a)
    return if (p.contains('/')) p.substringBeforeLast('/') else p.ifEmpty { "/" }
}
