package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun SonixHomeScreen(
    onNavigateLive: () -> Unit,
    onNavigateMovies: () -> Unit,
    onNavigateSeries: () -> Unit,
    onOpenPower: () -> Unit,
    onRefresh: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(SonixBgGradientStart, SonixBgGradientEnd)
                )
            )
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar
            Box(modifier = Modifier.fillMaxWidth()) {
                // Top Right (RTL start)
                SonixBrandLogo(modifier = Modifier.align(Alignment.CenterEnd))

                // Center Live Clock
                LiveClockDisplay(modifier = Modifier.align(Alignment.Center))

                // Top Left (RTL end) Toolbar
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToolSquareButton(icon = Icons.Default.PowerSettingsNew, tint = SonixDangerRed, onClick = onOpenPower)
                    ToolSquareButton(icon = Icons.Default.Refresh, onClick = onRefresh)
                    ToolSquareButton(icon = Icons.Default.Person, onClick = onOpenProfile)
                    ToolSquareButton(icon = Icons.Default.Settings, onClick = onOpenSettings)
                    ToolSquareButton(icon = Icons.Default.Search, onClick = onOpenSearch)
                }
            }

            // Center Large Glass Panel (80% width, 55% height)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(0.62f)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SonixBgPanel.copy(alpha = 0.70f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    HeroActionCard(
                        title = "أفلام",
                        subtitle = "أحدث إصدارات السينما العالمية بدقة 4K",
                        icon = Icons.Default.Movie,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateMovies
                    )
                    HeroActionCard(
                        title = "مسلسلات",
                        subtitle = "مواسم وحلقات كاملة بأعلى نقاوة",
                        icon = Icons.Default.Tv,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateSeries
                    )
                    HeroActionCard(
                        title = "قنوات",
                        subtitle = "البث الحي والمباشر لجميع القنوات",
                        icon = Icons.Default.LiveTv,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateLive
                    )
                }
            }

            // Bottom User Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                UserInfoCard()
            }
        }
    }
}

