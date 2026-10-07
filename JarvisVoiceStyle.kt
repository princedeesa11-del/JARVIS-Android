package com.example.voice

/**
 * Supported Voice Profiles for JARVIS.
 * Provides distinct pitch, speech rate, and personality styling
 * adhering strictly to the user requirement for Jarvis, Maya-style, and Venom-style voices.
 */
enum class JarvisVoiceStyle(
    val id: String,
    val title: String,
    val subtitle: String,
    val pitch: Float,
    val speechRate: Float,
    val testPhrase: String
) {
    INDIAN_MALE(
        id = "indian_male",
        title = "Jarvis Indian Male",
        subtitle = "Natural, calm, confident Indian male voice (en-IN, hi-IN, gu-IN)",
        pitch = 0.92f,
        speechRate = 1.0f,
        testPhrase = "Hello, I am Jarvis. Ready for your directive, sir. All neural systems are online."
    ),
    JARVIS(
        id = "jarvis",
        title = "Jarvis (Core)",
        subtitle = "Calm, articulate, polite, and ultra-professional",
        pitch = 0.95f,
        speechRate = 1.05f,
        testPhrase = "Jarvis online and fully operational. All neural systems are standing by for your command."
    ),
    MAYA_STYLE(
        id = "maya",
        title = "Jarvis Companion",
        subtitle = "Warm, empathetic, friendly, and lively companion",
        pitch = 1.25f,
        speechRate = 1.0f,
        testPhrase = "Hello! I am right here with you. What would you like to explore or create today?"
    ),
    VENOM_STYLE(
        id = "venom",
        title = "Venom Style",
        subtitle = "Deep, assertive, commanding, and powerful",
        pitch = 0.60f,
        speechRate = 0.90f,
        testPhrase = "We are ready. Speak your directive, and consider it executed without hesitation."
    );

    companion object {
        fun fromId(id: String?): JarvisVoiceStyle {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: JARVIS
        }
    }
}
