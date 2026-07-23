package com.prompter.app.asr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * SenseVoice 모델 파일 관리.
 * 최초 1회만 다운로드하며, 이후 인식은 완전 오프라인으로 동작한다.
 * 다운로드가 싫으면 아래 경로에 직접 파일을 넣어도 된다 (adb push):
 *   /sdcard/Android/data/com.prompter.app/files/model/model.int8.onnx
 *   /sdcard/Android/data/com.prompter.app/files/model/tokens.txt
 */
object ModelManager {

    // sherpa-onnx 공식 배포 모델: sense-voice zh/en/ja/ko/yue (int8, ~240MB)
    private const val BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

    private val FILES = mapOf(
        "model.int8.onnx" to "$BASE/model.int8.onnx",
        "tokens.txt" to "$BASE/tokens.txt",
    )

    fun modelDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "model").apply { mkdirs() }

    fun isReady(ctx: Context): Boolean {
        val dir = modelDir(ctx)
        return FILES.keys.all { File(dir, it).let { f -> f.exists() && f.length() > 0 } }
    }

    /**
     * 누락된 파일만 다운로드. onProgress(파일명, 받은바이트, 전체바이트)
     */
    suspend fun download(
        ctx: Context,
        onProgress: (String, Long, Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = modelDir(ctx)
            for ((name, url) in FILES) {
                val dest = File(dir, name)
                if (dest.exists() && dest.length() > 0) continue
                val tmp = File(dir, "$name.part")

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(
                        Exception("$name 다운로드 실패 (HTTP ${conn.responseCode})")
                    )
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            onProgress(name, got, total)
                        }
                    }
                }
                if (!tmp.renameTo(dest)) return@withContext Result.failure(Exception("$name 저장 실패"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
