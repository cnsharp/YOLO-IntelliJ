package com.cnsharp.yolo.settings

import com.cnsharp.yolo.YoloBundle.message
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * Per-agent LLM-backend injection. Each agent that can be pointed at a custom LLM backend owns a
 * [AgentLlmInjector] implementation: env-based agents build spawn-environment overrides; config-file
 * agents write/merge an on-disk config file and return the env vars that carry the API key into the
 * process. This keeps every agent's (verified-against-official-docs) mechanism in one place.
 *
 * Two injection styles exist:
 *  - **env-based** ([EnvInjector]): non-invasive, no files touched (claude/goose/aider/gemini/copilot/trae/hermes).
 *  - **config-file-based** ([ConfigInjector]): writes a config file at launch (codebuddy/opencode/codex/
 *    cline/kilo/kimi/openclaw/pi/continue). The API key is never written in plaintext — the file references
 *    an env var and the real secret is injected into the spawned process env.
 *
 * All writes are self-cleaning: every YOLO-managed entry uses a `yolo-` id (or `yoloId` for YAML), so
 * rebinding / unbinding never leaves stale entries behind.
 */
interface AgentLlmInjector {
    /** Primary agent command (lower-cased), e.g. "claude". */
    val command: String
    /** Additional commands that resolve to this injector (e.g. "gemini-cli" -> gemini). */
    val aliases: List<String> get() = emptyList()
    /** True if the backend is configured via a config file (not spawn env). */
    val configBased: Boolean
    /** Human-readable note shown in the provider dialog, or null if fully env-configurable. */
    fun injectionNote(): String?
    /** Env var overrides to inject at spawn (env-based agents). */
    fun toEnv(provider: LlmProvider): Map<String, String>
    /** Write the config file and return the env vars carrying the API key. [home] is the user home. */
    fun applyConfig(provider: LlmProvider, home: Path): Map<String, String>
    /** Remove all YOLO-managed entries for this agent. */
    fun removeConfig(home: Path)
}

object LlmInjectorRegistry {
    val all: List<AgentLlmInjector> = listOf(
        ClaudeInjector, GooseInjector, AiderInjector, GeminiInjector,
        CopilotInjector, TraeInjector, HermesInjector,
        CodeBuddyInjector, OpenCodeInjector, CodexInjector,
        ClineInjector, KiloInjector, KimiInjector, OpenClawInjector, PiInjector, ContinueInjector
    )
    private val byCmd: Map<String, AgentLlmInjector> = buildMap {
        for (inj in all) {
            put(inj.command, inj)
            for (a in inj.aliases) put(a.lowercase(), inj)
        }
    }
    fun byCommand(cmd: String): AgentLlmInjector? = byCmd[cmd.lowercase()]
}

// =====================================================================================
// Shared helpers (top-level, used by both env and config injectors)
// =====================================================================================

/** Prefix for every provider id YOLO writes into agent config files. Lets us upsert/remove our
 *  entries safely without disturbing user-managed ones (see each [AgentLlmInjector]). */
const val YOLO_PROVIDER_ID_PREFIX = "yolo-"

fun llmConfigProviderId(provider: LlmProvider): String = YOLO_PROVIDER_ID_PREFIX + provider.id

fun llmFamilyToVendor(family: ProviderFamily): String = when (family) {
    ProviderFamily.ANTHROPIC -> "Anthropic"
    ProviderFamily.OPENAI -> "OpenAI"
    ProviderFamily.GEMINI -> "Google"
    ProviderFamily.CUSTOM -> "Custom"
}

// =====================================================================================
// ENV-BASED injectors
// =====================================================================================

abstract class EnvInjector(
    override val command: String,
    private val noteKey: String? = null,
    vararg noteArgs: Any
) : AgentLlmInjector {
    private val noteArgs: Array<out Any> = noteArgs
    override val configBased = false
    override fun injectionNote(): String? = noteKey?.let { message(it, *noteArgs) }
    override fun applyConfig(provider: LlmProvider, home: Path) = emptyMap<String, String>()
    override fun removeConfig(home: Path) {}

    protected fun key(provider: LlmProvider): String? = LlmProvider.getApiKey(provider.apiKeyRef)
    protected fun base(provider: LlmProvider): String = provider.baseUrl.takeIf { it.isNotBlank() } ?: ""
    protected fun model(provider: LlmProvider): String = provider.defaultModel.takeIf { it.isNotBlank() } ?: ""
}

