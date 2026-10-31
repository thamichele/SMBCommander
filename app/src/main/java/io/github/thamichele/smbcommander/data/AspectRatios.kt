package io.github.thamichele.smbcommander.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Width / height of each photo as it is shown (turned upright), learnt from its thumbnail the first time it is made or
 * loaded, and kept in a small file on the phone: the mosaic of the gallery lays photos out by their real shape
 * without asking the NAS anything.
 */
class AspectRatios(private val file: File) {

    private val ratios = ConcurrentHashMap<String, Float>()
    private val _version = MutableStateFlow(0)

    /** Grows each time a new shape is learnt, so the mosaic can arrange itself again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        runCatching {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                val ratio = line.substring(tab + 1).toFloatOrNull()
                if (tab > 0 && ratio != null && ratio > 0f) ratios[line.substring(0, tab)] = ratio
            }
        }
    }

    operator fun get(key: String): Float? = ratios[key]

    /** Learns a shape; with [publish] false the mosaic hears of it only at the next [publish]. */
    fun put(key: String, ratio: Float, publish: Boolean = true) {
        if (ratio <= 0f || ratio.isNaN() || ratios.put(key, ratio) != null) return
        synchronized(this) { runCatching { file.appendText("$key\t$ratio\n") } }
        if (publish) _version.value++
    }

    fun publish() {
        _version.value++
    }

    fun clear() {
        ratios.clear()
        synchronized(this) { file.delete() }
        _version.value++
    }
}
