# JARVIS — Personal AI Assistant for Android

JARVIS is a full-featured, on-device and cloud-hybrid personal AI assistant built for Android using Kotlin, Jetpack Compose, Material 3, Room, Google ML Kit, Android Speech/TTS, CameraX/ImageCapture, and Gemini API.

---

## 1. System Requirements
- **Android Studio**: Ladybug / Meerkat or newer (Android Studio with AGP 8.8+)
- **JDK**: Java 17 or Java 21
- **Android SDK**:
  - `compileSdk`: 35 (or 36)
  - `targetSdk`: 35
  - `minSdk`: 26 (Android 8.0 Oreo)
- **Gradle**: 8.10+ (Gradle Kotlin DSL)

---

## 2. Build & Test Commands

### Assemble Debug APK
```bash
gradle assembleDebug
```
The resulting debug APK is created at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Run Unit & Robolectric Tests
```bash
gradle :app:testDebugUnitTest
```

### Run Lint
```bash
gradle :app:lint
```

---

## 3. Configuration & API Keys
JARVIS supports two operating modes for the intelligence layer:
1. **Cloud Multi-Modal AI (Gemini)**:
   - Configured via `BuildConfig.GEMINI_API_KEY` through the Secrets panel or `.env`.
   - Supports user-specified keys directly in the **Settings Screen** (`SettingsScreen.kt`).
   - Supported models: `gemini-3.5-flash`, `gemini-3.1-pro-preview`, `gemini-2.5-flash`.
2. **Local Offline Core**:
   - When no API key is supplied or when internet connectivity is severed, JARVIS automatically engages `LocalOfflineAIProvider` to parse tool execution patterns, answer system commands, perform offline device operations, and format responses locally.

---

## 4. Hardware, System Services & Permissions Setup

### Microphone & Speech (Voice Pipeline)
- **Permissions**: `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`
- **Wake Word Detection**: Powered by continuous on-device keyword matching (`KeywordSpeechWakeWordDetector`) hosted in a persistent foreground service (`WakeWordService`).
- **Supported Wake Words**: *"JARVIS"*, *"HEY JARVIS"*, *"OK JARVIS"*, *"WAKE UP JARVIS"*.
- **Languages Supported**: English, Hindi (`hi-IN`), Gujarati (`gu-IN`).
- **State Machine**: `IDLE` -> `WAKE_WORD_READY` -> `LISTENING` -> `PROCESSING` -> `EXECUTING` -> `SPEAKING` -> `INTERRUPTED` -> `ERROR`.

### Camera & Vision
- **Permissions**: `CAMERA`
- **Capture**: Full camera capture and Gallery image decoder with aspect ratio preview.
- **On-Device OCR**: Offline Latin text recognition powered by Google ML Kit (`com.google.mlkit:text-recognition:16.0.1`) in `OcrEngine.kt`.
- **Multimodal Analysis**: Integrated with Gemini Flash multi-modal vision prompts with directive templates.

### Screen Understanding (MediaProjection)
- **Permissions**: `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- **Foreground Service**: `ScreenCaptureService.kt` with persistent notification channel (`jarvis_screen_capture_channel`).
- **Consent**: Employs standard Android `MediaProjectionManager` intent request before acquiring virtual display frames.

### Accessibility Service
- **Service**: `JarvisAccessibilityService.kt`
- **Configuration**: Declared in `res/xml/accessibility_service_config.xml`.
- **Capabilities**: Reads screen view hierarchy nodes, inspects interactive text, triggers home/back global actions, and performs non-sensitive UI operations.

### Notification Listener
- **Service**: `JarvisNotificationListenerService.kt`
- **Purpose**: Collects system notification updates, parses notification titles/texts, and provides on-demand summaries without broadcasting sensitive information to external servers.

### Calling & Messaging (Rule 8 Compliant)
- **Permissions**: `CALL_PHONE`, `READ_CONTACTS`
- **Calling**: Performs contact search and validates call intent permissions: returns `DIALER_OPENED` or `CALL_INITIATED` without falsified completion status.
- **Messaging**: Uses `Intent.ACTION_SENDTO` to prepare SMS drafts with recipient and message bodies.

### Calendar & Reminders
- **Permissions**: `READ_CALENDAR`, `WRITE_CALENDAR`, `POST_NOTIFICATIONS`
- **Calendar**: Creates and queries events directly via `CalendarContract.Events`.
- **Reminders**: Schedules alarms and persistent notifications using `AlarmManager` and `JarvisAlarmReceiver`.

### Automation Engine (Rule 10 Compliant)
- **Entity**: `AutomationRule` with tracking for `runCount`, `failureCount`, `lastRun`, `trigger`, `conditions`, and `actions`.
- **Execution**: Evaluates triggers (`BATTERY_LOW`, `BATTERY_CHARGING`, `TIME_DAILY`, `USER_COMMAND`) and runs automated notifications or spoken feedback.

### Persistent Memory (Rule 9 Compliant)
- **Database**: SQLite Room persistence (`JarvisDatabase`).
- **Categories**: `SHORT_TERM`, `LONG_TERM`, `PREFERENCE`, `TASK`, `EPISODIC`, `CONVERSATION`, `PERSONA`.
- **Privacy & Security**: Built-in credential sanitization filters passwords, API keys, tokens, and sensitive secrets before insertion. Supports full CRUD, search, archive, and persona scoping.

### Personas
- **JARVIS**: Calm, analytical, and hyper-efficient British AI assistant.
- **MAYA**: Empathetic, warm, and creative companion.
- **VENOM**: Edgy, sarcastic, humorous anti-hero assistant.
- Switching persona adjusts prompt system instructions, vocal greetings, and TTS pitch/rates.

### Offline Web Projects Studio
- Offline multi-file code editor (`HTML`, `CSS`, `JS`) with live sandboxed `WebView` preview, project export, and Room database persistence (`WebProjectManager`).

---

## 5. Known Android Limitations
1. **Background Mic & Screen Capture**: Android 14+ enforces strict foreground service type restrictions (`microphone`, `mediaProjection`). Services display persistent notification icons while active.
2. **Direct Calling & SMS**: Directly dispatching calls without launching the dialer requires `CALL_PHONE` permission to be granted explicitly at runtime. Direct background SMS sending requires default SMS app role; standard intent launches the SMS composer.
3. **Hardware Telephony**: Non-phone devices (tablets, Chromebooks) declare `<uses-feature android:name="android.hardware.telephony" android:required="false" />` to maintain full installation compatibility.