object ClaudeInjector : EnvInjector("claude") {
    override fun toEnv(provider: LlmProvider): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val b = base(provider); val k = key(provider); val m = model(provider)
        if (b.isNotBlank()) map["ANTHROPIC_BASE_URL"] = b
        if (k != null) map["ANTHROPIC_API_KEY"] = k
        if (m.isNotBlank()) map["ANTHROPIC_MODEL"] = m
        return map
    }
}

object GooseInjector : EnvInjector("goose") {
    override fun toEnv(provider: LlmProvider): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val b = base(provider); val k = key(provider); val m = model(provider)
        val providerName = when (provider.family) {
            ProviderFamily.ANTHROPIC -> "anthropic"
            ProviderFamily.OPENAI -> "openai"
            ProviderFamily.GEMINI -> "gemini"
            ProviderFamily.CUSTOM -> "" // CUSTOM: let the user set GOOSE_PROVIDER via envOverrides
        }
        if (providerName.isNotBlank()) map["GOOSE_PROVIDER"] = providerName
        if (m.isNotBlank()) map["GOOSE_MODEL"] = m
        if (b.isNotBlank()) map["GOOSE_PROVIDER__HOST"] = b
        if (k != null) map["GOOSE_PROVIDER__API_KEY"] = k
        return map
    }
}

object AiderInjector : EnvInjector("aider") {
    override fun toEnv(provider: LlmProvider): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val k = key(provider); val m = model(provider)
        if (m.isNotBlank()) map["AIDER_MODEL"] = m
        when (provider.family) {
            ProviderFamily.OPENAI -> {
                if (k != null) map["AIDER_OPENAI_API_KEY"] = k
                val b = base(provider)
                if (b.isNotBlank()) map["AIDER_OPENAI_API_BASE"] = b
            }
            ProviderFamily.ANTHROPIC -> { if (k != null) map["AIDER_ANTHROPIC_API_KEY"] = k }
            ProviderFamily.GEMINI -> { if (k != null) map["GEMINI_API_KEY"] = k }
            ProviderFamily.CUSTOM -> { /* envOverrides only */ }
        }
        return map
    }
}

object GeminiInjector : EnvInjector("gemini", noteKey = "provider.note.gemini") {
    override val aliases = listOf("gemini-cli")
    override fun toEnv(provider: LlmProvider): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        key(provider)?.let { map["GEMINI_API_KEY"] = it }
        return map
    }
}

object CopilotInjector : EnvInjector("copilot") {
    override fun toEnv(provider: LlmProvider): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val b = base(provider); val k = key(provider); val m = model(provider)
        if (b.isNotBlank()) map["COPILOT_PROVIDER_BASE_URL"] = b
        if (k != null) map["COPILOT_PROVIDER_API_KEY"] = k
        map["COPILOT_PROVIDER_TYPE"] = when (provider.family) {
            ProviderFamily.ANTHROPIC -> "anthropic"
            else -> "openai" // OPENAI / CUSTOM / GEMINI all map to the openai BYOK slot
        }
        if (m.isNotBlank()) map["COPILOT_MODEL"] = m
        return map
    }
}

object TraeInjector : EnvInjector("trae") {
    override fun toEnv(provider: LlmProvider): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val b = base(provider); val k = key(provider); val m = model(provider)
        val (baseVar, keyVar) = when (provider.family) {
            ProviderFamily.ANTHROPIC -> "ANTHROPIC_BASE_URL" to "ANTHROPIC_API_KEY"
            ProviderFamily.GEMINI -> "GOOGLE_BASE_URL" to "GEMINI_API_KEY"
            else -> "OPENAI_BASE_URL" to "OPENAI_API_KEY"
        }
        if (b.isNotBlank()) map[baseVar] = b
        if (k != null) map[keyVar] = k
        if (m.isNotBlank()) map["TRAE_MODEL"] = m
        return map
    }
}

object HermesInjector : EnvInjector("hermes") {
    override fun toEnv(provider: LlmProvider): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val b = base(provider); val k = key(provider); val m = model(provider)
        if (b.isNotBlank()) map["OPENAI_BASE_URL"] = b
        if (k != null) map["OPENAI_API_KEY"] = k
        if (m.isNotBlank()) map["HERMES_MODEL"] = m
        return map
    }
}

