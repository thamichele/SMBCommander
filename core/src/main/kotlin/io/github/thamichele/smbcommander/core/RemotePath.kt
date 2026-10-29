package io.github.thamichele.smbcommander.core

/**
 * A path inside one SMB share, always written with forward slashes and without leading or trailing slash.
 * The share root is the empty path.
 */
object RemotePath {

    /** ".." is dropped too: a path must never climb out of the folder it was built from. */
    fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

    fun child(parent: String, name: String): String {
        val p = normalize(parent)
        val n = normalize(name)
        return if (p.isEmpty()) n else "$p/$n"
    }

    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")

    fun name(path: String): String = normalize(path).substringAfterLast('/')

    /** Path in the form smbj expects: backslashes, root as "". */
    fun toSmb(path: String): String = normalize(path).replace('/', '\\')

    /** Lower-case extension without the dot; "" for names without one and for dot files like ".profile". */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** "foto.jpg" + "modificato" -> "foto (modificato).jpg"; keeps names without extension and dot files intact. */
    fun withSuffix(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($suffix)" else "${name.substring(0, dot)} ($suffix)${name.substring(dot)}"
    }

    /** "photo.jpg" numbered [n] becomes "photo_2.jpg", as the photo backup names a different file with a taken name. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "${name}_$n" else "${name.substring(0, dot)}_$n${name.substring(dot)}"
    }

    /** Hidden temporary name used while uploading, next to the target file. */
    fun uploadTempName(name: String): String = ".$name.smbcommander-tmp"

    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it in INVALID_CHARS } && !name.endsWith(" ") &&
            !name.endsWith(".")

    private const val INVALID_CHARS = "\\/:*?\"<>|"
}
