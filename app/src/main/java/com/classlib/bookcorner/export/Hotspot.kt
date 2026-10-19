package com.classlib.bookcorner.export

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** 热点与 IP 相关的小工具。 */
object Net {

    private const val TAG = "Net"

    /** 把 WifiManager 返回的 little-endian int 转成点分字符串 */
    fun intToIp(ip: Int): String =
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"

    /**
     * 找热点侧的 IPv4。
     * 优先看名字里带 ap / softap / wlan1 的接口（不同 ROM 命名不同），
     * 找不到就退回 Android 软热点沿用的默认网关 192.168.43.1。
     */
    fun hotspotIp(): String {
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all.hasMoreElements()) {
                val nif = all.nextElement()
                val name = nif.name.lowercase()
                val looksLikeAp = name.startsWith("ap") || name.contains("softap") ||
                        name == "wlan1" || name == "swlan0"
                if (!looksLikeAp || !nif.isUp) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "hotspotIp failed", e)
        }
        return "192.168.43.1"
    }

    /** 当前 WiFi 客户端地址（B 态用） */
    @Suppress("DEPRECATION")
    fun wifiClientIp(ctx: Context): String? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null else intToIp(ip)
        } catch (e: Throwable) {
            null
        }
    }

    /** WiFi 二维码内容：手机相机扫了可直接加入网络 */
    fun wifiQrPayload(ssid: String, pass: String): String =
        "WIFI:T:WPA;S:$ssid;P:$pass;;"
}

/**
 * 本地热点（A 态）。
 *
 * 用官方 API `startLocalOnlyHotspot`：该网络无法访问互联网，正好符合需求，
 * 也不消耗流量。厂商 ROM 可能裁剪，失败时由调用方降级到 B 态。
 */
class Hotspot(private val ctx: Context) {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(onReady: (ssid: String, pass: String) -> Unit, onFail: (String) -> Unit) {
        val wm = try {
            ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        } catch (e: Throwable) {
            onFail("取不到 WifiManager")
            return
        }

        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val cred = credentialsOf(res)
                    main.post {
                        if (cred == null) onFail("热点已开，但取不到 SSID") else onReady(cred.first, cred.second)
                    }
                }

                override fun onStopped() {
                    main.post { onFail("热点被系统关闭") }
                }

                override fun onFailed(reason: Int) {
                    main.post { onFail("热点开启失败（reason=$reason）") }
                }
            }, main)
        } catch (e: SecurityException) {
            onFail("缺少定位/热点权限")
        } catch (e: Throwable) {
            onFail("这台设备不支持本地热点：${e.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun credentialsOf(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cfg = res.softApConfiguration
                val ssid = cfg.ssid ?: return null
                val pass = cfg.passphrase ?: ""
                ssid to pass
            } else {
                val cfg = res.wifiConfiguration ?: return null
                val ssid = cfg.SSID ?: return null
                val pass = cfg.preSharedKey ?: ""
                ssid to pass
            }
        } catch (e: Throwable) {
            Log.w("Hotspot", "credentials failed", e)
            null
        }
    }

    fun stop() {
        try {
            reservation?.close()
        } catch (_: Throwable) {
        }
        reservation = null
    }
}
