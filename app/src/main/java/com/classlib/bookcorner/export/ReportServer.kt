package com.classlib.bookcorner.export

import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * 临时下载服务。只在报表页存活期间运行。
 *
 * 只暴露 /r 一个路径，且必须带正确的一次性 token，
 * 避免同一热点下其他人乱下。
 */
class ReportServer(
    port: Int,
    private val token: String,
    private val file: File
) : NanoHTTPD(port) {

    val boundPort: Int get() = listeningPort

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        if (uri != "/r") return notFound()
        if (session.parameters["t"]?.firstOrNull() != token) return notFound()

        return try {
            val stream = file.inputStream()
            newFixedLengthResponse(
                Response.Status.OK,
                MIME_XLSX,
                stream,
                file.length()
            ).apply {
                addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
                addHeader("Cache-Control", "no-store")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "read failed")
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")

    companion object {
        const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
