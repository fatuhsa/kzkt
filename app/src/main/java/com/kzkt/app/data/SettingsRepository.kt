package com.kzkt.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kzkt_settings")

/**
 * Settings persistence via DataStore Preferences.
 * Ported from the original Python JSON settings approach
 */
class SettingsRepository(private val context: Context) {

    companion object {
        // Provider
        private val KEY_PROVIDER = stringPreferencesKey("llm_provider")
        private val KEY_LANGUAGE = stringPreferencesKey("target_language")
        // Base URLs
        private val KEY_BASE_URL_GEMINI = stringPreferencesKey("base_url_gemini")
        private val KEY_BASE_URL_OPENAI = stringPreferencesKey("base_url_openai")
        private val KEY_BASE_URL_OPENROUTER = stringPreferencesKey("base_url_openrouter")
        private val KEY_BASE_URL_ZEN = stringPreferencesKey("base_url_zen")
        private val KEY_BASE_URL_OPENCODEGO = stringPreferencesKey("base_url_opencodego")
        private val KEY_CUSTOM_BASE_URL = stringPreferencesKey("custom_base_url")

        // API Keys
        private val KEY_GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        private val KEY_OPENAI_KEY = stringPreferencesKey("openai_api_key")
        private val KEY_OPENROUTER_KEY = stringPreferencesKey("openrouter_api_key")
        private val KEY_ZEN_KEY = stringPreferencesKey("zen_api_key")
        private val KEY_OPENCODEGO_KEY = stringPreferencesKey("opencodego_api_key")
        private val KEY_CUSTOM_KEY = stringPreferencesKey("custom_api_key")

        // Models
        private val KEY_MODEL_GEMINI = stringPreferencesKey("model_gemini")
        private val KEY_MODEL_OPENAI = stringPreferencesKey("model_openai")
        private val KEY_MODEL_OPENROUTER = stringPreferencesKey("model_openrouter")
        private val KEY_MODEL_ZEN = stringPreferencesKey("model_zen")
        private val KEY_MODEL_OPENCODEGO = stringPreferencesKey("model_opencodego")
        private val KEY_MODEL_CUSTOM = stringPreferencesKey("model_custom")

        // Tweak params
        private val KEY_MAX_BUBBLES = intPreferencesKey("max_bubbles_per_request")
        private val KEY_REQUEST_DELAY = floatPreferencesKey("min_request_delay")
        private val KEY_SFX_MODE = stringPreferencesKey("filter_sfx_mode")
        private val KEY_PAD_X = floatPreferencesKey("pad_x_ratio")
        private val KEY_PAD_Y = floatPreferencesKey("pad_y_ratio")
        private val KEY_MIN_PAD = intPreferencesKey("min_pad")
        private val KEY_CUSTOM_FONT = stringPreferencesKey("custom_font_path")
        private val KEY_USE_INPAINTING = booleanPreferencesKey("use_inpainting")
        private val KEY_USE_LOCAL_OCR = booleanPreferencesKey("use_local_ocr")
        private val KEY_LOCAL_OCR_SCRIPT = stringPreferencesKey("local_ocr_script")
        private val KEY_CUSTOM_TIMEOUT = intPreferencesKey("custom_timeout_sec")
        private val KEY_DEV_LOGS = booleanPreferencesKey("enable_dev_logs")
        private val KEY_USE_IMAGE_UPSCALER = booleanPreferencesKey("use_image_upscaler")
        private val KEY_TRANSLATE_SFX = booleanPreferencesKey("translate_sfx")
    }
 
