package com.classlib.bookcorner.export

import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * 临时下载服务。只在报表页存活期间运行。
 *
 * 三个路径，全部要求带一次性 token（同一热点下别人猜不到）：
 *   /i  索引页（老师扫码落在这里，两个文件都能点）
 *   /r  Excel 报表
 *   /b  备份 json
 */
class ReportServer(
    port: Int,
    private val token: String,
    private val xlsx: File,
    private val json: File,
    private val summary: String
) : NanoHTTPD(port) {

    val boundPort: Int get() = listeningPort

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        if (session.parameters["t"]?.firstOrNull() != token) return notFound()
        return when (uri) {
            "/", "/i" -> index()
            "/r" -> send(xlsx, MIME_XLSX)
            "/b" -> send(json, "application/json; charset=utf-8")
            else -> notFound()
        }
    }

    private fun index(): Response {
        val html = """
            <!doctype html>
            <html lang="zh-CN"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>班级图书角</title>
            <style>
              body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;margin:0;padding:24px;background:#f5f5f3;color:#1a1a1a}
              h1{font-size:19px;margin:0 0 6px}
              .sub{color:#777;font-size:13px;margin:0 0 22px}
              a{display:block;text-align:center;padding:16px;border-radius:10px;text-decoration:none;font-size:17px;margin-bottom:12px}
              .main{background:#1D9E75;color:#fff}
              .bak{background:#fff;color:#1a1a1a;border:1px solid #dcdcdc}
              .tip{font-size:12px;color:#8a8a86;line-height:1.7;margin-top:22px}
            </style></head><body>
            <h1>班级图书角</h1>
            <p class="sub">$summary</p>
            <a class="main" href="/r?t=$token">下载 Excel 报表</a>
            <a class="bak" href="/b?t=$token">下载备份文件（.json）</a>
            <p class="tip">Excel 用来查看，备份文件用来恢复。建议两个都存进微信收藏。</p>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun send(file: File, mime: String): Response = try {
        newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length()).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            addHeader("Cache-Control", "no-store")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "read failed")
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
