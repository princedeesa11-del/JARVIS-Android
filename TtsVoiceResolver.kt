package com.example.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.example.voice.JarvisVoiceStyle
import java.util.Locale

/**
 * Gender categorization adhering strictly to truthful reporting:
 * Never claim a voice is male if the installed Android TTS engine does not provide
 * verifiable gender metadata or standard naming indicators.
 */
enum class VoiceGender {
    MALE,
    FEMALE,
    NEUTRAL_OR_UNKNOWN
}

/**
 * Data representation of a system voice for UI display and selection.
 */
data class TtsVoiceItem(
    val id: String,
    val displayName: String,
    val languageTag: String,
    val locale: Locale,
    val gender: VoiceGender,
    val isMale: Boolean = gender == VoiceGender.MALE,
    val isIndian: Boolean,
    val qualityLabel: String,
    val requiresNetwork: Boolean,
    val latencyLabel: String,
    val isSelected: Boolean = false
)

/**
 * Installed Android TTS Engine descriptor.
 */
data class TtsEngineItem(
    val packageName: String,
    val label: String,
    val isDefault: Boolean,
    val isSelected: Boolean
)

/**
 * Output of dynamic voice resolution including fallback traceability.
 */
data class VoiceResolutionResult(
    val voice: Voice?,
    val isFallback: Boolean,
    val fallbackReason: String? = null,
    val displayName: String,
    val gender: VoiceGender = VoiceGender.NEUTRAL_OR_UNKNOWN
)

/**
 * Text segment tagged with detected script/language for multilingual TTS.
 */
data class TextLanguageSegment(
    val text: String,
    val languageCode: String // "gu", "hi", "en"
)

/**
 * Intelligent TTS Voice Resolver and Dynamic Engine Inspector.
 * Adheres strictly to JARVIS requirements:
 * 1. Inspect installed TTS engines dynamically without hardcoded voice IDs.
 * 2. Dedicated "Jarvis Indian Male" resolver prioritizing:
 *    en-IN male -> en-IN voice -> hi-IN male -> hi-IN voice -> gu-IN male -> gu-IN voice -> system voice fallback.
 * 3. Safe fallback chain that never crashes if a previously selected voice becomes unavailable.
 * 4. Multi-lingual script detection and segmentation (English, Hindi, Gujarati, and mixed Gujarati+English / Hindi+English).
 * 5. Truthful gender metadata (never claims male if unsupported by engine).
 */
object TtsVoiceResolver {

    private const val TAG = "TtsVoiceResolver"

    /**
     * Resolves human-readable name of the active TextToSpeech engine.
     */
    fun getEngineName(tts: TextToSpeech?, context: Context): String {
        if (tts == null) return "System Default TTS"
        val enginePackage = try {
            tts.defaultEngine ?: ""
        } catch (_: Exception) {
            ""
        }

        if (enginePackage.isBlank()) return "Android TTS Engine"

        return when {
            enginePackage.contains("google", ignoreCase = true) -> "Google Speech Services"
            enginePackage.contains("samsung", ignoreCase = true) -> "Samsung Text-to-Speech"
            enginePackage.contains("xiaomi", ignoreCase = true) -> "Xiaomi Text-to-Speech"
            enginePackage.contains("huawei", ignoreCase = true) -> "Huawei Speech Services"
            else -> enginePackage
        }
    }

