package com.classlib.bookcorner.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.CopyDao
import com.classlib.bookcorner.model.Copy

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var tvStat: TextView

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
                    ACTION_SEARCH -> SearchActivity::class.java
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

        findViewById<TextView>(R.id.btnIntake).setOnClickListener { go(ACTION_INTAKE) }
        findViewById<TextView>(R.id.btnBorrow).setOnClickListener { go(ACTION_BORROW) }
        findViewById<TextView>(R.id.btnReturn).setOnClickListener { go(ACTION_RETURN) }
        findViewById<TextView>(R.id.btnSearch).setOnClickListener { go(ACTION_SEARCH) }
        findViewById<TextView>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, MemberActivity::class.java))
        }
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
            App.ui {
                tvTotal.text = getString(R.string.home_total, total)
                tvStat.text = getString(R.string.home_stat, inStock, out)
            }
        }
    }

    companion object {
        private const val ACTION_INTAKE = "intake"
        private const val ACTION_BORROW = "borrow"
        private const val ACTION_RETURN = "return"
        private const val ACTION_SEARCH = "search"
    }
}
