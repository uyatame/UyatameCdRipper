package com.uyatame.cdripper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 追加アイコン(アプリ独自のシンプルなパス) */
object AppIcons {
    private fun icon(name: String, path: String, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes(path),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.Black),
            )
            .build()

    val Pause = icon("Pause", "M6,5h4v14h-4zM14,5h4v14h-4z")
    val SkipNext = icon("SkipNext", "M6,6l8.5,6l-8.5,6zM16,6h2v12h-2z")
    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12h-2zM18,6v12l-8.5,-6z")
    val Eject = icon("Eject", "M5,17h14v2h-14zM12,5l6.67,10h-13.34z")
    val Album = icon(
        "Album",
        "M12,2a10,10 0 1,0 0.001,0zM12,7.5a4.5,4.5 0 1,1 -0.001,0zM12,11a1,1 0 1,0 0.001,0z",
        evenOdd = true,
    )
    val MusicNote = icon(
        "MusicNote",
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55c-2.21,0 -4,1.79 -4,4s1.79,4 4,4s4,-1.79 4,-4V7h4V3z",
    )
    val Download = icon("Download", "M19,9h-4V3H9v6H5l7,7l7,-7zM5,18v2h14v-2z")
    val Library = icon(
        "Library",
        "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 1.5,0.51V5h4z",
        evenOdd = true,
    )
    val Shuffle = icon(
        "Shuffle",
        "M10.59,9.17L5.41,4L4,5.41l5.17,5.17zM14.5,4l2.04,2.04L4,18.59L5.41,20L17.96,7.46L20,9.5V4zM14.83,13.41l-1.41,1.41l3.13,3.13L14.5,20H20v-5.5l-2.04,2.04z",
    )
    val Repeat = icon("Repeat", "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4z")
    val RepeatOne = icon("RepeatOne", "M7,7h10v3l4,-4l-4,-4v3H5v6h2V7zM17,17H7v-3l-4,4l4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z")
    val Grid = icon("Grid", "M3,3h8v8H3zM13,3h8v8h-8zM3,13h8v8H3zM13,13h8v8h-8z")
    val ListView = icon("ListView", "M3,5h4v4H3zM9,6h12v2H9zM3,10h4v4H3zM9,11h12v2H9zM3,15h4v4H3zM9,16h12v2H9z")
}
