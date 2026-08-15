@file:OptIn(ExperimentalMaterial3Api::class)

package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.Config
import com.kzkt.app.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** API key / base URL / model card for the currently selected provider. */
@Composable
fun ActiveProviderConfigCard(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    // Read only the fields this card needs, each via its own derivedStateOf, so a
    // change to any single setting recomposes just this card — not the whole
    // Settings screen (recomposition storm).
    val providerKey by remember { derivedStateOf { viewModel.settings.value.llmProvider } }
    val meta = Config.PROVIDER_REGISTRY[providerKey] ?: return

    val apiKey by remember(providerKey) { derivedStateOf {
        when (providerKey) {
            "gemini" -> viewModel.settings.value.geminiApiKey
            "openai" -> viewModel.settings.value.openaiApiKey
            "openrouter" -> viewModel.settings.value.openrouterApiKey
            "zen" -> viewModel.settings.value.zenApiKey
            "opencodego" -> viewModel.settings.value.opencodegoApiKey
            "custom" -> viewModel.settings.value.customApiKey
            "anthropic" -> viewModel.settings.value.anthropicApiKey
            else -> ""
        }
    } }

    val baseUrl by remember(providerKey) { derivedStateOf { viewModel.settings.value.getBaseUrl(providerKey) } }
    val defaultBaseUrl = meta.defaultBaseUrl

    val currentModel by remember(providerKey) { derivedStateOf {
        when (providerKey) {
            "gemini" -> viewModel.settings.value.modelGemini
            "openai" -> viewModel.settings.value.modelOpenai
            "openrouter" -> viewModel.settings.value.modelOpenrouter
            "zen" -> viewModel.settings.value.modelZen
            "opencodego" -> viewModel.settings.value.modelOpencodego
            "custom" -> viewModel.settings.value.modelCustom
            "anthropic" -> viewModel.settings.value.modelAnthropic
            else -> meta.defaultModel
        }
    } }

    // Live API-key / base-URL field states (hoisted so the "Test API Key" button
    // validates what the user is actually typing, not the debounced saved value).
    var apiKeyText by remember(providerKey) { mutableStateOf(apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(apiKeyText) {
        if (apiKeyText != apiKey) {
            delay(350)
            viewModel.settingsRepo.saveApiKey(providerKey, apiKeyText)
        }
    }
    var baseUrlText by remember(providerKey) { mutableStateOf(baseUrl) }
    LaunchedEffect(baseUrlText) {
        if (baseUrlText != baseUrl) {
            delay(350)
            viewModel.settingsRepo.saveBaseUrl(providerKey, baseUrlText)
        }
    }

    val detected: List<String> = viewModel.providerModels[providerKey] ?: emptyList()
    val presetList: List<String> = Config.PRESET_MODELS[providerKey] ?: emptyList()
    val allModels = remember(presetList, detected) { (presetList + detected).distinct().sorted() }
    val isLoading = viewModel.modelsLoading.value

    // One unified card for all provider fields (API key / base URL / model) — a
    // single shared background instead of separate boxes per input.
    Text(
        "${meta.displayName} Configuration",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
    )
    val cardContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // API key field — hoisted state (see apiKeyText / apiKeyVisible above).
            // The single debounced LaunchedEffect is the only write path.
            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it },
                label = { Text(if (meta.requiresKey) "API Key" else "API Key (Optional)") },
                placeholder = { Text(if (meta.requiresKey) "Enter API Key" else "Optional API Key") },
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (apiKeyVisible) "Hide" else "Show",
                        )
                    }
                },
            )
            // Base URL is only meaningful for the generic "Custom" provider — the
            // built-in providers use their own fixed endpoints.
            if (providerKey == "custom") {
                OutlinedTextField(
                    value = baseUrlText,
                    onValueChange = { baseUrlText = it },
                    label = { Text("Base URL") },
                    placeholder = { Text(if (defaultBaseUrl.isNotBlank()) defaultBaseUrl else "https://api.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (defaultBaseUrl.isNotBlank() && baseUrlText != defaultBaseUrl) {
                            TextButton(onClick = {
                                baseUrlText = defaultBaseUrl
                                scope.launch { viewModel.settingsRepo.saveBaseUrl(providerKey, defaultBaseUrl) }
                            }) {
                                Text("Reset", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                )
            }
            // Model field — editable text with a dropdown of presets / detected models.
            ModelDropdownInput(
                label = meta.displayName,
                value = currentModel,
                presets = if (allModels.isNotEmpty()) allModels else listOf(meta.defaultModel),
                onValue = { scope.launch { viewModel.settingsRepo.saveModel(providerKey, it) } },
            )
        }
    }

    // Detect (model auto-detection, network call) and Test (health check) are
    // real actions, so they are buttons — side by side to keep the card compact.
    Spacer(Modifier.height(8.dp))
    val testState = viewModel.providerTestState.value
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { viewModel.fetchModelsForProvider(providerKey, baseUrl, apiKey) },
            enabled = !isLoading,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Fetching…")
            } else {
                Icon(
                    imageVector = Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Detect Models")
            }
        }
        OutlinedButton(
            onClick = { viewModel.testProviderConnection(providerKey, baseUrlText, apiKeyText, currentModel) },
            enabled = testState?.loading != true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
        ) {
            if (testState?.loading == true) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Testing…")
            } else {
                Icon(
                    imageVector = Icons.Outlined.GppGood,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Test API")
            }
        }
    }
    testState?.let { state ->
        if (!state.loading && state.message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            val resultColor = when (state.ok) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = resultColor,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** Editable model-name field with a dropdown of presets / detected models. */
@Composable
fun ModelDropdownInput(
    label: String,
    value: String,
    presets: List<String>,
    onValue: (String) -> Unit,
) {
    var textState by remember(value) { mutableStateOf(value) }
    var expanded by remember { mutableStateOf(false) }

    // Debounce writes: typing a long model name would otherwise hit DataStore on
    // every keystroke. Saving happens 350 ms after typing pauses (or immediately
    // when picking from the dropdown).
    LaunchedEffect(textState) {
        if (textState != value) {
            delay(350)
            onValue(textState)
        }
    }

    val options = remember(presets, value) {
        val list = presets.toMutableList()
        if (value.isNotBlank() && value !in list) list.add(0, value)
        list
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = textState,
            onValueChange = { newText ->
                textState = newText
            },
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        textState = model
                        onValue(model)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