@Composable
fun SonixLiveScreen(
    currentChannel: Channel,
    onSelectChannel: (Channel) -> Unit,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    dualAudioState: DualAudioState,
    onOpenDualAudio: () -> Unit,
    onOpenEpg: () -> Unit,
    onOpenSettings: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateMovies: () -> Unit,
    onNavigateSeries: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategoryId by remember { mutableStateOf("cat-sports") }
    var channelSearchQuery by remember { mutableStateOf("") }
    var categorySearchQuery by remember { mutableStateOf("") }

    val filteredCategories = remember(categorySearchQuery) {
        if (categorySearchQuery.isBlank()) SonixData.categories
        else SonixData.categories.filter { it.name.contains(categorySearchQuery, ignoreCase = true) }
    }

    val displayedChannels = remember(selectedCategoryId, channelSearchQuery, favorites) {
        val base = if (selectedCategoryId == "cat-fav") {
            SonixData.channels.filter { favorites.contains(it.id) }
        } else {
            SonixData.channels.filter { it.category == selectedCategoryId }
        }
        if (channelSearchQuery.isBlank()) base
        else base.filter { it.name.contains(channelSearchQuery, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SonixBgScreen)
    ) {
        // Live Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(SonixBgPanel)
                .border(1.dp, SonixBorderPanel)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Right (Title + Back)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onNavigateHome,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = SonixTextSilver, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "الرئيسية", color = SonixTextSilver, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(text = "مباشر", color = SonixTextWhite, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }

            // Center (Search)
            OutlinedTextField(
                value = channelSearchQuery,
                onValueChange = { channelSearchQuery = it },
                placeholder = { Text("البحث في القنوات...", fontSize = 11.sp, color = SonixTextMuted) },
                singleLine = true,
                modifier = Modifier
                    .width(320.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SonixBgScreen,
                    unfocusedContainerColor = SonixBgScreen,
                    focusedBorderColor = SonixFocusBlue,
                    unfocusedBorderColor = SonixBorderPanel
                ),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = SonixTextMuted, modifier = Modifier.size(16.dp))
                }
            )

            // Left (Now Playing Ticker)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "أنت تشاهد:", color = SonixFocusBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(text = currentChannel.categoryName, color = SonixTextSilver, fontSize = 11.sp)
                Text(text = ">>", color = SonixAccentGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(text = currentChannel.name, color = SonixTextWhite, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        // 4 Columns: 1. Sidebar (~4%), 2. Categories (~22%), 3. Channels (~28%), 4. Player (~46%)
        Row(modifier = Modifier.fillMaxSize()) {
            // 1. Sidebar (~4%)
            Column(
                modifier = Modifier
                    .width(54.dp)
                    .fillMaxHeight()
                    .background(SonixBgPanel)
                    .border(1.dp, SonixBorderPanel)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SonixSelectedItemBg)
                            .border(1.dp, SonixFocusBlue.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "S", color = SonixFocusBlue, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }

                    // Live (Active)
                    IconButton(
                        onClick = {},
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SonixAccentGold.copy(alpha = 0.15f))
                            .border(1.dp, SonixAccentGold, RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Default.LiveTv, contentDescription = null, tint = SonixAccentGold)
                    }

                    // Movies
                    IconButton(onClick = onNavigateMovies, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = SonixTextSilver)
                    }

                    // Series
                    IconButton(onClick = onNavigateSeries, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = SonixTextSilver)
                    }

                    // EPG
                    IconButton(onClick = onOpenEpg, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = SonixTextSilver)
                    }
                }

                // Settings at bottom
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = SonixTextSilver)
                }
            }

            // 2. Categories Column (~22%)
            Column(
                modifier = Modifier
                    .weight(0.24f)
                    .fillMaxHeight()
                    .background(Color(0xFF0E141B))
                    .border(1.dp, SonixBorderPanel)
            ) {
                Box(modifier = Modifier.padding(8.dp)) {
                    OutlinedTextField(
                        value = categorySearchQuery,
                        onValueChange = { categorySearchQuery = it },
                        placeholder = { Text("البحث في الفئات...", fontSize = 11.sp, color = SonixTextMuted) },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SonixBgScreen,
                            unfocusedContainerColor = SonixBgScreen,
                            focusedBorderColor = SonixFocusBlue,
                            unfocusedBorderColor = SonixBorderPanel
                        )
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                    items(filteredCategories) { cat ->
                        val isSelected = selectedCategoryId == cat.id
                        val count = if (cat.id == "cat-fav") favorites.size else cat.count
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) SonixSelectedItemBg else Color.Transparent)
                                .border(1.dp, if (isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { selectedCategoryId = cat.id }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cat.name,
                                color = if (isSelected) SonixTextWhite else SonixTextSilver,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.35f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = "$count", color = SonixFocusBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                }
            }

            // 3. Channels Column (~28%)
            Column(
                modifier = Modifier
                    .weight(0.30f)
                    .fillMaxHeight()
                    .background(SonixBgPanel)
                    .border(1.dp, SonixBorderPanel)
            ) {
                // Header Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SonixSelectedItemBg)
                        .border(1.dp, SonixBorderPanel)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = SonixData.categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "القنوات",
                        color = SonixFocusBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SonixAccentGold.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = "${displayedChannels.size} قناة", color = SonixAccentGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                    items(displayedChannels) { ch ->
                        val isCurrent = currentChannel.id == ch.id
                        val isFav = favorites.contains(ch.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) SonixSelectedItemBg else Color(0x660E141B))
                                .border(1.dp, if (isCurrent) SonixFocusBlue.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { onSelectChannel(ch) }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = "${ch.number}", color = SonixTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SonixBgScreen)
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = ch.logoText, color = SonixAccentGold, fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                            Text(
                                text = ch.name,
                                color = SonixTextWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (isFav) {
                                Icon(Icons.Default.Favorite, contentDescription = null, tint = SonixDangerRed, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                }
            }

            // 4. Player Column (~46%)
            SonixPlayerView(
                channel = currentChannel,
                dualAudioState = dualAudioState,
                isFavorite = favorites.contains(currentChannel.id),
                onToggleFavorite = { onToggleFavorite(currentChannel.id) },
                onOpenEpg = onOpenEpg,
                onOpenDualAudio = onOpenDualAudio,
                modifier = Modifier.weight(0.46f)
            )
        }
    }
}

@Composable
fun SonixVodScreen(
    title: String,
    items: List<VodItem>,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SonixBgScreen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(SonixBgPanel)
                .border(1.dp, SonixBorderPanel)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onNavigateBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = SonixTextSilver, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "الرئيسية", color = SonixTextSilver, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(text = title, color = SonixTextWhite, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(text = "مكتبة متجددة بأعلى دقة 4K HDR", color = SonixTextSilver, fontSize = 12.sp)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { item ->
                Card(
                    modifier = Modifier
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SonixBorderPanel))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                        ) {
                            Text(text = item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(text = item.meta, color = SonixAccentGold, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
