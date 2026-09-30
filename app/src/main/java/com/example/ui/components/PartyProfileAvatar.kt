package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.StoreGold
import com.example.ui.theme.StorePrimary
import com.example.ui.theme.SurfaceWarm

@Composable
fun PartyProfileAvatar(
    photoUri: String?,
    name: String,
    size: Dp = 48.dp,
    showEditBadge: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val initials = rememberInitials(name)
    val avatarModifier = Modifier
        .size(size)
        .clip(CircleShape)
        .then(
            if (onClick != null) Modifier.clickable { onClick() } else Modifier
        )

    Box(
        modifier = Modifier.wrapContentSize(),
        contentAlignment = Alignment.BottomEnd
    ) {
        if (!photoUri.isNullOrBlank()) {
            AsyncImage(
                model = com.example.utils.ImageSyncHelper.getImageModel(photoUri),
                contentDescription = "$name Profile Photo",
                contentScale = ContentScale.Crop,
                modifier = avatarModifier
                    .background(SurfaceWarm)
                    .border(1.5.dp, StorePrimary.copy(alpha = 0.5f), CircleShape)
            )
        } else {
            Box(
                modifier = avatarModifier
                    .background(StorePrimary.copy(alpha = 0.12f))
                    .border(1.5.dp, StorePrimary.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (initials.isNotBlank()) {
                    Text(
                        text = initials,
                        color = StorePrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.38f).sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = StorePrimary,
                        modifier = Modifier.size(size * 0.55f)
                    )
                }
            }
        }

        if (showEditBadge) {
            Box(
                modifier = Modifier
                    .size((size.value * 0.32f).coerceAtLeast(20f).dp)
                    .clip(CircleShape)
                    .background(StoreGold)
                    .border(1.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Edit Photo",
                    tint = Color.White,
                    modifier = Modifier.size((size.value * 0.2f).coerceAtLeast(12f).dp)
                )
            }
        }
    }
}

private fun rememberInitials(name: String): String {
    if (name.isBlank()) return ""
    val parts = name.trim().split("\\s+".toRegex())
    return when {
        parts.size >= 2 -> "${parts[0].take(1)}${parts[1].take(1)}".uppercase()
        parts.isNotEmpty() && parts[0].length >= 2 -> parts[0].take(2).uppercase()
        parts.isNotEmpty() -> parts[0].take(1).uppercase()
        else -> ""
    }
}

@Composable
fun EnlargedPhotoDialog(
    photoUri: String?,
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (!photoUri.isNullOrBlank()) {
                    AsyncImage(
                        model = com.example.utils.ImageSyncHelper.getImageModel(photoUri),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(3.dp, StorePrimary, CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(StorePrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(100.dp),
                            tint = StorePrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
