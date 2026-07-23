package com.prompter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prompter.app.asr.ModelManager
import com.prompter.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("prompter", MODE_PRIVATE) }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchPrompter()
            else Toast.makeText(this, R.string.mic_needed, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.scriptInput.setText(prefs.getString("script", ""))
        refreshModelStatus()

        b.btnDownload.setOnClickListener { downloadModel() }

        b.btnStart.setOnClickListener {
            val script = b.scriptInput.text.toString()
            if (script.isBlank()) {
                Toast.makeText(this, R.string.script_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("script", script).apply()
            if (!ModelManager.isReady(this)) {
                Toast.makeText(this, R.string.model_missing, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) launchPrompter()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchPrompter() {
        startActivity(Intent(this, PrompterActivity::class.java))
    }

    private fun refreshModelStatus() {
        val ready = ModelManager.isReady(this)
        b.modelStatus.setText(if (ready) R.string.model_ready else R.string.model_missing)
        b.btnDownload.isEnabled = !ready
    }

    private fun downloadModel() {
        b.btnDownload.isEnabled = false
        b.modelStatus.text = getString(R.string.downloading, "", 0)
        lifecycleScope.launch(Dispatchers.IO) {
            var lastPct = -1
            val result = ModelManager.download(this@MainActivity) { name, got, total ->
                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    runOnUiThread {
                        b.modelStatus.text = getString(R.string.downloading, name, pct)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                result.onFailure {
                    Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show()
                    b.btnDownload.isEnabled = true
                }
                refreshModelStatus()
            }
        }
    }
}
