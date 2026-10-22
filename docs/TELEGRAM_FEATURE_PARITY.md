# Telegram feature coverage

TeleFlip targets operation with a D-pad, number keys, and softkeys. Full Telegram feature parity is not complete. A successful build and automated tests do not establish live account or physical handset compatibility.

## Added keypad flows

| Area | Implemented behavior |
| --- | --- |
| Long menus | Nine choices per page; left/right or */# changes pages; 1–9 selects a visible choice. |
| Scheduling | Browse scheduled messages, send now after confirmation, reschedule using a numeric delay, delete. Scheduled updates stay out of ordinary message history until sent. |
| Media | Select 2–10 photos through Android's file picker and send a real album with caption and reply; edit captions where Telegram permits. Picker navigation depends on the installed Android file provider. |
| Contacts | Share a contact with a real phone number after confirmation; inspect received cards, copy a number, add a contact, or open the user's private chat. |
| Message actions | Multi-select up to 100 loaded messages for forwarding/deletion; keep forward attribution; save to Saved Messages; request message links and translations. Telegram enforces permissions. |
| Discussions | Retrieve the actual discussion chat and thread from Telegram; read earlier replies and send a reply into that thread. |
| Links | Inspect invite links and confirm joining; resolve public message links to actual server message IDs. Paid subscription invites remain unsupported. |
| Formatting | Send a whole draft with bold, italic, underline, strike, monospace, or spoiler formatting. Render supported received entities using UTF-16 offsets and mask spoilers until revealed. |
| Bots | Load published private/group bot commands; handle text, ordinary/password callbacks, URLs, copy-text, user links, and phone/location request buttons with consent for personal data. |
| Privacy | Update seven privacy audiences while retaining existing user/chat exceptions. |
| Account security | Set/change/disable two-step verification; set a recovery email and submit its verification code. Password changes preserve the existing recovery email. |
| Chat state | Mark chats unread and leave groups/channels after confirmation. |

These actions use TDLib requests and report errors from Telegram. Request acceptance for an outgoing message means queued; final delivery comes from subsequent TDLib message updates. No demo success is substituted for a failed server operation.

## Existing media and audio work

Real TDLib download/update handling supports photos, documents, audio, video, WEBP/WEBM stickers, and TGS rendering. Encrypted one-to-one voice transport uses NTgCalls with Telegram signaling, relay information, audio focus, foreground service, and call lifecycle handling. Device codecs, microphone/speaker routing, Bluetooth, relay connectivity, closed-lid behavior, and firmware background restrictions still require an SC3218 with a logged-in account and a second caller for end-to-end verification.

## Remaining substantial work

- Video calls, group calls, screen sharing, and livestream participation.
- Stories, Mini Apps/web apps, inline bot query results, login URL authorization, and advanced bot request-user/chat/poll buttons.
- Live location updates and proximity alerts.
- Complete administration, moderation, statistics, and every advanced group/channel setting.
- Complete Premium, gifts, invoice payments, subscriptions, and wallet flows. Existing paid-media Stars handling does not imply general payment support.
- Exhaustive Telegram text entity rendering and arbitrary selection-based rich-text composition.
- Full service-message presentation and every attachment/content subtype.

Unsupported operations must remain explicit rather than presenting fabricated data or success. Automated coverage checks request construction, failure propagation, message state, rendering, and Android 8 keypad behavior. Live Telegram interoperability and physical SC3218 verification remain required before claiming a fully operational release.
