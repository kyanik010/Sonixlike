package com.example.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.Channel
import com.example.model.DualAudioState
import com.example.ui.theme.*

@Composable
fun SonixPlayerView(
    channel: Channel,
    dualAudioState: DualAudioState,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenEpg: () -> Unit,
    onOpenDualAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMuted by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentVideoView by remember { mutableStateOf<VideoView?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SonixBgScreen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 16:9 Video Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, SonixBorderPanel, RoundedCornerShape(16.dp))
        ) {
            // Native Android VideoView
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setZOrderOnTop(false)
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            // If dual audio is active, mute the video player
                            if (dualAudioState.isActive || isMuted) {
                                mp.setVolume(0f, 0f)
                            } else {
                                mp.setVolume(1f, 1f)
                            }
                            start()
                        }
                        setOnErrorListener { _, _, _ ->
                            // Never crash on stream error, return true to handle gracefully
                            true
                        }
                        try {
                            setVideoURI(Uri.parse(channel.streamUrl))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        currentVideoView = this
                    }
                },
                update = { view ->
                    try {
                        view.setVideoURI(Uri.parse(channel.streamUrl))
                        view.start()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            )

            // Top Left Match Overlay
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SonixDangerRed)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(text = "LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                Text(text = "0 - 2", color = SonixAccentGold, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text(text = "55:07", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // Top Right External Audio Active Indicator
            if (dualAudioState.isActive) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE61F6F6B))
                        .border(1.dp, SonixFocusBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null, tint = SonixAccentGold, modifier = Modifier.size(16.dp))
                    Text(text = "صوت خارجي نشط", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Channel Info Row (Name + Favorite Heart)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = channel.name,
                color = SonixTextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(SonixBgPanel)
                    .border(1.dp, if (isFavorite) SonixDangerRed.copy(alpha = 0.5f) else SonixBorderPanel, RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) SonixDangerRed else SonixTextSilver
                )
            }
        }

        // Action Buttons Row (EPG + Favorite + Dual Audio)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenEpg,
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SonixBgPanel),
                border = ButtonDefaults.outlinedButtonBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SonixBorderPanel))
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = SonixTextSilver, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "دليل البرامج", color = SonixTextSilver, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onToggleFavorite,
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SonixBgPanel),
                border = ButtonDefaults.outlinedButtonBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SonixBorderPanel))
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) SonixDangerRed else SonixTextSilver,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isFavorite) "في المفضلة" else "إضافة للمفضلة",
                    color = SonixTextSilver,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onOpenDualAudio,
                modifier = Modifier.weight(1.3f).height(42.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (dualAudioState.isActive) SonixAccentGold.copy(alpha = 0.2f) else SonixSelectedItemBg
                ),
                border = ButtonDefaults.outlinedButtonBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(if (dualAudioState.isActive) SonixAccentGold else SonixFocusBlue.copy(alpha = 0.5f))
                )
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = if (dualAudioState.isActive) SonixAccentGold else SonixFocusBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (dualAudioState.isActive) "صوت: ${dualAudioState.sourceChannel?.name?.split(" ")?.firstOrNull() ?: ""}" else "الصوت الخارجي",
                    color = if (dualAudioState.isActive) SonixAccentGold else SonixFocusBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Quick Controls Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SonixBgPanel)
                .border(1.dp, SonixBorderPanel, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    currentVideoView?.let {
                        if (isPlaying) it.pause() else it.start()
                        isPlaying = !isPlaying
                    }
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = SonixTextSilver,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { isMuted = !isMuted }) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = if (isMuted) SonixDangerRed else SonixTextSilver,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(text = "FHD 1080p 60fps", color = SonixFocusBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "HLS Live Stream", color = SonixTextMuted, fontSize = 10.sp)
                Icon(Icons.Default.Fullscreen, contentDescription = null, tint = SonixTextSilver, modifier = Modifier.size(20.dp))
            }
        }
    }
}
