# MangaLens AI: real-time Arabic manga/manhwa/webtoon translator (Android)

Open your reader -> start MangaLens once -> read normally. Every new page is detected, OCR'd, translated and drawn over the original bubbles automatically. No per-page button, no screenshots, no uploads.

## Build & run
1. Android Studio Koala (2024.1) or newer, JDK 17. Open this folder and let Gradle sync (if it asks for a wrapper, use Gradle 8.9).
2. Run on a **real device** (Android 10+, API 29+). Emulators work poorly with MediaProjection + overlays.
3. First launch: tap **Start Translation** and accept, in order:
   - **Display over other apps** (overlay permission)
   - **Notifications** (Android 13+, required for the foreground service)
   - **Screen capture**, then choose **"Entire screen"** (not "a single app"), otherwise other apps can't be read.
4. Switch to your manga app. Use the floating bubble (tap it) to Pause/Resume or Close.

## Enabling AI translation (recommended for natural Arabic)
Default engine is **ML Kit on-device** (offline after a one-time ~30 MB model download per language). For manga-quality, context-aware Arabic:
- Settings -> Translation Engine -> **Claude** (paste an Anthropic API key; default model `claude-haiku-5-5`, editable) **or** **OpenAI-compatible** (base URL + model + key; works with OpenAI, OpenRouter, Gemini's OpenAI endpoint, DeepSeek, local servers).
- Keys are stored with EncryptedSharedPreferences (Android Keystore). Nothing is hardcoded. **Only OCR'd text is sent, never images.** "Allow cloud processing" = off keeps everything on-device. If a cloud call fails, it falls back to on-device automatically.
- The model also returns new character/place names; they are kept in a glossary and sent with every later request so names stay consistent.

## Architecture (package `com.mangalens.ai`)
| Module | Files |
|---|---|
| Screen capture service | `capture/ScreenCaptureService`, `ScreenCapturer` (MediaProjection + VirtualDisplay + ImageReader, foreground service type `mediaProjection`) |
| Screen change detection | `capture/ChangeDetector` (40x72 luma grid; STABLE -> CHANGED -> SETTLED), `pipeline/PageCache` (revisited pages are instant) |
| OCR | `ocr/OcrEngine` (ML Kit JA/KO/ZH/Latin in parallel, script-filtered), `ocr/Bubbles` (bubble grouping, reading order, SFX heuristic, background sampling) |
| Language detection | `ocr/LangDetector` |
| Translation engine | `translate/Translation.kt` (`TranslationProvider` interface: ML Kit, OpenAI-compatible, Anthropic) |
| Overlay manager | `overlay/OverlayManager`, `RegionsView` (auto-fit RTL text), `ControlBubble` |
| Cache | `data/Db` (Room: translation cache + history), in-memory page LRU |
| Settings & UI | `data/SettingsRepository`, `ui/Screens.kt` (Compose), `MainActivity` |
| Orchestration | `pipeline/TranslationPipeline` |

## How it stays automatic and cheap
- Polling reads a tiny luma grid straight from the frame buffer (no full bitmap), 150-450 ms depending on speed/battery mode.
- A page change hides the now-stale overlay immediately, waits for the screen to settle, then captures one **clean** frame (so our own Arabic overlay is never OCR'd), runs OCR once, translates, and draws results progressively (cached bubbles first).
- The overlay window is `FLAG_NOT_TOUCHABLE`: swipes, scrolls and pinch-zoom pass straight through.
- Identical text is never translated twice (SHA-256 keyed Room cache); bitmaps are recycled and never written to disk.

## Known limitations (be aware)
- **Not compiled in the authoring sandbox** (no Android SDK there). Expect to fix small compile nits on first sync.
- Apps that set `FLAG_SECURE` (some readers/DRM) produce black captures; nothing can be read there.
- ML Kit OCR has **no Cyrillic, Arabic, Thai, etc.** models, so Russian pages won't be recognized yet. Add another `OcrEngine` backend (Tesseract or Cloud Vision) to cover them.
- Vertical Japanese, curved and heavily stylised text depend on ML Kit's quality; stylised SFX are the weakest area. SFX detection is heuristic (toggle in Settings).
- While scrolling, overlays are hidden and redrawn once the page settles (translations come from cache, so it's fast) rather than sliding with the content.
- On-device translation is literal compared with an LLM; use a cloud provider for best Arabic.
- Orientation changes are handled by resizing the virtual display; Android 14+ shows a system consent prompt for every new capture session.

## Get an installable APK without Android Studio
1. Create a free GitHub repo and upload the contents of this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab -> "Build APK" -> run it (it also runs on every push).
3. When it finishes, download the **MangaLensAI-apk** artifact, unzip it, and copy `app-debug.apk` to your phone.
4. On the phone, open the file and allow "Install unknown apps" when asked.
