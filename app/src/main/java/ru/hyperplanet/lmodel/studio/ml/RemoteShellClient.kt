package ru.hyperplanet.lmodel.studio.util

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Клиент к РЕАЛЬНОМУ shell на удалённом Linux (PAW/VPS), endpoint POST /v1/shell.
 * Телефон контейнер не эмулирует — команды выполняются на сервере.
 */
object RemoteShellClient {

    data class Result(
        val ok: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val cwd: String,
        val error: String? = null
    )

    fun run(
        baseUrl: String,
        apiKey: String?,
        command: String,
        timeoutSec: Int = 20
    ): Result {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) {
            return Result(false, "", "", -1, "", "Не задан URL удалённого API")
        }
        return try {
            val url = URL("$root/v1/shell")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = (timeoutSec + 5) * 1000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val body = JSONObject()
                .put("command", command)
                .put("timeout", timeoutSec)
                .toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (code !in 200..299) {
                return Result(
                    false, json.optString("stdout"), json.optString("stderr"),
                    json.optInt("exit_code", code), json.optString("cwd"),
                    json.optString("error", "HTTP $code")
                )
            }
            Result(
                ok = json.optBoolean("ok", true),
                stdout = json.optString("stdout"),
                stderr = json.optString("stderr"),
                exitCode = json.optInt("exit_code", 0),
                cwd = json.optString("cwd"),
                error = json.optString("error").ifBlank { null }
            )
        } catch (e: Exception) {
            Result(false, "", "", -1, "", e.message)
        }
    }
}