    data class Settings(
        val llmProvider: String = "gemini",
        val targetLanguage: String = "Indonesian",
        val baseUrlGemini: String = "https://generativelanguage.googleapis.com/v1beta",
        val baseUrlOpenai: String = "https://api.openai.com/v1",
        val baseUrlOpenrouter: String = "https://openrouter.ai/api/v1",
        val baseUrlZen: String = "https://opencode.ai/zen/v1",
        val baseUrlOpencodego: String = "https://opencode.ai/zen/go/v1",
        val customBaseUrl: String = "",
        val geminiApiKey: String = "",
        val openaiApiKey: String = "",
        val openrouterApiKey: String = "",
        val zenApiKey: String = "",
        val opencodegoApiKey: String = "",
        val customApiKey: String = "",
        val modelGemini: String = "gemini-3.1-flash-lite",
        val modelOpenai: String = "gpt-5.4-mini",
        val modelOpenrouter: String = "qwen/qwen2.5-vl-72b-instruct:free",
        val modelZen: String = "minimax-m3-free",
        val modelOpencodego: String = "mimo-v2.5",
        val modelCustom: String = "gpt-5.4-mini",
        val maxBubblesPerRequest: Int = 30,
        val minRequestDelay: Float = 2.0f,
        val filterSfxMode: String = "balanced",
        val padXRatio: Float = 0.40f,
        val padYRatio: Float = 0.25f,
        val minPad: Int = 35,
        val customFontPath: String = "",
        val useInpainting: Boolean = false,
        val useLocalOcr: Boolean = false,
        val localOcrScript: String = "Japanese (ML Kit)",
        val customTimeoutSec: Int = 30,
        val enableDevLogs: Boolean = false,
        val useImageUpscaler: Boolean = false,
        val translateSfx: Boolean = false,
    ) {
        fun getBaseUrl(provider: String): String = when (provider) {
            "gemini" -> baseUrlGemini
            "openai" -> baseUrlOpenai
            "openrouter" -> baseUrlOpenrouter
            "zen" -> baseUrlZen
            "opencodego" -> baseUrlOpencodego
            "custom" -> customBaseUrl
            else -> ""
        }
    }
 
    private object Defaults {
        val settings = Settings()
    }
 
