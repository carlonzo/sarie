package sarie.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SarieRowBackground = Color(0xFF4CAF50).copy(alpha = 0.2f)

private const val LABEL_WEIGHT = 1.4f

@Composable
fun ConnectionBenchCard(
    rows: List<HostBench>?,
    status: String?,
    error: String?,
    isRunning: Boolean,
    isAnyRunning: Boolean,
    onEditUrls: () -> Unit,
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
                    text = "Connection benchmark",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onEditUrls, enabled = !isAnyRunning) {
                    Text("URLs")
                }
                Button(
                    onClick = onRun,
                    enabled = !isAnyRunning
                ) {
                    Text(if (isRunning) "Running..." else "Run")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            BenchRow(
                label = "ms",
                cells = listOf("dns", "conn", "tls", "ttfb", "warm", "resum"),
                bold = true,
            )

            for (host in rows.orEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = host.host,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StackRow("stock", host.stock, background = Color.Transparent)
                StackRow("Sarie", host.sarie, background = SarieRowBackground)
            }

            if (status != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = status, fontSize = 11.sp, color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "dns/conn/tls/ttfb: first request of the run (↺ = connection reused). " +
                    "warm: p50 time to headers over ${ConnectionBench.WARM_REQUESTS} requests on the open connection. " +
                    "resum: ttfb of a new connection after every connection closed; " +
                    "TLS 1.3 resumption for stock (TCP + TLS + request = 3 RTT), " +
                    "QUIC 0-RTT for Sarie (request rides the first flight = 1 RTT). " +
                    "QUIC's conn/tls span the whole handshake, which a 0-RTT request overlaps, " +
                    "so compare ttfb, not conn.",
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
private fun StackRow(name: String, bench: StackBench, background: Color) {
    val first = bench.first
    val protocol = first?.protocol?.takeIf { it != "error" }?.let { " $it" } ?: ""
    fun phase(value: Long?) = when {
        first == null -> "…"
        first.error != null -> "err"
        first.reused -> "↺"
        value != null -> "$value"
        else -> "—"
    }
    val resumed = bench.resumed
    val resumedCell = when {
        resumed == null -> if (first == null) "…" else ""
        resumed.error != null -> "err"
        resumed.reused -> "↺"
        else -> resumed.ttfbMs?.toString() ?: "—"
    }
    BenchRow(
        label = "$name$protocol",
        cells = listOf(
            phase(first?.dnsMs),
            phase(first?.connMs),
            phase(first?.tlsMs),
            when {
                first == null -> "…"
                first.error != null -> "err"
                else -> first.ttfbMs?.toString() ?: "—"
            },
            bench.warmP50Ms?.toString() ?: "",
            resumedCell,
        ),
        background = background,
    )
}

@Composable
private fun BenchRow(
    label: String,
    cells: List<String>,
    bold: Boolean = false,
    background: Color = Color.Transparent,
) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = weight,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.weight(LABEL_WEIGHT)
        )
        for (cell in cells) {
            Text(
                text = cell,
                fontFamily = FontFamily.Monospace,
                fontWeight = weight,
                fontSize = 11.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun BenchUrlsDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val urls = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    fun add() {
        BenchUrls.validate(input).fold(
            onSuccess = { url ->
                if (url in urls) {
                    inputError = "already in the list"
                } else {
                    urls.add(url)
                    input = ""
                    inputError = null
                }
            },
            onFailure = { inputError = it.message },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Benchmark URLs") },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    for (url in urls.toList()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = url,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { urls.remove(url) }) {
                                Text("Remove", fontSize = 12.sp)
                            }
                        }
                    }
                    if (urls.isEmpty()) {
                        Text("No URLs", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            inputError = null
                        },
                        placeholder = { Text("https://…", fontSize = 12.sp) },
                        singleLine = true,
                        isError = inputError != null,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { add() }, enabled = input.isNotBlank()) {
                        Text("Add")
                    }
                }
                if (inputError != null) {
                    Text(inputError!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "New hosts get a QUIC hint on the next app launch; until then Cronet finds h3 through Alt-Svc.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(urls.toList()) }, enabled = urls.isNotEmpty()) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    urls.clear()
                    urls.addAll(BenchUrls.DEFAULTS)
                }) {
                    Text("Defaults")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
