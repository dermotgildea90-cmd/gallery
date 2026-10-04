# Gallery Local API — 1.0.19

Based on upstream tag `1.0.19` (`3002f9a581511a7758b3e1129222af2ebe2d9f85`).
The socket server, configuration and API data types are adapted from
[bugroom/google-ai-edge-gallery-local-api](https://github.com/bugroom/google-ai-edge-gallery-local-api)
at `7868211`. The LiteRT LM dependency remains **0.11.0** and the upstream
`LlmChatModelHelper` initialization code is unchanged, including GPU selection.

## Install and start

Install the debug APK as **Gallery Local API** (`com.dermotgildea.gallery.localapi`).
It can coexist with official Edge Gallery. Models and settings are separate;
download/import your `.litertlm` model in this app.

Open the home menu → **API Server**. Choose a downloaded model, GPU or CPU,
localhost or LAN, and optionally a bearer API key. The default port is **8080**.
Press **Start server**. Stop before changing settings. Server startup is always
manual; saved preferences never silently expose the phone on the network.

Keep the app open while serving. This implementation is process-scoped, not an
Android foreground service; Android may terminate it in the background. Avoid
running an interactive model chat at the same time on memory-constrained phones.
LAN mode binds `0.0.0.0`; use the phone's displayed LAN address from other devices.
HTTP is unencrypted. Use a trusted LAN and enable authentication when sharing it.

## Endpoints

- `GET /health`: health, uptime and active connections.
- `GET /v1/models`: downloaded LiteRT LM models; use the returned `id` verbatim.
- `POST /v1/chat/completions`: text chat, with optional `stream: true` for SSE.
- `GET /v1/engines`: reference-fork compatibility alias.

When authentication is enabled **all endpoints** require
`Authorization: Bearer <your-key>`. No prompts, replies, headers or API keys are
persisted to traffic logs. API preferences are excluded from Android backup.

Example body (replace the model name with the ID from `/v1/models`):

```json
{
  "model": "MODEL_ID",
  "messages": [
    {"role": "system", "content": "Answer briefly."},
    {"role": "user", "content": "Hello"}
  ],
  "stream": true,
  "accelerator": "GPU",
  "max_tokens": 1024,
  "temperature": 0.7,
  "top_p": 0.95,
  "top_k": 40
}
```

Set an OpenAI-compatible client's base URL to `http://PHONE_IP:8080/v1`.
Text `system`, `user` and `assistant` messages are supported, ending with `user`.
Conversation roles use LiteRT's native history, rather than concatenated labels.
Each request owns a fresh engine and conversation, preventing previous requests
or UI history from leaking into the next response. This trades startup latency
for predictable resource ownership. Inference is serialized across API calls.

This is a text-chat subset, not the entire OpenAI API: no tools, image/audio
messages, custom stop sequences or exact token usage. `max_tokens` sets LiteRT's
total context capacity, not an exact output-only token limit. Streaming errors
are returned as an error event followed by `[DONE]`. Requests have a three-minute
timeout, bounded HTTP headers/body, bounded connection queue and read timeout.

## Build and verify

Use JDK 21 and the Android SDK platform `platforms;android-37.0` (the decimal
suffix is required by the SDK manager). From `Android/src` run:

```text
gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Release builds retain
upstream's debug signing configuration and are not store-signed releases.
The HTTP tests exercise authentication, health/models, completion JSON, SSE,
malformed requests and request-size limits without requiring a native model.
Real SmolLM3 GPU inference still requires a compatible Android phone and model.
