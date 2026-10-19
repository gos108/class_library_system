package com.classlib.bookcorner.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.BookFinder
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView
import com.classlib.bookcorner.util.Dates

/**
 * 查书：只读。
 *
 * 同一个入口吃三种输入——扫书脊的 ISBN、系统内 9 位码、书名的一部分。
 * 没有条码的旧书、套装单册，只能靠书名找，所以这里必须有输入框。
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var etQuery: EditText
    private lateinit var llResults: LinearLayout
    private lateinit var tvCount: TextView

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val code = res.data?.getStringExtra(ScanActivity.EXTRA_CODE).orEmpty()
            if (code.isNotBlank()) {
                etQuery.setText(code)
                search(code)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        etQuery = findViewById(R.id.etQuery)
        llResults = findViewById(R.id.llResults)
        tvCount = findViewById(R.id.tvCount)

        findViewById<TextView>(R.id.btnScan).setOnClickListener {
            scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<TextView>(R.id.btnGo).setOnClickListener { search(etQuery.text.toString()) }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(etQuery.text.toString())
                true
            } else {
                false
            }
        }

        val code = intent.getStringExtra(ScanActivity.EXTRA_CODE).orEmpty()
        if (code.isNotBlank()) {
            etQuery.setText(code)
            search(code)
        } else {
            tvCount.text = "扫书上的条码，或输入书名的一部分"
        }
    }

    private fun search(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            tvCount.text = "先输点东西再查"
            return
        }
        tvCount.text = "查询中…"
        App.io.execute {
            val r = BookFinder(App.db).resolve(q)
            App.ui { render(q, r) }
        }
    }

    private fun render(q: String, r: BookFinder.Result) {
        llResults.removeAllViews()
        if (r.copies.isEmpty()) {
            tvCount.text = when {
                r.looksLikeIsbn -> "ISBN $q —— 库里没有这本书"
                else -> "没找到「$q」"
            }
            return
        }

        val total = r.copies.size
        tvCount.text = if (total > MAX_ROWS) "共 $total 册，只列前 $MAX_ROWS 册" else "共 $total 册"
        r.copies.take(MAX_ROWS).forEach { v -> llResults.addView(row(v)) }
    }

    private fun row(v: CopyView): TextView {
        val tv = TextView(this)
        tv.text = rowText(v)
        tv.textSize = 14f
        tv.setTextColor(ContextCompat.getColor(this, colorOf(v.status)))
        tv.setBackgroundResource(R.color.surface)
        tv.setPadding(dp(10), dp(8), dp(10), dp(8))
        tv.setLineSpacing(dp(3).toFloat(), 1f)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(4)
        tv.layoutParams = lp
        return tv
    }

    private fun rowText(v: CopyView): String {
        val sb = StringBuilder()
        sb.append(v.title).append("　").append(Copy.label(v.status))
        sb.append("\n").append(v.barcode).append(" · 所有者 ").append(v.ownerName ?: "未登记")
        if (v.status == Copy.STATUS_OUT) {
            val due = v.dueAt ?: 0L
            val over = Dates.overdueDays(due)
            sb.append("\n借阅人 ").append(v.borrowerName ?: "未知")
                .append(" · 应还 ").append(Dates.md(due))
            if (over > 0) sb.append("（逾期 ").append(over).append(" 天）")
        }
        if (!v.shelf.isNullOrBlank()) sb.append("\n书架 ").append(v.shelf)
        return sb.toString()
    }

    private fun colorOf(status: String): Int = when (status) {
        Copy.STATUS_IN -> R.color.green
        Copy.STATUS_OUT -> R.color.blue
        Copy.STATUS_LOST -> R.color.red
        else -> R.color.text_dim
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_ROWS = 30
    }
}
