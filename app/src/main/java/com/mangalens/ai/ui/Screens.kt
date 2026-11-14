package com.mangalens.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangalens.ai.data.*

private val Scheme = darkColorScheme(
    primary = Color(0xFF8B7CFF), onPrimary = Color.White, secondary = Color(0xFF2DD4BF),
    background = Color(0xFF0B0B14), surface = Color(0xFF14141F), surfaceVariant = Color(0xFF1C1C2B),
    onSurface = Color(0xFFECECF4), onSurfaceVariant = Color(0xFF9A9AB0), onBackground = Color(0xFFECECF4)
)

@Composable fun MangaLensTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

@Composable
private fun Card2(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(v, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 16.dp))
    }
}

// ------------------------------------------------------------------ dashboard
@Composable
fun Dashboard(
    st: EngineSnapshot, s: AppSettings, needsKey: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onTogglePause: () -> Unit, onOpenSettings: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6C5CE7), Color(0xFF2DD4BF)))).padding(22.dp)
        ) {
            Column {
                Text("MangaLens AI", fontSize = 26.sp, color = Color.White)
                Text("Open your manga. Start once. Read.", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (st.running && !st.paused) Color(0xFF86EFAC) else Color(0xFFFDE68A)))
                    Spacer(Modifier.width(8.dp))
                    Text(if (!st.running) "Stopped" else if (st.paused) "Paused" else "Active", color = Color.White, fontSize = 15.sp)
                }
                Text(st.message, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                if (!st.running) {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF3B2FBF))) {
                        Text("Start Translation", fontSize = 16.sp)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onTogglePause, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF3B2FBF))) {
                            Text(if (st.paused) "Resume" else "Pause")
                        }
                        Button(onClick = onStop, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C), contentColor = Color.White)) {
                            Text("Stop Translation")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Card2("STATUS") {
            InfoRow("Target language", Langs.name(s.targetLang))
            InfoRow("Source language", if (s.sourceLang == "auto") "Automatic" else s.sourceLang)
            InfoRow("OCR engine", st.ocr)
            InfoRow("Translation engine", st.translator)
            InfoRow("Pages translated", "${st.pages}" + if (st.lastMs > 0) "  (last ${st.lastMs} ms)" else "")
            InfoRow("Battery mode", if (s.batterySaver) "Saver (slower polling)" else "Normal")
            InfoRow("Cloud processing", if (s.cloudEnabled && s.provider != Provider.ON_DEVICE) "On (text only)" else "Off")
        }
        if (s.provider == Provider.ON_DEVICE || needsKey) {
            Card2("TIP") {
                Text(
                    if (needsKey) "You picked an AI provider but no API key is saved, so on-device translation is being used."
                    else "On-device translation works offline after a one-time model download. For the most natural Arabic, add an AI provider key in Settings.",
                    fontSize = 14.sp
                )
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }
    }
}

// ------------------------------------------------------------------ settings helpers
@Composable
private fun SwitchRow(title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, fontSize = 15.sp); Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, fontSize = 15.sp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o ->
                val m = Modifier.weight(1f)
                val pad = PaddingValues(horizontal = 4.dp)
                if (i == selected) Button(onClick = { onSelect(i) }, modifier = m, contentPadding = pad) { Text(o, fontSize = 12.sp, maxLines = 1) }
                else OutlinedButton(onClick = { onSelect(i) }, modifier = m, contentPadding = pad) { Text(o, fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun DropdownRow(title: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 15.sp)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == value }?.second ?: value, fontSize = 13.sp) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(o.second) }, onClick = { onSelect(o.first); open = false }) }
            }
        }
    }
}

