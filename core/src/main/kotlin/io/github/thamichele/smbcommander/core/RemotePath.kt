/*
 * RemotePath.kt - paths inside an SMB share, and the names built from them
 *
 * Every path of the app goes through here: forward slashes, no leading or trailing slash, never "..". Also the names
 * the app makes (numbered copies, temporary upload names) and the check of a name typed by the user. No I/O.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.core

/**
 * A path inside one SMB share, always written with forward slashes and without leading or trailing slash.
 * The share root is the empty path.
 */
object RemotePath {

    /**
     * normalize() - the one form every path of the app is kept in
     * @param path a path with any slashes
     *
     * ".." is dropped too: a path must never climb out of the folder it was built from.
     *
     * Return: the path with forward slashes, without empty, "." or ".." parts.
     */
    fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

    /**
     * child() - a path inside a folder
     * @param parent the folder
     * @param name a name, or a path below the folder
     *
     * Return: the normalized path.
     */
    fun child(parent: String, name: String): String {
        val p = normalize(parent)
        val n = normalize(name)
        return if (p.isEmpty()) n else "$p/$n"
    }

    /**
     * parent() - the folder holding a path
     * @param path a path
     *
     * Return: the folder, "" at the top of the share.
     */
    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")

    /**
     * name() - the last part of a path
     * @param path a path
     *
     * Return: the name, "" for the top of the share.
     */
    fun name(path: String): String = normalize(path).substringAfterLast('/')

    /**
     * toSmb() - a path in the form smbj expects
     * @param path a path
     *
     * Return: the path with backslashes, root as "".
     */
    fun toSmb(path: String): String = normalize(path).replace('/', '\\')

    /**
     * extension() - the extension of a name
     * @param name a file name
     *
     * Return: lower-case, without the dot; "" for names without one and for dot files like ".profile".
     */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /**
     * withSuffix() - a name with a note in brackets before its extension
     * @param name a file name
     * @param suffix the note
     *
     * Return: "foto.jpg" + "modificato" -> "foto (modificato).jpg"; names without extension and dot files keep their
     * whole name before the note.
     */
    fun withSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($suffix)" else "${name.substring(0, dot)} ($suffix)${name.substring(dot)}"
    }

    /**
     * numbered() - a name with a number, as the photo backup names a different file with a taken name
     * @param name a file name
     * @param n the number
     *
     * Return: "photo.jpg" numbered 2 becomes "photo_2.jpg".
     */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "${name}_$n" else "${name.substring(0, dot)}_$n${name.substring(dot)}"
    }

    /**
     * uploadTempName() - the hidden temporary name used while uploading, next to the target file
     * @param name the name of the target
     *
     * Return: ".name.smbcommander-tmp".
     */
    fun uploadTempName(name: String): String = ".$name.smbcommander-tmp"

    /**
     * isValidName() - tells whether the user typed a name the NAS accepts
     * @param name the name
     *
     * Return: false for blank names, "." and "..", names with characters SMB forbids or ending in a space or a dot.
     */
    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it in INVALID_CHARS } && !name.endsWith(" ") &&
            !name.endsWith(".")

    private const val INVALID_CHARS = "\\/:*?\"<>|"
}