// =====================================================================================
// CONFIG-FILE injectors
// =====================================================================================

abstract class ConfigInjector(
    override val command: String,
    private val noteKey: String,
    vararg noteArgs: Any
) : AgentLlmInjector {
    private val noteArgs: Array<out Any> = noteArgs
    override val configBased = true
    override fun injectionNote(): String? = message(noteKey, *noteArgs)
    override fun toEnv(provider: LlmProvider) = emptyMap<String, String>()
    protected fun keyEnv(provider: LlmProvider, envName: String): Map<String, String> {
        val k = key(provider)
        return if (k != null) mapOf(envName to k) else emptyMap()
    }
    protected fun key(provider: LlmProvider): String? = LlmProvider.getApiKey(provider.apiKeyRef)
    protected fun base(provider: LlmProvider): String = provider.baseUrl.takeIf { it.isNotBlank() } ?: ""
    protected fun model(provider: LlmProvider): String = provider.defaultModel.takeIf { it.isNotBlank() } ?: ""

    // ---- shared JSON / TOML / YAML helpers (dependency-free, offline-safe) ----

    protected fun readJsonObject(file: Path): MutableMap<String, Any>? {
        if (!file.exists()) return null
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val parsed = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        return linkedMapOf<String, Any>().apply { parsed.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
    }

    protected fun readJsoncObject(file: Path): MutableMap<String, Any>? {
        if (!file.exists()) return null
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val parsed = runCatching { MiniJson.parse(stripJsonc(text)) }.getOrNull() as? Map<*, *> ?: return null
        return linkedMapOf<String, Any>().apply { parsed.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
    }

    protected fun readJsonArray(file: Path): MutableList<Any>? {
        if (!file.exists()) return null
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val parsed = runCatching { MiniJson.parse(text) }.getOrNull() as? List<*> ?: return null
        return mutableListOf<Any>().apply { parsed.forEach { if (it != null) add(it) } }
    }

    protected fun writeJson(file: Path, value: Any) {
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, MiniJson.stringify(value))
    }

    protected fun stripJsonc(text: String): String {
        val noBlock = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val noLine = noBlock.replace(Regex("//[^\\n]*"), "")
        return noLine.replace(Regex(",(\\s*[}\\]])"), "$1")
    }

    protected fun removeTomlTable(lines: MutableList<String>, prefix: String, prefixMatch: Boolean = false) {
        val fullHeader = "[$prefix]"
        val prefixHeader = "[$prefix"
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            val matches = if (prefixMatch) trimmed.startsWith(prefixHeader) else trimmed == fullHeader
            if (matches) {
                lines.removeAt(i)
                while (i < lines.size && lines[i].trim().isNotEmpty() && !lines[i].trim().startsWith("[")) {
                    lines.removeAt(i)
                }
            } else {
                i++
            }
        }
    }

    // ---- Continue YAML (minimal: models[] list only) ----

    protected data class ParsedYaml(val header: List<String>, val items: List<MutableMap<String, String>>)

    protected fun parseContinueModels(text: String): ParsedYaml {
        val lines = text.lines()
        val header = mutableListOf<String>()
        val items = mutableListOf<MutableMap<String, String>>()
        var inModels = false
        var current: MutableMap<String, String>? = null
        for (line in lines) {
            if (!inModels) {
                header.add(line)
                if (line.trim() == "models:") inModels = true
                continue
            }
            when {
                line.startsWith("- ") -> {
                    current = linkedMapOf()
                    items.add(current)
                    parseYamlKeyValue(line.removePrefix("- ").trim())?.let { current.put(it.first, it.second) }
                }
                line.startsWith("  ") && current != null -> {
                    parseYamlKeyValue(line.trim())?.let { current.put(it.first, it.second) }
                }
            }
        }
        return ParsedYaml(header, items)
    }

    protected fun parseYamlKeyValue(s: String): Pair<String, String>? {
        val idx = s.indexOf(':')
        if (idx < 0) return null
        val k = s.substring(0, idx).trim()
        var v = s.substring(idx + 1).trim()
        if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))) {
            v = v.substring(1, v.length - 1)
        }
        return k to v
    }

    protected fun writeYaml(file: Path, header: List<String>, items: List<Map<String, String>>) {
        file.parent?.let { Files.createDirectories(it) }
        val sb = StringBuilder()
        if (header.isNotEmpty() && header.none { it.trim() == "models:" }) {
            header.forEach { sb.appendLine(it) }
            sb.appendLine("models:")
        } else if (header.none { it.trim() == "models:" }) {
            sb.appendLine("models:")
        } else {
            header.forEach { sb.appendLine(it) }
        }
        for (item in items) {
            var first = true
            for ((k, v) in item) {
                val safe = v.replace("\\", "\\\\").replace("\"", "\\\"")
                if (first) {
                    sb.appendLine("- $k: \"$safe\"")
                    first = false
                } else {
                    sb.appendLine("  $k: \"$safe\"")
                }
            }
        }
        Files.writeString(file, sb.toString())
    }
}

