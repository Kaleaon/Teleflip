# Schok Classic SC3218 target

User-supplied handset identification:

- Model: SC3218
- Hardware: 1.1
- Firmware build: sc3218_V1.5.9_B
- Kernel: 3.18.71-perf

The [SC3218 user guide distributed by Charity Mobile](https://charitymobile.b-cdn.net/products/schok-classic/schokclassicug.pdf) lists Android 8 Go, a Qualcomm MSM8909 32-bit processor, 1 GB RAM, and a 480×800 main display. The 176×220 external display is separate from the application display.

The firmware's exact Android API level and display density have not been read from this handset. Kernel and firmware labels do not establish either value. Automated compatibility checks cover Android 8.0/API 26 and Android 8.1/API 27, with the main display modeled at hdpi (320×533 dp). Existing 240×320 keypad checks remain enabled.

## Application compatibility

- Minimum Android API: 26.
- APK includes `armeabi-v7a` TDLib and NTgCalls libraries for the 32-bit processor, as well as ARM64 libraries for other phones.
- Compact call controls apply below 600 dp available height. Accept, mute, speaker, and hangup stay outside the scrolling status area.
- Call controls support D-pad focus, softkeys, Call/End keys, and repeated-key protection.
- Active audio uses a microphone foreground service and a partial wake lock; leaving the activity does not tear down a call.

## Physical-handset verification still required

Automated JVM tests do not exercise this firmware's native audio drivers, hardware codec support, cellular audio-focus interactions, Bluetooth routing, or lid policy. Verify incoming/outgoing two-way audio, mute, earpiece/speaker switching, call termination, and opening/closing the flip on this handset. Also verify PNG/JPEG/WebP images, TGS/WebM stickers, audio/video playback, and download completion with actual Telegram content.

The app does not control the vendor's external-display UI. Its notification and lid behavior must be checked on the phone.
