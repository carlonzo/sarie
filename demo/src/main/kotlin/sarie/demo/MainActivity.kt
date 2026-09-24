package sarie.demo

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chuckerteam.chucker.api.Chucker
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.launch
import sarie.bridge.SarieBridge

enum class ScenarioType {
    ROUND_TRIP,
    PARALLEL,
    SETUP,
    DOWNLOAD,
}

data class ThumbItem(
    val bitmap: Bitmap? = null,
    val borderColor: Color = Color.Transparent,
)

data class DownloadUiState(
    val progress: Float = 0f,
    val isIndeterminate: Boolean = false,
    val mbText: String = "0.0 MB",
    val statusText: String = "—",
    val statusColor: Color = Color.Gray,
)

fun DownloadProgress?.toUiState(): DownloadUiState {
    if (this == null) return DownloadUiState()
    val mb = bytesRead / (1024.0 * 1024.0)
    val mbText = String.format(Locale.US, "%.1f MB", mb)
    val isIndeterminate: Boolean
    val progress: Float
    if (totalBytes > 0) {
        isIndeterminate = false
        progress = (bytesRead.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        isIndeterminate = !isComplete
        progress = if (isComplete && error == null) 1f else 0f
    }
    val statusText: String
    val statusColor: Color
    if (isComplete) {
        if (error == null) {
            statusText = "ok"
            statusColor = Color(0xFF4CAF50)
        } else {
            statusText = "err: $error"
            statusColor = Color(0xFFF44336)
        }
    } else {
        statusText = if (bytesRead == 0L) "starting..." else "downloading..."
        statusColor = Color.Gray
    }
    return DownloadUiState(progress, isIndeterminate, mbText, statusText, statusColor)
}

class MainActivity : ComponentActivity() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DemoScreen(executor = executor)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(executor: ExecutorService? = null) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scaffoldState = rememberBottomSheetScaffoldState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val logVersion = DemoLog.version

    var runningScenario by remember { mutableStateOf<ScenarioType?>(null) }

    // Round Trip state
    var roundTripResult by remember { mutableStateOf<RoundTripResult?>(null) }
    var roundTripError by remember { mutableStateOf<String?>(null) }

    // Parallel Images state
    var parallelResult by remember { mutableStateOf<ParallelImagesResult?>(null) }
    var parallelError by remember { mutableStateOf<String?>(null) }
    val thumbs = remember {
        mutableStateListOf<ThumbItem>().apply {
            repeat(100) { add(ThumbItem()) }
        }
    }

    // Connection Setup state
    var setupResult by remember { mutableStateOf<ConnectionSetupResult?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }

    // Download Migration state
    var stockDownloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var sarieDownloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

    // Bottom Sheet state
    var selectedTab by remember { mutableIntStateOf(0) }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 72.dp,
        sheetDragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        coroutineScope.launch {
                            if (scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) {
                                scaffoldState.bottomSheetState.partialExpand()
                            } else {
                                scaffoldState.bottomSheetState.expand()
                            }
                        }
                    }
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Cronet ${DemoLog.cronetCount.get()} · stock ${DemoLog.stockCount.get()} · ${DemoLog.lastLine}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        },
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.weight(1f)
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Sarie", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Network", fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Cronet", fontSize = 12.sp) }
                        )
                    }
                    TextButton(onClick = { DemoLog.clear(selectedTab) }) {
                        Text("Clear", fontSize = 12.sp)
                    }
                    FilledTonalButton(onClick = {
                        val intent = Chucker.getLaunchIntent(context)
                        context.startActivity(intent)
                    }) {
                        Text("Chucker", fontSize = 12.sp)
                    }
                }

                val lines = remember(logVersion, selectedTab) {
                    DemoLog.getLines(selectedTab)
                }
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) {
                        listState.scrollToItem(lines.size - 1)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sarie demo", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = SarieBridge.engine?.versionString ?: "engine: not installed",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            RoundTripCard(
                result = roundTripResult,
                error = roundTripError,
                isRunning = runningScenario == ScenarioType.ROUND_TRIP,
                isAnyRunning = runningScenario != null,
                onRun = {
                    runningScenario = ScenarioType.ROUND_TRIP
                    roundTripError = null
                    executor?.execute {
                        try {
                            val res = Scenarios.runRoundTrip(DemoApp.instance.clients)
                            mainHandler.post {
                                roundTripResult = res
                                runningScenario = null
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                roundTripError = e.message
                                runningScenario = null
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ParallelImagesCard(
                result = parallelResult,
                error = parallelError,
                thumbs = thumbs,
                isRunning = runningScenario == ScenarioType.PARALLEL,
                isAnyRunning = runningScenario != null,
                onRun = {
                    runningScenario = ScenarioType.PARALLEL
                    parallelError = null
                    val stockColor = Color(0xFFFF9800)
                    val sarieColor = Color(0xFF4CAF50)
                    for (i in 0 until 100) {
                        thumbs[i] = ThumbItem()
                    }
                    executor?.execute {
                        try {
                            val res = Scenarios.runParallelImages(DemoApp.instance.clients) { index, bitmap, stack ->
                                val color = if (stack == Scenarios.Stack.STOCK) stockColor else sarieColor
                                mainHandler.post {
                                    thumbs[index] = ThumbItem(bitmap, color)
                                }
                            }
                            mainHandler.post {
                                parallelResult = res
                                runningScenario = null
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                parallelError = e.message
                                runningScenario = null
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            ConnectionSetupCard(
                result = setupResult,
                error = setupError,
                isRunning = runningScenario == ScenarioType.SETUP,
                isAnyRunning = runningScenario != null,
                onRun = {
                    runningScenario = ScenarioType.SETUP
                    setupError = null
                    executor?.execute {
                        try {
                            val res = Scenarios.runConnectionSetup(DemoApp.instance.clients)
                            mainHandler.post {
                                setupResult = res
                                runningScenario = null
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                setupError = e.message
                                runningScenario = null
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            DownloadMigrationCard(
                stockUiState = stockDownloadProgress.toUiState(),
                sarieUiState = sarieDownloadProgress.toUiState(),
                isRunning = runningScenario == ScenarioType.DOWNLOAD,
                isAnyRunning = runningScenario != null,
                onRun = {
                    runningScenario = ScenarioType.DOWNLOAD
                    stockDownloadProgress = DownloadProgress(0, -1, false, null)
                    sarieDownloadProgress = DownloadProgress(0, -1, false, null)
                    executor?.execute {
                        try {
                            Scenarios.runDownloadMigration(
                                clients = DemoApp.instance.clients,
                                onStockProgress = { progress ->
                                    mainHandler.post { stockDownloadProgress = progress }
                                },
                                onSarieProgress = { progress ->
                                    mainHandler.post { sarieDownloadProgress = progress }
                                }
                            )
                        } finally {
                            mainHandler.post { runningScenario = null }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

@Composable
fun RoundTripCard(
    result: RoundTripResult?,
    error: String?,
    isRunning: Boolean,
    isAnyRunning: Boolean,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Round trip",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRun,
                    enabled = !isAnyRunning
                ) {
                    Text(if (isRunning) "Running..." else "Run")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("", modifier = Modifier.weight(1f))
                Text(
                    text = if (result != null) "stock ${result.stockProtocol}" else "stock",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (result != null) "Sarie ${result.sarieProtocol}" else "Sarie",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("cold", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    text = result?.let { "${it.stockColdMs} ms" } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = result?.let { it.sarieColdMs?.let { ms -> "${ms} ms" } ?: "warm (restart app)" } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("warm p50", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    text = result?.let { "${it.stockWarmP50Ms} ms" } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = result?.let { "${it.sarieWarmP50Ms} ms" } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("warm p95", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    text = result?.let { "${it.stockWarmP95Ms} ms" } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = result?.let { "${it.sarieWarmP95Ms} ms" } ?: "—",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun ParallelImagesCard(
    result: ParallelImagesResult?,
    error: String?,
    thumbs: List<ThumbItem>,
    isRunning: Boolean,
    isAnyRunning: Boolean,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Parallel images (100)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRun,
                    enabled = !isAnyRunning
                ) {
                    Text(if (isRunning) "Running..." else "Run")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(10),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(100) { index ->
                    val item = thumbs[index]
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .background(if (item.borderColor != Color.Transparent) item.borderColor else Color(0x33888888))
                            .padding(if (item.borderColor != Color.Transparent) 2.dp else 0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = item.bitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x33888888))
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("", modifier = Modifier.weight(1.2f))
                Text(
                    text = "stock",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Sarie",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            val rows = listOf(
                "total wall" to Pair(result?.let { "${it.stockWallTotalMs} ms" }, result?.let { "${it.sarieWallTotalMs} ms" }),
                "first image" to Pair(result?.let { "${it.stockFirstImageMs} ms" }, result?.let { "${it.sarieFirstImageMs} ms" }),
                "per-image p50" to Pair(result?.let { "${it.stockP50Ms} ms" }, result?.let { "${it.sarieP50Ms} ms" }),
                "per-image p95" to Pair(result?.let { "${it.stockP95Ms} ms" }, result?.let { "${it.sarieP95Ms} ms" }),
                "per-image p99" to Pair(result?.let { "${it.stockP99Ms} ms" }, result?.let { "${it.sarieP99Ms} ms" }),
                "total bytes" to Pair(result?.let { "${it.stockTotalBytes / 1024} KB" }, result?.let { "${it.sarieTotalBytes / 1024} KB" }),
            )

            for ((label, pair) in rows) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(label, fontSize = 12.sp, modifier = Modifier.weight(1.2f))
                    Text(
                        text = pair.first ?: "—",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = pair.second ?: "—",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val protocolCountsText = if (result != null) {
                val stockCountsStr = result.stockProtocolCounts.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "none" }
                val sarieCountsStr = result.sarieProtocolCounts.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "none" }
                "protocol counts: stock [$stockCountsStr] failed: ${result.stockFailedCount} · Sarie [$sarieCountsStr] failed: ${result.sarieFailedCount}"
            } else {
                "protocol counts: —"
            }
            Text(
                text = protocolCountsText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )

            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun ConnectionSetupCard(
    result: ConnectionSetupResult?,
    error: String?,
    isRunning: Boolean,
    isAnyRunning: Boolean,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connection setup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRun,
                    enabled = !isAnyRunning
                ) {
                    Text(if (isRunning) "Running..." else "Run")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("host", fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.weight(1.5f))
                Text("dns", fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text("conn", fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text("tls", fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text("ttfb", fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }

            if (result != null) {
                for (row in result.rows) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${row.host} (${row.stack})",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1.5f)
                        )
                        fun formatCell(value: Long?, reused: Boolean): String = when {
                            reused -> "reused"
                            value != null -> "${value}ms"
                            else -> "—"
                        }
                        Text(
                            text = formatCell(row.dnsMs, row.socketReused),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatCell(row.connMs, row.socketReused),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatCell(row.tlsMs, row.socketReused),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (row.ttfbMs != null) "${row.ttfbMs}ms" else "—",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "QUIC folds connect+TLS into one handshake; Cronet's ssl range sits inside connect for QUIC.",
                fontSize = 11.sp
            )

            if (error != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun DownloadMigrationCard(
    stockUiState: DownloadUiState,
    sarieUiState: DownloadUiState,
    isRunning: Boolean,
    isAnyRunning: Boolean,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Big download / migration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRun,
                    enabled = !isAnyRunning
                ) {
                    Text(if (isRunning) "Running..." else "Run")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            DownloadRow(label = "stock", color = Color(0xFFFF9800), uiState = stockUiState)

            Spacer(modifier = Modifier.height(8.dp))

            DownloadRow(label = "Sarie", color = Color(0xFF4CAF50), uiState = sarieUiState)

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Real device: switch Wi-Fi ↔ mobile during the download. QUIC connection migration keeps H3 going; the TCP connection breaks.",
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun DownloadRow(
    label: String,
    color: Color,
    uiState: DownloadUiState,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp)
        )

        if (uiState.isIndeterminate) {
            LinearProgressIndicator(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                color = color,
                trackColor = Color(0x33888888)
            )
        } else {
            LinearProgressIndicator(
                progress = { uiState.progress },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                color = color,
                trackColor = Color(0x33888888)
            )
        }

        Text(
            text = uiState.mbText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp)
        )

        Text(
            text = uiState.statusText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = uiState.statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(72.dp)
                .padding(start = 8.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DemoScreenPreview() {
    MaterialTheme {
        DemoScreen()
    }
}
