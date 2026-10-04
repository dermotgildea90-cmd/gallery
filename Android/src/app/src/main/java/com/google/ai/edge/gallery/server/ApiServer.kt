/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.server

import android.util.Log
import com.google.ai.edge.gallery.data.ApiServerConfig
import com.google.ai.edge.gallery.data.AuthType
import com.google.ai.edge.gallery.data.api.ChatCompletionRequest
import com.google.ai.edge.gallery.data.api.ErrorResponse
import com.google.ai.edge.gallery.data.api.HealthResponse
import com.google.ai.edge.gallery.data.api.ModelsResponse
import com.google.ai.edge.gallery.data.api.toApiEngine
import com.google.ai.edge.gallery.data.api.toApiModel
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "ApiServer"
private const val LOG_MARKER = "LOCAL_API"

class ApiServer(
    private val config: ApiServerConfig,
    private val inferenceHandler: InferenceHandler,
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        isLenient = true
        ignoreUnknownKeys = true
    }
    // Compact serializer for SSE streaming: prettyPrint would inject newlines
    // into single-line data: payloads, breaking SSE clients.
    private val jsonCompact = Json {
        encodeDefaults = true
        isLenient = true
        ignoreUnknownKeys = true
    }
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val startTime = AtomicLong(0)
    private val currentConnections = AtomicLong(0)
    private val executor: ExecutorService = java.util.concurrent.ThreadPoolExecutor(4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(8))

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) {
            Log.w(TAG, "$LOG_MARKER event=server_already_running")
            return
        }

        // Ensure any previous server socket is closed before starting
        if (serverSocket != null) {
            Log.w(TAG, "$LOG_MARKER event=server_socket_exists_before_start")
            stop()
            Thread.sleep(100)
        }

        try {
            val socket = ServerSocket()
            serverSocket = socket
            socket.reuseAddress = true
            val address = try {
                InetAddress.getByName(config.host)
            } catch (e: Exception) {
                Log.e(TAG, "$LOG_MARKER event=dns_resolution_failed host=${config.host} error=${e.message}", e)

                throw e
            }
            socket.bind(InetSocketAddress(address, config.port))
            serverSocket = socket
            running = true
            startTime.set(System.currentTimeMillis())

            acceptThread = Thread({ acceptLoop(socket) }, "local-api-server").also { thread ->
                thread.isDaemon = true
                thread.start()
            }
            Log.i(TAG, "$LOG_MARKER event=server_started host=${config.host} port=${config.port}")

        } catch (e: Exception) {
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            val errorDetails = buildString {
                append("Error: ${e.message}\n")
                append("Type: ${e.javaClass.simpleName}\n")
                append("Host: ${config.host}\n")
                append("Port: ${config.port}\n")
                if (e.cause != null) {
                    append("Cause: ${e.cause?.message}\n")
                }
            }
            Log.e(TAG, "$LOG_MARKER event=server_start_failed details=$errorDetails", e)

            throw e
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        Log.i(TAG, "$LOG_MARKER event=server_stopped")

    }

    fun isRunning(): Boolean = running

    val boundPort: Int get() = serverSocket?.localPort ?: 0

    fun getUptime(): Long = if (startTime.get() > 0) {
        System.currentTimeMillis() - startTime.get()
    } else {
        0
    }

    fun getCurrentConnections(): Long = currentConnections.get()

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                client.soTimeout = 15000
                clients.add(client)
                try { executor.execute { handleClient(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
            } catch (e: SocketException) {
                if (running) {
                    Log.e(TAG, "$LOG_MARKER event=accept_socket_error error=${e.message}", e)

                }
            } catch (e: Exception) {
                Log.e(TAG, "$LOG_MARKER event=accept_error error=${e.message}", e)

            }
        }
    }

    private fun handleClient(socket: Socket) {
        currentConnections.incrementAndGet()
        var request: HttpRequest? = null
        try {
            socket.use { client ->
                try {
                    request = readRequest(client)
                    if (request == null) {
                        Log.w(TAG, "$LOG_MARKER event=empty_request remote=${client.remoteSocketAddress}")
                        return
                    }

                    Log.d(TAG, "$LOG_MARKER event=request_start method=${request.method} path=${request.path}")

                    if (request.method == "OPTIONS") {
                        writeResponse(client, 204, "", "text/plain")
                        return
                    }

                    if (!request.authorize()) {
                        writeJson(client, 401, ErrorResponse(error = "Unauthorized", type = "authentication_error"))

                        return
                    }

                    route(request, client)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "$LOG_MARKER event=request_bad_request error=${e.message}", e)

                    writeJsonOrLogDisconnect(client, 400, ErrorResponse(error = e.message ?: "Bad request", type = "bad_request"))
                } catch (e: SocketException) {
                    Log.i(TAG, "$LOG_MARKER event=client_socket_closed remote=${client.remoteSocketAddress} reason=${e.message}")

                } catch (e: Exception) {
                    Log.e(TAG, "$LOG_MARKER event=request_error error=${e.message}", e)

                    writeJsonOrLogDisconnect(client, 500, ErrorResponse(error = e.message ?: "Internal error", type = "internal_error"))
                }
            }
        } finally {
            clients.remove(socket)
            currentConnections.decrementAndGet()
        }
    }

    private fun route(request: HttpRequest, socket: Socket) {
        when {
            request.method == "GET" && request.path == "/health" -> {
                val response = HealthResponse(
                    status = "ok",
                    uptime = System.currentTimeMillis() - startTime.get(),
                    connections = currentConnections.get().toInt(),
                    loaded_model = null,
                )
                writeJson(socket, 200, response)

            }
            request.method == "GET" && request.path == "/v1/models" -> {
                val models = inferenceHandler.getDownloadedLlmModels()
                writeJson(socket, 200, ModelsResponse(data = models.map { it.toApiModel() }))

            }
            request.method == "GET" && request.path == "/v1/engines" -> {
                val models = inferenceHandler.getDownloadedLlmModels()
                writeJson(socket, 200, EnginesResponse(data = models.map { it.toApiEngine() }))

            }
            request.method == "POST" && request.path == "/v1/chat/completions" -> {
                val chatRequest = try { applyConfiguredDefaults(json.decodeFromString<ChatCompletionRequest>(request.body)) }
                catch (_: kotlinx.serialization.SerializationException) { throw IllegalArgumentException("Invalid chat completion JSON") }
                inferenceHandler.validate(chatRequest)
                if (chatRequest.stream) {
                    handleStreamChatCompletion(socket, chatRequest)
                } else {
                    try {
                        val response = runBlocking { inferenceHandler.handleChatCompletion(chatRequest) }
                        writeJson(socket, 200, response)

                    } catch (e: Exception) {

                        throw e
                    }
                }
            }
            else -> {
                writeJson(socket, 404, ErrorResponse(error = "Not found", type = "not_found"))

            }
        }
    }

    private fun readRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < delimiter.size) {
            val next = input.read()
            if (next == -1) return null
            require(headerBytes.size() < 16384) { "Headers too large" }
            headerBytes.write(next)
            matched = if (next.toByte() == delimiter[matched]) matched + 1 else 0
        }

        val headerText = headerBytes.toString(Charsets.UTF_8.name())
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) throw IllegalArgumentException("Invalid request line")

        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
        }

        require("transfer-encoding" !in headers) { "Use Content-Length; chunked requests are unsupported" }
        val contentLength = headers["content-length"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid Content-Length") } ?: 0
        require(contentLength in 0..1048576) { "Body must be at most 1 MiB" }
        val body = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val count = input.read(buffer, offset, contentLength - offset)
                require(count >= 0) { "Incomplete request body" }
                offset += count
            }
            String(buffer, 0, offset, Charsets.UTF_8)
        } else {
            ""
        }

        return HttpRequest(
            method = parts[0].uppercase(Locale.US),
            path = parts[1].substringBefore('?'),
            headers = headers,
            body = body,
        )
    }

    private fun applyConfiguredDefaults(request: ChatCompletionRequest): ChatCompletionRequest {
        val modelId = request.model.ifBlank { config.defaultModelId }
        if (modelId.isBlank()) {
            throw IllegalArgumentException("Model is required. Set request.model or choose a default API model in settings.")
        }
        return request.copy(
            model = modelId,
            temperature = request.temperature ?: config.defaultTemperature,
            max_tokens = request.max_tokens ?: config.defaultMaxTokens,
            top_p = request.top_p ?: config.defaultTopP,
            top_k = request.top_k ?: config.defaultTopK,
            accelerator = request.accelerator ?: config.defaultAccelerator,
            vision_accelerator = request.vision_accelerator ?: config.defaultVisionAccelerator,
        )
    }

    private fun HttpRequest.authorize(): Boolean {
        if (config.authType == AuthType.NONE) return true
        val expected = "Bearer ${config.apiKey}"
        return config.apiKey.isNotBlank() && java.security.MessageDigest.isEqual(headers["authorization"].orEmpty().toByteArray(), expected.toByteArray())
    }

    private inline fun <reified T> writeJson(socket: Socket, statusCode: Int, body: T) {
        writeResponse(socket, statusCode, json.encodeToString(body), "application/json")
    }

    private inline fun <reified T> writeJsonOrLogDisconnect(socket: Socket, statusCode: Int, body: T) {
        try {
            writeJson(socket, statusCode, body)
        } catch (e: SocketException) {
            Log.e(TAG, "$LOG_MARKER event=response_client_disconnected status=$statusCode reason=${e.message}")

        } catch (e: IOException) {
            Log.e(TAG, "$LOG_MARKER event=response_write_failed status=$statusCode reason=${e.message}")

        }
    }

    private fun writeResponse(socket: Socket, statusCode: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        writer.write("HTTP/1.1 $statusCode ${reasonPhrase(statusCode)}\r\n")
        writer.write("Content-Type: $contentType; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n")
        // No browser cross-origin access by default.
        writer.write("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
        writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.write("\r\n")
        writer.flush()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun reasonPhrase(statusCode: Int): String {
        return when (statusCode) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    @Serializable
    private data class EnginesResponse(
        val `object`: String = "list",
        val data: List<com.google.ai.edge.gallery.data.api.ApiEngine>,
    )

    private fun handleStreamChatCompletion(socket: Socket, request: ChatCompletionRequest) {
        try {
            val output = socket.getOutputStream()
            val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8))

            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: text/event-stream; charset=utf-8\r\n")
            writer.write("Cache-Control: no-cache\r\n")
            writer.write("Connection: close\r\n")
            // No browser cross-origin access by default.
            writer.write("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
            writer.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            writer.write("\r\n")
            writer.flush()

            val requestId = "chatcmpl-${System.currentTimeMillis()}"
            val startTime = System.currentTimeMillis()
            var doneSent = false
            var firstChunk = true
            fun writeDoneEvent() {
                val streamEvent = StreamChatChunk(
                    id = requestId,
                    `object` = "chat.completion.chunk",
                    created = System.currentTimeMillis() / 1000,
                    model = request.model,
                    choices = listOf(
                        StreamChoice(
                            index = 0,
                            delta = StreamDelta(content = "", role = null),
                            finish_reason = "stop"
                        )
                    )
                )
                writer.write("data: ${jsonCompact.encodeToString(streamEvent)}\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
                doneSent = true
            }

            runBlocking {
                try {
                    inferenceHandler.handleStreamChatCompletion(request) { chunk, done ->
                        try {
                            val streamEvent = StreamChatChunk(
                                id = requestId,
                                `object` = "chat.completion.chunk",
                                created = System.currentTimeMillis() / 1000,
                                model = request.model,
                                choices = listOf(
                                    StreamChoice(
                                        index = 0,
                                        delta = StreamDelta(content = chunk, role = if (firstChunk) "assistant" else null),
                                        finish_reason = if (done) "stop" else null
                                    )
                                )
                            )
                            firstChunk = false
                            val line = "data: ${jsonCompact.encodeToString(streamEvent)}\n\n"
                            writer.write(line)
                            writer.flush()

                            if (done) {
                                writer.write("data: [DONE]\n\n")
                                writer.flush()
                                doneSent = true
                            }
                        } catch (e: SocketException) {
                            throw ClientDisconnectedException("Client disconnected: ${e.message}")
                        } catch (e: IOException) {
                            throw ClientDisconnectedException("Client disconnected: ${e.message}")
                        }
                    }

                    try {
                        if (!doneSent) {
                            writeDoneEvent()
                        }
                    } catch (e: IOException) {
                        Log.i(TAG, "$LOG_MARKER event=chat_stream_done_write_failed request_id=$requestId reason=${e.message}")

                    }

                } catch (e: ClientDisconnectedException) {
                    Log.i(TAG, "$LOG_MARKER event=chat_stream_client_disconnected request_id=$requestId model=${request.model} reason=${e.message}")

                }
            }
        } catch (e: ClientDisconnectedException) {
            Log.i(TAG, "$LOG_MARKER event=chat_stream_client_disconnected model=${request.model} reason=${e.message}")

        } catch (e: Exception) {
            runCatching {
                val body = jsonCompact.encodeToString(ErrorResponse(error = "Generation failed", type = "server_error"))
                socket.getOutputStream().write("data: $body\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
            Log.e(TAG, "$LOG_MARKER event=stream_error model=${request.model}", e)

        }
    }

    @Serializable
    private data class StreamChatChunk(
        val id: String,
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<StreamChoice>
    )

    @Serializable
    private data class StreamChoice(
        val index: Int,
        val delta: StreamDelta,
        val finish_reason: String? = null
    )

    @Serializable
    private data class StreamDelta(
        val content: String,
        val role: String?
    )
}
