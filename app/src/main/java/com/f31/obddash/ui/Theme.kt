package com.f31.obddash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF03060B)
    val Panel = Color(0xFF09111C)
    val PanelHi = Color(0xFF0D1826)
    val Border = Color(0xFF1B2938)
    val Blue = Color(0xFF1E90FF)
    val BlueLight = Color(0xFF7FC4FF)
    val BlueDeep = Color(0xFF0B3D7A)
    val Red = Color(0xFFE5303A)
    val Text = Color(0xFFE8EEF5)
    val Dim = Color(0xFF8D9BAC)
    val Faint = Color(0xFF2A3747)
    val Green = Color(0xFF2ECC71)
    val Amber = Color(0xFFF5A623)
    val MLight = Color(0xFF16A6F0)
    val MDark = Color(0xFF1C3F9C)
    val MRed = Color(0xFFE2222B)
}

/** UI scale so the dashboard fits phones and tablets alike (design base ≈ 900 x 400 dp). */
val LocalUiScale = staticCompositionLocalOf { 1f }

@Composable
fun u(v: Number): Dp = (v.toFloat() * LocalUiScale.current).dp

@Composable
fun t(v: Number): TextUnit = (v.toFloat() * LocalUiScale.current).sp
