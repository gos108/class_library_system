package com.classlib.bookcorner.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.MemberDao
import com.classlib.bookcorner.model.Member
import com.classlib.bookcorner.util.NameMatcher
import com.classlib.bookcorner.voice.VoiceProbe
import com.classlib.bookcorner.voice.VoskEngine

/**
 * 选人：语音 → 拼音模糊匹配 → 候选点选 → 名单点选，四级降级。
 *
 * 注意：必须在 Activity 的 onCreate 期间构造（内部要 registerForActivityResult，
 * 处于 RESUMED 之后再注册会抛 IllegalStateException）。
 */
class PersonPicker(private val act: AppCompatActivity) {

    private val vosk = VoskEngine(act)
    private var members: List<Member> = emptyList()
    private var onPicked: ((Member) -> Unit)? = null
    private var btn: TextView? = null
    private var listening = false
    private var heard = ""

    private val systemVoice = act.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            applyName(list?.firstOrNull().orEmpty())
        }
    }

    fun loadMembers() {
        App.io.execute {
            val list = MemberDao(App.db).listActive()
            App.ui { members = list }
        }
    }

    fun attach(button: TextView, onPicked: (Member) -> Unit) {
        btn = button
        this.onPicked = onPicked
        button.setOnClickListener {
            if (listening) stopListening() else startVoice()
        }
    }

    fun release() = vosk.release()

    /** Activity 转发 onRequestPermissionsResult 时调用 */
    fun onPermissionResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQ_MIC) return false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            toast("没有麦克风权限，请从名单里选")
            showList()
        }
        return true
    }

    private fun startVoice() {
        if (VoiceProbe.hasSystemRecognizer(act)) {
            try {
                systemVoice.launch(VoiceProbe.buildIntent())
                return
            } catch (_: Throwable) {
                // 落到 Vosk
            }
        }
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        vosk.prepare(
            onReady = { startVosk() },
            onError = {
                toast("语音不可用，请从名单里选")
                showList()
            }
        )
    }

    private fun startVosk() {
        heard = ""
        listening = true
        btn?.text = "停"
        vosk.start(
            onText = { text ->
                heard = text
                stopListening()
                applyName(text)
            },
            onError = {
                stopListening()
                toast("没听清，请重说或从名单选")
            }
        )
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        vosk.stop()
        btn?.text = act.getString(R.string.btn_speak)
    }

    private fun applyName(text: String) {
        if (text.isBlank()) return
        val best = NameMatcher.best(text, members)
        if (best != null) {
            onPicked?.invoke(best)
            return
        }
        val hits = NameMatcher.match(text, members)
        if (hits.isEmpty()) {
            toast("没找到「$text」，请从名单选")
            showList()
        } else {
            val names = hits.map { it.member.name }.toTypedArray()
            AlertDialog.Builder(act)
                .setTitle("听成了「$text」，是哪一个？")
                .setItems(names) { _, i -> onPicked?.invoke(hits[i].member) }
                .show()
        }
    }

    fun showList() {
        if (members.isEmpty()) {
            toast("名册是空的，请先导入名单")
            return
        }
        val names = members.map { it.name }.toTypedArray()
        AlertDialog.Builder(act)
            .setTitle("选择")
            .setItems(names) { _, i -> onPicked?.invoke(members[i]) }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQ_MIC = 2001
    }
}
