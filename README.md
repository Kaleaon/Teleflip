# TeleFlip 📱

A native, lightweight, and responsive Telegram client meticulously engineered for Android flip phones, feature phones, and physical keypad devices (Schok Classic, Cat S22 Flip, Kyocera DuraXV, Sonim XP3/XP5, Xiaomi Qin, and similar Android Go / AOSP devices).

Built directly on official Telegram **TDLib** (Telegram Database Library) using Android NDK native binaries for maximum performance, minimal battery drain, and full MTProto protocol compliance.

---

## ✨ Features

- **100% Non-Touch Navigation**: Full support for 5-Way Directional Pad (Up, Down, Left, Right, Center D-Pad action). No cursor emulation or touchscreen required.
- **Physical 12-Key Multi-Tap Text Engine**:
  - Full T9 cycling on physical numeric keys (`2` = a→b→c→2, `9` = w→x→y→z→9, etc.).
  - `#` Key: Mode switching between `Abc` (Capitalized), `abc` (Lowercase), `ABC` (Uppercase), and `123` (Number lock).
  - `*` Key: Symbols popup dialog with D-Pad selection.
  - `0` Key: Space insertion.
  - `1` Key: Fast punctuation cycling (`.` `,` `?` `!` `'` `-` `"` `@` `:` `/` `1`).
  - Full suppression of conflicting soft-keyboard IME popups on Android Go.
- **Dynamic 3-Key Softkey Architecture**:
  - **Left Softkey (LSK)**: Contextual actions, Chat list category filters (*All / Direct / Groups / Channels*), Options menu.
  - **Center Softkey (D-Pad Center / Enter)**: Primary select, Open conversation, Send message.
  - **Right Softkey (RSK)**: Back, Attachments menu, Demo mode toggle.
- **Chat Management & Lazy Loading**:
  - Live chat list synchronization with unread count badges.
  - Infinite scroll / pagination loading on demand to conserve device RAM and data bandwidth.
  - Chat categorization tabs (All, Direct Messages, Groups, Channels).
- **High-Resolution Avatars & Icons**:
  - Background asynchronous TDLib file pipeline downloading contact and group avatars with circular rendering.
  - Lazy avatar caching for low-memory flip phone environments.
