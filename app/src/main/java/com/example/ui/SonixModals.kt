package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun DualAudioDialog(
    dualAudioState: DualAudioState,
    onStartAudio: () -> Unit,
    onStopAudio: () -> Unit,
    onAdjustDelay: (Int) -> Unit,
    onResetDelay: () -> Unit,
    onSelectSourceChannel: (Channel) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredChannels = remember(searchQuery) {
        if (searchQuery.isBlank()) SonixData.channels
        else SonixData.channels.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(540.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SonixBorderPanel))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null, tint = SonixFocusBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "نظام الصوت الخارجي (Dual Audio)", color = SonixTextWhite, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = SonixTextSilver)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Status Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SonixBgScreen)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "حالة اتصال الصوت:", color = SonixTextSilver, fontSize = 13.sp)
                    Text(
                        text = dualAudioState.statusText,
                        color = if (dualAudioState.isActive) SonixSuccessGreen else SonixTextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Current Source & Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SonixSelectedItemBg)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "مصدر الصوت الخارجي المختار:", color = SonixTextMuted, fontSize = 11.sp)
                        Text(
                            text = dualAudioState.sourceChannel?.name ?: "لم يتم اختيار مصدر صوت بعد",
                            color = SonixFocusBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row {
                        Button(
                            onClick = onStartAudio,
                            colors = ButtonDefaults.buttonColors(containerColor = SonixFocusBlue),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = "تشغيل الصوت", color = SonixBgScreen, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onStopAudio,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SonixDangerRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = "إيقاف", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Manual Delay Synchronization Box
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SonixBgScreen)
                        .border(1.dp, SonixBorderPanel, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "المزامنة اليدوية (Audio Delay)", color = SonixTextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(text = "يتم تأخير الصوت فيزيائياً دون إعادة تشغيل الفيديو", color = SonixTextMuted, fontSize = 10.sp)
                        }
                        Text(
                            text = "+${dualAudioState.delayMs} ms",
                            color = SonixAccentGold,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = { onAdjustDelay(-500) }, shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)) { Text("-500ms", fontSize = 10.sp) }
                        Button(onClick = { onAdjustDelay(-100) }, shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)) { Text("-100ms", fontSize = 10.sp) }
                        Button(onClick = onResetDelay, shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f))) { Text("إعادة ضبط (0ms)", fontSize = 10.sp) }
                        Button(onClick = { onAdjustDelay(100) }, shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)) { Text("+100ms", fontSize = 10.sp) }
                        Button(onClick = { onAdjustDelay(500) }, shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)) { Text("+500ms", fontSize = 10.sp) }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Channel Picker
                Text(text = "اختر قناة البث كمصدر للصوت:", color = SonixTextSilver, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SonixBgScreen)
                        .padding(4.dp)
                ) {
                    items(filteredChannels) { ch ->
                        val isSelected = dualAudioState.sourceChannel?.id == ch.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) SonixSelectedItemBg else Color.Transparent)
                                .clickable { onSelectSourceChannel(ch) }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "${ch.number}. ${ch.name}", color = if (isSelected) SonixFocusBlue else SonixTextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(text = ch.categoryName, color = SonixTextMuted, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)
                ) {
                    Text(text = "إغلاق النافذة", color = SonixTextWhite)
                }
            }
        }
    }
}

@Composable
fun EpgDialog(channel: Channel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(480.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SonixBgPanel)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "دليل البرامج - ${channel.name}", color = SonixTextWhite, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null, tint = SonixTextSilver) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SonixSelectedItemBg)
                        .padding(14.dp)
                ) {
                    Text(text = "يُعرض الآن", color = SonixDangerRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = channel.epgTitle, color = SonixTextWhite, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Text(text = "٢٠:٣٠ - ٢٣:١٥ (متبقي ٤٠ دقيقة)", color = SonixAccentGold, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = channel.epgDesc, color = SonixTextSilver, fontSize = 11.sp, lineHeight = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { 0.65f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.dp)), color = SonixFocusBlue, trackColor = SonixBgScreen)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SonixBgScreen)
                        .padding(12.dp)
                ) {
                    Text(text = "البرنامج التالي:", color = SonixTextMuted, fontSize = 10.sp)
                    Text(text = channel.epgNext, color = SonixTextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(text = "٢٣:١٥ - ٠٠:٣٠", color = SonixTextSilver, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End), colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)) {
                    Text("إغلاق", color = SonixTextWhite)
                }
            }
        }
    }
}

@Composable
fun ProfileDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.width(460.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SonixBgPanel)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("معلومات المشترك والاشتراك", color = SonixTextWhite, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null, tint = SonixTextSilver) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SonixBgScreen).padding(10.dp)) {
                        Text("اسم المستخدم:", color = SonixTextMuted, fontSize = 10.sp)
                        Text("—", color = SonixTextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SonixBgScreen).padding(10.dp)) {
                        Text("حالة الاشتراك:", color = SonixTextMuted, fontSize = 10.sp)
                        Text("—", color = SonixAccentGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SonixBgScreen).padding(10.dp)) {
                        Text("رقم المشترك:", color = SonixTextMuted, fontSize = 10.sp)
                        Text("—", color = SonixTextWhite, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SonixBgScreen).padding(10.dp)) {
                        Text("تاريخ الانتهاء:", color = SonixTextMuted, fontSize = 10.sp)
                        Text("غير محدد", color = SonixTextSilver, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SonixSelectedItemBg).padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("حالة اتصال السيرفر:", color = SonixTextSilver, fontSize = 12.sp)
                    Text("غير متحقق", color = SonixTextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End), colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)) {
                    Text("تم", color = SonixTextWhite)
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.width(480.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SonixBgPanel)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إعدادات SONIX TV وربط السيرفر", color = SonixTextWhite, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null, tint = SonixTextSilver) }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("تسجيل الدخول عبر Xtream Codes:", color = SonixFocusBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("رابط الخادم (Server URL)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("اسم المستخدم") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("كلمة المرور") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SonixFocusBlue)) {
                    Text("إغلاق", color = SonixBgScreen, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun GlobalSearchDialog(onSelectChannel: (Channel) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) {
        if (query.isBlank()) emptyList()
        else SonixData.channels.filter { it.name.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.width(480.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SonixBgPanel)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("البحث الشامل في SONIX TV", color = SonixTextWhite, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = null, tint = SonixTextSilver) }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("اكتب اسم القناة للبحث الفوري...") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                    items(matches) { ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SonixSelectedItemBg)
                                .clickable {
                                    onSelectChannel(ch)
                                    onDismiss()
                                }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "📺 ${ch.name}", color = SonixTextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = ch.categoryName, color = SonixAccentGold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ExitConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.width(380.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = SonixBgPanel)) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = SonixDangerRed, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text("تأكيد إغلاق التطبيق", color = SonixDangerRed, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("هل تريد حقاً الخروج من تطبيق SONIX TV؟", color = SonixTextWhite, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = SonixDangerRed)) {
                        Text("نعم، خروج", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = SonixSelectedItemBg)) {
                        Text("إلغاء", color = SonixTextWhite)
                    }
                }
            }
        }
    }
}