// -------------------------------------------------------------------------------------
// CodeBuddy: ~/.codebuddy/models.json
// -------------------------------------------------------------------------------------

object CodeBuddyInjector : ConfigInjector("codebuddy",
    "provider.note.codebuddy", LlmProviderSupport.CODEBUDDY_API_KEY_ENV
) {
    internal fun modelEntry(provider: LlmProvider): Map<String, Any>? {
        val url = modelUrl(provider) ?: return null
        return linkedMapOf(
            "id" to llmConfigProviderId(provider),
            "name" to provider.name.ifBlank { provider.id },
            "vendor" to llmFamilyToVendor(provider.family),
            "url" to url,
            "apiKey" to "\${${LlmProviderSupport.CODEBUDDY_API_KEY_ENV}}",
            "supportsToolCall" to true,
            "supportsImages" to true,
            "supportsReasoning" to true
        )
    }
    internal fun modelUrl(provider: LlmProvider): String? {
        val b = base(provider); if (b.isBlank()) return null
        return when (provider.family) {
            ProviderFamily.ANTHROPIC -> if (b.endsWith("/messages")) b else "$b/v1/messages"
            ProviderFamily.OPENAI, ProviderFamily.CUSTOM ->
                if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
            ProviderFamily.GEMINI -> b
        }
    }
    private fun path(home: Path) = home.resolve(".codebuddy").resolve("models.json")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val entry = modelEntry(provider) ?: return emptyMap()
        val file = path(home)
        val models = mutableListOf<Any>()
        readJsonObject(file)?.get("models")?.let { it as? List<*> }?.forEach { if (it is Map<*, *>) models.add(it) }
        models.removeIf { (it as? Map<*, *>)?.get("id") == entry["id"] }
        models.add(entry)
        writeJson(file, linkedMapOf("models" to models))
        return keyEnv(provider, LlmProviderSupport.CODEBUDDY_API_KEY_ENV)
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        val root = readJsonObject(file) ?: return
        val models = (root["models"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.filter { (it["id"] as? String)?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true } ?: return
        if (models.size == (root["models"] as? List<*>)?.size) return
        writeJson(file, linkedMapOf("models" to models))
    }
}

// -------------------------------------------------------------------------------------
// OpenCode: ~/.config/opencode/opencode.json
// -------------------------------------------------------------------------------------

object OpenCodeInjector : ConfigInjector("opencode",
    "provider.note.opencode", LlmProviderSupport.OPENCODE_API_KEY_ENV
) {
    internal fun options(provider: LlmProvider): Map<String, Any>? {
        val b = base(provider); if (b.isBlank()) return null
        val baseUrl = when (provider.family) {
            ProviderFamily.ANTHROPIC -> if (b.endsWith("/v1")) b else "$b/v1"
            else -> b
        }
        return linkedMapOf("baseURL" to baseUrl, "apiKey" to "{env:${LlmProviderSupport.OPENCODE_API_KEY_ENV}}")
    }
    internal fun modelValue(provider: LlmProvider): String? =
        model(provider).takeIf { it.isNotBlank() }?.let { "${llmConfigProviderId(provider)}/$it" }
    private fun path(home: Path) = home.resolve(".config").resolve("opencode").resolve("opencode.json")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val opts = options(provider) ?: return emptyMap()
        val id = llmConfigProviderId(provider)
        val file = path(home)
        val root = readJsonObject(file)?.toMutableMap() ?: linkedMapOf()
        val providerMap = (root["provider"] as? Map<*, *>).let {
            linkedMapOf<String, Any>().apply { it?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
        }
        providerMap.keys.filter { it.startsWith(YOLO_PROVIDER_ID_PREFIX) }.forEach { providerMap.remove(it) }
        providerMap[id] = linkedMapOf("options" to opts)
        root["provider"] = providerMap
        modelValue(provider)?.let { root["model"] = it }
        writeJson(file, root)
        return keyEnv(provider, LlmProviderSupport.OPENCODE_API_KEY_ENV)
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        val root = readJsonObject(file) ?: return
        val providerMap = (root["provider"] as? Map<*, *>) ?: return
        val filtered = linkedMapOf<String, Any>().apply {
            providerMap.forEach { (k, v) -> if ((k as? String)?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true) put(k.toString(), v as Any) }
        }
        if (filtered.size == providerMap.size) return
        root["provider"] = filtered
        writeJson(file, root)
    }
}

// -------------------------------------------------------------------------------------
// Codex: ~/.codex/config.toml
// -------------------------------------------------------------------------------------

object CodexInjector : ConfigInjector("codex",
    "provider.note.codex", LlmProviderSupport.CODEX_API_KEY_ENV
) {
    internal fun table(provider: LlmProvider): Map<String, String>? {
        val b = base(provider); if (b.isBlank()) return null
        return linkedMapOf(
            "base_url" to b,
            "env_key" to LlmProviderSupport.CODEX_API_KEY_ENV,
            "name" to provider.name.ifBlank { provider.id }
        )
    }
    private fun path(home: Path) = home.resolve(".codex").resolve("config.toml")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val tbl = table(provider) ?: return emptyMap()
        val id = llmConfigProviderId(provider)
        val file = path(home)
        val lines = if (file.exists()) Files.readAllLines(file).toMutableList() else mutableListOf()
        removeTomlTable(lines, "model_providers.$id")
        for (idx in lines.indices) {
            val t = lines[idx].trim()
            if (t.startsWith("model_provider") && t.contains(YOLO_PROVIDER_ID_PREFIX)) lines[idx] = "model_provider = \"openai\""
        }
        lines.add("")
        lines.add("model_provider = \"$id\"")
        model(provider).takeIf { it.isNotBlank() }?.let { lines.add("model = \"$it\"") }
        lines.add("")
        lines.add("[model_providers.$id]")
        tbl.forEach { (k, v) -> lines.add("$k = \"$v\"") }
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, lines)
        return keyEnv(provider, LlmProviderSupport.CODEX_API_KEY_ENV)
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        if (!file.exists()) return
        val lines = Files.readAllLines(file).toMutableList()
        val before = lines.size
        removeTomlTable(lines, prefix = "model_providers." + YOLO_PROVIDER_ID_PREFIX, prefixMatch = true)
        for (idx in lines.indices) {
            val t = lines[idx].trim()
            if (t.startsWith("model_provider") && t.contains(YOLO_PROVIDER_ID_PREFIX)) lines[idx] = "model_provider = \"openai\""
        }
        if (lines.size != before) Files.write(file, lines)
    }
}

