package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.api.ChatCompletionRequest
import com.google.ai.edge.gallery.data.api.ChatCompletionResponse

/** Keeps the HTTP protocol independently testable without loading a native model. */
interface InferenceHandler {
  fun getDownloadedLlmModels(): List<Model>
  fun validate(request: ChatCompletionRequest)
  suspend fun handleChatCompletion(request: ChatCompletionRequest): ChatCompletionResponse
  suspend fun handleStreamChatCompletion(request: ChatCompletionRequest, onChunk: (String, Boolean) -> Unit)
}