- **Rich Media & Attachments**:
  - **Voice Notes**: Dedicated voice recorder and player (`VoiceRecorder.kt` / `VoicePlayer.kt`) using AAC/M4A audio encoding with live timer preview.
  - **GPS Location Sharing**: One-click physical location broadcast using device GPS/Network providers.
  - **Offline Navigation Bridge**: Native `geo:` intent dispatching location pins directly into [Organic Maps](https://organicmaps.app/) or OsmAnd without requiring Google Play Services.
  - **Photo & Document Previews**: In-line message bubble previews with file size and media status badges.
- **2-Step Verification & Telegram Login**:
  - Seamless authentication flow supporting international phone numbers, SMS/Telegram login codes, and Cloud 2FA Password authentication.
  - Automatic local SIM card phone number detection via Android `TelephonyManager`.
  - Built-in offline Demo Mode for UI testing without network credentials.

---

## 🎮 Keypad Reference Guide

| Key | In Chat List | In Conversation | In Text Field |
| :--- | :--- | :--- | :--- |
| **D-Pad Up / Down** | Scroll chat list | Scroll message history | Move cursor / Scroll |
| **D-Pad Center** | Open selected chat | Send composed message / Open attachment | Enter / Submit |
| **Left Softkey (LSK)** | Options / Filter Tabs | Options (Copy, Reply, Delete) | Switch Symbols / Config |
| **Right Softkey (RSK)** | Demo Mode / Refresh | Attachments Menu (Voice, GPS, Photo) | Clear character / Back |
| **Keys 2 – 9** | Quick scroll / search | Quick scroll | Multi-tap letter entry |
| **Key 1** | — | — | Punctuation (`. , ? ! ' - " @ : / 1`) |
| **Key 0** | — | — | Space |
| **Key \*** | Diagnostics screen | — | Symbols dialog picker |
| **Key #** | — | — | Toggle Case Mode (`Abc` / `abc` / `ABC` / `123`) |
| **Call / Green Button** | — | Start / Stop Voice Recording | — |
| **Back Button** | Exit app | Return to Chat List | Close keyboard / Back |

---

## 🛡️ Privacy & API Credentials Configuration

TeleFlip connects directly to Telegram's MTProto servers via TDLib. To respect Telegram's API Terms of Service and protect developer credentials, API keys are not hardcoded into the public repository.

You can configure your credentials using either of the two methods below:

### Method 1: Local Properties (Recommended for Builds)

Create or edit `local.properties` in the project root:

```properties
sdk.dir=/path/to/your/android-sdk

# Telegram API credentials from https://my.telegram.org/apps
TELEGRAM_API_ID=12345678
TELEGRAM_API_HASH=0123456789abcdef0123456789abcdef
```

*Note: `local.properties` is included in `.gitignore` and will never be committed to version control.*

### Method 2: In-App Configuration (Runtime)

If compiled with default placeholder values, TeleFlip will detect missing credentials upon launch:
1. On the Login screen, press the **Left Softkey `[Config]`**.
2. Enter your `App api_id` and `App api_hash` using the keypad.
3. Select **Save**. TeleFlip will securely persist your credentials in private app storage (`EncryptedSharedPreferences` / `teleflip_prefs`) and initiate the MTProto connection.

---

## 🛠️ Building & Installation

### Prerequisites
- **JDK 17** or **JDK 21**
- **Android SDK** (API 26+ / Android 8.0 Oreo minimum)
- Physical Android flip phone with ADB debugging enabled (e.g. Schok Classic SC3218) or Android Emulator

### Command Line Build

```bash
# Clone the repository
git clone https://github.com/Kaleaon/Teleflip.git
cd Teleflip

# Copy the example properties file and configure your SDK and API keys
cp local.properties.example local.properties

# Build debug APK
./gradlew assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launching on Device

```bash
# Launch TeleFlip directly via ADB
adb shell am start -n com.teleflip.app/.MainActivity
```

---

## 🔍 Static Analysis & Pre-Commit Guardrails

TeleFlip uses multi-layered static analysis to ensure code quality, formatting, and complexity compliance using Ktlint, Detekt, and Android Lint.

### Running Gradle Linter Tasks

```bash
# Run Detekt static code analysis and generate HTML report
./gradlew detekt

# Run Ktlint formatting verification
./gradlew ktlintCheck

# Auto-format Kotlin code with Ktlint
./gradlew ktlintFormat

# Run Android Lint checks
./gradlew lintDebug
```

HTML code smell reports generated by Detekt are saved to `app/build/reports/detekt/detekt.html`.

### Setting Up Python Pre-Commit Hooks

Pre-commit hooks validate code formatting, static analysis, and Android Lint rules prior to git commits.

1. **Install Python 3 & Pre-Commit CLI**:
   ```bash
   pip install pre-commit
   ```

2. **Install Git Pre-Commit Hooks**:
   ```bash
   pre-commit install
   ```

3. **Run Pre-Commit Validation Manually**:
   ```bash
   pre-commit run --all-files
   ```

---

## 📁 Architecture & Code Structure

```
com.teleflip.app/
├── MainActivity.kt             # Main controller managing screen transitions and key event routing
├── TeleFlipApp.kt              # Application lifecycle and global initialization
├── model/
│   └── Models.kt               # Chat, Message, and UI state data classes
├── input/
│   └── KeypadInputEngine.kt    # Physical 12-key T9 multi-tap parser, timer, and mode manager
├── ui/
│   ├── SoftkeyManager.kt       # Dynamic softkey bar binding (LSK / Center / RSK)
│   ├── ChatListAdapter.kt      # Chat list RecyclerView adapter with avatar binding & badges
│   ├── MessageAdapter.kt       # Message bubble adapter with photo, audio, and location cards
│   ├── OptionsMenuDialog.kt    # D-pad accessible options modal
│   ├── SymbolsDialog.kt        # Physical grid symbol selector
│   └── DiagnosticsDialog.kt    # Network and TDLib runtime status inspector
├── telegram/
│   └── TelegramManager.kt      # TDLib native client wrapper, state machine, and coroutine pipelines
├── media/
│   ├── VoiceRecorder.kt        # MediaRecorder wrapper with AAC compression
│   └── VoicePlayer.kt          # MediaPlayer wrapper with audio playback controls
├── location/
│   └── LocationHelper.kt       # GPS provider listener and Organic Maps geo-intent builder
└── report/
    ├── AdbReporter.kt          # Structured diagnostic reporting over ADB logcat and broadcast
    └── AdbReportReceiver.kt    # BroadcastReceiver for automation, tests, and headless inspection
```

---

## 🤝 Contributing

Contributions, bug reports, and hardware compatibility feedback are welcome!
1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE) or the terms of the official Telegram Android license. See the LICENSE file for details.
