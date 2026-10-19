package com.classlib.bookcorner.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.db.CopyDao
import com.classlib.bookcorner.model.Copy

class HomeActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var tvStat: TextView

    /** 扫码 → 跳入库 */
    private val scanForIntake = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val code = res.data?.getStringExtra(ScanActivity.EXTRA_CODE)
            if (!code.isNullOrBlank()) {
                startActivity(
                    Intent(this, IntakeActivity::class.java)
                        .putExtra(ScanActivity.EXTRA_CODE, code)
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        tvTotal = findViewById(R.id.tvTotal)
        tvStat = findViewById(R.id.tvStat)

        findViewById<TextView>(R.id.btnIntake).setOnClickListener {
            scanForIntake.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<TextView>(R.id.btnBorrow).setOnClickListener {
            toast("借出在 S2 实现")
        }
        findViewById<TextView>(R.id.btnReturn).setOnClickListener {
            toast("归还在 S2 实现")
        }
        findViewById<TextView>(R.id.btnSearch).setOnClickListener {
            toast("查书在 S2 实现")
        }
        findViewById<TextView>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, MemberActivity::class.java))
        }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
