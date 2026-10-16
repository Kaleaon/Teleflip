<p align="center">
  <img src="teleflip-logo.png" width="160" height="160" alt="TeleFlip Logo" />
</p>

# TeleFlip 📱

A native, lightweight, and full-featured Telegram client meticulously engineered for Android flip phones, feature phones, and physical keypad devices (Schok Classic, Cat S22 Flip, Kyocera DuraXV, Sonim XP3/XP5, Xiaomi Qin, and similar Android Go / AOSP devices).

Built directly on official Telegram **TDLib** (Telegram Database Library) using Android NDK native binaries for maximum performance, minimal battery drain, and full MTProto protocol compliance.

---

## ✨ Features

- **100% Non-Touch Navigation**: Full support for 5-Way Directional Pad (Up, Down, Left, Right, Center D-Pad action). No cursor emulation or touchscreen required.
- **Physical 12-Key Multi-Tap Text Engine**:
  - Full T9 cycling on physical numeric keys (`2` = a→b→c→2, `9` = w→x→y→z→9, etc.).
  - `#` Key: Mode switching between `Abc` (Capitalized), `abc` (Lowercase), `ABC` (Uppercase), and `123` (Number lock).
  - `*` Key: Symbols & Emoji dialog with D-Pad grid selection.
  - `0` Key: Space insertion.
  - `1` Key: Fast punctuation cycling.
- **Full Telegram Messaging Parity**:
  - **Native Reply Threading & In-Place Editing**: Quoted reply previews inside message bubbles, live reply/edit context banner above the compose box, and `TdApi.EditMessageText` support.
  - **Keypad Emoji Reactions**: React to any message using `1`–`8` numeric shortcuts (`👍`, `❤️`, `😂`, `🔥`, `🎉`, `🙏`, `😢`, `👀`).
  - **Pinned Chats & Pinned Messages**: Pin/unpin chats (`📌`) and jump directly to pinned messages inside any conversation.
  - **Automatic Draft Persistence**: Unsent T9 message drafts are automatically saved per chat when backing out and restored when reopening the conversation.
  - **Saved Messages & New Chat / Group / Channel Dialog**: Start direct chats by `@username` or `+phone`, open personal **Saved Messages** cloud storage, or create new Groups and Channels.
- **Full-Screen Media Viewer & Playback (`MediaViewerDialog`)**:
  - **Full-Screen Photo Viewer with D-Pad Pan & Zoom**: Press `OK` on any photo to open full-screen view; press `LSK` or `2`/`8` to zoom (`1.0x`–`3.0x`), `D-Pad` to pan across the image, `5` to reset, and `1` to save to device storage.
  - **Inline & Full-Screen Video & Animated GIF Player**: Play video attachments and looping GIFs with `OK` (Play/Pause), `D-Pad Left/Right` (`±5s` seek), and `LSK` (Restart).
  - **Voice Notes & Speed Control**: Record AAC/M4A voice notes with Push-to-Talk (`Call` key or Attach menu) and play back at `1.0x`, `1.5x`, or `2.0x` speed.
  - **GPS Location & Offline Organic Maps Bridge**: Share hardware GPS coordinates or open received locations in Organic Maps / OsmAnd without Google Play Services.
- **Groups, Channels, Polls & Forum Topics**:
  - **Interactive Telegram Polls (`PollDialog`)**: Create anonymous or public multiple-choice polls using T9 input and vote or close polls with `1`–`9` keypad shortcuts.
  - **Group Members & Admin Management**: Inspect group member lists (`👑 Owner`, `🛡️ Admin`, `👤 Member`), promote/demote admins, or remove members.
  - **Forum Topic Threads**: Browse, filter, and create topic threads (`🧵`) in forum-enabled supergroups.
  - **Bot Command Menu**: Trigger `/start`, `/help`, `/settings`, `/status`, and `/cancel` with a single keypress.
- **Organization, Privacy & Closed-Flip Notifications**:
  - **Custom Chat Folders & Archived Chats**: Filter across `All`, `Direct`, `Groups`, `Channels`, `Bots`, `📁 Folders`, and `📦 Archive` tabs using `D-Pad Left/Right`.
  - **End-to-End Encrypted Secret Chats & Self-Destruct Timers**: Start `🔒 Secret Chats` (`TdApi.CreateNewSecretChat`) and configure message self-destruct timers (`Off`, `5s`, `15s`, `30s`, `1m`, `1h`).
  - **Closed-Flip Background Push Service (`TeleFlipNotificationService`)**: Keeps the MTProto connection alive without Google Play Services so incoming messages and voice calls trigger sound/vibration alerts while the flip phone is closed.

---

## 🎮 Keypad Reference Guide

| Key | In Chat List | In Conversation | In Media Viewer | In Text Field |
| :--- | :--- | :--- | :--- | :--- |
| **D-Pad Up / Down** | Scroll chat list | Scroll message history | Pan photo up/down | Move cursor / Scroll |
| **D-Pad Left / Right** | Cycle Tabs (`All`..`Archive`) | Navigate elements | Pan photo / Seek video `±5s` | Commit char / Move cursor |
| **D-Pad Center (OK)** | Open selected chat | Send / Open Media / Vote Poll | Zoom Photo / Play-Pause Video | Submit |
| **Left Softkey (LSK)** | Chat Options (New, Pin, Folder, Archive) | Attach / Message Options | Cycle Zoom / Restart Video | Clear / Action |
| **Right Softkey (RSK)** | Exit app | Back to Chat List | Close Viewer | Back |
| **Keys 1 – 9** | Quick-open Chat `1`–`9` | Quick menu / Reaction / Poll vote | `2` Zoom In, `8` Out, `5` Reset, `1` Save | Multi-tap T9 entry |
| **Key \*** | — | — | — | Symbols & Emoji picker |
| **Key #** | — | — | — | Toggle Case Mode (`Abc`/`abc`/`ABC`/`123`) |
| **Call / Green Button** | — | Start Call / Voice Recording | — | — |
| **Back Button** | Exit app | Cancel Reply/Edit or Return to List | Close Viewer | Close dialog / Back |

---

## 🛠️ Building & Verification

```bash
# Run Detekt static code analysis
./gradlew detekt

# Run Ktlint formatting verification
./gradlew ktlintCheck

# Run unit tests
./gradlew test

# Build debug APK
./gradlew assembleDebug
```

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
