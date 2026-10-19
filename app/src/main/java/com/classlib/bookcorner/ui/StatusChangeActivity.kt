package com.classlib.bookcorner.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.BookFinder
import com.classlib.bookcorner.db.CopyStatusDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.model.CopyView

/**
 * 遗失 / 出库 / 撤销。
 *
 * 只做「书不在了或又回来了」这一类，正常归还走 ReturnActivity。
 * 每条路径都必须选原因 + 二次确认，因为它是账目消失的唯一入口。
 */
class StatusChangeActivity : AppCompatActivity() {

    private var copy: CopyView? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var btn1: TextView
    private lateinit var btn2: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        btn1 = findViewById(R.id.btn1)
        btn2 = findViewById(R.id.btn2)

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
            val r = BookFinder(App.db).resolve(code)
            App.ui {
                if (r.copies.isEmpty()) {
                    toast(if (r.looksLikeIsbn) "这本书还没入库：${r.input}" else "库里没有这本书：${r.input}")
                    finish()
                    return@ui
                }
                CopyPicker.pick(this@StatusChangeActivity, "改哪一册", r.copies, onCancel = { finish() }) { v ->
                    bind(v)
                }
            }
        }
    }

    private fun bind(v: CopyView) {
        copy = v
        findViewById<TextView>(R.id.tvBarcode).text = "条码：${v.barcode}"
        findViewById<TextView>(R.id.tvTitle).text = v.title
        findViewById<TextView>(R.id.tvOwner).text = "所有者：${v.ownerName ?: "未登记"}"

        tvStatus.text = "当前：${Copy.label(v.status)}"
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (v.status) {
                    Copy.STATUS_IN -> R.color.green
                    Copy.STATUS_OUT -> R.color.blue
                    Copy.STATUS_LOST -> R.color.red
                    else -> R.color.text_dim
                }
            )
        )

        val targets = CopyStatusDao.targetsOf(v.status)

        if (targets.contains(Copy.STATUS_IN)) {
            // 遗失 / 出库 → 撤销
            btn1.visibility = View.VISIBLE
            btn1.text = "撤销，放回图书角"
            btn1.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.green)
            )
            btn1.setOnClickListener { confirm(v, Copy.STATUS_IN, null) }
            btn2.visibility = View.GONE
            tvHint.text = "撤销后这册书回到「入库」。原来那条记录会保留——「曾经以为丢了」也是事实。"
        } else {
            btn1.visibility = View.VISIBLE
            btn1.text = "标记为「遗失」"
            btn1.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.red)
            )
            btn1.setOnClickListener { pickReason(v, Copy.STATUS_LOST) }

            btn2.visibility = View.VISIBLE
            btn2.text = "标记为「出库」"
            btn2.setOnClickListener { pickReason(v, Copy.STATUS_REMOVED) }

            tvHint.text = "遗失 = 确定找不到了；出库 = 书不在图书角了（如所有者取回家）。都要选原因。"
        }
    }

    private fun pickReason(v: CopyView, to: String) {
        val reasons = if (to == Copy.STATUS_LOST) LOST_REASONS else REMOVED_REASONS
        AlertDialog.Builder(this)
            .setTitle(if (to == Copy.STATUS_LOST) "为什么会遗失" else "为什么出库")
            .setItems(reasons) { _, i -> confirm(v, to, reasons[i]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirm(v: CopyView, to: String, reason: String?) {
        val msg = buildString {
            append("《").append(v.title).append("》\n")
            append(Copy.label(v.status)).append(" → ").append(Copy.label(to)).append("\n")
            if (reason != null) append("原因：").append(reason).append("\n")
            if (v.status == Copy.STATUS_OUT && to != Copy.STATUS_IN) {
                append("\n这册书还在「").append(v.borrowerName ?: "借阅人")
                    .append("」手上，那条借阅记录会一并记为遗失。\n")
            }
            if (to == Copy.STATUS_IN) append("\n会重新出现在可借列表里。\n")
            append("\n确认吗？")
        }
        AlertDialog.Builder(this)
            .setTitle("再确认一次")
            .setMessage(msg)
            .setPositiveButton("确认") { _, _ -> doChange(v, to, reason) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doChange(v: CopyView, to: String, reason: String?) {
        App.io.execute {
            try {
                CopyStatusDao(App.db).change(v.copyId, to, reason)
                App.ui {
                    toast("已改为「${Copy.label(to)}」：《${v.title}》")
                    finish()
                }
            } catch (e: Throwable) {
                App.ui { toast(e.message ?: "修改失败") }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private val LOST_REASONS = arrayOf("找不到了", "借书人弄丢了", "其他原因")
        private val REMOVED_REASONS = arrayOf("所有者取回", "捐给班级", "破损丢弃", "其他原因")
    }
}
