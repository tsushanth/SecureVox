package com.securevox.app.presentation.theme

import androidx.compose.ui.graphics.Color

// Notebook palette. SecureVox is a sealed notebook: transcripts read as ink on a page, and
// everything stays inside the phone. Two accents, each reserved for one meaning:
//   signal red = a recording is live, or something destructive
//   verdigris  = on this device: playback, finished transcripts, progress
// Screens read these through MaterialTheme.colorScheme so light and dark both work.
val Graphite = Color(0xFF16181A)
val GraphiteSurface = Color(0xFF1F2224)
val GraphiteRaised = Color(0xFF282C2F)
val Bone = Color(0xFFEDEAE3)
val Slate = Color(0xFF9AA0A6)
val SlateDim = Color(0xFF6B7177)
val SignalRed = Color(0xFFEF5B4C)
val SignalRedDim = Color(0xFF4A1F1B)
val Verdigris = Color(0xFF6DB8AD)
val FavoriteAmber = Color(0xFFE0B04A)

val Paper = Color(0xFFF1EFEA)
val PaperRaised = Color(0xFFFAF9F6)
val PaperSunk = Color(0xFFE6E3DC)
val Ink = Color(0xFF1C1E20)
val InkSoft = Color(0xFF565C61)
val VerdigrisDeep = Color(0xFF2B6F67)
val SignalRedDeep = Color(0xFFC93A2C)
