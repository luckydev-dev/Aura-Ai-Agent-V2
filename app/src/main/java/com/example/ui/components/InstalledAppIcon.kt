package com.example.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AuraTheme

/**
 * Loads and displays the real application icon from the Android system via PackageManager.
 * If the package is not installed on the device, provides a clean Material 3 vector fallback icon.
 */
@Composable
fun InstalledAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    fallbackIcon: ImageVector? = null
) {
    val context = LocalContext.current
    val colors = AuraTheme.colors

    val iconBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = loadAppIconBitmap(context, packageName)
    }

    val resolvedFallback: ImageVector = fallbackIcon ?: resolveDefaultIcon(packageName)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E2333)),
        contentAlignment = Alignment.Center
    ) {
        val bmp = iconBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "App icon for $packageName",
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Icon(
                imageVector = resolvedFallback,
                contentDescription = "App icon for $packageName",
                tint = colors.accentStart,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

private fun loadAppIconBitmap(context: Context, packageName: String): ImageBitmap? {
    return try {
        val pm = context.packageManager
        val drawable: Drawable = pm.getApplicationIcon(packageName)
        drawableToBitmap(drawable)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }

    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96

    return try {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    } catch (_: Exception) {
        null
    }
}

private fun resolveDefaultIcon(packageName: String): ImageVector {
    val lower = packageName.lowercase()
    return when {
        lower.contains("whatsapp") || lower.contains("telegram") || lower.contains("messaging") || lower.contains("mms") ->
            Icons.Default.Chat
        lower.contains("gm") || lower.contains("mail") || lower.contains("email") ->
            Icons.Default.Email
        lower.contains("instagram") || lower.contains("snapchat") || lower.contains("twitter") || lower.contains("facebook") ->
            Icons.Default.People
        else ->
            Icons.Default.Apps
    }
}
