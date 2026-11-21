/*
 * AlbumsScreen.kt - the albums of the gallery on screen: their list, the tabs that lead to it, the album picker
 *
 * Only draws what GalleryViewModel knows and passes the user's choices back to it: no NAS access here. The photos
 * of an album are shown by GalleryScreen, with the same grid, mosaic and list of the whole gallery.
 *
 * Version: 1.25.0
 */
package io.github.thamichele.smbcommander.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.thamichele.smbcommander.core.Album
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.EmptyState
import io.github.thamichele.smbcommander.ui.GhostButton
import io.github.thamichele.smbcommander.ui.NameDialog
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.Pill
import io.github.thamichele.smbcommander.ui.PrimaryButton
import io.github.thamichele.smbcommander.ui.SectionLabel
import io.github.thamichele.smbcommander.ui.ToolButton
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType

/** Narrowest album card: two columns on a phone held upright, more on wider screens. */
private val ALBUM_CARD_MIN_WIDTH = 148.dp

/** Space between the album cards and around the grid, as in the photo grids. */
private val ALBUM_GRID_GAP = 12.dp
private val PAGE_MARGIN = 16.dp

/** Cover in a row of the album picker: the touch target of the kit. */
private val PICKER_COVER_SIZE = 44.dp

/** The picker never grows past this, so its buttons stay on screen with many albums. */
private val PICKER_MAX_HEIGHT = 360.dp

private const val NEW_ALBUM_TITLE = "Nuovo album"
private const val NEW_ALBUM_CONFIRM = "Crea"

/** The tabs under the header of the gallery, in the order they are drawn. */
internal enum class GalleryTab { Photos, Videos, Albums }

/**
 * albumTitle() - the name an album is shown with
 * @param state the gallery, for the backup folder the album lies in
 * @param path the folder of the album
 *
 * Return: the path below the backup folder ("Vacanze", "2026/10"), or the name of the backup folder for the
 * photos that lie directly in it.
 */
internal fun albumTitle(state: GalleryState, path: String): String {
    val known = state.albums.firstOrNull { it.path == path }?.name ?: RemotePath.name(path)
    return known.ifEmpty { state.folder?.let { RemotePath.name(it.path).ifEmpty { it.share } }.orEmpty() }
}

/**
 * GalleryTabs() - the three faces of the gallery: all the photos by date, only the videos, or the albums
 * @param tab which of the three is on screen
 * @param vm where the choice goes
 * @param actions the buttons of the page, on the right of the tabs
 *
 * Return: nothing; draws a row of three pills under the page header.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GalleryTabs(tab: GalleryTab, vm: GalleryViewModel, actions: @Composable () -> Unit = {}) {
    // the buttons share the line of the tabs; on a screen too narrow for both they wrap under them, on the right
    FlowRow(
        Modifier.fillMaxWidth().padding(start = PAGE_MARGIN, end = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Foto", selected = tab == GalleryTab.Photos, onClick = vm::openPhotos)
            Pill("Video", selected = tab == GalleryTab.Videos, onClick = vm::openVideos)
            Pill("Album", selected = tab == GalleryTab.Albums, onClick = vm::openAlbums)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/**
 * AlbumsPage() - the list of the albums: one card per folder of the backup folder, and the way to create one
 * @param state the gallery, with its albums already in display order
 * @param vm opens an album, creates a new one, refreshes
 *
 * Return: nothing; fills the screen in place of the photos.
 */
@Composable
internal fun AlbumsPage(state: GalleryState, vm: GalleryViewModel) {
    var isNaming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(context = state.folder?.fullPath.orEmpty(), title = "Album") {
            ToolButton(AppIcons.FolderPlus, NEW_ALBUM_TITLE, { isNaming = true })
            ToolButton(AppIcons.Refresh, "Aggiorna", vm::pull)
        }
        GalleryTabs(GalleryTab.Albums, vm)
        if (state.albums.isEmpty()) {
            EmptyState(
                icon = AppIcons.Folder,
                title = if (state.loading) "Cerco gli album sul NAS" else "Ancora nessun album",
                text = "Ogni cartella dentro la cartella del backup è un album. Creane uno: sul NAS nasce la sua cartella.",
            ) {
                PrimaryButton(onClick = { isNaming = true }) { Text(NEW_ALBUM_TITLE) }
            }
        } else {
            AlbumGrid(state, vm::openAlbum)
        }
    }
    if (isNaming) {
        NameDialog(NEW_ALBUM_TITLE, "", NEW_ALBUM_CONFIRM, onDismiss = { isNaming = false }) { name ->
            isNaming = false
            vm.createAlbum(name)
        }
    }
}