// -------------------------------------------------------------------------------------
// Cline: ~/.cline/data/settings/providers.json  (schema best-effort; sandbox-verify)
// -------------------------------------------------------------------------------------

object ClineInjector : ConfigInjector("cline",
    "provider.note.cline", LlmProviderSupport.CLINE_API_KEY_ENV
) {
    private fun entry(provider: LlmProvider): MutableMap<String, Any>? {
        val b = base(provider); if (b.isBlank()) return null
        // NOTE: Cline's config schema accepts only a plaintext `apiKey` (no `${VAR}`
        // interpolation). Per design decision the key is supplied by the user directly
        // in this config; YOLO writes baseUrl + modelId only.
        return linkedMapOf(
            "id" to llmConfigProviderId(provider),
            "name" to provider.name.ifBlank { provider.id },
            "provider" to "openai-compatible",
            "config" to linkedMapOf(
                "baseUrl" to b,
                "modelId" to model(provider)
            )
        )
    }
    private fun path(home: Path) = home.resolve(".cline").resolve("data").resolve("settings").resolve("providers.json")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val e = entry(provider) ?: return emptyMap()
        val id = llmConfigProviderId(provider)
        val file = path(home)
        val root = readJsonObject(file)?.toMutableMap() ?: linkedMapOf()
        val providers = (root["providers"] as? Map<*, *>).let {
            linkedMapOf<String, Any>().apply { it?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
        }
        providers.keys.filter { it.startsWith(YOLO_PROVIDER_ID_PREFIX) }.forEach { providers.remove(it) }
        // Preserve a key the user set directly in this config (we don't manage it).
        (providers[id] as? Map<*, *>)?.get("apiKey")?.let { e["apiKey"] = it }
        providers[id] = e
        root["providers"] = providers
        writeJson(file, root)
        return emptyMap()
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        val root = readJsonObject(file) ?: return
        val providers = (root["providers"] as? Map<*, *>) ?: return
        val filtered = linkedMapOf<String, Any>().apply {
            providers.forEach { (k, v) -> if ((k as? String)?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true) put(k.toString(), v as Any) }
        }
        if (filtered.size == providers.size) return
        root["providers"] = filtered
        writeJson(file, root)
    }
}

