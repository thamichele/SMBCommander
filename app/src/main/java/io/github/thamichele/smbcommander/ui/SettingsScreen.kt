// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * SettingsScreen.kt - the settings, one menu per subject
 *
 * The third section of the bottom bar. It shows a short list of menus
 * (Backup foto, Opzioni, Aspetto, NAS) and opens the page of the one touched.
 * The pages themselves are drawn elsewhere: the backup in BackupScreen.kt,
 * the NAS and the single groups of switches in SetupScreen.kt. Nothing is
 * saved here.
 *
 * Version: 1.25.0
 */
package io.github.thamichele.smbcommander.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.backup.BackupOptions
import io.github.thamichele.smbcommander.backup.BackupScreen
import io.github.thamichele.smbcommander.ui.theme.AccentStore
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape

private val PAGE_MARGIN = 16.dp
private val MENU_ICON_SIZE = 24.dp
private val MENU_CHEVRON_SIZE = 20.dp
private val MENU_GAP = 8.dp
private val PAGE_BOTTOM_ROOM = 24.dp

/** The menus of the settings, in the order of the list. */
private enum class SettingsMenu(val title: String, val icon: ImageVector) {
    Backup("Backup foto", AppIcons.Upload),
    Options("Opzioni", AppIcons.Sliders),
    Appearance("Aspetto", AppIcons.Palette),
    Nas("NAS", AppIcons.Server),
}

/**
 * SettingsScreen() - the list of the menus of the settings, and the page of the one that is open
 * @param vm the app's view model, handed to the pages that need it
 *
 * Back from a page returns to the list; back from the list is left to the app.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    var open by rememberSaveable { mutableStateOf<SettingsMenu?>(null) }
    val close = { open = null }
    // a notification or a "Vai al backup foto" button asks for the backup page itself, not for the list
    val backupWanted by vm.backupWanted.collectAsStateWithLifecycle()
    LaunchedEffect(backupWanted) {
        if (backupWanted) {
            open = SettingsMenu.Backup
            vm.backupShown()
        }
    }
    BackHandler(enabled = open != null, onBack = close)
    when (open) {
        null -> SettingsList(vm, onOpen = { open = it })
        SettingsMenu.Backup -> BackupScreen(onBack = close)
        SettingsMenu.Options -> SettingsPage(SettingsMenu.Options, close) {
            SectionLabel("Backup")
            BackupOptions()
            GalleryOptions(vm)
            VideoFrameOptions()
            OptionalSection(vm)
        }
        SettingsMenu.Appearance -> SettingsPage(SettingsMenu.Appearance, close) { AppearanceSection() }
        SettingsMenu.Nas -> SetupScreen(vm, onBack = close)
    }
}

/* Private: the list of the menus, each with a line that says what is inside or how it is set now. */
@Composable
private fun SettingsList(vm: AppViewModel, onOpen: (SettingsMenu) -> Unit) {
    val context = LocalContext.current
    val accent by remember(context) { AccentStore.state(context) }.collectAsStateWithLifecycle()
    val host = remember { vm.savedHost() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader(context = "SMBCommander", title = "Impostazioni")
        Column(Modifier.padding(horizontal = PAGE_MARGIN), verticalArrangement = Arrangement.spacedBy(MENU_GAP)) {
            for (menu in SettingsMenu.entries) {
                val detail = when (menu) {
                    SettingsMenu.Backup -> "Backup automatico, cartella sul NAS, album da salvare, libera spazio"
                    SettingsMenu.Options -> "Opzioni del backup, della Galleria e dei video, funzioni facoltative"
                    SettingsMenu.Appearance -> "Colore principale: ${accent.label}"
                    SettingsMenu.Nas -> host.ifEmpty { "Indirizzo e utente del NAS" }
                }
                MenuRow(menu, detail) { onOpen(menu) }
            }
        }
        Spacer(Modifier.height(PAGE_BOTTOM_ROOM))
    }
}

/* Private: one menu of the list: icon in the main colour, title, what is inside, and the arrow. */
@Composable
private fun MenuRow(menu: SettingsMenu, detail: String, onClick: () -> Unit) {
    val colors = Arancio.colors
    Panel(Modifier.clip(ArancioShape.card).clickable(role = Role.Button, onClickLabel = "Apri ${menu.title}", onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PAGE_MARGIN)) {
            Icon(menu.icon, contentDescription = null, tint = colors.accentText, modifier = Modifier.size(MENU_ICON_SIZE))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(menu.title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(MENU_CHEVRON_SIZE),
            )
        }
    }
}

/* Private: the frame of a page made of groups of switches: header with back, then the groups one under the other. */
@Composable
private fun SettingsPage(menu: SettingsMenu, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageHeader(
            context = "Impostazioni",
            title = menu.title,
            navigation = { ToolButton(AppIcons.Back, "Indietro", onBack) },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_MARGIN), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
        Box(Modifier.height(PAGE_BOTTOM_ROOM))
    }
}
