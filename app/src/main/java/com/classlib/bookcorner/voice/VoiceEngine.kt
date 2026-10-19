package com.classlib.bookcorner.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import com.classlib.bookcorner.App
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * 语音的两级实现：
 *  L1 系统识别器（若米兔系统注册了）——由 Activity 直接用 startActivityForResult 发起
 *  L2 Vosk 离线识别——本类负责
 *  L3 快选——UI 层保底，不在这里
 */
object VoiceProbe {

    /**
     * 判断系统是否有语音识别 Activity。
     * 米兔上有小爱同学，有可能注册了 RECOGNIZE_SPEECH；targetSdk=28 不需 <queries>。
     */
    fun hasSystemRecognizer(ctx: Context): Boolean = try {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        ctx.packageManager.queryIntentActivities(i, 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "说姓名")
    }
}

/**
 * Vosk 离线识别。模型放在 assets/model-cn（vosk-model-small-cn-0.22 解压后的内容）。
 *
 * 如果模型不存在，[prepare] 会回调 onError，调用方应降级到 L3 快选。
 */
class VoskEngine(private val ctx: Context) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    @Volatile
    var ready: Boolean = false
        private set

    /** 异步加载模型。同一进程只加载一次。 */
    fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        if (ready) {
            onReady()
            return
        }
        try {
            StorageService.unpack(
                ctx,
                MODEL_ASSET_DIR,
                MODEL_TARGET_DIR,
                StorageService.Callback<Model> { m ->
                    model = m
                    ready = true
                    App.ui { onReady() }
                },
                StorageService.Callback<IOException> { e ->
                    Log.w(TAG, "unpack failed", e)
                    App.ui { onError("语音模型未就绪：${e.message}") }
                }
            )
        } catch (e: Throwable) {
            Log.w(TAG, "unpack threw", e)
            onError("语音模型加载失败：${e.message}")
        }
    }

    fun start(onText: (String) -> Unit, onError: (String) -> Unit) {
        val m = model
        if (m == null) {
            onError("语音模型未加载")
            return
        }
        stop()
        try {
            val r = Recognizer(m, SAMPLE_RATE)
            recognizer = r
            val svc = SpeechService(r, SAMPLE_RATE)
            speechService = svc
            val started = svc.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    // 不用中间结果，避免抖动
                }

                override fun onResult(hypothesis: String?) {
                    val text = extractText(hypothesis)
                    if (text.isNotBlank()) App.ui { onText(text) }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = extractText(hypothesis)
                    if (text.isNotBlank()) App.ui { onText(text) }
                }

                override fun onError(exception: Exception?) {
                    App.ui { onError("识别出错：${exception?.message ?: "未知"}") }
                }

                override fun onTimeout() {
                    App.ui { onError("没有听到声音") }
                }
            })

            // startListening 返回 false 表示录音没能启动（多半是被占用或权限问题）。
            // 不处理的话界面会一直停在"正在听"，孩子会以为系统坏了。
            if (!started) {
                stop()
                onError("麦克风没能启动，请重试或从名单选")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "start failed", e)
            onError("无法启动录音：${e.message}")
        }
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Throwable) {
        }
        speechService = null
        try {
            recognizer?.close()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    fun release() {
        stop()
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
        ready = false
    }

    private fun extractText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString("text", "").replace(" ", "")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "VoskEngine"
        private const val SAMPLE_RATE = 16000.0f

        /** assets 下的模型目录名 */
        const val MODEL_ASSET_DIR = "model-cn"

        /** 解压到 app 私有目录的名字 */
        private const val MODEL_TARGET_DIR = "vosk-model-cn"
    }
}
