package com.m4lych.oblivion.engine

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Isolated native-loader diagnostic screen. Mapping a library is not an engine bootstrap or
 * a Minecraft gameplay session; this Activity deliberately does not change the production
 * GameLaunchManager provider boundary.
 */
class OblivionGameActivity : AppCompatActivity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            setBackgroundColor(Color.rgb(9, 10, 18))
            setTextColor(Color.rgb(232, 229, 238))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            text = "Preparing isolated native loader…"
            contentDescription = text
        }
        setContentView(statusView)

        try {
            System.loadLibrary(NATIVE_LIBRARY)
        } catch (error: UnsatisfiedLinkError) {
            showFailure("Oblivion's diagnostic native library could not be loaded.")
            Log.e(TAG, "Could not load $NATIVE_LIBRARY", error)
            return
        }

        val targetDir = File(filesDir, ENGINE_DIRECTORY)
        val directoryReady = runCatching { targetDir.isDirectory || targetDir.mkdirs() }
            .getOrDefault(false)
        if (!directoryReady) {
            showFailure("Oblivion could not create its private engine staging directory.")
            return
        }

        lifecycleScope.launch {
            val mapped = withContext(Dispatchers.IO) {
                runCatching { bootstrapEngine(targetDir.absolutePath) }
                    .onFailure { Log.e(TAG, "Native loader call failed", it) }
                    .getOrDefault(false)
            }
            if (isFinishing || isDestroyed) return@launch
            if (mapped) {
                statusView.text =
                    "Native library mapped for diagnostics. The game entry point was not invoked, and no Minecraft gameplay was started."
                statusView.contentDescription = statusView.text
                Log.i(TAG, "Native library mapping completed; gameplay was not initialized")
            } else {
                showFailure(
                    "The engine library could not be mapped. No Minecraft gameplay was started. " +
                        "Check the diagnostic log and the authorized runtime files staged in Oblivion's private directory."
                )
            }
        }
    }

    private fun showFailure(message: String) {
        statusView.text = message
        statusView.contentDescription = message
        Log.e(TAG, message)
    }

    private external fun bootstrapEngine(sandboxDir: String): Boolean

    companion object {
        private const val TAG = "OblivionGameActivity"
        private const val ENGINE_DIRECTORY = "mcpe_env"
        private const val NATIVE_LIBRARY = "oblivion_engine"
    }
}
