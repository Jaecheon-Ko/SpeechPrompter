package com.prompter.app

import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.prompter.app.asr.AsrEngine
import com.prompter.app.asr.ModelManager
import com.prompter.app.databinding.ActivityPrompterBinding
import com.prompter.app.matcher.ScriptMatcher

class PrompterActivity : AppCompatActivity() {

    private lateinit var b: ActivityPrompterBinding
    private lateinit var matcher: ScriptMatcher
    private lateinit var script: String
    private var engine: AsrEngine? = null
    private var running = false
    private var readSpan: ForegroundColorSpan? = null
    private lateinit var spannable: SpannableString

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPrompterBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        script = getSharedPreferences("prompter", MODE_PRIVATE)
            .getString("script", "") ?: ""
        matcher = ScriptMatcher(script)
        spannable = SpannableString(script)
        b.scriptView.text = spannable

        // 폰트 크기
        b.fontSlider.addOnChangeListener { _, v, _ ->
            b.scriptView.textSize = v
        }
        b.scriptView.textSize = b.fontSlider.value

        // 미러 모드 (하프미러 유리 프롬프터용 좌우 반전)
        b.mirrorSwitch.setOnCheckedChangeListener { _, on ->
            b.scrollView.scaleX = if (on) -1f else 1f
        }

        // 시작/정지
        b.btnToggle.setOnClickListener { if (running) stopAsr() else startAsr() }

        // 처음부터 다시
        b.btnRestart.setOnClickListener {
            matcher.reset()
            clearHighlight()
            b.scrollView.smoothScrollTo(0, 0)
        }
    }

    private fun startAsr() {
        val dir = ModelManager.modelDir(this)
        try {
            engine = AsrEngine(
                modelDir = dir,
                onPartial = { text -> runOnUiThread { onRecognized(text) } },
                onError = { msg -> runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                } },
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "모델 로딩 실패: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        engine?.start()
        running = true
        b.btnToggle.setText(R.string.stop)
        b.recIndicator.alpha = 1f
    }

    private fun stopAsr() {
        engine?.release()
        engine = null
        running = false
        b.btnToggle.setText(R.string.start)
        b.recIndicator.alpha = 0.2f
    }

    private fun onRecognized(text: String) {
        b.debugText.text = text.takeLast(40)
        if (!matcher.update(text)) return

        val offset = matcher.currentOriginalOffset().coerceIn(0, script.length)
        highlightUpTo(offset)
        scrollToOffset(offset)
    }

    /** 읽은 부분을 흐리게 표시 */
    private fun highlightUpTo(offset: Int) {
        readSpan?.let(spannable::removeSpan)
        if (offset > 0) {
            val span = ForegroundColorSpan(Color.parseColor("#556677"))
            spannable.setSpan(span, 0, offset, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            readSpan = span
        }
        b.scriptView.text = spannable
    }

    private fun clearHighlight() {
        readSpan?.let(spannable::removeSpan)
        readSpan = null
        b.scriptView.text = spannable
    }

    /** 현재 읽는 줄이 화면 상단 35% 지점에 오도록 스크롤 */
    private fun scrollToOffset(offset: Int) {
        val layout = b.scriptView.layout ?: return
        val line = layout.getLineForOffset(offset.coerceAtMost(script.length - 1).coerceAtLeast(0))
        val y = layout.getLineTop(line)
        val anchor = (b.scrollView.height * 0.35f).toInt()
        b.scrollView.smoothScrollTo(0, (y - anchor).coerceAtLeast(0))
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.release()
    }
}
