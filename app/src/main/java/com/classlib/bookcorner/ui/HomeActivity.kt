package com.classlib.bookcorner.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.CopyDao
import com.classlib.bookcorner.model.Copy
import com.classlib.bookcorner.util.Dates
import com.classlib.bookcorner.util.Prefs

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var tvStat: TextView
    private lateinit var tvExport: TextView

    /** 扫码回来后要跳去哪个页面 */
    private var pendingAction: String = ACTION_INTAKE

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val code = res.data?.getStringExtra(ScanActivity.EXTRA_CODE)
            if (!code.isNullOrBlank()) {
                val target = when (pendingAction) {
                    ACTION_BORROW -> BorrowActivity::class.java
                    ACTION_RETURN -> ReturnActivity::class.java
                    ACTION_STATUS -> StatusChangeActivity::class.java
                    else -> IntakeActivity::class.java
                }
                startActivity(Intent(this, target).putExtra(ScanActivity.EXTRA_CODE, code))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        tvTotal = findViewById(R.id.tvTotal)
        tvStat = findViewById(R.id.tvStat)
        tvExport = findViewById(R.id.tvExport)

        findViewById<TextView>(R.id.btnIntake).setOnClickListener { go(ACTION_INTAKE) }
        findViewById<TextView>(R.id.btnBorrow).setOnClickListener { go(ACTION_BORROW) }
        findViewById<TextView>(R.id.btnReturn).setOnClickListener { go(ACTION_RETURN) }
        // 查书自带输入框和扫码键，不必先扫一次——没有条码的旧书只能靠书名
        findViewById<TextView>(R.id.btnSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<TextView>(R.id.btnMore).setOnClickListener { showMore() }
    }

    private fun showMore() {
        val items = arrayOf("名册", "报表 / 备份", "遗失 / 出库")
        android.app.AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, MemberActivity::class.java))
                    1 -> startActivity(Intent(this, ReportActivity::class.java))
                    2 -> go(ACTION_STATUS)
                }
            }
            .show()
    }

    private fun go(action: String) {
        pendingAction = action
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        App.io.execute {
            val dao = CopyDao(App.db)
            val total = dao.countAll()
            val inStock = dao.countByStatus(Copy.STATUS_IN)
            val out = dao.countByStatus(Copy.STATUS_OUT)
            val lastExport = Prefs(this).lastExportAt
            App.ui {
                tvTotal.text = getString(R.string.home_total, total)
                tvStat.text = getString(R.string.home_stat, inStock, out)
                renderExportHint(lastExport)
            }
        }
    }

    /**
     * 「上次导出 N 天前」比一个沉默的定时备份有用得多：
     * 同设备快照防不住丢表，真正的备份只能离开设备，那就得让人记得导。
     */
    private fun renderExportHint(lastExport: Long) {
        if (lastExport <= 0) {
            tvExport.text = "还没导出过，建议先导一份"
            tvExport.setTextColor(ContextCompat.getColor(this, R.color.orange))
            return
        }
        val days = Dates.daysSince(lastExport)
        tvExport.text = if (days <= 0) "今天已导出" else "上次导出 $days 天前"
        tvExport.setTextColor(
            ContextCompat.getColor(this, if (days >= 7) R.color.orange else R.color.text_dim)
        )
    }

    companion object {
        private const val ACTION_INTAKE = "intake"
        private const val ACTION_BORROW = "borrow"
        private const val ACTION_RETURN = "return"
        private const val ACTION_STATUS = "status"
    }
}
