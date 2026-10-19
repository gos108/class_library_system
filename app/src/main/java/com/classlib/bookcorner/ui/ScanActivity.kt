package com.classlib.bookcorner.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.classlib.bookcorner.R
import com.classlib.bookcorner.util.Prefs
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * 通用扫码页。解到码后通过 setResult 返回。
 *
 * 注意：米兔 7 有前摄 + 侧摄，CameraSelector 只认 LENS_FACING，
 * 所以这里提供"切换摄像头"按钮，让用户自己选哪个能对上焦。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvHint: TextView
    private lateinit var prefs: Prefs

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    private val reader = MultiFormatReader().apply {
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(
            BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128, BarcodeFormat.EAN_13
        )
        hints[DecodeHintType.TRY_HARDER] = true
        setHints(hints)
    }

    @Volatile
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        prefs = Prefs(this)
        previewView = findViewById(R.id.preview)
        tvHint = findViewById(R.id.tvHint)

        findViewById<TextView>(R.id.btnFlip).setOnClickListener {
            prefs.cameraFacing = if (prefs.cameraFacing == "back") "front" else "back"
            tvHint.text = "已切到${if (prefs.cameraFacing == "front") "前置" else "侧摄"}摄像头"
            startCamera()
        }

        findViewById<TextView>(R.id.btnManual).setOnClickListener { showManualInput() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            toast("没有相机权限，无法扫码")
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                bind()
            } catch (e: Throwable) {
                toast("相机启动失败：${e.message}")
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        p.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        analysis.setAnalyzer(executor) { proxy ->
            try {
                decode(proxy)
            } catch (_: Throwable) {
            } finally {
                proxy.close()
            }
        }

        val facing = if (prefs.cameraFacing == "front") {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()

        try {
            p.bindToLifecycle(this, selector, preview, analysis)
            tvHint.text = getString(R.string.scan_hint)
        } catch (e: Throwable) {
            // 该方向没有摄像头时，自动换一个
            val alt = if (facing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            try {
                val s2 = CameraSelector.Builder().requireLensFacing(alt).build()
                p.bindToLifecycle(this, s2, preview, analysis)
                prefs.cameraFacing = if (alt == CameraSelector.LENS_FACING_FRONT) "front" else "back"
                tvHint.text = "已自动切换到可用摄像头"
            } catch (e2: Throwable) {
                toast("没有可用摄像头：${e.message}")
                finish()
            }
        }
    }

    private fun decode(proxy: ImageProxy) {
        if (done) return
        val image: Image = proxy.image ?: return
        val w = image.width
        val h = image.height

        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val data = ByteArray(w * h)

        buffer.rewind()
        if (rowStride == w) {
            val n = minOf(buffer.remaining(), w * h)
            buffer.get(data, 0, n)
        } else {
            val row = ByteArray(rowStride)
            var pos = 0
            for (r in 0 until h) {
                val n = minOf(rowStride, buffer.remaining())
                if (n <= 0) break
                buffer.get(row, 0, n)
                val copyLen = minOf(w, n)
                System.arraycopy(row, 0, data, pos, copyLen)
                pos += w
            }
        }

        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decodeWithState(bitmap)
            onFound(result.text)
        } catch (_: NotFoundException) {
        } catch (_: Throwable) {
        } finally {
            reader.reset()
        }
    }

    /**
     * 对不上焦、标签磨花、书本来就没条码时的兜底。
     *
     * 允许输三种东西：9 位内部码 / 13 位 ISBN / 书名的一部分。
     * 最后一种是无条码旧书唯一能走的路。
     */
    private fun showManualInput() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "9 位条码 / 13 位 ISBN / 书名"
            setTextColor(ContextCompat.getColor(this@ScanActivity, R.color.text))
            setHintTextColor(ContextCompat.getColor(this@ScanActivity, R.color.text_dim))
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("手输或搜书名")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isEmpty()) {
                    toast("没有输入内容")
                } else {
                    onFound(t)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onFound(code: String) {
        if (done) return
        done = true
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        executor.shutdown()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_MODE = "mode"
        const val MODE_INTAKE = "intake"
        const val MODE_SEARCH = "search"
        private const val REQ_CAMERA = 1001
    }
}
