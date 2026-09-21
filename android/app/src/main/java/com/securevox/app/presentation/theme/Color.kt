package com.securevox.app.presentation.theme

import androidx.compose.ui.graphics.Color

// Recorder palette — sampled from Google Recorder on-device (Sep 2026). A fixed,
// intentional palette rather than Material You dynamic color: dynamic color meant
// SecureVox only resembled Recorder by accident, because both were pulling from the
// same wallpaper. Two accents, each reserved for one meaning throughout the app:
// coral = recording is live / destructive, lavender = calm / playback / resume.
val RecorderInk = Color(0xFF0B0B0F)
val RecorderSurface = Color(0xFF1C1C1F)
val RecorderSurfaceRaised = Color(0xFF232326)
val RecorderCoral = Color(0xFFF2938A)
val RecorderCoralDim = Color(0xFF5C1F1B)
val RecorderLavender = Color(0xFFC9C6F5)
val RecorderTextPrimary = Color(0xFFE8E6EA)
val RecorderTextSecondary = Color(0xFF9C99A6)
val RecorderTextTertiary = Color(0xFF5E5C66)
val RecorderGold = Color(0xFFFFD700)
