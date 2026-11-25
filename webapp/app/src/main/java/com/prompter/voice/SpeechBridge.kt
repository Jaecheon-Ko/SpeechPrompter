package com.prompter.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Android 네이티브 SpeechRecognizer 를 웹의 SpeechRecognition 처럼 보이게 하는 브리지.
 *
 * - JS(speech-polyfill.js) → [JsApi] 로 start/stop/abort 호출
 * - 네이티브 → window.__androidSR.dispatch(id, type, data) 로 이벤트 전달
 *
 * Android 인식기는 한 문장이 끝나면 세션이 끝나므로, continuous 모드에서는
 * 내부적으로 즉시 재시작해 JS 쪽에는 끊김 없는 하나의 세션으로 보이게 한다.
 */
class SpeechBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val requestMicPermission: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val muter = BeepMuter(activity)

    val isAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    private var recognizer: SpeechRecognizer? = null

    private var sessionId = 0          // JS 쪽 세션 id
    private var running = false        // JS 가 듣기를 원하는 상태
    private var continuous = true
    private var interim = true
    private var lang = "ko-KR"
    private var startedEmitted = false
    private var token = 0              // startListening 호출마다 증가 (늦게 온 콜백 무시용)
    private var lastPartial = ""
    private var listenStartedAt = 0L
    private var consecutiveErrors = 0
    private var hostPaused = false
    private var waitingPermission = false
    private var restartRunnable: Runnable? = null

    val jsApi = JsApi()

    inner class JsApi {
        @JavascriptInterface
        fun start(id: Int, lang: String?, continuous: Boolean, interim: Boolean) {
            main.post { doStart(id, lang ?: "", continuous, interim) }
        }

        @JavascriptInterface
        fun stop(id: Int) {
            main.post { if (id == sessionId && running) finishSession() }
        }

        @JavascriptInterface
        fun abort(id: Int) {
            main.post { if (id == sessionId && running) finishSession() }
        }
    }

    // ---------------------------------------------------------------- 세션 제어

    private fun doStart(id: Int, lang: String, continuous: Boolean, interim: Boolean) {
        if (running && sessionId != id) finishSession() // 이전 세션은 종료 처리
        cancelRestart()
        sessionId = id
        this.lang = normalizeLang(lang)
        this.continuous = continuous
        this.interim = interim
        running = true
        startedEmitted = false
        consecutiveErrors = 0
        lastPartial = ""

        if (!hasMicPermission()) {
            waitingPermission = true
            requestMicPermission()
            return
        }
        listen()
    }

    fun onPermissionResult(granted: Boolean) {
        if (!waitingPermission) return
        waitingPermission = false
        if (!running) return
        if (granted) {
            listen() // 아직 onPause 상태면 onHostResume 에서 시작됨
        } else {
            emit("error", JSONObject().put("error", "not-allowed"))
            finishSession()
        }
    }

    private fun listen() {
        if (!running || hostPaused || waitingPermission) return
        val rec = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(activity).also { recognizer = it }
        } catch (e: Exception) {
            scheduleRestart(1000, recreate = true)
            return
        }
        val myToken = ++token
        lastPartial = ""
        rec.setRecognitionListener(Listener(myToken))
        muter.mute()
        listenStartedAt = SystemClock.elapsedRealtime()
        try {
            rec.startListening(buildIntent())
        } catch (e: Exception) {
            scheduleRestart(500, recreate = true)
        }
    }

    private fun finishSession() {
        val wasRunning = running
        running = false
        waitingPermission = false
        cancelRestart()
        token++
        destroyRecognizer()
        muter.unmute()
        if (wasRunning) emit("end", null)
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean) {
        cancelRestart()
        val r = Runnable {
            restartRunnable = null
            if (!running || hostPaused) return@Runnable
            if (recreate) destroyRecognizer()
            listen()
        }
        restartRunnable = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelRestart() {
        restartRunnable?.let { main.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            try { it.setRecognitionListener(null) } catch (_: Exception) {}
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }

    // ---------------------------------------------------------------- 액티비티 수명주기

    fun onHostPause() {
        hostPaused = true
        cancelRestart()
        token++
        destroyRecognizer()
        muter.unmute()
    }

    fun onHostResume() {
        hostPaused = false
        if (running) listen()
    }

    fun destroy() {
        cancelRestart()
        running = false
        destroyRecognizer()
        muter.unmute()
    }

    // ---------------------------------------------------------------- 인식 콜백

    private inner class Listener(private val myToken: Int) : RecognitionListener {
        private fun live() = myToken == token && running

        override fun onReadyForSpeech(params: Bundle?) {
            if (!live()) return
            if (!startedEmitted) {
                startedEmitted = true
                emit("start", null)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            val text = firstText(partialResults)
            if (text.isEmpty()) return
            lastPartial = text
            consecutiveErrors = 0
            if (interim) emitResult(text, false)
        }

        override fun onResults(results: Bundle?) {
            if (!live()) return
            token++ // 일부 기기는 결과 직후 가짜 에러를 한 번 더 보냄 → 무시
            val text = firstText(results).ifEmpty { lastPartial }
            lastPartial = ""
            consecutiveErrors = 0
            if (text.isNotEmpty()) emitResult(text, true)
            if (continuous) scheduleRestart(30, recreate = false) else finishSession()
        }

        override fun onError(error: Int) {
            if (!live()) return
            token++
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                emit("error", JSONObject().put("error", "not-allowed"))
                finishSession()
                return
            }
            // 문장 도중 끊겼으면 마지막 중간 결과를 확정으로 넘겨 위치를 잃지 않게 한다
            if (lastPartial.isNotEmpty()) {
                emitResult(lastPartial, true)
                lastPartial = ""
            }
            if (!continuous) {
                emit("error", JSONObject().put("error", webErrorName(error)))
                finishSession()
                return
            }
            consecutiveErrors++
            val quickFail = SystemClock.elapsedRealtime() - listenStartedAt < 400
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    scheduleRestart(if (quickFail) 300 else 30, recreate = consecutiveErrors > 3)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    scheduleRestart(400, recreate = true)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER,
                ERROR_TOO_MANY_REQUESTS,
                ERROR_SERVER_DISCONNECTED ->
                    scheduleRestart(minOf(4000L, 500L * consecutiveErrors), recreate = true)
                else ->
                    scheduleRestart(250, recreate = true)
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------------------------------------------------------- 헬퍼

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            // 대부분의 엔진이 무시하지만, 지원하는 기기에선 세션이 덜 끊긴다
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }

    private fun firstText(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            .orEmpty()

    private fun emitResult(text: String, isFinal: Boolean) {
        emit("result", JSONObject().put("transcript", text).put("isFinal", isFinal))
    }

    private fun emit(type: String, data: JSONObject?) {
        val js = "window.__androidSR&&window.__androidSR.dispatch($sessionId," +
            "${JSONObject.quote(type)},${data?.toString() ?: "null"})"
        main.post { webView.evaluateJavascript(js, null) }
    }

    private fun hasMicPermission() =
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun normalizeLang(l: String): String = when {
        l.isBlank() -> "ko-KR"
        l.equals("ko-mixed", ignoreCase = true) -> "ko-KR"
        else -> l
    }

    private fun webErrorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> "network"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        else -> "aborted"
    }

    companion object {
        // API 31 상수를 minSdk 26 에서도 쓰기 위해 값으로 둔다
        private const val ERROR_TOO_MANY_REQUESTS = 10
        private const val ERROR_SERVER_DISCONNECTED = 11
    }
}

/**
 * 인식기가 재시작할 때마다 나는 "띵" 소리를 막기 위해, 듣는 동안만 시스템/알림/미디어
 * 볼륨을 음소거하고 멈추면 원래대로 되돌린다.
 */
private class BeepMuter(activity: Activity) {
    private val am = activity.getSystemService(AudioManager::class.java)
    private val streams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
    )
    private val muted = mutableSetOf<Int>()

    fun mute() {
        val am = am ?: return
        for (s in streams) {
            if (s in muted) continue
            try {
                if (am.isStreamMute(s)) continue
                am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                muted += s
            } catch (_: Exception) {
                // 방해금지 모드 등에서 SecurityException 가능 → 무시
            }
        }
    }

    fun unmute() {
        val am = am ?: return
        for (s in muted) {
            try { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        muted.clear()
    }
}
