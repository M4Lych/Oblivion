package com.m4lych.oblivion.hostspike

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class HostCompatibilityActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingProbe: Future<*>? = null
    private lateinit var logger: SpikeLogger
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger = SpikeLogger(applicationContext)
        logger.event("activity_created", mapOf("android_api" to android.os.Build.VERSION.SDK_INT))
        buildScreen()
        // Package/device inspection is idempotent. Re-run after activity/process recreation
        // so the restored screen never remains stuck in a stale loading state.
        runProbe()
    }

    override fun onStart() {
        super.onStart()
        logger.event("activity_started")
    }

    override fun onResume() {
        super.onResume()
        logger.event("activity_resumed")
    }

    override fun onPause() {
        logger.event("activity_paused")
        super.onPause()
    }

    override fun onStop() {
        logger.event("activity_stopped")
        super.onStop()
    }

    override fun onDestroy() {
        pendingProbe?.cancel(true)
        worker.shutdownNow()
        logger.event("activity_destroyed", mapOf("changing_configuration" to isChangingConfigurations))
        super.onDestroy()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 10, 18))
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }

        column.addView(text("OBLIVION / EXPERIMENT", 13, Color.rgb(199, 173, 116), true))
        column.addView(text("MCBE host compatibility probe", 25, Color.rgb(236, 233, 242), true, dp(10)))
        column.addView(text("This isolated app checks Android-visible package and device metadata. It does not launch Minecraft or load its runtime.", 15, Color.rgb(200, 197, 209), false, dp(12)))

        status = text("Waiting for compatibility check…", 18, Color.rgb(119, 184, 177), true, dp(24))
        column.addView(status)

        details = text("No probe result yet.", 14, Color.rgb(200, 197, 209), false, dp(12)).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        column.addView(details)

        column.addView(text("HOST BOUNDARY", 12, Color.rgb(199, 173, 116), true, dp(24)))
        column.addView(text("No authorized public Minecraft Android runtime interface is available to this experiment. An installed package, exported launch Activity, ABI, or installer label does not grant an in-process game API or prove ownership.", 14, Color.rgb(200, 197, 209), false, dp(8)))

        refresh = Button(this).apply {
            text = "Re-check package and device metadata"
            setOnClickListener { runProbe() }
        }
        column.addView(refresh, matchWrapTop(dp(24)))

        val exit = Button(this).apply {
            text = "Exit experiment"
            setOnClickListener { finish() }
        }
        column.addView(exit, matchWrapTop(dp(10)))

        scroll.addView(column)
        setContentView(scroll)
    }

    private fun runProbe() {
        refresh.isEnabled = false
        status.text = "Inspecting public package/device metadata…"
        details.text = "No APK contents or game-private paths are read."
        logger.event("probe_started")

        pendingProbe = worker.submit {
            val result = runCatching { HostCompatibilityProbe.inspect(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refresh.isEnabled = true
                result.onSuccess { report ->
                    status.text = report.outcome.replace('_', ' ')
                    status.setTextColor(
                        if (report.installed) Color.rgb(224, 174, 129) else Color.rgb(200, 197, 209)
                    )
                    details.text = report.asDisplayText()
                    logger.event(
                        "probe_completed",
                        mapOf(
                            "outcome" to report.outcome,
                            "minecraft_installed" to report.installed,
                            "android_api" to report.androidApi,
                            "device_manufacturer" to report.deviceManufacturer,
                            "device_model" to report.deviceModel,
                            "android_release" to report.androidRelease,
                            "process_64_bit" to report.processIs64Bit,
                            "supported_abis" to report.supportedAbis.joinToString()
                        )
                    )
                }.onFailure { error ->
                    status.text = "Compatibility probe failed"
                    status.setTextColor(Color.rgb(255, 128, 128))
                    details.text = "${error.javaClass.simpleName}: package metadata could not be read. No game launch was attempted."
                    logger.event("probe_failed", mapOf("error_type" to error.javaClass.simpleName))
                }
            }
        }
    }

    private fun text(value: String, sizeSp: Int, color: Int, bold: Boolean, top: Int = 0): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp.toFloat()
            setTextColor(color)
            gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (top > 0) setPadding(0, top, 0, 0)
            layoutParams = matchWrap()
        }

    private fun matchWrapTop(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