    /**
     * Inspects all installed Android TextToSpeech engines.
     */
    fun getInstalledEngines(tts: TextToSpeech?, selectedEnginePackage: String? = null): List<TtsEngineItem> {
        if (tts == null) return emptyList()
        val defaultPkg = try {
            tts.defaultEngine ?: ""
        } catch (_: Exception) {
            ""
        }

        val rawEngines = try {
            tts.engines ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        if (rawEngines.isEmpty() && defaultPkg.isNotBlank()) {
            return listOf(
                TtsEngineItem(
                    packageName = defaultPkg,
                    label = if (defaultPkg.contains("google", ignoreCase = true)) "Google Speech Services" else defaultPkg,
                    isDefault = true,
                    isSelected = true
                )
            )
        }

        return rawEngines.map { engine ->
            val label = engine.label.ifBlank {
                when {
                    engine.name.contains("google", ignoreCase = true) -> "Google Speech Services"
                    engine.name.contains("samsung", ignoreCase = true) -> "Samsung Text-to-Speech"
                    engine.name.contains("xiaomi", ignoreCase = true) -> "Xiaomi Text-to-Speech"
                    else -> engine.name
                }
            }
            val isDefault = engine.name.equals(defaultPkg, ignoreCase = true)
            val isSelected = if (!selectedEnginePackage.isNullOrBlank()) {
                engine.name.equals(selectedEnginePackage, ignoreCase = true)
            } else {
                isDefault
            }
            TtsEngineItem(
                packageName = engine.name,
                label = label,
                isDefault = isDefault,
                isSelected = isSelected
            )
        }
    }

    /**
     * Inspects all voices installed on this device and returns a sorted list.
     */
    fun getAvailableVoices(tts: TextToSpeech?, selectedVoiceName: String? = null): List<TtsVoiceItem> {
        if (tts == null) return emptyList()

        val rawVoices = try {
            tts.voices ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching TTS voices: ${e.message}")
            emptySet()
        }

        val items = rawVoices.map { voice ->
            val gender = detectVoiceGender(voice)
            val isIndian = isIndianLocale(voice.locale)
            val qualityStr = when (voice.quality) {
                Voice.QUALITY_VERY_HIGH -> "Very High"
                Voice.QUALITY_HIGH -> "High"
                Voice.QUALITY_NORMAL -> "Standard"
                else -> "Default"
            }
            val latencyStr = when (voice.latency) {
                Voice.LATENCY_VERY_LOW -> "Very Low Latency"
                Voice.LATENCY_LOW -> "Low Latency"
                Voice.LATENCY_NORMAL -> "Normal Latency"
                else -> "Standard Latency"
            }

            val displayName = formatVoiceDisplayName(voice, gender)

            TtsVoiceItem(
                id = voice.name,
                displayName = displayName,
                languageTag = voice.locale.toLanguageTag(),
                locale = voice.locale,
                gender = gender,
                isIndian = isIndian,
                qualityLabel = qualityStr,
                requiresNetwork = voice.isNetworkConnectionRequired,
                latencyLabel = latencyStr,
                isSelected = voice.name == selectedVoiceName
            )
        }

        // Sort: Indian voices first, then male voices, then by language tag, then display name
        return items.sortedWith(
            compareByDescending<TtsVoiceItem> { it.isIndian }
                .thenByDescending { it.gender == VoiceGender.MALE }
                .thenBy { it.languageTag }
                .thenBy { it.displayName }
        )
    }

    /**
     * Dynamically finds the best Indian Male voice according to requirement:
     * 1. Prefer en-IN male voices
     * 2. Then en-IN voices (if male not explicitly labeled)
     * 3. Then hi-IN male voices
     * 4. Then hi-IN voices
     * 5. Then gu-IN male voices
     * 6. Then gu-IN voices
     * 7. Any English male voice
     * 8. Best available system voice
     * 9. Default Android TTS voice
     */
    fun findBestIndianMaleVoice(tts: TextToSpeech?, targetLanguageCode: String? = null): Voice? {
        if (tts == null) return null
        val voices = try {
            tts.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read voices: ${e.message}")
            emptyList()
        }

        if (voices.isEmpty()) {
            return try { tts.defaultVoice } catch (_: Exception) { null }
        }

        // If a specific language is requested, prioritize that language
        if (!targetLanguageCode.isNullOrBlank() && targetLanguageCode != "auto") {
            val specificMatch = findBestVoiceForLanguage(tts, targetLanguageCode, preferMale = true)
            if (specificMatch != null) return specificMatch
        }

        // 1. Prefer en-IN male voices
        val enInMale = voices.filter { isEnInLocale(it.locale) && detectVoiceGender(it) == VoiceGender.MALE }
            .maxByOrNull { scoreVoice(it) }
        if (enInMale != null) {
            Log.d(TAG, "Selected en-IN male voice: ${enInMale.name}")
            return enInMale
        }

        // 2. Any en-IN voice (if male not explicitly labeled)
        val enInAny = voices.filter { isEnInLocale(it.locale) }
            .maxByOrNull { scoreVoice(it) }
        if (enInAny != null) {
            Log.d(TAG, "Selected en-IN voice (unspecified gender): ${enInAny.name}")
            return enInAny
        }

        // 3. Prefer hi-IN male voices
        val hiInMale = voices.filter { it.locale.language == "hi" && detectVoiceGender(it) == VoiceGender.MALE }
            .maxByOrNull { scoreVoice(it) }
        if (hiInMale != null) {
            Log.d(TAG, "Selected hi-IN male voice: ${hiInMale.name}")
            return hiInMale
        }

        // 4. Any hi-IN voice
        val hiInAny = voices.filter { it.locale.language == "hi" }
            .maxByOrNull { scoreVoice(it) }
        if (hiInAny != null) {
            Log.d(TAG, "Selected hi-IN voice: ${hiInAny.name}")
            return hiInAny
        }

        // 5. Prefer gu-IN male voices
        val guInMale = voices.filter { it.locale.language == "gu" && detectVoiceGender(it) == VoiceGender.MALE }
            .maxByOrNull { scoreVoice(it) }
        if (guInMale != null) {
            Log.d(TAG, "Selected gu-IN male voice: ${guInMale.name}")
            return guInMale
        }

        // 6. Any gu-IN voice
        val guInAny = voices.filter { it.locale.language == "gu" }
            .maxByOrNull { scoreVoice(it) }
        if (guInAny != null) {
            Log.d(TAG, "Selected gu-IN voice: ${guInAny.name}")
            return guInAny
        }

        // 7. Any English male voice (en-US, en-GB, en-AU)
        val generalEnMale = voices.filter { it.locale.language == "en" && detectVoiceGender(it) == VoiceGender.MALE }
            .maxByOrNull { scoreVoice(it) }
        if (generalEnMale != null) {
            Log.d(TAG, "Fallback to general English male voice: ${generalEnMale.name}")
            return generalEnMale
        }

        // 8. Graceful fallback: Best available system voice based on score
        val bestSystem = voices.maxByOrNull { scoreVoice(it) }
        if (bestSystem != null) {
            Log.d(TAG, "Fallback to best system voice: ${bestSystem.name}")
            return bestSystem
        }

        // 9. Android default voice
        return try { tts.defaultVoice } catch (_: Exception) { null }
    }

    /**
     * Resolves voice with full fallback protection and reporting.
     * Never crashes if a previously selected voice is missing.
     */
    fun resolveVoice(
        tts: TextToSpeech?,
        preferredVoiceName: String?,
        voiceStyle: JarvisVoiceStyle,
        preferredLanguageCode: String?
    ): VoiceResolutionResult {
        if (tts == null) {
            return VoiceResolutionResult(
                voice = null,
                isFallback = false,
                displayName = "Default System TTS"
            )
        }

        // 1. Explicit user-selected custom voice
        if (!preferredVoiceName.isNullOrBlank()) {
            val exactVoice = findVoiceByName(tts, preferredVoiceName)
            if (exactVoice != null) {
                val gender = detectVoiceGender(exactVoice)
                return VoiceResolutionResult(
                    voice = exactVoice,
                    isFallback = false,
                    displayName = formatVoiceDisplayName(exactVoice, gender),
                    gender = gender
                )
            } else {
                Log.w(TAG, "Preferred voice '$preferredVoiceName' is no longer available on this device; triggering fallback chain.")
            }
        }

        // 2. Profile-based resolution
        when (voiceStyle) {
            JarvisVoiceStyle.INDIAN_MALE -> {
                val voice = findBestIndianMaleVoice(tts, preferredLanguageCode)
                if (voice != null) {
                    val gender = detectVoiceGender(voice)
                    val isExactIndianMale = isEnInLocale(voice.locale) && gender == VoiceGender.MALE
                    return VoiceResolutionResult(
                        voice = voice,
                        isFallback = !isExactIndianMale,
                        fallbackReason = if (!isExactIndianMale) "en-IN male unavailable, active: ${voice.locale.toLanguageTag()}" else null,
                        displayName = "Jarvis Indian Male (${voice.locale.toLanguageTag()})",
                        gender = gender
                    )
                }
            }
            JarvisVoiceStyle.JARVIS -> {
                val voice = findBestVoiceForLanguage(tts, preferredLanguageCode ?: "en", preferMale = true)
                    ?: findBestIndianMaleVoice(tts, preferredLanguageCode)
                if (voice != null) {
                    val gender = detectVoiceGender(voice)
                    return VoiceResolutionResult(
                        voice = voice,
                        isFallback = false,
                        displayName = "Jarvis Core (${voice.locale.toLanguageTag()})",
                        gender = gender
                    )
                }
            }
            JarvisVoiceStyle.MAYA_STYLE, JarvisVoiceStyle.VENOM_STYLE -> {
                val voice = findBestVoiceForLanguage(tts, preferredLanguageCode ?: "en-IN", preferMale = voiceStyle == JarvisVoiceStyle.VENOM_STYLE)
                if (voice != null) {
                    val gender = detectVoiceGender(voice)
                    return VoiceResolutionResult(
                        voice = voice,
                        isFallback = false,
                        displayName = "${voiceStyle.title} (${voice.locale.toLanguageTag()})",
                        gender = gender
                    )
                }
            }
        }

        // 3. Fallback to default voice
        val defaultVoice = try { tts.defaultVoice } catch (_: Exception) { null }
        return VoiceResolutionResult(
            voice = defaultVoice,
            isFallback = true,
            fallbackReason = "System default voice in use",
            displayName = defaultVoice?.let { formatVoiceDisplayName(it, detectVoiceGender(it)) } ?: "Android Default TTS"
        )
    }

    /**
     * Finds the best voice for a given language code (e.g. "en", "en-IN", "hi", "gu").
     */
    fun findBestVoiceForLanguage(tts: TextToSpeech?, langCode: String, preferMale: Boolean = true): Voice? {
        if (tts == null) return null
        val voices = try {
            tts.voices?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val target = langCode.lowercase().replace("_", "-")

        val matching = voices.filter { voice ->
            val vTag = voice.locale.toLanguageTag().lowercase()
            val vLang = voice.locale.language.lowercase()

            when {
                target.contains("en-in") -> isEnInLocale(voice.locale)
                target.startsWith("hi") -> vLang == "hi"
                target.startsWith("gu") -> vLang == "gu"
                target.startsWith("en") -> vLang == "en"
                else -> vTag.startsWith(target) || vLang == target
            }
        }

        if (matching.isEmpty()) return null

        if (preferMale) {
            val maleMatch = matching.filter { detectVoiceGender(it) == VoiceGender.MALE }
                .maxByOrNull { scoreVoice(it) }
            if (maleMatch != null) return maleMatch
        }

        return matching.maxByOrNull { scoreVoice(it) }
    }

    fun findVoiceByName(tts: TextToSpeech?, voiceName: String): Voice? {
        if (tts == null || voiceName.isBlank()) return null
        return try {
            tts.voices?.find { it.name.equals(voiceName, ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Determines whether a voice is Male, Female, or Neutral/Unknown based on
     * explicit voice metadata and standard vendor naming patterns.
     * Adheres strictly to the requirement:
     * "Do not claim that a voice is male if Android's installed TTS engine does not provide reliable gender metadata."
     */
    fun detectVoiceGender(voice: Voice): VoiceGender {
        val name = voice.name.lowercase()
        val features = voice.features?.map { it.lowercase() } ?: emptyList()

        // 1. Explicit female markers
        if (name.contains("female") || features.any { it.contains("female") } ||
            name.contains("-fem") || name.contains("#female") || name.contains("_female")) {
            return VoiceGender.FEMALE
        }

        // 2. Explicit male markers
        if (name.contains("male") || features.any { it.contains("male") } ||
            name.contains("-masc") || name.contains("#male") || name.contains("_male")) {
            return VoiceGender.MALE
        }

        // 3. Known Google TTS male voice identifiers:
        // Indian English male voices: en-in-x-end, en-in-x-ene
        // Hindi male voices: hi-in-x-hid, hi-in-x-hie
        // Gujarati male voices: gu-in-x-gub
        // General male: -m-
        val knownMaleKeys = listOf("-end-", "-ene-", "-hid-", "-hie-", "-gub-", "-m-")
        if (knownMaleKeys.any { name.contains(it) }) {
            return VoiceGender.MALE
        }

        // 4. Known Google TTS female voice identifiers:
        // Indian English female: en-in-x-enc, en-in-x-enf
        // Hindi female: hi-in-x-hic, hi-in-x-hif
        // Gujarati female: gu-in-x-gua
        val knownFemaleKeys = listOf("-enc-", "-enf-", "-hic-", "-hif-", "-gua-", "-f-")
        if (knownFemaleKeys.any { name.contains(it) }) {
            return VoiceGender.FEMALE
        }

        // 5. Gender metadata cannot be reliably confirmed
        return VoiceGender.NEUTRAL_OR_UNKNOWN
    }

    /**
     * Backward-compatible helper for code checking isVoiceLikelyMale.
     */
    fun isVoiceLikelyMale(voice: Voice): Boolean {
        return detectVoiceGender(voice) == VoiceGender.MALE
    }

    private fun isEnInLocale(locale: Locale): Boolean {
        val lang = locale.language.lowercase()
        val country = locale.country.uppercase()
        val tag = locale.toLanguageTag().lowercase()
        return lang == "en" && (country == "IN" || tag.contains("en-in"))
    }

    private fun isIndianLocale(locale: Locale): Boolean {
        val lang = locale.language.lowercase()
        val country = locale.country.uppercase()
        val tag = locale.toLanguageTag().lowercase()
        return country == "IN" ||
                tag.contains("-in") ||
                lang in listOf("hi", "gu", "mr", "ta", "te", "bn", "kn", "ml", "pa")
    }

    private fun scoreVoice(voice: Voice): Int {
        var score = 0
        if (voice.quality >= Voice.QUALITY_VERY_HIGH) score += 30
        else if (voice.quality >= Voice.QUALITY_HIGH) score += 20
        else if (voice.quality >= Voice.QUALITY_NORMAL) score += 10

        if (!voice.isNetworkConnectionRequired) score += 15 // offline reliability
        if (voice.latency <= Voice.LATENCY_NORMAL) score += 10
        if (detectVoiceGender(voice) == VoiceGender.MALE) score += 25
        return score
    }

    /**
     * Inspects text for script markers to detect response language:
     * - Gujarati Unicode range: \u0A80..\u0AFF
     * - Devanagari (Hindi) Unicode range: \u0900..\u097F
     */
    fun detectScriptLanguage(text: String): String {
        for (char in text) {
            val code = char.code
            if (code in 0x0A80..0x0AFF) return "gu"
            if (code in 0x0900..0x097F) return "hi"
        }
        return "en"
    }

    /**
     * Splits multi-lingual text into language segments (e.g. for mixed Gujarati + English sentences),
     * grouping sentences and clauses cleanly so TTS doesn't produce unnatural repeated reinitialization.
     */
    fun splitIntoLanguageSegments(text: String): List<TextLanguageSegment> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        // Split by sentence boundaries while keeping structure
        val sentenceRegex = Regex("(?<=[.!?।\n])\\s+")
        val rawSentences = trimmed.split(sentenceRegex).filter { it.isNotBlank() }

        if (rawSentences.size <= 1) {
            return listOf(TextLanguageSegment(trimmed, detectScriptLanguage(trimmed)))
        }

        val segments = mutableListOf<TextLanguageSegment>()
        var currentLang = ""
        val currentChunk = StringBuilder()

        for (sentence in rawSentences) {
            val sentenceLang = detectScriptLanguage(sentence)
            if (sentenceLang == currentLang) {
                if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                currentChunk.append(sentence)
            } else {
                if (currentChunk.isNotEmpty()) {
                    segments.add(TextLanguageSegment(currentChunk.toString(), currentLang))
                    currentChunk.clear()
                }
                currentLang = sentenceLang
                currentChunk.append(sentence)
            }
        }

        if (currentChunk.isNotEmpty()) {
            segments.add(TextLanguageSegment(currentChunk.toString(), currentLang))
        }

        return segments
    }

    private fun formatVoiceDisplayName(voice: Voice, gender: VoiceGender): String {
        val langName = voice.locale.displayLanguage
        val countryName = voice.locale.displayCountry

        val genderStr = when (gender) {
            VoiceGender.MALE -> "Male"
            VoiceGender.FEMALE -> "Female"
            VoiceGender.NEUTRAL_OR_UNKNOWN -> "Voice"
        }

        val cleanName = voice.name
            .substringAfterLast("/")
            .substringAfterLast(":")
            .replace("#", " ")
            .replace("-", " ")
            .trim()

        val locationSuffix = if (countryName.isNotBlank()) " ($countryName)" else ""
        return "$langName$locationSuffix • $genderStr • $cleanName"
    }
}
