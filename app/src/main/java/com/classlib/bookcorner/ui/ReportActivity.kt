package com.classlib.bookcorner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.App
import com.classlib.bookcorner.R
import com.classlib.bookcorner.export.Hotspot
import com.classlib.bookcorner.export.Net
import com.classlib.bookcorner.export.ReportBuilder
import com.classlib.bookcorner.export.ReportServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import kotlin.random.Random

/**
 * 报表页：生成 xlsx → 起临时下载服务 → 开热点（A 态）或让老师开热点（B 态）。
 *
 * A 态：手表自己开本地热点（无法访问互联网），老师扫码连上、再扫码下载。
 * B 态：热点开不了时降级——老师手机开热点，手表连上，显示本机 IP。
 */
class ReportActivity : AppCompatActivity() {

    private lateinit var tvState: TextView
    private lateinit var tvStep1: TextView
    private lateinit var tvStep2: TextView
    private lateinit var tvWifi: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvTip: TextView
    private lateinit var ivWifiQr: ImageView
    private lateinit var ivUrlQr: ImageView
    private lateinit var btnRefresh: TextView

    private var server: ReportServer? = null
    private val hotspot = Hotspot(this)
    private var file: File? = null
    private val token: String = randomToken()

    private var port: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvState = findViewById(R.id.tvState)
        tvStep1 = findViewById(R.id.tvStep1)
        tvStep2 = findViewById(R.id.tvStep2)
        tvWifi = findViewById(R.id.tvWifi)
        tvUrl = findViewById(R.id.tvUrl)
        tvTip = findViewById(R.id.tvTip)
        ivWifiQr = findViewById(R.id.ivWifiQr)
        ivUrlQr = findViewById(R.id.ivUrlQr)
        btnRefresh = findViewById(R.id.btnRefresh)

        findViewById<TextView>(R.id.btnStop).setOnClickListener { finish() }
        btnRefresh.setOnClickListener { showBMode() }

        prepare()
    }

    /** 生成 xlsx + 起服务 */
    private fun prepare() {
        tvState.text = "正在生成报表…"
        App.io.execute {
            try {
                val f = ReportBuilder(this).build()
                val s = ReportServer(0, token, f)   // 0 = 随机空闲端口
                s.start(3000, false)
                App.ui {
                    file = f
                    server = s
                    port = s.boundPort
                    android.util.Log.d(TAG, "server started, port=$port")
                    tvState.text = "报表已生成（${f.length() / 1024} KB）"
                    startHotspot()
                }
            } catch (e: Throwable) {
                App.ui {
                    tvState.text = "生成失败：${e.message}"
                    tvTip.text = "请回到首页重试"
                }
            }
        }
    }

    /** 先试 A 态 */
    private fun startHotspot() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION
            )
            showBMode()
            return
        }
        tvState.text = "正在开启手表热点…"
        hotspot.start(
            onReady = { ssid, pass -> showAMode(ssid, pass) },
            onFail = { reason ->
                tvTip.text = "手表热点不可用：$reason"
                showBMode()
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startHotspot()
            } else {
                tvTip.text = "没有定位权限，无法开热点，改用老师热点模式"
                showBMode()
            }
        }
    }

    /** A 态：手表热点 */
    private fun showAMode(ssid: String, pass: String) {
        val ip = Net.hotspotIp()
        val url = "http://$ip:$port/r?t=$token"
        android.util.Log.d(TAG, "A-mode url=$url")

        tvState.text = "报表已就绪"
        tvStep1.visibility = View.VISIBLE
        ivWifiQr.visibility = View.VISIBLE
        tvWifi.visibility = View.VISIBLE
        tvStep2.visibility = View.VISIBLE
        btnRefresh.visibility = View.GONE

        tvWifi.text = "$ssid\n密码 $pass"
        tvUrl.text = "$ip:$port"
        tvTip.text = "手机扫上面第一个码连上 WiFi（提示无法上网时选\"保持连接\"），再扫第二个码下载"

        ivWifiQr.setImageBitmap(qr(Net.wifiQrPayload(ssid, pass)))
        ivUrlQr.setImageBitmap(qr(url))
    }

    /** B 态：老师手机开热点，手表连上 */
    private fun showBMode() {
        val ip = Net.wifiClientIp(this)
        tvState.text = "请老师打开手机热点"
        tvStep1.visibility = View.GONE
        ivWifiQr.visibility = View.GONE
        tvWifi.visibility = View.GONE
        tvStep2.visibility = View.VISIBLE
        btnRefresh.visibility = View.VISIBLE

        if (ip == null) {
            tvUrl.text = "未连上任何 WiFi"
            ivUrlQr.setImageBitmap(null)
            tvTip.text = "让手表连上老师的手机热点后，点「刷新地址」"
            android.util.Log.d(TAG, "B-mode, no wifi ip, port=$port")
        } else {
            val url = "http://$ip:$port/r?t=$token"
            android.util.Log.d(TAG, "B-mode url=$url")
            tvUrl.text = "$ip:$port"
            ivUrlQr.setImageBitmap(qr(url))
            tvTip.text = "老师手机浏览器打开上面的地址即可下载"
        }
    }

    private fun qr(text: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.stop() } catch (_: Throwable) {}
        server = null
        hotspot.stop()
        file?.delete()
    }

    private fun randomToken(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..10).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        private const val REQ_LOCATION = 3001
        private const val TAG = "Report"
    }
}
