package com.example.musicore

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        startService(Intent(this, PlaybackService::class.java))
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MusicoreTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Si Android paró el servicio mientras la app estaba en segundo plano, se vuelve a arrancar
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
    }

    override fun onStop() {
        super.onStop()
        Engine.savePosition()
    }
}

@Composable
fun App() {
    var detail by remember { mutableStateOf<Long?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlaylistInfo?>(null) }
    val d = detail
    when {
        creating || editing != null -> Editor(editing) { creating = false; editing = null }
        d != null -> Detail(d) { detail = null }
        else -> Home(onOpen = { detail = it }, onNew = { creating = true }, onEdit = { editing = it })
    }
}

// ───────────────────────────── Inicio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(onOpen: (Long) -> Unit, onNew: () -> Unit, onEdit: (PlaylistInfo) -> Unit) {
    val lists by Engine.playlists.collectAsState()
    val active by Engine.activePlaylist.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Musicore", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew) { Text("＋  Nueva lista") } },
        bottomBar = { PlayerBar() },
    ) { pad ->
        if (lists.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
                Text("Crea tu primera lista con el botón de abajo", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(lists, key = { it.id }) { pl -> PlaylistCard(pl, pl.id == active, onOpen, onEdit) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistCard(pl: PlaylistInfo, active: Boolean, onOpen: (Long) -> Unit, onEdit: (PlaylistInfo) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Card(
        onClick = { onOpen(pl.id) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) cs.primaryContainer else cs.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (active) "🎧" else "🎵", fontSize = 26.sp, modifier = Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(pl.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${pl.count} canciones" + if (active) " · activa" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) cs.onPrimaryContainer else cs.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Editar carpetas") }, onClick = { menu = false; onEdit(pl) })
                    DropdownMenuItem(text = { Text("Eliminar lista") }, onClick = { menu = false; confirm = true })
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("¿Eliminar «${pl.name}»?") },
        text = { Text("Solo se elimina la lista. Tus archivos de música no se tocan.") },
        confirmButton = { TextButton(onClick = { confirm = false; Engine.deletePlaylist(pl.id) }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
    )
}

// ───────────────────────────── Detalle de lista + cola

private enum class RowState { Played, Current, Upcoming, Pending }

private fun rowState(i: Int, pos: Int) = when {
    i < 0 -> RowState.Pending
    i < pos -> RowState.Played
    i == pos -> RowState.Current
    else -> RowState.Upcoming
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Detail(id: Long, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val lists by Engine.playlists.collectAsState()
    val pl = lists.find { it.id == id }
    if (pl == null) { LaunchedEffect(Unit) { onBack() }; return }

    val active by Engine.activePlaylist.collectAsState()
    val cur by Engine.current.collectAsState()
    val version by Engine.version.collectAsState()
    // Solo tamaño, posición y revisión (~50 bytes). Las canciones se piden fila a fila, las visibles.
    val info = remember(id, active, cur?.id, version, lists) { Engine.queueInfo(id) }
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    // La búsqueda la hace Rust y devuelve solo los números de fila que coinciden
    val matches = remember(id, q, info.rev, info.active) { if (q.isEmpty()) emptyList() else Engine.search(id, q) }

    // Mantiene a la vista la canción actual (con un par de anteriores encima)
    LaunchedEffect(cur?.id, info.active, q.isEmpty()) {
        if (q.isEmpty() && info.active && info.len > 0) listState.animateScrollToItem(info.pos)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.headlineSmall) } },
            )
        },
        bottomBar = { PlayerBar() },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Buscar en la lista") },
                leadingIcon = { Text("🔍") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (q.isNotEmpty()) {
                    item { SectionTitle("Resultados", "${matches.size}") }
                    if (matches.isEmpty()) item {
                        Text("Ninguna canción coincide", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(matches.size, key = { matches[it] }, contentType = { "song" }) { k -> QueueRow(id, matches[k], info) }
                } else {
                    item {
                        Button(onClick = { Engine.startPlaylist(id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (info.active) "🔀  Mezclar de nuevo" else "🔀  Reproducir en aleatorio")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    info.error?.let { e ->
                        item { Text("Error al leer la cola: $e", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (info.len > 0) {
                        item {
                            if (info.active) SectionTitle("Cola de esta vuelta", "${info.pos + 1} de ${info.len}")
                            else SectionTitle("Canciones", "${info.len}")
                        }
                    }
                    items(info.len, key = { it }, contentType = { "song" }) { i -> QueueRow(id, i, info) }
                }
            }
        }
    }
}

/** Una fila: pide a Rust solo su canción, y solo cuando la fila entra en pantalla. */
@Composable
private fun QueueRow(listId: Long, index: Int, info: QueueInfo) {
    val s = remember(listId, index, info.rev) { Engine.itemAt(listId, index) } ?: return
    SongRow(s, rowState(if (info.active) index else -1, info.pos)) { Engine.playSong(listId, s.id) }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongRow(song: Song, state: RowState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val isCur = state == RowState.Current
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val fg = when (state) {
        RowState.Played -> cs.onSurfaceVariant.copy(alpha = 0.65f)
        RowState.Current -> cs.onPrimaryContainer
        else -> cs.onSurface
    }
    val mark = when (state) { RowState.Played -> "✓"; RowState.Current -> "▶"; RowState.Upcoming -> "›"; RowState.Pending -> "·" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (isCur) cs.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark, color = if (isCur) cs.primary else fg, modifier = Modifier.width(24.dp))
        if (isCur) AutoScrollText(song.title, Modifier.weight(1f), color = fg, fontWeight = FontWeight.Bold)
        else Text(song.title, Modifier.weight(1f), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
            Text("⋮", color = fg, fontSize = 22.sp)
            // El menú solo existe mientras está abierto: así cada fila cuesta menos de pintar
            if (menu) DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                // Aquí se pueden añadir más opciones en el futuro
                DropdownMenuItem(text = { Text("Borrar canción") }, onClick = { menu = false; confirm = true })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("¿Borrar canción?") },
        text = { Text("«${song.title}» se eliminará del dispositivo de forma permanente.") },
        confirmButton = { TextButton(onClick = { confirm = false; failed = !Engine.deleteSong(song) }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
    )
    if (failed) AlertDialog(
        onDismissRequest = { failed = false },
        confirmButton = { TextButton(onClick = { failed = false }) { Text("OK") } },
        text = { Text("No se pudo borrar el archivo. Comprueba que la carpeta tiene permiso de escritura.") },
    )
}

// ───────────────────────────── Reproductor

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun PlayerBar() {
    val song by Engine.current.collectAsState()
    if (song == null) return
    val playing by Engine.playing.collectAsState()
    val vol by Engine.volume.collectAsState()
    val pos by Engine.position.collectAsState()
    val dur by Engine.duration.collectAsState()
    val err by Engine.playbackError.collectAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val shownPos = drag?.let { (it * dur).toLong() } ?: pos
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Surface(
        color = cs.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            AutoScrollText(song?.title ?: "", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            err?.let { Text("⚠ $it", color = cs.error, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }

            // Barra de progreso: tiempo actual · barra · duración total
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(shownPos), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Slider(
                    value = frac,
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { Engine.seekTo((it * dur).toLong()) }; drag = null },
                    enabled = dur > 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(fmt(dur), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Engine.prev() }) { Text("⏮", style = MaterialTheme.typography.headlineSmall) }
                FilledIconButton(onClick = { Engine.toggle() }, modifier = Modifier.size(56.dp)) {
                    Text(if (playing) "⏸" else "▶", fontSize = 24.sp)
                }
                TextButton(onClick = { Engine.next() }) { Text("⏭", style = MaterialTheme.typography.headlineSmall) }
                TextButton(onClick = { confirm = true }) { Text("🗑", style = MaterialTheme.typography.headlineSmall) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔈", Modifier.padding(end = 8.dp))
                Slider(value = vol, onValueChange = Engine::setVolume, modifier = Modifier.weight(1f))
                Text("${(vol * 100).toInt()}%", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("¿Borrar canción?") },
        text = { Text("«${song?.title}» se eliminará del dispositivo de forma permanente.") },
        confirmButton = { TextButton(onClick = { confirm = false; failed = !Engine.deleteCurrent() }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
    )
    if (failed) AlertDialog(
        onDismissRequest = { failed = false },
        confirmButton = { TextButton(onClick = { failed = false }) { Text("OK") } },
        text = { Text("No se pudo borrar el archivo. Comprueba que la carpeta tiene permiso de escritura.") },
    )
}

// ───────────────────────────── Crear / editar lista

private fun prettyFolder(uri: String): String {
    val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
    val vol = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val root = if (vol == "primary") "Almacenamiento interno" else "Tarjeta / USB ($vol)"
    return (listOf(root) + path.split('/').filter { it.isNotEmpty() }).joinToString("  ›  ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(info: PlaylistInfo?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(info?.name ?: "") }
    val folders = remember { mutableStateListOf<String>().apply { info?.let { addAll(it.folders) } } }
    var busy by remember { mutableStateOf(false) }
    BackHandler(enabled = !busy, onBack = onClose)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            if (uri.toString() !in folders) folders.add(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (info == null) "Nueva lista" else "Editar lista", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = onClose, enabled = !busy) { Text("←", style = MaterialTheme.typography.headlineSmall) } },
            )
        },
        bottomBar = {
            Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    Button(
                        enabled = !busy && name.isNotBlank() && folders.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        onClick = {
                            scope.launch {
                                busy = true
                                val songs = withContext(Dispatchers.IO) { Scanner.scan(ctx, folders.toList()) }
                                Engine.savePlaylist(info?.id, name.trim(), folders.toList(), songs)
                                busy = false
                                onClose()
                            }
                        },
                    ) { Text(if (busy) "Escaneando carpetas…" else "Guardar lista", fontWeight = FontWeight.SemiBold) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nombre de la lista") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            Column {
                Text("Carpetas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Se incluyen también todas sus subcarpetas", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (folders.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Aún no has añadido ninguna carpeta", Modifier.padding(20.dp), color = cs.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(folders.toList(), key = { it }) { f ->
                    Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📁", fontSize = 22.sp, modifier = Modifier.padding(end = 12.dp))
                            Text(prettyFolder(f), Modifier.weight(1f), color = cs.onSecondaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { folders.remove(f) }, enabled = !busy) { Text("✕", color = cs.onSecondaryContainer) }
                        }
                    }
                }
            }
            FilledTonalButton(onClick = { picker.launch(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("＋  Añadir carpeta")
            }
        }
    }
}

// ───────────────────────────── Texto que se desliza

/**
 * Si el texto no cabe: espera 2 s, se desliza despacio hasta el final, espera 2 s ahí
 * y vuelve al principio. Si cabe, se queda quieto.
 */
@Composable
fun AutoScrollText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    LaunchedEffect(text, scroll.maxValue) {
        scroll.scrollTo(0)
        if (scroll.maxValue <= 0) return@LaunchedEffect
        while (true) {
            delay(2000)
            val ms = (scroll.maxValue / (45f * density) * 1000).toInt().coerceAtLeast(400) // ~45 dp/s
            scroll.animateScrollTo(scroll.maxValue, tween(ms, easing = LinearEasing))
            delay(2000)
            scroll.animateScrollTo(0, tween(600))
        }
    }
    Text(
        text, modifier.horizontalScroll(scroll, enabled = false),
        style = style, color = color, fontWeight = fontWeight, maxLines = 1, softWrap = false,
    )
}
