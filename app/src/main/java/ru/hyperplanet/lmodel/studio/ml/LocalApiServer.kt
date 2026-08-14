package ru.hyperplanet.lmodel.studio.ml

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import ru.hyperplanet.lmodel.studio.data.AppDatabase
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Локальный HTTP API — только на этом устройстве (127.0.0.1).
 * Опционально Authorization: Bearer <apiKey> (если ключ задан).
 */
class LocalApiServer(
    private val context: Context,
    private val modelId: Long,
    private val apiKey: String? = null,
    private val port: Int = 8765
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running.get()
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = ss
            running.set(true)
            acceptThread = thread(name = "LModelLocalApi", isDaemon = true) {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        thread(isDaemon = true) { handleClient(client) }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }
            true
        } catch (_: Exception) {
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            var contentLength = 0
            var authorization: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                when {
                    line.startsWith("Content-Length:", ignoreCase = true) ->
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    line.startsWith("Authorization:", ignoreCase = true) ->
                        authorization = line.substringAfter(":").trim()
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            if (!apiKey.isNullOrBlank()) {
                val needsAuth = !(method == "GET" && path == "/v1/health")
                if (needsAuth && !checkAuth(authorization)) {
                    writeResponse(
                        socket, 401, "application/json",
                        JSONObject().put("error", "unauthorized").toString()
                    )
                    return
                }
            }

            val (code, contentType, responseBody) = route(method, path, body)
            writeResponse(socket, code, contentType, responseBody)
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun checkAuth(header: String?): Boolean {
        if (apiKey.isNullOrBlank()) return true
        if (header.isNullOrBlank()) return false
        val token = if (header.startsWith("Bearer ", ignoreCase = true))
            header.substring(7).trim() else header.trim()
        return token == apiKey
    }

    private fun route(method: String, path: String, body: String): Triple<Int, String, String> {
        return when {
            method == "GET" && path == "/v1/health" ->
                jsonOf("status" to "ok", "modelId" to modelId, "mode" to "local")

            method == "GET" && (path == "/v1/models" || path == "/v1/models/") -> {
                val model = runBlocking { AppDatabase.getInstance(context).modelDao().getById(modelId) }
                val obj = JSONObject().put("object", "list")
                val data = JSONArray()
                if (model != null) {
                    data.put(
                        JSONObject()
                            .put("id", "lmodel-${model.id}")
                            .put("object", "model")
                            .put("name", model.name)
                            .put("trained", model.isTrained)
                    )
                }
                obj.put("data", data)
                Triple(200, "application/json", obj.toString())
            }

            method == "POST" && path == "/v1/chat/completions" -> handleChat(body)

            method == "GET" && path == "/" ->
                Triple(200, "text/plain; charset=utf-8", "LModel Studio Local API\n$baseUrl\n")

            else -> Triple(404, "application/json", JSONObject().put("error", "not_found").toString())
        }
    }

    private fun handleChat(body: String): Triple<Int, String, String> {
        return try {
            val json = JSONObject(body)
            val messages = json.optJSONArray("messages") ?: JSONArray()
            var userMsg = ""
            var systemPrompt = ""
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                when (m.optString("role")) {
                    "system" -> systemPrompt = m.optString("content")
                    "user" -> userMsg = m.optString("content")
                }
            }
            if (userMsg.isBlank()) {
                return Triple(400, "application/json", JSONObject().put("error", "empty user message").toString())
            }
            val db = AppDatabase.getInstance(context)
            val model = runBlocking { db.modelDao().getById(modelId) }
            val params = runBlocking {
                db.modelParameterDao().getForModel(modelId).associate { it.key.lowercase() to it.value }
            }
            val result = InferenceEngine.generateResponse(
                trainedDataJson = model?.trainedDataJson,
                systemPrompt = systemPrompt,
                userMessage = userMsg,
                parameters = params
            )
            val response = JSONObject()
            response.put("id", "chatcmpl-local-$modelId")
            response.put("object", "chat.completion")
            response.put("created", System.currentTimeMillis() / 1000)
            response.put("model", "lmodel-$modelId")
            val choices = JSONArray()
            val choice = JSONObject()
                .put("index", 0)
                .put(
                    "message",
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", result.text)
                        .put("reasoning", result.reasoning)
                )
                .put("finish_reason", "stop")
            choices.put(choice)
            response.put("choices", choices)
            response.put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", result.promptTokens)
                    .put("completion_tokens", result.completionTokens)
                    .put("total_tokens", result.promptTokens + result.completionTokens)
            )
            Triple(200, "application/json", response.toString())
        } catch (e: Exception) {
            Triple(500, "application/json", JSONObject().put("error", e.message ?: "internal").toString())
        }
    }

    private fun writeResponse(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        OutputStreamWriter(out, Charsets.UTF_8).apply { write(header); flush() }
        out.write(bytes)
        out.flush()
    }

    private fun jsonOf(vararg pairs: Pair<String, Any?>): Triple<Int, String, String> {
        val o = JSONObject()
        pairs.forEach { (k, v) -> o.put(k, v) }
        return Triple(200, "application/json", o.toString())
    }

    companion object {
        fun generateApiKey(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val rnd = java.security.SecureRandom()
            val sb = StringBuilder("lms_")
            repeat(32) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            return sb.toString()
        }
    }
}
