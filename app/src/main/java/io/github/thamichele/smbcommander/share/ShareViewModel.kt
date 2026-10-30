package io.github.thamichele.smbcommander.share

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.NasChange
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.EditSync
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.describe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ShareState(
    /** Null: choosing among "Le mie cartelle"; otherwise the folder being browsed for the destination. */
    val folder: SavedFolder? = null,
    val subfolders: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** "Carico 2 di 5: foto.jpg" while uploading. */
    val progress: String? = null,
    val fraction: Float? = null,
)

class ShareViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmbCommanderApp
    private var uris: List<Uri> = emptyList()
    private var text: String? = null

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    val configured: Boolean get() = app.repository.isConfigured
    val folders: List<SavedFolder> get() = app.folders.all()

    /** How many items are being shared: the text counts as one file. */
    val count: Int get() = uris.size + if (uris.isEmpty() && !text.isNullOrBlank()) 1 else 0

    fun take(uris: List<Uri>, text: String?) {
        this.uris = uris
        this.text = text
    }

    fun open(folder: SavedFolder) {
        _state.value = ShareState(folder = folder, loading = true)
        viewModelScope.launch {
            try {
                val list = app.repository.io { it.list(folder.share, folder.path) }
                    .filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("@") }
                    .sortedBy { it.name.lowercase() }
                _state.update { if (it.folder == folder) it.copy(subfolders = list, loading = false) else it }
            } catch (e: Exception) {
                _state.update { if (it.folder == folder) it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) else it }
            }
        }
    }

    /** Goes up one folder; false when already at the list of saved folders. */
    fun up(): Boolean {
        val folder = _state.value.folder ?: return false
        if (_state.value.progress != null) return true
        val saved = folders.firstOrNull { it.share == folder.share && (folder.path == it.path) }
        if (saved != null || folder.path.isEmpty()) {
            _state.value = ShareState()
        } else {
            open(SavedFolder(folder.share, RemotePath.parent(folder.path)))
        }
        return true
    }

    /** Uploads everything into the folder being browsed; [onDone] gets the message to show. */
    fun saveHere(onDone: (String) -> Unit) {
        val folder = _state.value.folder ?: return
        if (_state.value.progress != null) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            var saved = 0
            try {
                if (uris.isEmpty() && !text.isNullOrBlank()) {
                    val bytes = text.orEmpty().toByteArray()
                    val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ITALY).format(Date())
                    _state.update { it.copy(progress = "Salvo il testo", fraction = null) }
                    app.repository.io { fs ->
                        val target = EditSync.freePath(fs, folder.share, RemotePath.child(folder.path, "Testo condiviso $stamp.txt"))
                        EditSync.safeUpload(fs, folder.share, target, bytes.size.toLong(), { ByteArrayInputStream(bytes) })
                    }
                    saved = 1
                }
                for ((i, uri) in uris.withIndex()) {
                    val (name, size) = resolver.describe(uri)
                    val label = if (uris.size > 1) "Carico ${i + 1} di ${uris.size}: $name" else "Carico $name"
                    _state.update { it.copy(progress = label, fraction = null) }
                    app.repository.io { fs ->
                        val target = EditSync.freePath(fs, folder.share, RemotePath.child(folder.path, name))
                        EditSync.safeUpload(fs, folder.share, target, size, {
                            resolver.openInputStream(uri) ?: error("Impossibile leggere «$name»")
                        }) { done, total ->
                            _state.update { it.copy(fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null) }
                        }
                    }
                    saved++
                }
                app.nasChanged.tryEmit(NasChange(folder.share, folder.path))
                onDone(
                    (if (saved == 1) "1 file salvato" else "$saved file salvati") + " in ${folder.name}"
                )
            } catch (e: Exception) {
                _state.update {
                    it.copy(progress = null, fraction = null, error = "Salvati $saved file, poi: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }
}