    val settingsFlow: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            llmProvider = prefs[KEY_PROVIDER] ?: Defaults.settings.llmProvider,
            targetLanguage = prefs[KEY_LANGUAGE] ?: Defaults.settings.targetLanguage,
            baseUrlGemini = prefs[KEY_BASE_URL_GEMINI] ?: Defaults.settings.baseUrlGemini,
            baseUrlOpenai = prefs[KEY_BASE_URL_OPENAI] ?: Defaults.settings.baseUrlOpenai,
            baseUrlOpenrouter = prefs[KEY_BASE_URL_OPENROUTER] ?: Defaults.settings.baseUrlOpenrouter,
            baseUrlZen = prefs[KEY_BASE_URL_ZEN] ?: Defaults.settings.baseUrlZen,
            baseUrlOpencodego = prefs[KEY_BASE_URL_OPENCODEGO] ?: Defaults.settings.baseUrlOpencodego,
            customBaseUrl = prefs[KEY_CUSTOM_BASE_URL] ?: Defaults.settings.customBaseUrl,
            geminiApiKey = prefs[KEY_GEMINI_KEY] ?: Defaults.settings.geminiApiKey,
            openaiApiKey = prefs[KEY_OPENAI_KEY] ?: Defaults.settings.openaiApiKey,
            openrouterApiKey = prefs[KEY_OPENROUTER_KEY] ?: Defaults.settings.openrouterApiKey,
            zenApiKey = prefs[KEY_ZEN_KEY] ?: Defaults.settings.zenApiKey,
            opencodegoApiKey = prefs[KEY_OPENCODEGO_KEY] ?: Defaults.settings.opencodegoApiKey,
            customApiKey = prefs[KEY_CUSTOM_KEY] ?: Defaults.settings.customApiKey,
            modelGemini = prefs[KEY_MODEL_GEMINI] ?: Defaults.settings.modelGemini,
            modelOpenai = prefs[KEY_MODEL_OPENAI] ?: Defaults.settings.modelOpenai,
            modelOpenrouter = prefs[KEY_MODEL_OPENROUTER] ?: Defaults.settings.modelOpenrouter,
            modelZen = prefs[KEY_MODEL_ZEN] ?: Defaults.settings.modelZen,
            modelOpencodego = prefs[KEY_MODEL_OPENCODEGO] ?: Defaults.settings.modelOpencodego,
            modelCustom = prefs[KEY_MODEL_CUSTOM] ?: Defaults.settings.modelCustom,
            maxBubblesPerRequest = prefs[KEY_MAX_BUBBLES] ?: Defaults.settings.maxBubblesPerRequest,
            minRequestDelay = prefs[KEY_REQUEST_DELAY] ?: Defaults.settings.minRequestDelay,
            filterSfxMode = prefs[KEY_SFX_MODE] ?: Defaults.settings.filterSfxMode,
            padXRatio = prefs[KEY_PAD_X] ?: Defaults.settings.padXRatio,
            padYRatio = prefs[KEY_PAD_Y] ?: Defaults.settings.padYRatio,
            minPad = prefs[KEY_MIN_PAD] ?: Defaults.settings.minPad,
            customFontPath = prefs[KEY_CUSTOM_FONT] ?: Defaults.settings.customFontPath,
            useInpainting = prefs[KEY_USE_INPAINTING] ?: Defaults.settings.useInpainting,
            useLocalOcr = prefs[KEY_USE_LOCAL_OCR] ?: Defaults.settings.useLocalOcr,
            localOcrScript = prefs[KEY_LOCAL_OCR_SCRIPT] ?: Defaults.settings.localOcrScript,
            customTimeoutSec = prefs[KEY_CUSTOM_TIMEOUT] ?: Defaults.settings.customTimeoutSec,
            enableDevLogs = prefs[KEY_DEV_LOGS] ?: Defaults.settings.enableDevLogs,
            useImageUpscaler = prefs[KEY_USE_IMAGE_UPSCALER] ?: Defaults.settings.useImageUpscaler,
            translateSfx = prefs[KEY_TRANSLATE_SFX] ?: Defaults.settings.translateSfx,
        )
    }

    suspend fun saveUseLocalOcr(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_LOCAL_OCR] = enabled }
    }

    suspend fun saveLocalOcrScript(script: String) {
        context.dataStore.edit { it[KEY_LOCAL_OCR_SCRIPT] = script }
    }
 
    suspend fun saveProvider(provider: String) {
        context.dataStore.edit { it[KEY_PROVIDER] = provider }
    }
 
    suspend fun saveLanguage(language: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = language }
    }
 
    suspend fun saveApiKey(providerName: String, key: String) {
        context.dataStore.edit { prefs ->
            val keyPref = when (providerName) {
                "gemini" -> KEY_GEMINI_KEY
                "openai" -> KEY_OPENAI_KEY
                "openrouter" -> KEY_OPENROUTER_KEY
                "zen" -> KEY_ZEN_KEY
                "opencodego" -> KEY_OPENCODEGO_KEY
                "custom" -> KEY_CUSTOM_KEY
                else -> return@edit
            }
            prefs[keyPref] = key
        }
    }
 
    suspend fun saveModel(providerName: String, model: String) {
        context.dataStore.edit { prefs ->
            val key = when (providerName) {
                "gemini" -> KEY_MODEL_GEMINI
                "openai" -> KEY_MODEL_OPENAI
                "openrouter" -> KEY_MODEL_OPENROUTER
                "zen" -> KEY_MODEL_ZEN
                "opencodego" -> KEY_MODEL_OPENCODEGO
                "custom" -> KEY_MODEL_CUSTOM
                else -> return@edit
            }
            prefs[key] = model
        }
    }
 
    suspend fun saveBaseUrl(providerName: String, url: String) {
        context.dataStore.edit { prefs ->
            val key = when (providerName) {
                "gemini" -> KEY_BASE_URL_GEMINI
                "openai" -> KEY_BASE_URL_OPENAI
                "openrouter" -> KEY_BASE_URL_OPENROUTER
                "zen" -> KEY_BASE_URL_ZEN
                "opencodego" -> KEY_BASE_URL_OPENCODEGO
                "custom" -> KEY_CUSTOM_BASE_URL
                else -> return@edit
            }
            prefs[key] = url
        }
    }

    suspend fun saveCustomFontPath(path: String) {
        context.dataStore.edit { it[KEY_CUSTOM_FONT] = path }
    }
 
    suspend fun saveUseInpainting(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_INPAINTING] = enabled }
    }
 
    suspend fun saveCustomTimeoutSec(seconds: Int) {
        context.dataStore.edit { it[KEY_CUSTOM_TIMEOUT] = seconds.coerceIn(30, 600) }
    }

    suspend fun saveEnableDevLogs(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DEV_LOGS] = enabled }
    }

    suspend fun saveUseImageUpscaler(use: Boolean) {
        context.dataStore.edit { it[KEY_USE_IMAGE_UPSCALER] = use }
    }

    suspend fun saveTranslateSfx(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TRANSLATE_SFX] = enabled }
    }

    /**
     * Serialize every stored preference into a flat JSON map (key → {t: type, v: value}).
     * Used by the full-data backup feature; types are tagged so [importAll] can rebuild
     * strongly-typed preferences.
     */
    suspend fun exportAllJson(): String {
        val prefs = context.dataStore.data.first()
        val root = JSONObject()
        prefs.asMap().forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is String -> { entry.put("t", "string"); entry.put("v", value) }
                is Int -> { entry.put("t", "int"); entry.put("v", value) }
                is Long -> { entry.put("t", "long"); entry.put("v", value) }
                is Float -> { entry.put("t", "float"); entry.put("v", value.toDouble()) }
                is Boolean -> { entry.put("t", "boolean"); entry.put("v", value) }
                is Set<*> -> { entry.put("t", "stringSet"); entry.put("v", value.joinToString("\u0001")) }
                else -> return@forEach
            }
            root.put(key.name, entry)
        }
        return root.toString()
    }

    /** Restore all preferences from a JSON map produced by [exportAllJson]. */
    suspend fun importAllJson(json: String) {
        val root = JSONObject(json)
        context.dataStore.edit { prefs ->
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val entry = root.getJSONObject(name)
                when (entry.getString("t")) {
                    "string" -> prefs[stringPreferencesKey(name)] = entry.getString("v")
                    "int" -> prefs[intPreferencesKey(name)] = entry.getInt("v")
                    "long" -> prefs[longPreferencesKey(name)] = entry.getLong("v")
                    "float" -> prefs[floatPreferencesKey(name)] = entry.getDouble("v").toFloat()
                    "boolean" -> prefs[booleanPreferencesKey(name)] = entry.getBoolean("v")
                    "stringSet" -> prefs[stringSetPreferencesKey(name)] = entry.getString("v").split("\u0001").toSet()
                }
            }
        }
    }
 
    suspend fun saveTweakParam(keyField: String, value: Any) {
        context.dataStore.edit { prefs ->
            when (keyField) {
                "max_bubbles" -> if (value is Int) prefs[KEY_MAX_BUBBLES] = value
                "request_delay" -> if (value is Float) prefs[KEY_REQUEST_DELAY] = value
                "sfx_mode" -> if (value is String) prefs[KEY_SFX_MODE] = value
                "pad_x" -> if (value is Float) prefs[KEY_PAD_X] = value
                "pad_y" -> if (value is Float) prefs[KEY_PAD_Y] = value
                "min_pad" -> if (value is Int) prefs[KEY_MIN_PAD] = value
                "use_inpainting" -> if (value is Boolean) prefs[KEY_USE_INPAINTING] = value
                "custom_timeout" -> if (value is Int) prefs[KEY_CUSTOM_TIMEOUT] = value.coerceIn(30, 600)
            }
        }
    }
 
    suspend fun resetToDefault() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_MAX_BUBBLES)
            prefs.remove(KEY_REQUEST_DELAY)
            prefs.remove(KEY_SFX_MODE)
            prefs.remove(KEY_PAD_X)
            prefs.remove(KEY_PAD_Y)
            prefs.remove(KEY_MIN_PAD)
            prefs.remove(KEY_USE_INPAINTING)
            prefs.remove(KEY_CUSTOM_TIMEOUT)
            prefs.remove(KEY_DEV_LOGS)
            prefs.remove(KEY_CUSTOM_FONT)
            prefs.remove(KEY_USE_LOCAL_OCR)
            prefs.remove(KEY_LOCAL_OCR_SCRIPT)
            prefs.remove(KEY_USE_IMAGE_UPSCALER)
            prefs.remove(KEY_TRANSLATE_SFX)
        }
    }
}
