package com.kalotrapezis.drive

import android.content.Context

/**
 * A service AI Assist can use for a part of its work (the user, 12 Nov: "add more APIs for Tetra as drop-in replacements like
 * OpenAI and 11Labs"): its OpenAI-style API at [api], the model for each part it can do (null: it cannot), and what its chat
 * needs beside the body Mistral takes ([chatExtra]; [temperature] false for reasoning models, which refuse one).
 */
internal data class AiProvider(val id: String, val name: String, val api: String, val site: String,
    val chat: String? = null, val listen: String? = null, val voice: String? = null,
    val chatExtra: Map<String, Any> = emptyMap(), val temperature: Boolean = true) {
    val isMistral get() = id == AiProviders.MISTRAL.id
}

/** The part of AI Assist's work a service is chosen for (Settings › AI Assist › Services). */
internal enum class AiPart { CHAT, LISTEN, VOICE }

/**
 * Settings › AI Assist › Services: which service thinks (chat and tools), listens (speech to text) and speaks (the voice);
 * Mistral for each unless chosen, on this phone only, as the keys are. Live talk, web search and pictures are Mistral's own
 * and stay with Mistral's key.
 */
internal object AiProviders {
    val MISTRAL = AiProvider("mistral", "Mistral", Mistral.API, "console.mistral.ai", Mistral.CHAT, Mistral.TRANSCRIBE, Mistral.TTS)
    // shortcut: model ids as OpenAI's and Groq's pages had them on 12 Nov 2026 (gpt-4o-mini-tts shuts down 6 Jan 2027, with no
    // successor on /audio/speech yet); update them when the services retire them
    val OPENAI = AiProvider("openai", "OpenAI", "https://api.openai.com/v1", "platform.openai.com › API keys",
        "gpt-6-luna", "gpt-transcribe", "gpt-4o-mini-tts", mapOf("reasoning_effort" to "none"), temperature = false) // its tools on Chat Completions need effort "none"
    val GROQ = AiProvider("groq", "Groq", "https://api.groq.com/openai/v1", "console.groq.com › API Keys",
        "openai/gpt-oss-120b", "whisper-large-v3-turbo", chatExtra = mapOf("reasoning_effort" to "low"))
    val ELEVENLABS = AiProvider("elevenlabs", "ElevenLabs", "https://api.elevenlabs.io/v1", "elevenlabs.io › Developers › API Keys",
        listen = "scribe_v2", voice = "eleven_flash_v2_5") // no chat of its own: its LLMs live only inside its voice Agents
    val ALL = listOf(MISTRAL, OPENAI, GROQ, ELEVENLABS)

    fun model(p: AiProvider, part: AiPart) = when (part) { AiPart.CHAT -> p.chat; AiPart.LISTEN -> p.listen; AiPart.VOICE -> p.voice }
    fun choices(part: AiPart) = ALL.filter { model(it, part) != null }

    private fun prefs(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    fun of(c: Context, part: AiPart): AiProvider = prefs(c).getString("provider_${part.name.lowercase()}", null)
        ?.let { id -> choices(part).firstOrNull { it.id == id } } ?: MISTRAL
    fun set(c: Context, part: AiPart, p: AiProvider) = prefs(c).edit().putString("provider_${part.name.lowercase()}", p.id).apply()
}
