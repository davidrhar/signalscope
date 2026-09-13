package com.signalscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    clickable(interactionSource = src, indication = null, onClick = onClick)
}
