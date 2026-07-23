package com.prompter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.prompter.app.asr.ModelManager
import com.prompter.app.data.Script
import com.prompter.app.data.ScriptAdapter
import com.prompter.app.data.ScriptFileReader
import com.prompter.app.data.ScriptStore
import com.prompter.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("prompter", MODE_PRIVATE) }
    private lateinit var adapter: ScriptAdapter

    /** 파일이나 목록에서 불러온 경우 그 제목을 저장 제목으로 사용 */
    private var currentTitle: String? = null

    /** 마지막으로 저장(또는 로드)된 본문 — 미저장 변경 감지용 */
    private var savedText: String = ""

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchPrompter()
            else Toast.makeText(this, R.string.mic_needed, Toast.LENGTH_LONG).show()
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            try {
                val (title, text) = ScriptFileReader.read(this, uri)
                b.scriptInput.setText(text)
                currentTitle = title
                Toast.makeText(this, "\"$title\" 불러옴", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "파일 읽기 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.scriptInput.setText(prefs.getString("script", ""))
        savedText = b.scriptInput.text.toString()
        refreshModelStatus()

        adapter = ScriptAdapter(
            onTap = { s ->
                b.scriptInput.setText(s.text)
                currentTitle = s.title
                savedText = s.text
            },
            onDelete = { s -> confirmDelete(s) },
        )
        b.rvScripts.layoutManager = LinearLayoutManager(this)
        b.rvScripts.adapter = adapter
        refreshList()

        b.btnDownload.setOnClickListener { downloadModel() }
        b.btnLoadFile.setOnClickListener {
            filePicker.launch(
                arrayOf(
                    "text/plain",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                )
            )
        }
        b.btnSave.setOnClickListener { saveCurrent(toast = true) }
        b.btnClear.setOnClickListener { clearInput() }

        b.btnStart.setOnClickListener {
            val script = b.scriptInput.text.toString()
            if (script.isBlank()) {
                Toast.makeText(this, R.string.script_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveCurrent(toast = false) // 시작 시 자동 저장
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

    private fun saveCurrent(toast: Boolean) {
        val text = b.scriptInput.text.toString()
        if (text.isBlank()) return
        val title = currentTitle
            ?: text.lineSequence().first { it.isNotBlank() }.trim().take(30)
        ScriptStore.upsert(this, title, text)
        currentTitle = title
        savedText = text
        refreshList()
        if (toast) Toast.makeText(this, "저장됨", Toast.LENGTH_SHORT).show()
    }

    private fun clearInput() {
        val text = b.scriptInput.text.toString()
        if (text.isBlank()) return
        val unsaved = text != savedText
        if (unsaved) {
            AlertDialog.Builder(this)
                .setMessage("입력된 대본을 지우시겠습니까?")
                .setPositiveButton("지우기") { _, _ -> doClear() }
                .setNegativeButton("취소", null)
                .show()
        } else doClear()
    }

    private fun doClear() {
        b.scriptInput.setText("")
        currentTitle = null
        savedText = ""
    }

    private fun confirmDelete(s: Script) {
        AlertDialog.Builder(this)
            .setMessage("\"${s.title}\" 대본을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                ScriptStore.delete(this, s.id)
                refreshList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun refreshList() {
        adapter.submit(ScriptStore.load(this))
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
