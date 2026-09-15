package com.cnsharp.yolo.settings

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AgentConfigInjectorTest {

    private val yoloId = YOLO_PROVIDER_ID_PREFIX + "p1"

    private lateinit var tempHome: Path

    @Before
    fun setUp() {
        tempHome = Files.createTempDirectory("yolo-config-test")
        AgentConfigInjector.testHomeOverride = tempHome
    }

    @After
    fun tearDown() {
        AgentConfigInjector.testHomeOverride = null
    }

    private fun provider(family: ProviderFamily, baseUrl: String, model: String = "my-model") = LlmProvider(
        id = "p1", name = "My Provider", family = family, baseUrl = baseUrl, defaultModel = model
    )

    // ---- pure builders ----------------------------------------------------

    @Test
    fun `codeBuddy url heuristic appends chat path for openai`() {
        val p = provider(ProviderFamily.OPENAI, "https://api.openai.com/v1")
        assertEquals("https://api.openai.com/v1/chat/completions", CodeBuddyInjector.modelUrl(p))
    }

    @Test
    fun `codeBuddy url heuristic appends v1-messages for anthropic`() {
        val p = provider(ProviderFamily.ANTHROPIC, "https://api.anthropic.com")
        assertEquals("https://api.anthropic.com/v1/messages", CodeBuddyInjector.modelUrl(p))
    }

    @Test
    fun `codeBuddy entry references env key and prefixed id`() {
        val p = provider(ProviderFamily.OPENAI, "https://api.openai.com/v1")
        val entry = CodeBuddyInjector.modelEntry(p)!!
        assertEquals(yoloId, entry["id"])
        assertEquals("\${${LlmProviderSupport.CODEBUDDY_API_KEY_ENV}}", entry["apiKey"])
        assertEquals("My Provider", entry["name"])
        assertEquals("OpenAI", entry["vendor"])
        assertTrue(entry["supportsToolCall"] as Boolean)
    }

    @Test
    fun `codeBuddy entry null when baseUrl blank`() {
        assertNull(CodeBuddyInjector.modelEntry(provider(ProviderFamily.OPENAI, "")))
    }

    @Test
    fun `openCode options build baseURL and env apiKey`() {
        val p = provider(ProviderFamily.ANTHROPIC, "https://api.anthropic.com/v1")
        val opts = OpenCodeInjector.options(p)!!
        assertEquals("https://api.anthropic.com/v1", opts["baseURL"])
        assertEquals("{env:${LlmProviderSupport.OPENCODE_API_KEY_ENV}}", opts["apiKey"])
        assertEquals("$yoloId/my-model", OpenCodeInjector.modelValue(p))
    }

    @Test
    fun `codex provider table builds base_url env_key name`() {
        val p = provider(ProviderFamily.OPENAI, "https://my.proxy/v1")
        val tbl = CodexInjector.table(p)!!
        assertEquals("https://my.proxy/v1", tbl["base_url"])
        assertEquals(LlmProviderSupport.CODEX_API_KEY_ENV, tbl["env_key"])
        assertEquals("My Provider", tbl["name"])
    }

    // ---- MiniJson round-trip ----------------------------------------------

    @Test
    fun `miniJson round-trips nested object array string number bool null`() {
        val json = """{"models":[{"id":"a","n":3.5,"b":true,"z":null,"arr":[1,"x"]}]}"""
        val parsed = MiniJson.parse(json) as Map<*, *>
        val models = parsed["models"] as List<*>
        assertEquals("a", (models[0] as Map<*, *>)["id"])
        val out = MiniJson.stringify(parsed)
        val reparsed = MiniJson.parse(out) as Map<*, *>
        assertEquals(parsed, reparsed)
    }

    @Test
    fun `miniJson escapes strings`() {
        val out = MiniJson.stringify(linkedMapOf("k" to "a\"b\\c\n"))
        assertTrue(out.contains("\\\""))
        assertTrue(out.contains("\\\\"))
        val reparsed = MiniJson.parse(out) as Map<*, *>
        assertEquals("a\"b\\c\n", reparsed["k"])
    }

    // ---- file writing (via temp home) ------------------------------------

    @Test
    fun `applyConfig writes codebuddy models json referencing key env`() {
        val p = provider(ProviderFamily.OPENAI, "https://api.openai.com/v1")
        AgentConfigInjector.applyConfig(p, "codebuddy")

        val file = tempHome.resolve(".codebuddy").resolve("models.json")
        assertTrue(Files.exists(file))
        val parsed = MiniJson.parse(Files.readString(file)) as Map<*, *>
        val models = parsed["models"] as List<*>
        assertEquals(1, models.size)
        val entry = models[0] as Map<*, *>
        assertEquals(yoloId, entry["id"])
        // api key is referenced via env, never written in plaintext.
        assertEquals("\${${LlmProviderSupport.CODEBUDDY_API_KEY_ENV}}", entry["apiKey"])
    }

    @Test
    fun `applyConfig merges without dropping existing codebuddy models`() {
        val file = tempHome.resolve(".codebuddy").resolve("models.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, MiniJson.stringify(linkedMapOf("models" to listOf(
            linkedMapOf("id" to "user-model", "name" to "User", "vendor" to "OpenAI",
                "url" to "https://x", "apiKey" to "k")
        ))))
        val p = provider(ProviderFamily.OPENAI, "https://api.openai.com/v1")
        AgentConfigInjector.applyConfig(p, "codebuddy")

        val parsed = MiniJson.parse(Files.readString(file)) as Map<*, *>
        val models = parsed["models"] as List<*>
        assertEquals(2, models.size)
        assertTrue(models.any { (it as Map<*, *>)["id"] == yoloId })
        assertTrue(models.any { (it as Map<*, *>)["id"] == "user-model" })
    }

    @Test
    fun `removeAll drops yolo entries from codebuddy`() {
        val file = tempHome.resolve(".codebuddy").resolve("models.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, MiniJson.stringify(linkedMapOf("models" to listOf(
            linkedMapOf("id" to yoloId), linkedMapOf("id" to "keep-me")
        ))))
        AgentConfigInjector.removeAll("codebuddy")
        val parsed = MiniJson.parse(Files.readString(file)) as Map<*, *>
        val models = parsed["models"] as List<*>
        assertEquals(1, models.size)
        assertEquals("keep-me", (models[0] as Map<*, *>)["id"])
    }

    @Test
    fun `applyConfig writes codex config toml with provider table`() {
        val p = provider(ProviderFamily.OPENAI, "https://my.proxy/v1")
        AgentConfigInjector.applyConfig(p, "codex")

        val text = Files.readString(tempHome.resolve(".codex").resolve("config.toml"))
        assertTrue(text.contains("[model_providers.$yoloId]"))
        assertTrue(text.contains("base_url = \"https://my.proxy/v1\""))
        assertTrue(text.contains("env_key = \"${LlmProviderSupport.CODEX_API_KEY_ENV}\""))
        assertTrue(text.contains("model_provider = \"$yoloId\""))
        assertTrue(text.contains("model = \"my-model\""))
    }

    @Test
    fun `applyConfig writes opencode json provider options`() {
        val p = provider(ProviderFamily.ANTHROPIC, "https://api.anthropic.com/v1")
        AgentConfigInjector.applyConfig(p, "opencode")

        val parsed = MiniJson.parse(
            Files.readString(tempHome.resolve(".config").resolve("opencode").resolve("opencode.json"))
        ) as Map<*, *>
        val providerMap = parsed["provider"] as Map<*, *>
        val opts = (providerMap[yoloId] as Map<*, *>)["options"] as Map<*, *>
        assertEquals("https://api.anthropic.com/v1", opts["baseURL"])
        assertEquals("{env:${LlmProviderSupport.OPENCODE_API_KEY_ENV}}", opts["apiKey"])
        assertEquals("$yoloId/my-model", parsed["model"])
    }

    @Test
    fun `non-config agents return empty from applyConfig`() {
        assertTrue(AgentConfigInjector.applyConfig(provider(ProviderFamily.OPENAI, "https://x"), "claude").isEmpty())
    }

    // ---- new config agents -------------------------------------------------

    @Test
    fun `applyConfig writes cline providers json`() {
        val p = provider(ProviderFamily.OPENAI, "https://cline.proxy/v1")
        AgentConfigInjector.applyConfig(p, "cline")
        val parsed = MiniJson.parse(
            Files.readString(tempHome.resolve(".cline").resolve("data").resolve("settings").resolve("providers.json"))
        ) as Map<*, *>
        val providers = parsed["providers"] as Map<*, *>
        val entry = providers[yoloId] as Map<*, *>
        assertEquals("openai-compatible", entry["provider"])
        assertEquals("https://cline.proxy/v1", (entry["config"] as Map<*, *>)["baseUrl"])
        assertEquals("my-model", (entry["config"] as Map<*, *>)["modelId"])
        // Cline key is not written by YOLO (config-only plaintext); the user fills it.
        assertFalse((entry["config"] as Map<*, *>).containsKey("apiKey"))
    }

    @Test
    fun `applyConfig writes kilo jsonc with env apiKey and tolerates comments`() {
        // seed an existing jsonc file WITH comments + trailing comma to exercise the tolerant reader
        val file = tempHome.resolve(".config").resolve("kilo").resolve("kilo.jsonc")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{
  // user comment
  "provider": { "openai-compatible": { "options": { "baseURL": "https://old", "apiKey": "k" }, } }
}
""")
        val p = provider(ProviderFamily.OPENAI, "https://kilo.proxy/v1")
        AgentConfigInjector.applyConfig(p, "kilo")
        val parsed = MiniJson.parse(Files.readString(file)) as Map<*, *>
        val opts = ((parsed["provider"] as Map<*, *>)["openai-compatible"] as Map<*, *>)["options"] as Map<*, *>
        assertEquals("https://kilo.proxy/v1", opts["baseURL"])
        assertEquals("{env:${LlmProviderSupport.KILO_API_KEY_ENV}}", opts["apiKey"])
    }

    @Test
    fun `applyConfig writes kimi config toml`() {
        val p = provider(ProviderFamily.OPENAI, "https://kimi.proxy/v1")
        AgentConfigInjector.applyConfig(p, "kimi")
        val text = Files.readString(tempHome.resolve(".kimi").resolve("config.toml"))
        assertTrue(text.contains("[providers.$yoloId]"))
        assertTrue(text.contains("base_url = \"https://kimi.proxy/v1\""))
        assertTrue(text.contains("type = \"openai_legacy\""))
        // Kimi key is not written by YOLO (config-only plaintext); the user fills it.
        assertFalse(text.contains("api_key"))
        assertTrue(text.contains("[models.$yoloId]"))
    }

    @Test
    fun `applyConfig writes openclaw json with default model`() {
        val p = provider(ProviderFamily.OPENAI, "https://oc.proxy/v1")
        AgentConfigInjector.applyConfig(p, "openclaw")
        val parsed = MiniJson.parse(
            Files.readString(tempHome.resolve(".openclaw").resolve("openclaw.json"))
        ) as Map<*, *>
        val models = parsed["models"] as Map<*, *>
        val providers = models["providers"] as Map<*, *>
        val entry = providers[yoloId] as Map<*, *>
        assertEquals("https://oc.proxy/v1", entry["baseUrl"])
        assertEquals("openai-completions", entry["api"])
        assertEquals("\${${LlmProviderSupport.OPENCLAW_API_KEY_ENV}}", entry["apiKey"])
        val agents = parsed["agents"] as Map<*, *>
        assertEquals("openai/my-model", (agents["default"] as Map<*, *>)["model"])
    }

    @Test
    fun `applyConfig writes pi models json`() {
        val p = provider(ProviderFamily.OPENAI, "https://pi.proxy/v1")
        AgentConfigInjector.applyConfig(p, "pi")
        val models = MiniJson.parse(Files.readString(tempHome.resolve(".pi").resolve("agent").resolve("models.json"))) as List<*>
        assertEquals(1, models.size)
        val m = models[0] as Map<*, *>
        assertEquals(yoloId, m["id"])
        assertEquals("https://pi.proxy/v1", m["baseUrl"])
        assertEquals("openai-completions", m["api"])
        assertEquals("\${${LlmProviderSupport.PI_API_KEY_ENV}}", m["apiKey"])
    }

    @Test
    fun `applyConfig writes continue config yaml with yoloId`() {
        val p = provider(ProviderFamily.OPENAI, "https://cont.proxy/v1")
        AgentConfigInjector.applyConfig(p, "continue")
        val text = Files.readString(tempHome.resolve(".continue").resolve("config.yaml"))
        assertTrue(text.contains("models:"))
        assertTrue(text.contains("yoloId: \"$yoloId\""))
        assertTrue(text.contains("apiBase: \"https://cont.proxy/v1\""))
        assertTrue(text.contains("apiKey: \"\${{ secrets.${LlmProviderSupport.CONTINUE_API_KEY_ENV} }}\""))
    }

    @Test
    fun `removeAll drops yolo entries from kimi openclaw pi and continue`() {
        // kimi
        val kimiFile = tempHome.resolve(".kimi").resolve("config.toml")
        Files.createDirectories(kimiFile.parent)
        Files.writeString(kimiFile, "[providers.$yoloId]\nbase_url = \"x\"\n[models.$yoloId]\nmodel = \"m\"\n")
        AgentConfigInjector.removeAll("kimi")
        assertFalse(Files.readString(kimiFile).contains(yoloId))

        // openclaw
        val ocFile = tempHome.resolve(".openclaw").resolve("openclaw.json")
        Files.createDirectories(ocFile.parent)
        Files.writeString(ocFile, MiniJson.stringify(linkedMapOf("models" to linkedMapOf("providers" to linkedMapOf(yoloId to linkedMapOf("baseUrl" to "x"), "keep" to linkedMapOf("baseUrl" to "y"))))))
        AgentConfigInjector.removeAll("openclaw")
        val oc = MiniJson.parse(Files.readString(ocFile)) as Map<*, *>
        assertTrue((oc["models"] as Map<*, *>)["providers"].let { it as Map<*, *> }.containsKey("keep"))
        assertFalse((oc["models"] as Map<*, *>)["providers"].let { it as Map<*, *> }.containsKey(yoloId))

        // pi
        val piFile = tempHome.resolve(".pi").resolve("agent").resolve("models.json")
        Files.createDirectories(piFile.parent)
        Files.writeString(piFile, MiniJson.stringify(listOf(linkedMapOf("id" to yoloId), linkedMapOf("id" to "keep"))))
        AgentConfigInjector.removeAll("pi")
        val pis = MiniJson.parse(Files.readString(piFile)) as List<*>
        assertEquals(1, pis.size)
        assertEquals("keep", (pis[0] as Map<*, *>)["id"])

        // continue
        val contFile = tempHome.resolve(".continue").resolve("config.yaml")
        Files.createDirectories(contFile.parent)
        Files.writeString(contFile, "models:\n- yoloId: \"$yoloId\"\n  name: a\n- yoloId: \"keep\"\n  name: b\n")
        AgentConfigInjector.removeAll("continue")
        assertTrue(Files.readString(contFile).contains("keep"))
        assertFalse(Files.readString(contFile).contains(yoloId))
    }
}