// -------------------------------------------------------------------------------------
// Kilo: ~/.config/kilo/kilo.jsonc  (JSONC; apiKey supports {env:VAR})
// -------------------------------------------------------------------------------------

object KiloInjector : ConfigInjector("kilo",
    "provider.note.kilo", LlmProviderSupport.KILO_API_KEY_ENV
) {
    private fun options(provider: LlmProvider): Map<String, Any>? {
        val b = base(provider); if (b.isBlank()) return null
        val baseUrl = when (provider.family) {
            ProviderFamily.ANTHROPIC -> if (b.endsWith("/v1")) b else "$b/v1"
            else -> b
        }
        return linkedMapOf("baseURL" to baseUrl, "apiKey" to "{env:${LlmProviderSupport.KILO_API_KEY_ENV}}")
    }
    private fun modelEntry(provider: LlmProvider): Pair<String, Map<String, Any>>? {
        val m = model(provider).takeIf { it.isNotBlank() } ?: return null
        return m to linkedMapOf("name" to provider.name.ifBlank { m }, "tool_call" to true)
    }
    private fun path(home: Path) = home.resolve(".config").resolve("kilo").resolve("kilo.jsonc")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val opts = options(provider) ?: return emptyMap()
        val me = modelEntry(provider)
        val file = path(home)
        val root = readJsoncObject(file)?.toMutableMap() ?: linkedMapOf()
        val providerMap = (root["provider"] as? Map<*, *>).let {
            linkedMapOf<String, Any>().apply { it?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
        }
        val oc = (providerMap["openai-compatible"] as? Map<*, *>).let {
            linkedMapOf<String, Any>().apply { it?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
        }
        val models = (oc["models"] as? Map<*, *>).let {
            linkedMapOf<String, Any>().apply { it?.forEach { (k, v) -> if ((k as? String)?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true) put(k.toString(), v as Any) } }
        }
        me?.let { models[it.first] = it.second }
        oc["options"] = opts
        oc["models"] = models
        providerMap["openai-compatible"] = oc
        root["provider"] = providerMap
        writeJson(file, root)
        return keyEnv(provider, LlmProviderSupport.KILO_API_KEY_ENV)
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        val root = readJsoncObject(file) ?: return
        val providerMap = (root["provider"] as? Map<*, *>) ?: return
        val filtered = linkedMapOf<String, Any>().apply {
            providerMap.forEach { (k, v) -> if ((k as? String)?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true) put(k.toString(), v as Any) }
        }
        if (filtered.size == providerMap.size) return
        root["provider"] = filtered
        writeJson(file, root)
    }
}

// -------------------------------------------------------------------------------------
// Kimi: ~/.kimi/config.toml  (TOML; api_key reference is best-effort — sandbox-verify)
// -------------------------------------------------------------------------------------

object KimiInjector : ConfigInjector("kimi",
    "provider.note.kimi", LlmProviderSupport.KIMI_API_KEY_ENV
) {
    private fun table(provider: LlmProvider): Map<String, String>? {
        val b = base(provider); if (b.isBlank()) return null
        // NOTE: Kimi's config schema accepts only a plaintext `api_key` (no `${VAR}`
        // interpolation). Per design decision the key is supplied by the user directly
        // in this config; YOLO writes base_url + model only.
        return linkedMapOf(
            "type" to "openai_legacy",
            "base_url" to b
        )
    }
    private fun modelTable(provider: LlmProvider): Map<String, String>? {
        val m = model(provider).takeIf { it.isNotBlank() } ?: return null
        return linkedMapOf("provider" to llmConfigProviderId(provider), "model" to m)
    }
    private fun path(home: Path) = home.resolve(".kimi").resolve("config.toml")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val tbl = table(provider) ?: return emptyMap()
        val id = llmConfigProviderId(provider)
        val mt = modelTable(provider)
        val file = path(home)
        val lines = if (file.exists()) Files.readAllLines(file).toMutableList() else mutableListOf()
        removeTomlTable(lines, "providers.$id")
        removeTomlTable(lines, "models.$id")
        lines.add("")
        lines.add("[providers.$id]")
        tbl.forEach { (k, v) -> lines.add("$k = \"$v\"") }
        if (mt != null) {
            lines.add("")
            lines.add("[models.$id]")
            mt.forEach { (k, v) -> lines.add("$k = \"$v\"") }
        }
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, lines)
        return emptyMap()
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        if (!file.exists()) return
        val lines = Files.readAllLines(file).toMutableList()
        val before = lines.size
        removeTomlTable(lines, prefix = "providers." + YOLO_PROVIDER_ID_PREFIX, prefixMatch = true)
        removeTomlTable(lines, prefix = "models." + YOLO_PROVIDER_ID_PREFIX, prefixMatch = true)
        if (lines.size != before) Files.write(file, lines)
    }
}

