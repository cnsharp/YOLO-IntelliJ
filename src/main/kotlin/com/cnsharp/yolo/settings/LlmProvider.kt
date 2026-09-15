package com.cnsharp.yolo.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Which LLM backend family a [LlmProvider] speaks. This drives which environment variables get injected
 * into the spawned agent process (see [com.cnsharp.yolo.links] / the launch-site adapter).
 *
 * - [ANTHROPIC]: reads ANTHROPIC_BASE_URL / ANTHROPIC_API_KEY / ANTHROPIC_MODEL (Claude Code, OpenCode, …).
 * - [OPENAI]: reads OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_MODEL (Codex, Aider, …).
 * - [GEMINI]: reads GEMINI_API_KEY / GEMINI_BASE_URL (Gemini CLI, …).
 * - [CUSTOM]: provider carries its own explicit env-var → value pairs in [LlmProvider.envOverrides].
 */
enum class ProviderFamily {
    ANTHROPIC, OPENAI, GEMINI, CUSTOM
}

/**
 * A user-defined LLM provider (CC Switch-style), persisted in [AgentExtenderSettings.State].
 *
 * The real API key is intentionally NOT stored here: only [apiKeyRef] — a key into the IDEA
 * [PasswordSafe] keystore — is serialized. The actual secret lives in the
 * OS-backed credential store, so it never lands in plain text in `agentExtender.xml`.
 *
 * [envOverrides] lets a [ProviderFamily.CUSTOM] provider inject arbitrary `NAME=VALUE` pairs (e.g. an
 * OpenAI-compatible relay that needs a non-standard header as an env var). For the built-in families this
 * is normally empty; the family adapter supplies the standard variables.
 */
data class LlmProvider(
    var id: String = "",
    var name: String = "",
    var family: ProviderFamily = ProviderFamily.ANTHROPIC,
    var baseUrl: String = "",
    /** PasswordSafe key for the real api key; blank means "no key stored". */
    var apiKeyRef: String = "",
    var defaultModel: String = "",
    var envOverrides: MutableMap<String, String> = mutableMapOf()
) {
    companion object {
        private const val SERVICE_NAME = "YOLO LLM Provider"
        /** Prefix for the PasswordSafe key under which each provider's real api key is stored. */
        private const val API_KEY_REF_PREFIX = "yolo-llm-"
        private val RANDOM = java.security.SecureRandom()

        /** A fresh, unique key under which the real api key is stored in PasswordSafe. */
        fun newApiKeyRef(): String {
            val suffix = (0 until 8).map { RANDOM.nextInt(36).toString(36) }.joinToString("")
            return "$API_KEY_REF_PREFIX${System.currentTimeMillis().toString(36)}-$suffix"
        }

        private fun attributes(ref: String) = CredentialAttributes(SERVICE_NAME, ref)

        /** Resolve the real api key for [ref], or null if none / unavailable. */
        fun getApiKey(ref: String): String? =
            if (ref.isBlank()) null else runCatching { PasswordSafe.instance.getPassword(attributes(ref)) }.getOrNull()

        /** Store the real api key under [ref]. */
        fun setApiKey(ref: String, secret: String) =
            runCatching { PasswordSafe.instance.setPassword(attributes(ref), secret) }

        /** Forget the real api key stored under [ref]. */
        fun clearApiKey(ref: String) =
            runCatching { PasswordSafe.instance.setPassword(attributes(ref), null) }
    }
}
