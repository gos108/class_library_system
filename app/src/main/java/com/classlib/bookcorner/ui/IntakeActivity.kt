package com.classlib.bookcorner.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.BookDao
import com.classlib.bookcorner.db.CopyDao
import com.classlib.bookcorner.db.MemberDao
import com.classlib.bookcorner.model.Book
import com.classlib.bookcorner.model.Member
import com.classlib.bookcorner.util.NameMatcher
import com.classlib.bookcorner.util.Prefs
import com.classlib.bookcorner.voice.VoiceProbe
import com.classlib.bookcorner.voice.VoskEngine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 入库：扫码 → 带出书名 → 选分类 / 所有者 / 册数 → 写入。
 */
class IntakeActivity : AppCompatActivity() {

    private lateinit var tvBarcode: TextView
    private lateinit var etTitle: EditText
    private lateinit var tvAuthor: TextView
    private lateinit var spCategory: Spinner
    private lateinit var tvOwner: TextView
    private lateinit var tvCount: TextView

    private lateinit var prefs: Prefs
    private lateinit var vosk: VoskEngine

    private var code: String = ""
    private var isbn: String? = null
    private var author: String? = null
    private var selectedOwner: Member? = null
    private var count: Int = 1
    private var members: List<Member> = emptyList()

    private var listening = false
    private var lastHeard = ""

    private val systemVoice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = list?.firstOrNull().orEmpty()
            applyName(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intake)

        prefs = Prefs(this)
        vosk = VoskEngine(this)

        tvBarcode = findViewById(R.id.tvBarcode)
        etTitle = findViewById(R.id.etTitle)
        tvAuthor = findViewById(R.id.tvAuthor)
        spCategory = findViewById(R.id.spCategory)
        tvOwner = findViewById(R.id.tvOwner)
        tvCount = findViewById(R.id.tvCount)

        spCategory.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Prefs.CATEGORIES
        )

        code = intent.getStringExtra(ScanActivity.EXTRA_CODE).orEmpty()
        tvBarcode.text = "条码：$code"

        findViewById<TextView>(R.id.btnMinus).setOnClickListener {
            if (count > 1) {
                count--
                tvCount.text = count.toString()
            }
        }
        findViewById<TextView>(R.id.btnPlus).setOnClickListener {
            if (count < 20) {
                count++
                tvCount.text = count.toString()
            }
        }
        findViewById<TextView>(R.id.btnSpeak).setOnClickListener {
            if (listening) {
                stopListening()
            } else {
                speak()
            }
        }
        tvOwner.setOnClickListener { showPicker() }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { save() }

        loadMembers()
        resolveCode()
    }

    /* ---------------- 扫码结果处理 ---------------- */

    private fun resolveCode() {
        if (code.isEmpty()) {
            toast("没有取到条码")
            return
        }
        if (code.length == 13 && (code.startsWith("978") || code.startsWith("979"))) {
            isbn = code
            lookupIsbn(code)
            return
        }
        // 9 位内部码：说明这册书已经在库里
        App.io.execute {
            val exist = CopyDao(App.db).findByBarcode(code)
            if (exist != null) {
                App.ui {
                    toast("这本书已经入库了（${exist.barcode}）")
                    finish()
                }
            }
        }
    }

    private fun lookupIsbn(isbn: String) {
        App.io.execute {
            var t: String? = null
            var a: String? = null
            try {
                val conn = URL("https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(body)
                    val items = root.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val vi = items.getJSONObject(0).optJSONObject("volumeInfo")
                        t = vi?.optString("title")
                        val authors = vi?.optJSONArray("authors")
                        if (authors != null && authors.length() > 0) a = authors.getString(0)
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {
            }
            App.ui {
                if (!t.isNullOrBlank()) {
                    etTitle.setText(t)
                    author = a
                    tvAuthor.text = a ?: ""
                    tvAuthor.visibility = View.VISIBLE
                } else {
                    tvAuthor.text = "没查到这本书，请手动填书名"
                    tvAuthor.visibility = View.VISIBLE
                }
            }
        }
    }

    /* ---------------- 语音选所有者 ---------------- */

    private fun speak() {
        if (VoiceProbe.hasSystemRecognizer(this)) {
            try {
                systemVoice.launch(VoiceProbe.buildIntent())
                return
            } catch (_: Throwable) {
                // 落到 Vosk
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        vosk.prepare(
            onReady = { startVosk() },
            onError = {
                toast("语音不可用，请从名单里选")
                showPicker()
            }
        )
    }

    private fun startVosk() {
        lastHeard = ""
        listening = true
        findViewById<TextView>(R.id.btnSpeak).text = "停"
        vosk.start(
            onText = { text ->
                lastHeard = text
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
        findViewById<TextView>(R.id.btnSpeak).text = getString(R.string.btn_speak)
        if (lastHeard.isBlank()) {
            toast("没有听到名字")
        }
    }

    private fun applyName(text: String) {
        if (text.isBlank()) return
        val best = NameMatcher.best(text, members)
        if (best != null) {
            setOwner(best)
            return
        }
        val hits = NameMatcher.match(text, members)
        if (hits.isEmpty()) {
            toast("没找到「$text」，请从名单选")
            showPicker()
        } else {
            val names = hits.map { it.member.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("听成了「$text」，是哪一个？")
                .setItems(names) { _, i -> setOwner(hits[i].member) }
                .show()
        }
    }

    private fun setOwner(m: Member) {
        selectedOwner = m
        tvOwner.text = m.name
    }

    private fun showPicker() {
        if (members.isEmpty()) {
            toast("名册是空的，请先导入名单")
            return
        }
        val names = members.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择所有者")
            .setItems(names) { _, i -> setOwner(members[i]) }
            .show()
    }

    private fun loadMembers() {
        App.io.execute {
            val list = MemberDao(App.db).listActive()
            App.ui { members = list }
        }
    }

    /* ---------------- 保存 ---------------- */

    private fun save() {
        val title = etTitle.text.toString().trim()
        if (title.isEmpty()) {
            toast("请填写书名")
            return
        }
        val idx = spCategory.selectedItemPosition.coerceIn(0, Prefs.CATEGORIES.size - 1)
        val category = Prefs.CATEGORIES[idx]
        val ownerId = selectedOwner?.id
        val n = count
        val classCode = prefs.classCode
        val catCode = prefs.categoryCode(category)
        val isbnValue = isbn
        val authorValue = author
        val source = if (isbnValue != null) "scan" else "voice"

        App.io.execute {
            try {
                val db = App.db
                db.beginTransaction()
                val barcodes = ArrayList<String>()
                try {
                    val bookDao = BookDao(db)
                    val existing = isbnValue?.let { bookDao.findByIsbn(it) }
                    val bookId = existing?.id ?: bookDao.insert(
                        Book(
                            isbn = isbnValue,
                            title = title,
                            author = authorValue,
                            category = category,
                            source = source
                        )
                    )
                    val copyDao = CopyDao(db)
                    repeat(n) {
                        barcodes.add(copyDao.insert(bookId, ownerId, catCode, classCode).barcode)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                App.ui {
                    toast("已入库 ${barcodes.size} 册\n${barcodes.joinToString("  ")}")
                    finish()
                }
            } catch (e: Throwable) {
                App.ui { toast("入库失败：${e.message}") }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            speak()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vosk.release()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_MIC = 2001
    }
}
