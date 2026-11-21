/*
 * NasShares.kt - names of the shares of the other NAS, so that every screen can keep using one "share" string
 *
 * The app was built around one NAS: everything (folders, transfers, thumbnails, the bin) speaks of a share name and
 * a path. A share of another NAS is written "nas<id>:<share>", which can never collide with a real share name because
 * SMB forbids ':' in share names. The main NAS keeps its plain names, so nothing saved before changes. The NAS itself,
 * the folder that lists its shares, is "nas<id>:" with nothing after the colon.
 *
 * This file only builds, splits and shows these names; MultiNasFileSystem sends each call to the right NAS.
 *
 * Version: 1.27.2
 */
package io.github.thamichele.smbcommander.core

object NasShares {

    /* SMB share names cannot contain it: see MS-SRVS, NetrShareAdd, shi*_netname */
    const val SEPARATOR = ':'
    private const val PREFIX = "nas"
    /* Between the name of a NAS and the folders inside it, where a single line shows both. */
    private const val TRAIL_SEPARATOR = " / "

    /*
     * Names of the other NAS by id, as the user called them. Written by the app when the list of NAS changes and read
     * by the screens; a missing name falls back to "NAS <id>".
     */
    @Volatile
    var names: Map<String, String> = emptyMap()

    /**
     * of() - the share name of [share] on the other NAS [nasId]
     * @param nasId id of the other NAS, letters and digits only
     * @param share name of the share on that NAS; empty for the NAS itself
     *
     * Return: the combined name, such as "nas2:Multimedia".
     */
    fun of(nasId: String, share: String): String {
        require(nasId.isNotEmpty() && nasId.all { it.isLetterOrDigit() }) { "id del NAS non valido: $nasId" }
        return "$PREFIX$nasId$SEPARATOR$share"
    }

    /**
     * root() - the folder that stands for the whole other NAS [nasId] and lists its shares
     * @param nasId id of the other NAS
     *
     * Return: "nas<id>:".
     */
    fun root(nasId: String): String = of(nasId, "")

    /**
     * nasOf() - which NAS a share name belongs to
     * @param share any share name used by the app
     *
     * Return: the id of the other NAS, or null for a share of the main NAS.
     */
    fun nasOf(share: String): String? {
        val colon = share.indexOf(SEPARATOR)
        if (colon <= PREFIX.length || !share.startsWith(PREFIX)) return null
        return share.substring(PREFIX.length, colon).takeIf { id -> id.all { it.isLetterOrDigit() } }
    }

    /**
     * localOf() - the name of the share as its own NAS knows it
     * @param share any share name used by the app
     *
     * Return: the part after "nas<id>:", or [share] itself on the main NAS; empty for the NAS root.
     */
    fun localOf(share: String): String = if (nasOf(share) == null) share else share.substringAfter(SEPARATOR)

    /**
     * isRoot() - true for the folder that lists the shares of another NAS
     * @param share any share name used by the app
     *
     * Nothing can be written there: it is not a folder on any disk.
     *
     * Return: true for "nas<id>:".
     */
    fun isRoot(share: String): Boolean = nasOf(share) != null && localOf(share).isEmpty()

    /**
     * label() - the name of the NAS that holds [share], as the user called it
     * @param share any share name used by the app
     *
     * Return: the name of the other NAS, or null on the main NAS.
     */
    fun label(share: String): String? = nasOf(share)?.let { id -> names[id] ?: "NAS $id" }

    /**
     * trail() - the names to show for [share], outermost first
     * @param share any share name used by the app
     *
     * A share called exactly as its NAS ("STREAMING" on the NAS "STREAMING") is shown once: twice the same word
     * in a row reads as a mistake.
     *
     * Return: [share] on the main NAS; the NAS name and then the share name on another NAS.
     */
    fun trail(share: String): List<String> {
        val nas = label(share) ?: return listOf(share)
        val local = localOf(share)
        return if (local.isEmpty() || local.equals(nas, ignoreCase = true)) listOf(nas) else listOf(nas, local)
    }

    /**
     * display() - [share] and [path] as one readable line, such as "Casa / Multimedia / Foto"
     * @param share any share name used by the app
     * @param path a path inside the share, possibly empty
     *
     * Return: the line to show; never the internal "nas<id>:" form.
     */
    fun display(share: String, path: String = ""): String =
        (trail(share) + RemotePath.normalize(path).split('/').filter { it.isNotEmpty() }).joinToString(TRAIL_SEPARATOR)

    /**
     * shownName() - the name of the folder [share] itself, with no path inside it
     * @param share any share name used by the app
     *
     * Return: the share name, or the NAS name for the root of another NAS.
     */
    fun shownName(share: String): String = trail(share).last()
}