@Composable
private fun AlbumGrid(state: GalleryState, onOpen: (String) -> Unit) {
    val named = remember(state.albums) { state.albums.filterNot { it.isDated } }
    val dated = remember(state.albums) { state.albums.filter { it.isDated } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(ALBUM_CARD_MIN_WIDTH),
        contentPadding = PaddingValues(start = PAGE_MARGIN, end = PAGE_MARGIN, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(ALBUM_GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(ALBUM_GRID_GAP),
        modifier = Modifier.fillMaxSize(),
    ) {
        albumSection("I tuoi album", named, state, onOpen)
        albumSection("Cartelle per data", dated, state, onOpen)
    }
}

/** One titled group of album cards; nothing at all when [albums] is empty. */
private fun LazyGridScope.albumSection(title: String, albums: List<Album>, state: GalleryState, onOpen: (String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = "section-$title", span = { GridItemSpan(maxLineSpan) }) {
        SectionLabel(title, Modifier.padding(top = 8.dp))
    }
    items(albums, key = { it.path }) { album ->
        AlbumCard(album, albumTitle(state, album.path)) { onOpen(album.path) }
    }
}

@Composable
private fun AlbumCard(album: Album, title: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(ArancioShape.card).clickable(role = Role.Button, onClickLabel = "Apri l'album", onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AlbumCover(album, Modifier.fillMaxWidth().aspectRatio(1f), iconSize = 32.dp)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            albumCount(album),
            style = ArancioType.data,
            color = Arancio.colors.textSecondary,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

/** The newest photo of the album, or a folder on a bordered tile while the album is empty. */
@Composable
private fun AlbumCover(album: Album, modifier: Modifier, iconSize: Dp) {
    val c = Arancio.colors
    val cover = album.cover
    Box(
        modifier.clip(ArancioShape.card).background(c.surface).border(1.dp, c.border, ArancioShape.card),
        contentAlignment = Alignment.Center,
    ) {
        if (cover == null) {
            Icon(AppIcons.Folder, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(iconSize))
        } else {
            AsyncImage(
                model = Thumb(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun albumCount(album: Album): String = when (album.count) {
    0 -> "Vuoto"
    1 -> "1 elemento"
    else -> "${album.count} elementi"
}

/**
 * AlbumPicker() - asks which album the selected photos are moved to, or creates one for them
 * @param state the gallery, with its albums
 * @param vm creates the new album when the user asks for one
 * @param count how many photos are about to move, for the title
 * @param onDismiss the user gave up: nothing moves
 * @param onPick called with the folder of the chosen album, also right after a new one was created
 *
 * Return: nothing; shows a dialog over the gallery.
 */
@Composable
internal fun AlbumPicker(state: GalleryState, vm: GalleryViewModel, count: Int, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var isNaming by remember { mutableStateOf(false) }
    if (isNaming) {
        NameDialog(NEW_ALBUM_TITLE, "", NEW_ALBUM_CONFIRM, onDismiss = { isNaming = false }) { name ->
            isNaming = false
            vm.createAlbum(name, onCreated = onPick)
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surfaceRaised,
        title = {
            Text(if (count == 1) "Sposta 1 foto in un album" else "Sposta $count foto in un album", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = PICKER_MAX_HEIGHT)) {
                item(key = "new") {
                    PickerRow(NEW_ALBUM_TITLE, "Crea la cartella sul NAS", icon = AppIcons.FolderPlus) { isNaming = true }
                }
                // the album being shown is where the photos already are
                listItems(state.albums.filter { it.path != state.album }, key = { it.path }) { album ->
                    PickerRow(albumTitle(state, album.path), albumCount(album), album) { onPick(album.path) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun PickerRow(title: String, detail: String, album: Album? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = Arancio.colors
    Row(
        Modifier.fillMaxWidth().clip(ArancioShape.control).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (album != null) {
            AlbumCover(album, Modifier.size(PICKER_COVER_SIZE), iconSize = 20.dp)
        } else if (icon != null) {
            Box(Modifier.size(PICKER_COVER_SIZE), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.accentText, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = ArancioType.data, color = c.textSecondary)
        }
    }
}
