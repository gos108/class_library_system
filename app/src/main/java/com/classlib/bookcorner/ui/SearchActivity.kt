package com.classlib.bookcorner.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.CopyDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.util.Dates

/** 查书：只读。扫一下就知道这册书的状态、所有者、谁借着、几号还。 */
class SearchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }

        val code = intent.getStringExtra(ScanActivity.EXTRA_CODE).orEmpty()
        if (code.isBlank()) {
            toast("没有取到条码")
            finish()
            return
        }
        load(code)
    }

    private fun load(code: String) {
        App.io.execute {
            val v = CopyDao(App.db).viewByBarcode(code)
            App.ui {
                if (v == null) {
                    toast("库里没有这个条码：$code")
                    finish()
                    return@ui
                }
                findViewById<TextView>(R.id.tvBarcode).text = "条码：${v.barcode}"
                findViewById<TextView>(R.id.tvTitle).text = v.title
                findViewById<TextView>(R.id.tvAuthor).text =
                    listOfNotNull(v.author, v.category).joinToString(" · ")

                val statusView = findViewById<TextView>(R.id.tvStatus)
                statusView.text = Copy.label(v.status)
                statusView.setTextColor(
                    ContextCompat.getColor(
                        this@SearchActivity,
                        when (v.status) {
                            Copy.STATUS_IN -> R.color.green
                            Copy.STATUS_OUT -> R.color.blue
                            Copy.STATUS_LOST -> R.color.red
                            else -> R.color.text_dim
                        }
                    )
                )

                val lines = ArrayList<String>()
                lines.add("所有者：${v.ownerName ?: "未登记"}")
                if (v.status == Copy.STATUS_OUT) {
                    val due = v.dueAt ?: 0L
                    val over = Dates.overdueDays(due)
                    lines.add("借阅人：${v.borrowerName ?: "未知"}")
                    lines.add(
                        if (over > 0) "应还：${Dates.md(due)}（已逾期 $over 天）"
                        else "应还：${Dates.md(due)}"
                    )
                }
                if (!v.shelf.isNullOrBlank()) lines.add("书架位：${v.shelf}")
                findViewById<TextView>(R.id.tvDetail).text = lines.joinToString("\n")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
