package sarie.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chuckerteam.chucker.api.Chucker
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import org.chromium.net.CronetProvider
import sarie.bridge.SarieBridge

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvPeek: TextView
    private lateinit var peekBar: View
    private lateinit var tabLayout: TabLayout
    private lateinit var btnClear: MaterialButton
    private lateinit var btnChucker: MaterialButton
    private lateinit var rvLogs: RecyclerView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private val logAdapter = LogAdapter()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Round Trip card views
    private lateinit var btnRunRoundTrip: MaterialButton
    private lateinit var tvStockHeader: TextView
    private lateinit var tvSarieHeader: TextView
    private lateinit var tvStockCold: TextView
    private lateinit var tvSarieCold: TextView
    private lateinit var tvStockWarmP50: TextView
    private lateinit var tvSarieWarmP50: TextView
    private lateinit var tvStockWarmP95: TextView
    private lateinit var tvSarieWarmP95: TextView
    private lateinit var tvRoundTripStatus: TextView

    // Parallel Images card views
    private lateinit var btnRunParallel: MaterialButton
    private lateinit var rvThumbs: RecyclerView
    private lateinit var tvParallelStockHeader: TextView
    private lateinit var tvParallelSarieHeader: TextView
    private lateinit var tvStockWallTotal: TextView
    private lateinit var tvSarieWallTotal: TextView
    private lateinit var tvStockFirstImage: TextView
    private lateinit var tvSarieFirstImage: TextView
    private lateinit var tvStockP50: TextView
    private lateinit var tvSarieP50: TextView
    private lateinit var tvStockP95: TextView
    private lateinit var tvSarieP95: TextView
    private lateinit var tvStockP99: TextView
    private lateinit var tvSarieP99: TextView
    private lateinit var tvStockTotalBytes: TextView
    private lateinit var tvSarieTotalBytes: TextView
    private lateinit var tvProtocolCounts: TextView
    private lateinit var tvParallelStatus: TextView
    private val thumbAdapter = ThumbAdapter()

    // Connection Setup card views
    private lateinit var btnRunSetup: MaterialButton
    private lateinit var tableSetup: android.widget.TableLayout
    private lateinit var tvSetupStatus: TextView

    // Download Migration card views
    private lateinit var btnRunDownload: MaterialButton
    private lateinit var pbStock: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var tvStockProgress: TextView
    private lateinit var tvStockStatus: TextView
    private lateinit var pbSarie: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var tvSarieProgress: TextView
    private lateinit var tvSarieStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        tvPeek = findViewById(R.id.tvPeek)
        peekBar = findViewById(R.id.peekBar)
        tabLayout = findViewById(R.id.tabLayout)
        btnClear = findViewById(R.id.btnClear)
        btnChucker = findViewById(R.id.btnChucker)
        rvLogs = findViewById(R.id.rvLogs)

        val bottomSheet: View = findViewById(R.id.bottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        // Round Trip views
        btnRunRoundTrip = findViewById(R.id.btnRunRoundTrip)
        tvStockHeader = findViewById(R.id.tvStockHeader)
        tvSarieHeader = findViewById(R.id.tvSarieHeader)
        tvStockCold = findViewById(R.id.tvStockCold)
        tvSarieCold = findViewById(R.id.tvSarieCold)
        tvStockWarmP50 = findViewById(R.id.tvStockWarmP50)
        tvSarieWarmP50 = findViewById(R.id.tvSarieWarmP50)
        tvStockWarmP95 = findViewById(R.id.tvStockWarmP95)
        tvSarieWarmP95 = findViewById(R.id.tvSarieWarmP95)
        tvRoundTripStatus = findViewById(R.id.tvRoundTripStatus)

        btnRunRoundTrip.setOnClickListener {
            runRoundTripScenario()
        }

        // Parallel Images views
        btnRunParallel = findViewById(R.id.btnRunParallel)
        rvThumbs = findViewById(R.id.rvThumbs)
        tvParallelStockHeader = findViewById(R.id.tvParallelStockHeader)
        tvParallelSarieHeader = findViewById(R.id.tvParallelSarieHeader)
        tvStockWallTotal = findViewById(R.id.tvStockWallTotal)
        tvSarieWallTotal = findViewById(R.id.tvSarieWallTotal)
        tvStockFirstImage = findViewById(R.id.tvStockFirstImage)
        tvSarieFirstImage = findViewById(R.id.tvSarieFirstImage)
        tvStockP50 = findViewById(R.id.tvStockP50)
        tvSarieP50 = findViewById(R.id.tvSarieP50)
        tvStockP95 = findViewById(R.id.tvStockP95)
        tvSarieP95 = findViewById(R.id.tvSarieP95)
        tvStockP99 = findViewById(R.id.tvStockP99)
        tvSarieP99 = findViewById(R.id.tvSarieP99)
        tvStockTotalBytes = findViewById(R.id.tvStockTotalBytes)
        tvSarieTotalBytes = findViewById(R.id.tvSarieTotalBytes)
        tvProtocolCounts = findViewById(R.id.tvProtocolCounts)
        tvParallelStatus = findViewById(R.id.tvParallelStatus)

        rvThumbs.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 10)
        rvThumbs.adapter = thumbAdapter

        btnRunParallel.setOnClickListener {
            runParallelScenario()
        }

        // Connection Setup views
        btnRunSetup = findViewById(R.id.btnRunSetup)
        tableSetup = findViewById(R.id.tableSetup)
        tvSetupStatus = findViewById(R.id.tvSetupStatus)

        btnRunSetup.setOnClickListener {
            runSetupScenario()
        }

        // Download Migration views
        btnRunDownload = findViewById(R.id.btnRunDownload)
        pbStock = findViewById(R.id.pbStock)
        tvStockProgress = findViewById(R.id.tvStockProgress)
        tvStockStatus = findViewById(R.id.tvStockStatus)
        pbSarie = findViewById(R.id.pbSarie)
        tvSarieProgress = findViewById(R.id.tvSarieProgress)
        tvSarieStatus = findViewById(R.id.tvSarieStatus)

        btnRunDownload.setOnClickListener {
            runDownloadScenario()
        }

        updateToolbarSubtitle()

        tabLayout.addTab(tabLayout.newTab().setText("Sarie"))
        tabLayout.addTab(tabLayout.newTab().setText("Network"))
        tabLayout.addTab(tabLayout.newTab().setText("Cronet"))

        val layoutManager = LinearLayoutManager(this)
        rvLogs.layoutManager = layoutManager
        rvLogs.adapter = logAdapter

        peekBar.setOnClickListener {
            if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            } else if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            }
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updateLogsList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        btnClear.setOnClickListener {
            DemoLog.clear(tabLayout.selectedTabPosition)
            updateLogsList()
        }

        btnChucker.setOnClickListener {
            val intent = Chucker.getLaunchIntent(this)
            startActivity(intent)
        }

        DemoLog.onChangeListener = {
            if (!isFinishing && !isDestroyed) {
                updatePeekText()
                updateLogsList()
            }
        }

        updatePeekText()
        updateLogsList()
    }

    override fun onResume() {
        super.onResume()
        updateToolbarSubtitle()
    }

    private fun updateToolbarSubtitle() {
        val providers = CronetProvider.getAllProviders(this)
        val activeProvider = providers.firstOrNull { it.isEnabled }
        val version = SarieBridge.engine?.versionString ?: ""
        toolbar.subtitle = if (activeProvider != null) {
            "engine: ${activeProvider.name} $version"
        } else {
            "engine: none"
        }
    }

    private fun updatePeekText() {
        tvPeek.text = "Cronet ${DemoLog.cronetCount.get()} · stock ${DemoLog.stockCount.get()} · ${DemoLog.lastLine}"
    }

    private fun updateLogsList() {
        val lines = DemoLog.getLines(tabLayout.selectedTabPosition)
        logAdapter.submitList(lines)
        if (lines.isNotEmpty()) {
            rvLogs.scrollToPosition(lines.size - 1)
        }
    }

    private fun setRunButtonsEnabled(enabled: Boolean) {
        btnRunRoundTrip.isEnabled = enabled
        btnRunParallel.isEnabled = enabled
        btnRunSetup.isEnabled = enabled
        btnRunDownload.isEnabled = enabled
    }

    private fun runRoundTripScenario() {
        setRunButtonsEnabled(false)
        btnRunRoundTrip.text = "Running..."
        tvRoundTripStatus.visibility = View.GONE

        executor.execute {
            try {
                val result = Scenarios.runRoundTrip(DemoApp.instance.clients)
                runOnUiThread {
                    tvStockHeader.text = "stock ${result.stockProtocol}"
                    tvSarieHeader.text = "Sarie ${result.sarieProtocol}"
                    tvStockCold.text = "${result.stockColdMs} ms"
                    tvSarieCold.text = result.sarieColdMs?.let { "${it} ms" } ?: "restart app"
                    tvStockWarmP50.text = "${result.stockWarmP50Ms} ms"
                    tvSarieWarmP50.text = "${result.sarieWarmP50Ms} ms"
                    tvStockWarmP95.text = "${result.stockWarmP95Ms} ms"
                    tvSarieWarmP95.text = "${result.sarieWarmP95Ms} ms"
                    btnRunRoundTrip.text = "Run"
                    setRunButtonsEnabled(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvRoundTripStatus.visibility = View.VISIBLE
                    tvRoundTripStatus.text = "Error: ${e.message}"
                    btnRunRoundTrip.text = "Run"
                    setRunButtonsEnabled(true)
                }
            }
        }
    }

    private fun runParallelScenario() {
        setRunButtonsEnabled(false)
        btnRunParallel.text = "Running..."
        tvParallelStatus.visibility = View.GONE

        val stockColor = android.graphics.Color.parseColor("#FF9800")
        val sarieColor = android.graphics.Color.parseColor("#4CAF50")

        executor.execute {
            try {
                val result = Scenarios.runParallelImages(DemoApp.instance.clients) { index, bitmap, stack ->
                    runOnUiThread {
                        val color = if (stack == Scenarios.Stack.STOCK) stockColor else sarieColor
                        thumbAdapter.updateImage(index, bitmap, color)
                    }
                }
                runOnUiThread {
                    tvStockWallTotal.text = "${result.stockWallTotalMs} ms"
                    tvSarieWallTotal.text = "${result.sarieWallTotalMs} ms"
                    tvStockFirstImage.text = "${result.stockFirstImageMs} ms"
                    tvSarieFirstImage.text = "${result.sarieFirstImageMs} ms"
                    tvStockP50.text = "${result.stockP50Ms} ms"
                    tvSarieP50.text = "${result.sarieP50Ms} ms"
                    tvStockP95.text = "${result.stockP95Ms} ms"
                    tvSarieP95.text = "${result.sarieP95Ms} ms"
                    tvStockP99.text = "${result.stockP99Ms} ms"
                    tvSarieP99.text = "${result.sarieP99Ms} ms"
                    tvStockTotalBytes.text = "${result.stockTotalBytes / 1024} KB"
                    tvSarieTotalBytes.text = "${result.sarieTotalBytes / 1024} KB"

                    val stockCountsStr = result.stockProtocolCounts.entries.joinToString { "${it.key}: ${it.value}" }
                    val sarieCountsStr = result.sarieProtocolCounts.entries.joinToString { "${it.key}: ${it.value}" }
                    tvProtocolCounts.text = "protocol counts: stock [$stockCountsStr] · Sarie [$sarieCountsStr]"

                    btnRunParallel.text = "Run"
                    setRunButtonsEnabled(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvParallelStatus.visibility = View.VISIBLE
                    tvParallelStatus.text = "Error: ${e.message}"
                    btnRunParallel.text = "Run"
                    setRunButtonsEnabled(true)
                }
            }
        }
    }

    private fun runSetupScenario() {
        setRunButtonsEnabled(false)
        btnRunSetup.text = "Running..."
        tvSetupStatus.visibility = View.GONE

        executor.execute {
            try {
                val result = Scenarios.runConnectionSetup(DemoApp.instance.clients)
                runOnUiThread {
                    if (tableSetup.childCount > 1) {
                        tableSetup.removeViews(1, tableSetup.childCount - 1)
                    }
                    val mono = android.graphics.Typeface.MONOSPACE
                    for (row in result.rows) {
                        val tr = android.widget.TableRow(this)
                        tr.addView(android.widget.TextView(this).apply {
                            text = "${row.host} (${row.stack})"
                            textSize = 11f
                            typeface = mono
                        })
                        fun addCell(value: Long?) {
                            tr.addView(android.widget.TextView(this).apply {
                                text = value?.let { "${it}ms" } ?: "—"
                                gravity = android.view.Gravity.END
                                textSize = 11f
                                typeface = mono
                            })
                        }
                        addCell(row.dnsMs)
                        addCell(row.connMs)
                        addCell(row.tlsMs)
                        addCell(row.ttfbMs)
                        tableSetup.addView(tr)
                    }
                    btnRunSetup.text = "Run"
                    setRunButtonsEnabled(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvSetupStatus.visibility = View.VISIBLE
                    tvSetupStatus.text = "Error: ${e.message}"
                    btnRunSetup.text = "Run"
                    setRunButtonsEnabled(true)
                }
            }
        }
    }

    private fun runDownloadScenario() {
        setRunButtonsEnabled(false)
        btnRunDownload.text = "Running..."
        pbStock.progress = 0
        pbSarie.progress = 0
        tvStockProgress.text = "0.0 MB"
        tvSarieProgress.text = "0.0 MB"
        tvStockStatus.text = "starting..."
        tvSarieStatus.text = "starting..."
        tvStockStatus.setTextColor(android.graphics.Color.GRAY)
        tvSarieStatus.setTextColor(android.graphics.Color.GRAY)

        executor.execute {
            try {
                Scenarios.runDownloadMigration(
                    clients = DemoApp.instance.clients,
                    onStockProgress = { progress ->
                        runOnUiThread {
                            val total = if (progress.totalBytes > 0) progress.totalBytes else 8_927_529L
                            val pct = ((progress.bytesRead.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
                            pbStock.progress = pct
                            val mb = progress.bytesRead / (1024.0 * 1024.0)
                            tvStockProgress.text = String.format(java.util.Locale.US, "%.1f MB", mb)
                            if (progress.isComplete) {
                                if (progress.error == null) {
                                    tvStockStatus.text = "ok"
                                    tvStockStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                                } else {
                                    tvStockStatus.text = "err: ${progress.error}"
                                    tvStockStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
                                }
                            } else {
                                tvStockStatus.text = "downloading..."
                            }
                        }
                    },
                    onSarieProgress = { progress ->
                        runOnUiThread {
                            val total = if (progress.totalBytes > 0) progress.totalBytes else 8_927_529L
                            val pct = ((progress.bytesRead.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
                            pbSarie.progress = pct
                            val mb = progress.bytesRead / (1024.0 * 1024.0)
                            tvSarieProgress.text = String.format(java.util.Locale.US, "%.1f MB", mb)
                            if (progress.isComplete) {
                                if (progress.error == null) {
                                    tvSarieStatus.text = "ok"
                                    tvSarieStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                                } else {
                                    tvSarieStatus.text = "err: ${progress.error}"
                                    tvSarieStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
                                }
                            } else {
                                tvSarieStatus.text = "downloading..."
                            }
                        }
                    },
                )
            } finally {
                runOnUiThread {
                    btnRunDownload.text = "Run"
                    setRunButtonsEnabled(true)
                }
            }
        }
    }

    class ThumbItem(
        var bitmap: android.graphics.Bitmap? = null,
        var borderColor: Int = android.graphics.Color.TRANSPARENT,
    )

    class ThumbAdapter : RecyclerView.Adapter<ThumbAdapter.ViewHolder>() {
        val items = Array(100) { ThumbItem() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false)
            return ViewHolder(view as android.widget.ImageView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.imageView.setImageBitmap(item.bitmap)
            if (item.borderColor != android.graphics.Color.TRANSPARENT) {
                holder.imageView.setPadding(2, 2, 2, 2)
                holder.imageView.setBackgroundColor(item.borderColor)
            } else {
                holder.imageView.setPadding(0, 0, 0, 0)
                holder.imageView.setBackgroundColor(android.graphics.Color.parseColor("#33888888"))
            }
        }

        override fun getItemCount(): Int = 100

        fun updateImage(index: Int, bitmap: android.graphics.Bitmap?, color: Int) {
            items[index].bitmap = bitmap
            items[index].borderColor = color
            notifyItemChanged(index)
        }

        class ViewHolder(val imageView: android.widget.ImageView) : RecyclerView.ViewHolder(imageView)
    }

    class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        private var items: List<String> = emptyList()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_row, parent, false)
            return ViewHolder(view as TextView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.textView.text = items[position]
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }
}
