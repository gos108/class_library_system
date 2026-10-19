package com.classlib.bookcorner.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.BookFinder
import com.classlib.bookcorner.db.LoanDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView
import com.classlib.bookcorner.model.Member
import com.classlib.bookcorner.util.Dates
import com.classlib.bookcorner.util.Prefs

/**
 * 借出：扫书脊码 / 手输 ISBN / 输书名 → 选人 → 确认。
 *
 * 现在的入口码可以是出版商 ISBN——这才是真书上手就能扫到的东西。
 * 内部 9 位码只是系统内的短码，不依赖书上贴标签。
 */
class BorrowActivity : AppCompatActivity() {

    private lateinit var picker: PersonPicker
    private var copy: CopyView? = null
    private var person: Member? = null

    private lateinit var tvPerson: TextView
    private lateinit var btnConfirm: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_borrow)

        // 必须在 onCreate 期间构造：内部要注册 ActivityResult
        picker = PersonPicker(this)
        picker.loadMembers()

        tvPerson = findViewById(R.id.tvPerson)
        btnConfirm = findViewById(R.id.btnConfirm)

        picker.attach(findViewById(R.id.btnSpeak)) { m ->
            person = m
            tvPerson.text = m.name
        }
        tvPerson.setOnClickListener { picker.showList() }

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
        val days = Prefs(this).loanDays
        App.io.execute {
            val r = BookFinder(App.db).resolve(code)
            App.ui {
                if (r.copies.isEmpty()) {
                    toast(if (r.looksLikeIsbn) "这本书还没入库：${r.input}" else "库里没有这本书：${r.input}")
                    finish()
                    return@ui
                }

                val avail = r.inStock()
                if (avail.isEmpty()) {
                    val v = r.copies[0]
                    toast(
                        if (v.status == Copy.STATUS_OUT) {
                            "这本书已经借出去了" + (v.borrowerName?.let { "（$it）" } ?: "")
                        } else {
                            "这本书当前是「${Copy.label(v.status)}」，不能借"
                        }
                    )
                    finish()
                    return@ui
                }

                // 同一 ISBN 的多册物理上可互换，不必让用户点；
                // 只有按书名搜出不同书目时才有歧义，那时才弹选册。
                val candidates = if (r.kind == BookFinder.Kind.TITLE) avail else avail.take(1)
                CopyPicker.pick(this@BorrowActivity, "选一册借出", candidates, onCancel = { finish() }) { v ->
                    bind(v, days, avail.size)
                }
            }
        }
    }

    private fun bind(v: CopyView, days: Int, inStockCount: Int) {
        copy = v
        val extra = if (inStockCount > 1) " · 同书还有 ${inStockCount - 1} 册在库" else ""
        findViewById<TextView>(R.id.tvBarcode).text = "条码：${v.barcode}"
        findViewById<TextView>(R.id.tvTitle).text = v.title
        findViewById<TextView>(R.id.tvOwner).text = "所有者：${v.ownerName ?: "未登记"}$extra"
        findViewById<TextView>(R.id.tvDue).text =
            "借期 $days 天，应还 ${Dates.md(Dates.plusDays(System.currentTimeMillis(), days))}"
    }

    private fun confirm() {
        val c = copy ?: return
        val p = person
        if (p == null) {
            toast("先选借给谁")
            return
        }
        val days = Prefs(this).loanDays
        btnConfirm.isEnabled = false
        App.io.execute {
            try {
                LoanDao(App.db).borrow(c.copyId, p.id, null, days)
                App.ui {
                    toast("已借出：《${c.title}》→ ${p.name}")
                    finish()
                }
            } catch (e: Throwable) {
                App.ui {
                    btnConfirm.isEnabled = true
                    toast(e.message ?: "借出失败")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        picker.onPermissionResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::picker.isInitialized) picker.release()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
