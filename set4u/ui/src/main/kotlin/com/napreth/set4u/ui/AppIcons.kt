package com.napreth.set4u.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The small set of symbols used by the app; no full icon catalogue in the APK. */
internal object AppIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black),
    ).build()

    val Back = icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.42,-1.41L7.83,13H20z")
    val Chevron = icon("Chevron", "M9.3,6.7 14.6,12 9.3,17.3 10.7,18.7 17.4,12 10.7,5.3z")
    val Dropdown = icon("Dropdown", "M7.4,8.6 12,4 16.6,8.6 15.2,10 12,6.8 8.8,10zM7.4,15.4 8.8,14 12,17.2 15.2,14 16.6,15.4 12,20z")
    val Check = icon("Check", "M9,16.17 4.83,12 3.41,13.41 9,19 21,7 19.59,5.59z")
    val Network = icon("Network", "M1,8l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.92,1.92 7.07,1.92 1,8zM9,16l3,3 3,-3c-1.66,-1.66 -4.34,-1.66 -6,0zM5,12l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2c-3.87,-3.87 -10.13,-3.87 -14,0z")
    val Device = icon("Device", "M17,1H7a2,2 0,0 0,-2,2v18a2,2 0,0 0,2,2h10a2,2 0,0 0,2,-2V3a2,2 0,0 0,-2,-2zM17,19H7V5h10zM13,22h-2v-1h2z")
    val About = icon("About", "M12,2a10,10 0,1 0,0,20 10,10 0,0 0,0,-20zM13,17h-2v-6h2zM13,9h-2V7h2z")
    val Link = icon("Link", "M3.9,12a3.1,3.1 0,0 1,3.1,-3.1h4V7H7a5,5 0,0 0,0,10h4v-1.9H7A3.1,3.1 0,0 1,3.9,12zM8,13h8v-2H8zM17,7h-4v1.9h4a3.1,3.1 0,0 1,0,6.2h-4V17h4a5,5 0,0 0,0,-10z")
    val Appearance = icon("Appearance", "M12,3a9,9 0,1 0,0,18h1a2,2 0,0 0,1.4,-3.4l-.7,-.7a1,1 0,0 1,.7,-1.7H17a5,5 0,0 0,5,-5C22,6.1 17.5,3 12,3zM7,12a1.5,1.5 0,1 1,0,-3 1.5,1.5 0,0 1,0,3zM10,8a1.5,1.5 0,1 1,0,-3 1.5,1.5 0,0 1,0,3zM15,8a1.5,1.5 0,1 1,0,-3 1.5,1.5 0,0 1,0,3zM18,12a1.5,1.5 0,1 1,0,-3 1.5,1.5 0,0 1,0,3z")
    val Settings = icon("Settings", "M19.4,13a8,8 0,0 0,0,-2l2,-1.5 -2,-3.4 -2.3,.9a8,8 0,0 0,-1.7,-1L15,3H9l-.4,3a8,8 0,0 0,-1.7,1l-2.3,-.9 -2,3.4L4.6,11a8,8 0,0 0,0,2l-2,1.5 2,3.4 2.3,-.9a8,8 0,0 0,1.7,1L9,21h6l.4,-3a8,8 0,0 0,1.7,-1l2.3,.9 2,-3.4zM12,16a4,4 0,1 1,0,-8 4,4 0,0 1,0,8z")
}
