package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.ApiServerConfig
import com.google.ai.edge.gallery.data.AuthType
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.api.*
import java.net.Socket
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApiServerTest {
  private class FakeInference : InferenceHandler {
    override fun getDownloadedLlmModels() = emptyList<Model>()
    override fun validate(request: ChatCompletionRequest) { require(request.messages.isNotEmpty()) }
    override suspend fun handleChatCompletion(request: ChatCompletionRequest) = ChatCompletionResponse(
      id = "chatcmpl-test", model = request.model,
      choices = listOf(Choice(0, ChatMessage("assistant", "Hello"), "stop")), usage = null,
    )
    override suspend fun handleStreamChatCompletion(request: ChatCompletionRequest, onChunk: (String, Boolean) -> Unit) {
      onChunk("Hello\nworld", false)
    }
  }

  private fun withServer(config: ApiServerConfig = ApiServerConfig(port = 0), test: (ApiServer) -> Unit) {
    val server = ApiServer(config, FakeInference())
    try { server.start(); test(server) } finally { server.stop() }
  }

  private fun send(server: ApiServer, path: String, body: String? = null, auth: String = ""): String =
    Socket("127.0.0.1", server.boundPort).use { socket ->
      socket.soTimeout = 3000
      val content = body.orEmpty().toByteArray(Charsets.UTF_8)
      val header = "${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${content.size}\r\n$auth\r\n"
      socket.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
      socket.getOutputStream().write(content)
      socket.getOutputStream().flush()
      socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

  @Test fun healthAndModelsIncludeOpenAiObjectDefaults() = withServer { server ->
    assertTrue(send(server, "/health").startsWith("HTTP/1.1 200"))
    val response = send(server, "/v1/models")
    val body = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
    assertEquals("list", body["object"]!!.jsonPrimitive.content)
    assertEquals(0, body["data"]!!.jsonArray.size)
  }

  @Test fun bearerAuthProtectsHealthAndModels() = withServer(ApiServerConfig(port = 0, authType = AuthType.API_KEY, apiKey = "test-only")) { server ->
    assertTrue(send(server, "/health").startsWith("HTTP/1.1 401"))
    val denied = send(server, "/v1/models", auth = "Authorization: Bearer wrong\r\n")
    assertTrue(denied.startsWith("HTTP/1.1 401"))
    assertTrue(Json.parseToJsonElement(denied.substringAfter("\r\n\r\n")).jsonObject["error"] is JsonObject)
    assertTrue(send(server, "/v1/models", auth = "Authorization: Bearer test-only\r\n").startsWith("HTTP/1.1 200"))
  }

  @Test fun completionsAndStreamingAreCompatible() = withServer { server ->
    val request = """{"model":"test","messages":[{"role":"user","content":"hi"}]}"""
    val response = send(server, "/v1/chat/completions", request)
    val body = Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
    assertEquals("chat.completion", body["object"]!!.jsonPrimitive.content)
    val stream = send(server, "/v1/chat/completions", request.dropLast(1) + ",\"stream\":true}")
    val events = stream.substringAfter("\r\n\r\n").split("\n\n").filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
    assertEquals("[DONE]", events.last())
    assertEquals(1, events.count { it == "[DONE]" })
    events.dropLast(1).forEach { assertEquals("chat.completion.chunk", Json.parseToJsonElement(it).jsonObject["object"]!!.jsonPrimitive.content) }
  }

  @Test fun malformedAndOversizeRequestsAreRejected() = withServer { server ->
    assertTrue(send(server, "/v1/chat/completions", "{").startsWith("HTTP/1.1 400"))
    assertTrue(send(server, "/missing").startsWith("HTTP/1.1 404"))
    Socket("127.0.0.1", server.boundPort).use { socket ->
      socket.soTimeout = 3000
      socket.getOutputStream().write("POST /v1/chat/completions HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".toByteArray())
      assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 400"))
    }
  }
}
