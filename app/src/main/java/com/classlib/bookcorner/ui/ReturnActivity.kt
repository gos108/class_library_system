package com.classlib.bookcorner.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.BookFinder
import com.classlib.bookcorner.db.LoanDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView
import com.classlib.bookcorner.util.Dates

/** 归还：扫 ISBN / 手输 → 显示借阅人 → 确认。 */
class ReturnActivity : AppCompatActivity() {

    private var copy: CopyView? = null
    private lateinit var btnConfirm: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_return)

        btnConfirm = findViewById(R.id.btnConfirm)

        val code = intent.getStringExtra(ScanActivity.EXTRA_CODE).orEmpty()
        if (code.isBlank()) {
            toast("没有取到条码")
            finish()
            return
        }
        load(code)
        btnConfirm.setOnClickListener { confirm() }
    }

    private fun load(code: String) {
        App.io.execute {
            val r = BookFinder(App.db).resolve(code)
            App.ui {
                if (r.copies.isEmpty()) {
                    toast(if (r.looksLikeIsbn) "这本书还没入库：${r.input}" else "库里没有这本书：${r.input}")
                    finish()
                    return@ui
                }

                val onLoan = r.onLoan()
                if (onLoan.isEmpty()) {
                    val v = r.copies[0]
                    toast(
                        if (v.status == Copy.STATUS_IN) "这本书没有被借出"
                        else "这本书当前是「${Copy.label(v.status)}」，不在借出中"
                    )
                    finish()
                    return@ui
                }

                val candidates = if (r.kind == BookFinder.Kind.TITLE) onLoan else onLoan.take(1)
                CopyPicker.pick(this@ReturnActivity, "还的是哪一册", candidates, onCancel = { finish() }) { v ->
                    bind(v)
                }
            }
        }
    }

    private fun bind(v: CopyView) {
        copy = v
        findViewById<TextView>(R.id.tvBarcode).text = "条码：${v.barcode}"
        findViewById<TextView>(R.id.tvTitle).text = v.title
        findViewById<TextView>(R.id.tvBorrower).text = v.borrowerName ?: "（未知）"

        val dueView = findViewById<TextView>(R.id.tvDue)
        val due = v.dueAt ?: 0L
        val over = Dates.overdueDays(due)
        if (over > 0) {
            dueView.text = "应还 ${Dates.md(due)} · 已逾期 $over 天"
            dueView.setTextColor(ContextCompat.getColor(this@ReturnActivity, R.color.red))
        } else {
            dueView.text = "应还 ${Dates.md(due)}"
            dueView.setTextColor(ContextCompat.getColor(this@ReturnActivity, R.color.text_dim))
        }
    }

    private fun confirm() {
        val c = copy ?: return
        btnConfirm.isEnabled = false
        App.io.execute {
            val ok = LoanDao(App.db).giveBack(c.copyId, null)
            App.ui {
                if (ok) {
                    toast("已归还：《${c.title}》")
                    finish()
                } else {
                    btnConfirm.isEnabled = true
                    toast("这本书没有被借出，可能已经还过了")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
