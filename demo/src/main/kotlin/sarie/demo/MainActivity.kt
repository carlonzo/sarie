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
