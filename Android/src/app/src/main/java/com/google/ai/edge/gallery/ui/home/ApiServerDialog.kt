/* Copyright 2026. Licensed under the Apache License, Version 2.0. */
package com.google.ai.edge.gallery.ui.home

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.data.*
import com.google.ai.edge.gallery.server.*
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun ApiServerDialog(modelManagerViewModel: ModelManagerViewModel, onDismiss: () -> Unit) {
  val app = LocalContext.current.applicationContext as Application
  val settings = remember { ApiServerConfigManager(app) }
  val config by settings.configFlow.collectAsState()
  val status by ApiServerManager.serverStatus.collectAsState()
  val modelState by modelManagerViewModel.uiState.collectAsState()
  val models = modelState.tasks.flatMap { it.models }.distinctBy { it.name }.filter {
    it.runtimeType == RuntimeType.LITERT_LM && modelState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
  }
  val running = status is ServerStatus.Running
  val starting = status is ServerStatus.Starting
  var port by remember { mutableStateOf(config.port.toString()) }
  var error by remember { mutableStateOf("") }
  val addresses = remember {
    runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
      .filter { it is Inet4Address && !it.isLoopbackAddress }.mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
  }
  LaunchedEffect(Unit) { ApiServerManager.initialize(app) }
  Dialog(onDismissRequest = onDismiss) {
    Surface(shape = MaterialTheme.shapes.large) {
      Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("API Server", style = MaterialTheme.typography.headlineSmall)
        Text(if (starting) "Starting…" else if (running) "Running" else "Stopped")
        if (status is ServerStatus.Error) Text((status as ServerStatus.Error).message, color = MaterialTheme.colorScheme.error)
        Text("Keep Gallery open while serving requests. Stop the server before changing settings.")
        Row {
          RadioButton(selected = config.host == "127.0.0.1", enabled = !running && !starting, onClick = { settings.updateConfig { it.copy(host = "127.0.0.1") } })
          Text("This phone only (localhost)", Modifier.padding(top = 12.dp))
        }
        Row {
          RadioButton(selected = config.host == "0.0.0.0", enabled = !running && !starting, onClick = { settings.updateConfig { it.copy(host = "0.0.0.0") } })
          Text("Local network (LAN)", Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(port, { port = it }, enabled = !running && !starting, label = { Text("Port (default 8080)") })
        Row {
          Checkbox(config.authType != AuthType.NONE, enabled = !running && !starting, onCheckedChange = { checked -> settings.updateConfig { it.copy(authType = if (checked) AuthType.API_KEY else AuthType.NONE) } })
          Text("Require API key", Modifier.padding(top = 12.dp))
        }
        if (config.authType != AuthType.NONE) {
          OutlinedTextField(config.apiKey, { value -> settings.updateConfig { it.copy(apiKey = value) } }, enabled = !running && !starting,
            label = { Text("Bearer API key") }, visualTransformation = PasswordVisualTransformation())
        }
        if (config.host == "0.0.0.0") Text("LAN requests use unencrypted HTTP. Use a trusted network and an API key.")
        Text("Default model", style = MaterialTheme.typography.titleMedium)
        if (models.isEmpty()) Text("Download or import a LiteRT LM model in Models first.")
        models.forEach { model ->
          Row {
            RadioButton(config.defaultModelId == model.name, enabled = !running && !starting, onClick = { settings.updateConfig { it.copy(defaultModelId = model.name) } })
            Text(model.displayName.ifEmpty { model.name }, Modifier.padding(top = 12.dp).weight(1f))
          }
        }
        Text("Accelerator", style = MaterialTheme.typography.titleMedium)
        Row {
          listOf(Accelerator.GPU, Accelerator.CPU).forEach { accelerator ->
            RadioButton(config.defaultAccelerator == accelerator.label, enabled = !running && !starting, onClick = { settings.updateConfig { it.copy(defaultAccelerator = accelerator.label) } })
            Text(accelerator.label, Modifier.padding(top = 12.dp))
          }
        }
        Text("Base URL: http://127.0.0.1:${config.port}/v1")
        if (config.host == "0.0.0.0") addresses.forEach { Text("LAN: http://$it:${config.port}/v1") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !starting, onClick = {
          if (running) ApiServerManager.stopServer()
          else {
            val number = port.toIntOrNull()
            if (number == null || number !in 1024..65535) error = "Choose a port from 1024 to 65535."
            else if (config.authType != AuthType.NONE && config.apiKey.isBlank()) error = "Enter an API key."
            else { error = ""; settings.updateConfig { it.copy(port = number) }; ApiServerManager.startServer(modelManagerViewModel) }
          }
        }) { Text(if (running) "Stop server" else "Start server") }
        TextButton(onClick = onDismiss) { Text("Close") }
      }
    }
  }
}