// ------------------------------------------------------------------ settings
@Composable
fun SettingsScreen(repo: SettingsRepository, s: AppSettings) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card2("TRANSLATION") {
            DropdownRow("Target language", s.targetLang, Langs.targets) { v -> repo.update { it.copy(targetLang = v) } }
            val srcCodes = listOf("auto", "ja", "ko", "zh", "en")
            ChoiceRow("Source language", listOf("Auto", "日本語", "한국어", "中文", "English"), srcCodes.indexOf(s.sourceLang).coerceAtLeast(0)) { i ->
                repo.update { it.copy(sourceLang = srcCodes[i]) }
            }
            SwitchRow("Automatic translation", "Detect new pages and translate without any tap", s.autoTranslate) { v -> repo.update { it.copy(autoTranslate = v) } }
            ChoiceRow("Quality", listOf("Fast", "Balanced", "Best"), s.quality) { i -> repo.update { it.copy(quality = i) } }
            ChoiceRow("Speed", listOf("Fast", "Balanced", "Relaxed"), s.speed) { i -> repo.update { it.copy(speed = i) } }
        }
        Card2("TRANSLATION ENGINE") {
            ChoiceRow("Provider", listOf("On-device", "OpenAI-compat.", "Claude"), s.provider.ordinal) { i -> repo.update { it.copy(provider = Provider.values()[i]) } }
            SwitchRow("Allow cloud processing", "Off = nothing leaves the phone (on-device models only)", s.cloudEnabled) { v -> repo.update { it.copy(cloudEnabled = v) } }
            if (s.provider != Provider.ON_DEVICE) {
                var key by remember(s.provider) { mutableStateOf(repo.apiKey(s.provider)) }
                if (s.provider == Provider.OPENAI) {
                    OutlinedTextField(s.openAiBaseUrl, { v -> repo.update { it.copy(openAiBaseUrl = v) } }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    OutlinedTextField(s.openAiModel, { v -> repo.update { it.copy(openAiModel = v) } }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    OutlinedTextField(s.anthropicModel, { v -> repo.update { it.copy(anthropicModel = v) } }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                OutlinedTextField(
                    key, { v -> key = v; repo.setApiKey(s.provider, v) }, label = { Text("API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    if (repo.secureAvailable) "Stored encrypted with the Android Keystore. Only recognized text is sent, never screenshots."
                    else "Secure storage unavailable on this device: the key cannot be saved.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Card2("OVERLAY") {
            SliderRow("Overlay opacity", s.overlayOpacity, 0.8f..1f, "${(s.overlayOpacity * 100).toInt()}%") { v -> repo.update { it.copy(overlayOpacity = v) } }
            SliderRow("Arabic font size", s.fontScale, 0.6f..1.6f, "x${"%.1f".format(s.fontScale)}") { v -> repo.update { it.copy(fontScale = v) } }
            SwitchRow("Match bubble color", "Sample the bubble background automatically", s.bgAuto) { v -> repo.update { it.copy(bgAuto = v) } }
            if (!s.bgAuto) {
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(0xFFFFFFFF, 0xFFF2F2F2, 0xFFFFF8E1, 0xFF1A1A1A).forEach { c ->
                        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(c))
                            .clickable { repo.update { it.copy(bgColor = c.toInt()) } })
                    }
                }
            }
            SwitchRow("Translate sound effects", "Off hides SFX (they stay untouched)", s.showSfx) { v -> repo.update { it.copy(showSfx = v) } }
            SwitchRow("Floating control bubble", "Applies next time you start translation", s.bubbleEnabled) { v -> repo.update { it.copy(bubbleEnabled = v) } }
        }
        Card2("BATTERY") {
            SwitchRow("Battery optimization mode", "Slower polling, lower-resolution OCR", s.batterySaver) { v -> repo.update { it.copy(batterySaver = v) } }
        }
        Card2("PRIVACY") {
            Text("Screenshots are processed in memory only and never saved. OCR runs on-device. If cloud translation is on, only the extracted text is sent to the provider you chose.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ history
@Composable
fun HistoryScreen(entries: List<TranslationEntity>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Translation history", fontSize = 20.sp)
            TextButton(onClick = onClear) { Text("Clear") }
        }
        if (entries.isEmpty()) Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn {
            items(entries, key = { it.id }) { e ->
                Card2 {
                    Text("${e.srcLang} → ${e.tgtLang}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Text(e.source, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.translated, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