// -------------------------------------------------------------------------------------
// OpenClaw: ~/.openclaw/openclaw.json
// -------------------------------------------------------------------------------------

object OpenClawInjector : ConfigInjector("openclaw",
    "provider.note.openclaw", LlmProviderSupport.OPENCLAW_API_KEY_ENV
) {
    private fun entry(provider: LlmProvider): Map<String, Any>? {
        val b = base(provider); if (b.isBlank()) return null
        val api = when (provider.family) {
            ProviderFamily.ANTHROPIC -> "anthropic"
            ProviderFamily.GEMINI -> "gemini"
            else -> "openai-completions"
        }
        return linkedMapOf(
            "baseUrl" to b,
            "apiKey" to "\${${LlmProviderSupport.OPENCLAW_API_KEY_ENV}}",
            "api" to api
        )
    }
    private fun modelValue(provider: LlmProvider): String? =
        model(provider).takeIf { it.isNotBlank() }?.let { "openai/$it" }
    private fun path(home: Path) = home.resolve(".openclaw").resolve("openclaw.json")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val e = entry(provider) ?: return emptyMap()
        val id = llmConfigProviderId(provider)
        val file = path(home)
        val root = readJsonObject(file)?.toMutableMap() ?: linkedMapOf()
        // OpenClaw nests providers under `models.providers.<id>` (verified against docs;
        // needs sandbox verification with a real OpenClaw install).
        val models = (root["models"] as? Map<*, *>).let {
            linkedMapOf<String, Any>().apply { it?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
        }
        val providers = (models["providers"] as? Map<*, *>).let {
            linkedMapOf<String, Any>().apply { it?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
        }
        providers.keys.filter { it.startsWith(YOLO_PROVIDER_ID_PREFIX) }.forEach { providers.remove(it) }
        providers[id] = e
        models["providers"] = providers
        root["models"] = models
        modelValue(provider)?.let {
            val agents = (root["agents"] as? Map<*, *>).let { m ->
                linkedMapOf<String, Any>().apply { m?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
            }
            val def = (agents["default"] as? Map<*, *>).let { m ->
                linkedMapOf<String, Any>().apply { m?.forEach { (k, v) -> if (v != null) put(k.toString(), v) } }
            }
            def["model"] = it
            agents["default"] = def
            root["agents"] = agents
        }
        writeJson(file, root)
        return keyEnv(provider, LlmProviderSupport.OPENCLAW_API_KEY_ENV)
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        val root = readJsonObject(file) ?: return
        val models = (root["models"] as? Map<*, *>) ?: return
        val providers = (models["providers"] as? Map<*, *>) ?: return
        val filtered = linkedMapOf<String, Any>().apply {
            providers.forEach { (k, v) -> if ((k as? String)?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true) put(k.toString(), v as Any) }
        }
        if (filtered.size == providers.size) return
        root["models"] = linkedMapOf<String, Any>().apply {
            models.forEach { (k, v) -> if (v != null) put(k.toString(), v) }
            put("providers", filtered)
        }
        writeJson(file, root)
    }
}

// -------------------------------------------------------------------------------------
// Pi: ~/.pi/agent/models.json + ~/.pi/agent/auth.json
// -------------------------------------------------------------------------------------

object PiInjector : ConfigInjector("pi",
    "provider.note.pi", LlmProviderSupport.PI_API_KEY_ENV
) {
    private fun modelEntry(provider: LlmProvider): Map<String, Any>? {
        val b = base(provider); if (b.isBlank()) return null
        val api = when (provider.family) {
            ProviderFamily.ANTHROPIC -> "anthropic"
            ProviderFamily.GEMINI -> "google-generative-ai"
            else -> "openai-completions"
        }
        // Pi resolves `${VAR}` (and `$VAR`) in `apiKey`; `auth.json` is NOT the
        // documented key mechanism, so we only write the model entry.
        return linkedMapOf(
            "id" to llmConfigProviderId(provider),
            "name" to provider.name.ifBlank { provider.id },
            "api" to api,
            "baseUrl" to b,
            "apiKey" to "\${${LlmProviderSupport.PI_API_KEY_ENV}}",
            "models" to (model(provider).takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList<String>())
        )
    }
    private fun modelsPath(home: Path) = home.resolve(".pi").resolve("agent").resolve("models.json")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val e = modelEntry(provider) ?: return emptyMap()
        val id = llmConfigProviderId(provider)
        val file = modelsPath(home)
        val list = readJsonArray(file)?.toMutableList() ?: mutableListOf()
        list.removeIf { (it as? Map<*, *>)?.get("id") == id }
        list.add(e)
        writeJson(file, list)
        return keyEnv(provider, LlmProviderSupport.PI_API_KEY_ENV)
    }
    override fun removeConfig(home: Path) {
        val file = modelsPath(home)
        val list = readJsonArray(file) ?: return
        val filtered = list.filterIsInstance<Map<*, *>>()
            .filter { (it["id"] as? String)?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true }
        if (filtered.size == list.size) return
        writeJson(file, filtered)
    }
}

// -------------------------------------------------------------------------------------
// Continue: ~/.continue/config.yaml  (minimal YAML; yoloId custom field for dedup)
// -------------------------------------------------------------------------------------

object ContinueInjector : ConfigInjector("continue",
    "provider.note.continue", LlmProviderSupport.CONTINUE_API_KEY_ENV
) {
    private fun entry(provider: LlmProvider): Map<String, String>? {
        val b = base(provider); if (b.isBlank()) return null
        val providerName = when (provider.family) {
            ProviderFamily.ANTHROPIC -> "anthropic"
            ProviderFamily.GEMINI -> "google"
            else -> "openai"
        }
        return linkedMapOf(
            "name" to provider.name.ifBlank { provider.id },
            "provider" to providerName,
            "model" to (model(provider).takeIf { it.isNotBlank() } ?: "custom-model"),
            "apiBase" to b,
            // Continue reads secrets from ~/.continue/.env via mustache `${{ secrets.VAR }}`;
            // it cannot see the spawned process env from the IDE. The user fills the
            // key in that .env file themselves (per design decision).
            "apiKey" to "\${{ secrets.${LlmProviderSupport.CONTINUE_API_KEY_ENV} }}"
        )
    }
    private fun path(home: Path) = home.resolve(".continue").resolve("config.yaml")
    override fun applyConfig(provider: LlmProvider, home: Path): Map<String, String> {
        val e = entry(provider) ?: return emptyMap()
        val id = llmConfigProviderId(provider)
        val file = path(home)
        val text = if (file.exists()) runCatching { Files.readString(file) }.getOrNull() ?: "" else ""
        val parsed = parseContinueModels(text)
        val kept = parsed.items.filter { it["yoloId"] != id }.toMutableList()
        val out = linkedMapOf<String, String>()
        e.forEach { (k, v) -> out[k] = v }
        out["yoloId"] = id
        kept.add(out)
        writeYaml(file, parsed.header, kept)
        return emptyMap()
    }
    override fun removeConfig(home: Path) {
        val file = path(home)
        if (!file.exists()) return
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return
        val parsed = parseContinueModels(text)
        val kept = parsed.items.filter { it["yoloId"]?.startsWith(YOLO_PROVIDER_ID_PREFIX) != true }
        if (kept.size == parsed.items.size) return
        writeYaml(file, parsed.header, kept)
    }
}
