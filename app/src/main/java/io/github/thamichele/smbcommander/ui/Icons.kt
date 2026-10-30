package io.github.thamichele.smbcommander.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons of the Arancio UI kit: 24x24, stroke 1.75, round caps and joins. */
object AppIcons {
    val Folder = icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val FolderPlus = icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM12 10v6M9 13h6")
    val File = icon("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5")
    val Document = icon("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4")
    val Image = icon("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM8.5 10a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3zM20 15l-5-5L5 20")
    val Video = icon("M4 6h11a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1zM16 10l5-3v10l-5-3")
    val Music = icon("M9 18V5l12-2v13M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0zM21 16a3 3 0 1 1-6 0a3 3 0 1 1 6 0z")
    val Copy = icon("M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1zM5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1")
    val Move = icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM9 13h6M13 10.5l2.5 2.5l-2.5 2.5")
    val Play = icon("M7 4.5v15a.5.5 0 0 0 .77.42l11.5-7.5a.5.5 0 0 0 0-.84L7.77 4.08A.5.5 0 0 0 7 4.5z")
    val Archive = icon("M4 4h16v4H4zM5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8M10 12h4")
    val Search = icon("M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM20 20l-4-4")
    val Close = icon("M6 6l12 12M18 6L6 18")
    val Back = icon("M19 12H5M12 19l-7-7l7-7")
    val More = icon("M12 5h.01M12 12h.01M12 19h.01", stroke = 3f)
    val Plus = icon("M12 5v14M5 12h14", stroke = 2.25f)
    val Upload = icon("M12 16V4M7 9l5-5l5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3")
    val Download = icon("M12 4v12M7 11l5 5l5-5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3")
    val Rename = icon("M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4")
    val Delete = icon("M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3")
    val Refresh = icon("M20 11a8 8 0 0 0-14.9-3M4 4v4h4M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4")
    val Server = icon("M5 3h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM5 13h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1zM8 7h.01M8 17h.01")
    val Grid = icon("M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z")
    val List = icon("M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01")
    val OpenWith = icon("M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val Alert = icon("M12 3l9 16H3zM12 10v4M12 17h.01")
    val Check = icon("M5 12l5 5l9-10")
    val Sparkle = icon("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z")
    val Undo = icon("M9 14L4 9l5-5M4 9h11a5 5 0 0 1 0 10h-3")
    val Skip = icon("M4 12h13M13 7l5 5l-5 5M21 5v14")
    val Info = icon("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 8h.01")
    val ChevronRight = icon("M9 6l6 6l-6 6")
    val Eye = icon("M2 12s3.5-7 10-7s10 7 10 7s-3.5 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6a3 3 0 1 0 0 6z")
    val EyeOff = icon("M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.1M6.6 6.6C3.7 8.4 2 12 2 12s3.5 7 10 7a9.6 9.6 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2")

    private fun icon(pathData: String, stroke: Float = 1.75f): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(pathData),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
