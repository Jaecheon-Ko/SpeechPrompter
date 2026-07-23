package com.prompter.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.prompter.app.asr.AsrEngine
import com.prompter.app.asr.ModelManager
import com.prompter.app.databinding.ActivityPrompterBinding
import com.prompter.app.matcher.WordMatcher
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PrompterActivity : AppCompatActivity() {

    private lateinit var b: ActivityPrompterBinding
    private lateinit var matcher: WordMatcher
    private lateinit var script: String
    private lateinit var spannable: SpannableString
    private var engine: AsrEngine? = null
    private var engineLang = ""

    // ---- 모드 상태 ----
    private var voiceMode = true
    private var langMode = 0 // 0 한국어, 1 한영, 2 English
    private var running = false
    private var barsVisible = true

    // ---- 스크롤 루프 상태 (가이드 3-3) ----
    private var lastFrameNs = 0L
    private var lastMatchNs = 0L
    private var lastAdvanceNs = 0L
    private var wps = 2.2f           // 읽기 속도(단어/초) EMA
    private var lead = 0f            // 선행 보정
    private var maxTarget = 0f       // ★ 단조 증가 잠금
    private var manualPosF = 0f      // 수동 모드 부동소수 누적
    private var userAdjusting = false // 손 스크롤 중 — 자동 추적 일시 정지
    private var snapRunnable: Runnable? = null

    // ---- 색상 ----
    private var colorRead = 0
    private var colorAccent = 0
    private var colorUpcoming = 0
    private val spans = arrayOfNulls<ForegroundColorSpan>(3)

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            step(now)
            if (running) choreographer.postFrameCallback(this)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPrompterBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 화면 꺼짐 방지

        colorRead = ContextCompat.getColor(this, R.color.text_read)
        colorAccent = ContextCompat.getColor(this, R.color.accent)
        colorUpcoming = ContextCompat.getColor(this, R.color.text_upcoming)

        script = getSharedPreferences("prompter", MODE_PRIVATE)
            .getString("script", "") ?: ""
        matcher = WordMatcher(script)
        spannable = SpannableString(script)
        b.scriptView.text = spannable
        b.scriptView.setTextColor(colorUpcoming)
        applyHighlight()

        // 상하 패딩을 화면 높이 비례로 (첫 줄·마지막 줄도 읽기 라인 도달)
        b.scrollView.addOnLayoutChangeListener { _, _, t, _, bt, _, ot, _, ob ->
            val h = bt - t
            if (h > 0 && h != ob - ot) applyProportionalPadding(h)
        }

        // ---- 컨트롤 1행 ----
        b.voiceSwitch.isChecked = true
        b.voiceSwitch.setOnCheckedChangeListener { _, on ->
            voiceMode = on
            if (running) {
                if (on) startEngineIfNeeded() else engine?.stop()
                manualPosF = b.scrollView.scrollY.toFloat()
            }
            updateRecIndicator()
        }

        b.btnLang.setOnClickListener {
            langMode = (langMode + 1) % 3
            b.btnLang.text = langLabel()
            // 실행 중이면 엔진 언어 교체
            if (running && voiceMode) startEngineIfNeeded()
        }
        b.btnLang.text = langLabel()

        b.btnToggle.setOnClickListener { if (running) stopSession() else startSession() }

        b.btnRestart.setOnClickListener {
            matcher.reset(0)
            maxTarget = 0f
            lead = 0f
            manualPosF = 0f
            b.scrollView.scrollTo(0, 0)
            applyHighlight()
        }

        b.mirrorSwitch.setOnCheckedChangeListener { _, on ->
            b.scrollView.scaleX = if (on) -1f else 1f
        }

        // ---- 컨트롤 2행: 슬라이더 3종 ----
        b.fontSlider.addOnChangeListener { _, v, _ -> b.scriptView.textSize = v }
        b.scriptView.textSize = b.fontSlider.value

        b.posSlider.addOnChangeListener { _, v, _ -> setReadLinePercent(v / 100f) }
        setReadLinePercent(b.posSlider.value / 100f)

        b.spacingSlider.addOnChangeListener { _, v, _ ->
            b.scriptView.setLineSpacing(0f, v)
        }

        // 속도 슬라이더는 루프에서 직접 읽음 (수동 모드 전용)

        // 본문 탭 → 바 숨김/표시
        b.scriptView.setOnClickListener { toggleBars() }

        // 손 스크롤: 자동 추적을 잠시 멈추고, 손을 떼면 그 지점부터 다시 따라감
        // (뒤로 되감아 지나간 부분부터 재녹화 가능)
        b.scrollView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    userAdjusting = true
                    snapRunnable?.let(b.scrollView::removeCallbacks)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val r = Runnable {
                        manualPosF = b.scrollView.scrollY.toFloat()
                        if (running && voiceMode) snapToReadLine()
                        else maxTarget = b.scrollView.scrollY.toFloat()
                        userAdjusting = false
                    }
                    snapRunnable = r
                    b.scrollView.postDelayed(r, 700) // 관성 스크롤이 멎을 시간
                }
            }
            false
        }

        if (matcher.words.isEmpty()) {
            Toast.makeText(this, R.string.script_empty, Toast.LENGTH_LONG).show()
            b.btnToggle.isEnabled = false
        }
        updateRecIndicator()
    }

    private fun langLabel() = when (langMode) {
        0 -> "한국어"; 2 -> "English"; else -> "한영"
    }

    private fun senseVoiceLang() = when (langMode) {
        0 -> "ko"; 2 -> "en"; else -> "" // 한영 = 자동 감지
    }

    private fun applyProportionalPadding(h: Int) {
        val side = (24 * resources.displayMetrics.density).toInt()
        b.scriptView.setPadding(side, (h * 0.45f).toInt(), side, (h * 0.70f).toInt())
    }

    private fun setReadLinePercent(p: Float) {
        val lp = b.guideRead.layoutParams as ConstraintLayout.LayoutParams
        lp.guidePercent = p
        b.guideRead.layoutParams = lp
    }

    private fun readLinePercent() = b.posSlider.value / 100f

    // ================= 세션 시작/정지 =================

    private fun startSession() {
        snapToReadLine() // 정지 상태에서 손으로 옮긴 지점부터 시작
        runCountdown {
            running = true
            lastFrameNs = 0L
            lastMatchNs = 0L
            lastAdvanceNs = 0L
            lead = 0f
            maxTarget = b.scrollView.scrollY.toFloat()
            manualPosF = b.scrollView.scrollY.toFloat()
            if (voiceMode) startEngineIfNeeded()
            b.btnToggle.setText(R.string.stop)
            updateRecIndicator()
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun stopSession() {
        running = false
        engine?.stop() // 마이크만 끔 — 모델은 로드 상태 유지
        b.btnToggle.setText(R.string.start)
        updateRecIndicator()
    }

    private fun startEngineIfNeeded() {
        val lang = senseVoiceLang()
        if (engine != null && engineLang != lang) {
            engine?.release(); engine = null
        }
        if (engine == null) {
            try {
                engine = AsrEngine(
                    modelDir = ModelManager.modelDir(this),
                    language = lang,
                    onPartial = { text -> runOnUiThread { onRecognized(text) } },
                    onError = { msg -> runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    } },
                )
                engineLang = lang
            } catch (e: Throwable) {
                Toast.makeText(this, "모델 로딩 실패: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }
        engine?.start()
    }

    /** 시작 전 3-2-1 카운트다운 (각 800ms) */
    private fun runCountdown(then: () -> Unit) {
        b.countdown.visibility = View.VISIBLE
        val seq = listOf("3", "2", "1")
        seq.forEachIndexed { i, s ->
            b.countdown.postDelayed({ b.countdown.text = s }, i * 800L)
        }
        b.countdown.postDelayed({
            b.countdown.visibility = View.GONE
            then()
        }, seq.size * 800L)
    }

    // ================= 프레임 루프 (가이드 3-3) =================

    private fun step(now: Long) {
        if (lastFrameNs == 0L) { lastFrameNs = now; return }
        val dt = ((now - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
        lastFrameNs = now

        val sv = b.scrollView
        if (userAdjusting) return
        if (voiceMode) {
            if (matcher.words.isEmpty()) return
            val h = sv.height
            val sinceMatch = if (lastMatchNs == 0L) 999f else (now - lastMatchNs) / 1e9f
            // 3초 이상 무매칭 → 선행보정 0으로 감쇠 (말 멈추면 스크롤도 멈춤)
            val leadTarget = if (sinceMatch < 3f) min(120f, wps * 30f) else 0f
            lead += (leadTarget - lead) * min(1f, dt * 2f)

            var target = wordTop(matcher.curIdx) - readLinePercent() * h + lead
            target = max(target, maxTarget)
            maxTarget = target // ★ 역주행 금지

            val cur = sv.scrollY.toFloat()
            val next = cur + (target - cur) * min(1f, dt * 10f)
            sv.scrollTo(0, next.roundToInt().coerceAtLeast(0))
        } else {
            // 수동 등속 스크롤
            manualPosF += b.speedSlider.value * dt
            sv.scrollTo(0, manualPosF.roundToInt().coerceAtLeast(0))
        }
    }

    private fun wordTop(idx: Int): Float {
        val layout = b.scriptView.layout ?: return 0f
        val w = matcher.words.getOrNull(idx) ?: return 0f
        val line = layout.getLineForOffset(w.start.coerceAtMost(script.length - 1))
        return (layout.getLineTop(line) + b.scriptView.paddingTop).toFloat()
    }

    // ================= 인식 → 매칭 =================

    private fun onRecognized(text: String) {
        b.debugText.text = text.takeLast(40)
        val prev = matcher.curIdx
        if (!matcher.update(text, mixed = langMode == 1)) return

        val now = System.nanoTime()
        lastMatchNs = now
        // 읽기 속도 EMA (α=0.3, 순간값 상한 6 wps)
        if (lastAdvanceNs > 0L) {
            val dt = (now - lastAdvanceNs) / 1e9f
            if (dt > 0.05f) {
                val instant = ((matcher.curIdx - prev) / dt).coerceAtMost(6f)
                wps += 0.3f * (instant - wps)
            }
        }
        lastAdvanceNs = now
        applyHighlight()
    }

    // ================= 하이라이트 (가이드 3-2) =================

    /** 읽은 부분 어둡게 / 현재 단어 노랑 / 남은 부분 밝게 — 3구간만 갱신 */
    private fun applyHighlight() {
        val words = matcher.words
        if (words.isEmpty()) return
        val cur = matcher.curIdx
        spans.forEach { it?.let(spannable::removeSpan) }

        if (cur > 0) {
            spans[0] = ForegroundColorSpan(colorRead).also {
                spannable.setSpan(it, 0, words[cur - 1].end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else spans[0] = null
        spans[1] = ForegroundColorSpan(colorAccent).also {
            spannable.setSpan(it, words[cur].start, words[cur].end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        spans[2] = ForegroundColorSpan(colorUpcoming).also {
            spannable.setSpan(it, words[cur].end, spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.scriptView.text = spannable
    }

    // ================= 원하는 위치에서 시작 (가이드 3-4) =================

    private fun snapToReadLine() {
        val words = matcher.words
        if (words.isEmpty()) return
        val targetY = b.scrollView.scrollY + readLinePercent() * b.scrollView.height
        val nearest = words.indices.minByOrNull { abs(wordTop(it) - targetY) } ?: 0
        matcher.reset(nearest)
        maxTarget = b.scrollView.scrollY.toFloat()
        lead = 0f
        lastMatchNs = 0L
        applyHighlight()
    }

    // ================= UI 잡동사니 =================

    private fun toggleBars() {
        barsVisible = !barsVisible
        val a = if (barsVisible) 1f else 0f
        b.controlBar.animate().alpha(a).setDuration(200).withStartAction {
            if (barsVisible) b.controlBar.visibility = View.VISIBLE
        }.withEndAction {
            if (!barsVisible) b.controlBar.visibility = View.GONE
        }.start()
    }

    private fun updateRecIndicator() {
        b.recIndicator.alpha = if (running && voiceMode) 1f else 0.2f
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        engine?.release()
    }
}
