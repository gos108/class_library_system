package com.classlib.bookcorner.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.CopyDao
import com.classlib.bookcorner.db.LoanDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView
import com.classlib.bookcorner.model.Member
import com.classlib.bookcorner.util.Dates
import com.classlib.bookcorner.util.Prefs

/** 借出：扫码/手输条码 → 选人 → 确认。 */
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
            val v = CopyDao(App.db).viewByBarcode(code)
            App.ui {
                if (v == null) {
                    toast("库里没有这个条码：$code")
                    finish()
                    return@ui
                }
                when (v.status) {
                    Copy.STATUS_IN -> Unit
                    Copy.STATUS_OUT -> {
                        toast("这本书已经借出去了" + (v.borrowerName?.let { "（$it）" } ?: ""))
                        finish()
                        return@ui
                    }
                    else -> {
                        toast("这本书当前是「${Copy.label(v.status)}」，不能借")
                        finish()
                        return@ui
                    }
                }
                copy = v
                findViewById<TextView>(R.id.tvBarcode).text = "条码：${v.barcode}"
                findViewById<TextView>(R.id.tvTitle).text = v.title
                findViewById<TextView>(R.id.tvOwner).text =
                    "所有者：${v.ownerName ?: "未登记"}"
                findViewById<TextView>(R.id.tvDue).text =
                    "借期 $days 天，应还 ${Dates.md(Dates.plusDays(System.currentTimeMillis(), days))}"
            }
        }
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
