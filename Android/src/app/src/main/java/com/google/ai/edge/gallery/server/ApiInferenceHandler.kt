/* Copyright 2025 Google LLC. Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 */
package com.google.ai.edge.gallery.server

import android.content.Context
import com.google.ai.edge.gallery.data.*
import com.google.ai.edge.gallery.data.api.*
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Port of bugroom's bridge using the unchanged 1.0.19 initializer and private conversations. */
class ApiInferenceHandler(
  private val context: Context,
  private val modelManagerViewModel: ModelManagerViewModel,
  maxConcurrent: Int,
  private val requestTimeoutMs: Long,
) : InferenceHandler {
  companion object { private val inferenceLock = Mutex() }

  override fun getDownloadedLlmModels(): List<Model> =
    modelManagerViewModel.getAllDownloadedModels().filter { it.runtimeType == RuntimeType.LITERT_LM }

  override fun validate(request: ChatCompletionRequest) {
    require(request.messages.isNotEmpty()) { "messages must not be empty" }
    require(request.messages.all { it.role in listOf("system", "user", "assistant") }) { "Only text system, user and assistant messages are supported" }
    require(request.messages.last().role == "user") { "The last message must be from the user" }
    require(request.stop.isNullOrEmpty()) { "Custom stop sequences are unsupported" }
    require(request.temperature == null || (request.temperature.isFinite() && request.temperature in 0.0..2.0)) { "temperature must be 0–2" }
    require(request.top_p == null || (request.top_p.isFinite() && request.top_p > 0 && request.top_p <= 1)) { "top_p must be greater than 0 and at most 1" }
    require(request.top_k == null || request.top_k in 1..1024) { "top_k must be 1–1024" }
    require(request.max_tokens == null || request.max_tokens in 1..32768) { "max_tokens must be 1–32768" }
    val source = getDownloadedLlmModels().firstOrNull { it.name == request.model }
    require(source != null) { "Choose a downloaded LiteRT LM model from /v1/models" }
    val accelerator = request.accelerator ?: Accelerator.GPU.label
    require(Accelerator.entries.any { it.label == accelerator }) { "Invalid accelerator" }
    require(source.accelerators.isEmpty() || source.accelerators.any { it.label == accelerator }) { "Accelerator is unsupported by this model" }
  }

  override suspend fun handleChatCompletion(request: ChatCompletionRequest): ChatCompletionResponse {
    val response = StringBuilder()
    generate(request) { chunk, _ -> response.append(chunk) }
    return ChatCompletionResponse(
      id = "chatcmpl-${UUID.randomUUID()}", model = request.model,
      choices = listOf(Choice(0, ChatMessage("assistant", response.toString()), "stop")),
      usage = null, // LiteRT does not expose exact token counts through this callback.
    )
  }

  override suspend fun handleStreamChatCompletion(request: ChatCompletionRequest, onChunk: (String, Boolean) -> Unit) = generate(request, onChunk)

  private suspend fun generate(request: ChatCompletionRequest, onChunk: (String, Boolean) -> Unit) {
    validate(request)
    withTimeout(requestTimeoutMs) {
      inferenceLock.withLock {
        val source = getDownloadedLlmModels().first { it.name == request.model }
        val model = source.copy()
        model.configValues = source.configValues.toMap() + mapOf(
          ConfigKeys.ACCELERATOR.label to (request.accelerator ?: Accelerator.GPU.label),
          ConfigKeys.TEMPERATURE.label to (request.temperature ?: 0.7).toFloat(),
          ConfigKeys.TOPP.label to (request.top_p ?: 0.95).toFloat(),
          ConfigKeys.TOPK.label to (request.top_k ?: 40),
          ConfigKeys.MAX_TOKENS.label to (request.max_tokens ?: 1024),
        )
        var instance: LlmModelInstance? = null
        val finished = CompletableDeferred<Unit>()
        try {
          val initialized = CompletableDeferred<Unit>()
          // Preserve upstream's EngineConfig and GPU/runtime initialization.
          LlmChatModelHelper.initialize(
            context = context, model = model, taskId = BuiltInTaskId.LLM_CHAT,
            supportImage = false, supportAudio = false,
            onDone = { error ->
              if (error.isEmpty() && model.instance != null) initialized.complete(Unit)
              else initialized.completeExceptionally(IllegalStateException(error.ifEmpty { "Initialization failed" }))
            },
          )
          initialized.await()
          instance = model.instance as LlmModelInstance
          currentCoroutineContext().ensureActive()
          val system = request.messages.filter { it.role == "system" }.joinToString("\n") { it.content }
          val history = request.messages.dropLast(1).filter { it.role != "system" }.map {
            if (it.role == "assistant") Message.model(it.content) else Message.user(it.content)
          }
          LlmChatModelHelper.resetConversation(
            model = model,
            systemInstruction = system.takeIf { it.isNotEmpty() }?.let { Contents.of(it) },
            initialMessages = history,
          )
          instance.conversation.sendMessageAsync(
            Contents.of(request.messages.last().content),
            object : MessageCallback {
              override fun onMessage(message: Message) {
                if (!finished.isCompleted) try { onChunk(message.toString(), false) }
                catch (e: Exception) { finished.completeExceptionally(e) }
              }
              override fun onDone() { finished.complete(Unit) }
              override fun onError(throwable: Throwable) { finished.completeExceptionally(throwable) }
            },
            mapOf("enable_thinking" to false),
          )
          finished.await()
        } finally {
          // Close only this request's resources, never the UI's shared model instance.
          val owned = instance ?: model.instance as? LlmModelInstance
          if (owned != null) {
            runCatching { owned.conversation.cancelProcess() }
            runCatching { owned.conversation.close() }
            runCatching { owned.engine.close() }
          }
        }
      }
    }
  }
}
