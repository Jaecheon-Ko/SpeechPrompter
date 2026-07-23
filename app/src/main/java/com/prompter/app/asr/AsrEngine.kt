package com.prompter.app.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/**
 * 온디바이스 준스트리밍 음성인식 엔진.
 *
 * SenseVoice는 오프라인(비스트리밍) 모델이지만 int8 기준 추론이 매우 빨라서,
 * "말하는 동안 누적된 오디오를 0.5초마다 다시 디코딩"하는 방식으로
 * 사실상 스트리밍처럼 동작시킨다. 프롬프터 용도로는 이 방식이
 * 부분 결과의 안정성 면에서 오히려 유리하다.
 *
 * 침묵 구간은 간단한 RMS 에너지 게이트로 걸러 버퍼를 리셋한다.
 * (문장 단위로 버퍼가 비워져 디코딩 길이가 계속 짧게 유지됨)
 */
class AsrEngine(
    modelDir: File,
    private val onPartial: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val recognizer: OfflineRecognizer
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(modelDir, "model.int8.onnx").absolutePath,
                    language = "", // auto: ko/en 혼용 자동 감지
                    useInverseTextNormalization = true,
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(config = config)
    }

    @SuppressLint("MissingPermission") // 호출 측(Activity)에서 권한 확인
    fun start() {
        if (job != null) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            onError("마이크를 초기화할 수 없습니다")
            return
        }
        record = rec
        rec.startRecording()

        job = scope.launch {
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100ms
            val speech = ArrayList<Float>(SAMPLE_RATE * MAX_SEG_SEC)
            var silenceMs = 0
            var sinceDecodeMs = 0
            var speaking = false

            while (isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue

                var sum = 0.0
                val floats = FloatArray(n)
                for (i in 0 until n) {
                    val f = chunk[i] / 32768.0f
                    floats[i] = f
                    sum += f * f
                }
                val rms = sqrt(sum / n)

                if (rms > ENERGY_GATE) {
                    speaking = true
                    silenceMs = 0
                } else if (speaking) {
                    silenceMs += 100
                }

                if (speaking) {
                    floats.forEach(speech::add)
                    sinceDecodeMs += 100

                    val segmentEnd = silenceMs >= END_SILENCE_MS ||
                            speech.size >= SAMPLE_RATE * MAX_SEG_SEC
                    val timeToDecode = sinceDecodeMs >= DECODE_EVERY_MS

                    if ((timeToDecode || segmentEnd) && speech.size >= SAMPLE_RATE / 2) {
                        val text = decode(speech.toFloatArray())
                        if (text.isNotBlank()) onPartial(text)
                        sinceDecodeMs = 0
                    }
                    if (segmentEnd) {
                        speech.clear()
                        speaking = false
                        silenceMs = 0
                        sinceDecodeMs = 0
                    }
                }
            }
        }
    }

    private fun decode(samples: FloatArray): String = try {
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text
        stream.release()
        text
    } catch (e: Exception) {
        onError("디코딩 오류: ${e.message}")
        ""
    }

    fun stop() {
        job?.cancel(); job = null
        record?.run { try { stop() } catch (_: Exception) {}; release() }
        record = null
    }

    fun release() {
        stop()
        recognizer.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        /** 이 RMS 이상이면 발화 중으로 간주 (환경에 따라 조정) */
        private const val ENERGY_GATE = 0.012
        /** 이만큼 조용하면 문장 종료로 보고 버퍼 리셋 */
        private const val END_SILENCE_MS = 700
        /** 부분 결과 디코딩 주기 */
        private const val DECODE_EVERY_MS = 500
        /** 한 세그먼트 최대 길이(초) — 초과 시 강제 리셋 */
        private const val MAX_SEG_SEC = 10
    }
}
