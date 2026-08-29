package com.andew.ailocal

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andew.ailocal.ai.*
import com.andew.ailocal.diagnostics.DeviceDiagnostics
import com.andew.ailocal.model.ModelStore
import com.andew.ailocal.ui.AndewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ChatMessage(val role: String, val text: String)

class MainActivity : ComponentActivity() {
    private lateinit var store: ModelStore
    private lateinit var engine: LlamaCliEngine
    private var pendingImport by mutableStateOf<Uri?>(null)
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ModelStore(this)
        engine = LlamaCliEngine(this)
        setContent {
            val scope = rememberCoroutineScope()
            var models by remember { mutableStateOf(store.list()) }
            var selected by remember { mutableStateOf(models.firstOrNull()) }
            var profile by remember { mutableStateOf(Fold6Profiles.balanced) }
            var input by remember { mutableStateOf("") }
            var running by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("Menyiapkan engine lokal…") }
            var diagnostics by remember { mutableStateOf(DeviceDiagnostics.memory(this@MainActivity)) }
            var messages by remember { mutableStateOf(listOf(ChatMessage("system", "Andew AI Local siap. Import model GGUF untuk mulai."))) }
            var activeNav by remember { mutableStateOf("Chat") }

            LaunchedEffect(Unit) {
                status = engine.prepareRuntime().fold({ "Online (Lokal) • llama.cpp b10603" }, { "Runtime error: ${it.message}" })
                diagnostics = DeviceDiagnostics.memory(this@MainActivity)
            }
            LaunchedEffect(pendingImport) {
                pendingImport?.let { uri ->
                    val result = withContext(Dispatchers.IO) { runCatching { store.import(uri) } }
                    result.onSuccess { f ->
                        models = store.list()
                        selected = f
                        status = "Model aktif • ${f.name}"
                    }.onFailure { status = "Import gagal • ${it.message}" }
                    pendingImport = null
                }
            }

            AndewTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF06090D)) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(activeNav) { activeNav = it }
                        MainWorkspace(
                            activeNav = activeNav,
                            messages = messages,
                            input = input,
                            onInput = { input = it },
                            onSend = {
                                val prompt = input.trim()
                                if (prompt.isNotEmpty() && selected != null && !running) {
                                    input = ""
                                    messages = messages + ChatMessage("user", prompt)
                                    messages = messages + ChatMessage("assistant", "")
                                    running = true
                                    scope.launch {
                                        val index = messages.lastIndex
                                        val result = runCatching {
                                            engine.generate(requireNotNull(selected), prompt, profile).collect { chunk ->
                                                messages = messages.toMutableList().also { list ->
                                                    list[index] = list[index].copy(text = list[index].text + chunk)
                                                }
                                            }
                                        }
                                        if (result.isFailure) {
                                            messages = messages.toMutableList().also { list ->
                                                list[index] = list[index].copy(text = "[ERROR] ${result.exceptionOrNull()?.message}")
                                            }
                                        }
                                        running = false
                                        diagnostics = withContext(Dispatchers.IO) { DeviceDiagnostics.memory(this@MainActivity) }
                                    }
                                }
                            },
                            onImport = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                            onSelfTest = {
                                scope.launch {
                                    status = withContext(Dispatchers.IO) {
                                        engine.selfTest().fold({ "Runtime OK • ${it.take(90)}" }, { "Runtime ERROR • ${it.message}" })
                                    }
                                    diagnostics = withContext(Dispatchers.IO) { DeviceDiagnostics.memory(this@MainActivity) }
                                }
                            },
                            onStop = { engine.cancel(); running = false },
                            models = models,
                            selected = selected,
                            onSelectModel = { selected = it },
                            profile = profile,
                            onProfile = { profile = it },
                            status = status,
                            diagnostics = diagnostics,
                            thermal = DeviceDiagnostics.thermalStatus(this@MainActivity),
                            running = running
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(active: String, onSelect: (String) -> Unit) {
    val items = listOf("Chat", "Model", "Dokumen", "Tools", "RAB / BOQ", "Solar & HM", "Kalkulator", "Excel Analyzer", "Pengaturan")
    Column(
        Modifier.width(250.dp).fillMaxHeight().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("△", color = Color(0xFF63FF9C), fontSize = 48.sp, fontWeight = FontWeight.Black)
            Text("Andew", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("AI LOCAL", color = Color(0xFF63FF9C), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Surface(color = Color(0xFF0B1712), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("✦ 100% OFFLINE", color = Color(0xFF63FF9C), fontWeight = FontWeight.Bold)
                    Text("Privat • Aman • Tanpa Cloud", color = Color(0xFF9AA6B2), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            items.forEach { item ->
                val selected = item == active
                Surface(
                    color = if (selected) Color(0xFF122A1D) else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onSelect(item) }
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selected) "●" else "○", color = if (selected) Color(0xFF63FF9C) else Color(0xFF7E8995))
                        Spacer(Modifier.width(12.dp))
                        Text(item, color = if (selected) Color.White else Color(0xFFB7C0CA), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
        Surface(color = Color(0xFF10151B), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("ENGINE", color = Color(0xFF8C98A5), fontSize = 11.sp)
                Text("● llama.cpp b10603", color = Color(0xFF63FF9C), fontWeight = FontWeight.SemiBold)
                Text("ARM64 • Offline", color = Color(0xFF7E8995), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MainWorkspace(
    activeNav: String,
    messages: List<ChatMessage>,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onImport: () -> Unit,
    onSelfTest: () -> Unit,
    onStop: () -> Unit,
    models: List<java.io.File>,
    selected: java.io.File?,
    onSelectModel: (java.io.File) -> Unit,
    profile: ModelProfile,
    onProfile: (ModelProfile) -> Unit,
    status: String,
    diagnostics: String,
    thermal: String,
    running: Boolean
) {
    Row(Modifier.fillMaxSize().padding(end = 16.dp, top = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Surface(color = Color(0xFF0B1017), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Text("Selamat datang kembali,", color = Color(0xFF9AA6B2))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Andew", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text(" ✦", color = Color(0xFF63FF9C), fontSize = 26.sp)
                    }
                    Text("Asisten AI lokal untuk pekerjaan teknis, dokumen, RAB, dan analisis data.", color = Color(0xFFB8C1CB))
                    Text("Bekerja lokal tanpa cloud.", color = Color(0xFF63FF9C), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("AKSI CEPAT", color = Color(0xFF7E8995), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickCard("Chat AI", "Tanya apa saja")
                QuickCard("Analisis Dokumen", "PDF • Word • Excel")
                QuickCard("RAB / BOQ", "Volume • Tonase")
                QuickCard("Solar & HM", "Monitoring alat")
                QuickCard("Kalkulator", "Presisi teknik")
            }
            Spacer(Modifier.height(14.dp))
            Surface(color = Color(0xFF0A0F15), shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (activeNav == "Chat") "CHAT AI" else activeNav.uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (activeNav == "Chat") {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(messages) { msg ->
                                val isUser = msg.role == "user"
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                                    Surface(
                                        color = if (isUser) Color(0xFF12351F) else Color(0xFF121922),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.widthIn(max = 700.dp)
                                    ) { Text(msg.text.ifBlank { "…" }, Modifier.padding(12.dp), color = Color(0xFFE8EDF2)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                            OutlinedTextField(value = input, onValueChange = onInput, modifier = Modifier.weight(1f), placeholder = { Text("Apa yang ingin Anda kerjakan hari ini?") }, maxLines = 4)
                            if (running) Button(onClick = onStop) { Text("Stop") }
                            else Button(onClick = onSend, enabled = selected != null && input.isNotBlank()) { Text("Kirim  ➤") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onImport) { Text("Lampirkan GGUF") }
                            OutlinedButton(onClick = onSelfTest) { Text("Self-test") }
                        }
                    } else {
                        Text("Modul $activeNav sedang dipersiapkan sebagai workspace khusus.", color = Color(0xFF9AA6B2))
                        Text("Engine dan struktur modul sudah dipisahkan agar fitur dapat ditambahkan tanpa merusak inference core.", color = Color(0xFF7E8995), fontSize = 13.sp)
                    }
                }
            }
        }
        RightPanel(models, selected, onSelectModel, profile, onProfile, status, diagnostics, thermal)
    }
}

@Composable
private fun RowScope.QuickCard(title: String, subtitle: String) {
    Surface(color = Color(0xFF0E151C), shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).height(92.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color(0xFF7E8995), fontSize = 11.sp)
        }
    }
}

@Composable
private fun RightPanel(
    models: List<java.io.File>, selected: java.io.File?, onSelectModel: (java.io.File) -> Unit,
    profile: ModelProfile, onProfile: (ModelProfile) -> Unit, status: String, diagnostics: String, thermal: String
) {
    Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("MODEL AKTIF") {
            Text(selected?.name ?: "Belum ada GGUF", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(profile.name, color = Color(0xFF63FF9C), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(profile == Fold6Profiles.balanced, { onProfile(Fold6Profiles.balanced) }, label = { Text("Balanced") })
                FilterChip(profile == Fold6Profiles.pro, { onProfile(Fold6Profiles.pro) }, label = { Text("Pro") })
            }
            Text("Context ${profile.context} • ${profile.threads} threads", color = Color(0xFF8B97A3), fontSize = 12.sp)
            models.forEach { file -> Text("• ${file.name}", color = if (file == selected) Color(0xFF63FF9C) else Color(0xFF7E8995), modifier = Modifier.clickable { onSelectModel(file) }) }
        }
        InfoCard("STATUS SISTEM") {
            Text(diagnostics, color = Color(0xFFB8C1CB), fontSize = 12.sp)
            Text(thermal, color = Color(0xFFB8C1CB), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(status, color = Color(0xFF63FF9C), fontSize = 12.sp)
        }
        InfoCard("PRIVASI") {
            Text("100% Offline Core", color = Color(0xFF63FF9C), fontWeight = FontWeight.SemiBold)
            Text("Tidak perlu API key untuk inference lokal.", color = Color(0xFF8B97A3), fontSize = 12.sp)
        }
        InfoCard("TARGET DEVICE") {
            Text("Galaxy Z Fold6", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Snapdragon 8 Gen 3 • RAM 12 GB • ARM64", color = Color(0xFF8B97A3), fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color(0xFF0B1017), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF17212B), RoundedCornerShape(18.dp))) {
        Column(Modifier.padding(16.dp), content = { Text(title, color = Color(0xFF7E8995), fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); content() })
    }
}
