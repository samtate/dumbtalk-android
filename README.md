# DumbTalk

Signal, Telegram, WhatsApp and SMS in one inbox for Android keypad phones. Works with a D-pad,
softkeys or touch. Android 10+ and a 64-bit ARM processor required.

<table>
  <tr>
    <td><img src="docs/screenshots/inbox.png" width="200" alt="Unified inbox"></td>
    <td><img src="docs/screenshots/chat.png" width="200" alt="WhatsApp conversation"></td>
    <td><img src="docs/screenshots/sms.png" width="200" alt="SMS conversation"></td>
  </tr>
</table>

## Install

Download the APK from [Releases](https://github.com/samtate/dumbtalk-android/releases/latest),
or add this repo to [Obtainium](https://github.com/ImranR98/Obtainium).

The first run explains the keys and permissions. Choose **Try the demo** for made-up chats on
all four services; it won't send real messages or change your default SMS app.

## Set up

Open **Menu → Settings → Services**.

- **SMS:** choose **Make default SMS app**. Existing texts appear in the inbox. Uses your default
  SMS SIM; normal carrier charges apply. Texts stay in Android's message store if you switch apps.
- **Signal:** scan the linking QR code from Signal on your main phone. Old messages aren't copied.
- **Telegram:** enter your own API ID/hash from [my.telegram.org](https://my.telegram.org), then
  link by QR or phone number. No API credentials are bundled in the APK.
- **WhatsApp:** link by QR or phone-number pairing code.

Allow notifications and background activity for timely delivery. **Settings → Background delivery**
shows restrictions that need attention.

## Keys

| Key | Action |
| --- | --- |
| Up / Down | Move |
| Centre | Select, type or send, as shown in the softkey bar |
| Left softkey or D-pad Left | Menu / actions |
| Right softkey / Back | Back |

Choose your menu key during setup or in Settings. DumberOS users can use D-pad Left because the
system takes the physical left softkey. Text fields keep Left for moving the cursor.

## Features

- Signal, Telegram and WhatsApp: text, media, voice notes, replies, reactions, edits, groups and polls.
- SMS: plain text, long and Unicode messages, search, mute, delivery status and failed-send retry.
- Notification replies and read actions, search across chats, and encrypted backup/restore.
- No MMS, RCS or calls. SMS has no attachments, reactions, quoted replies or read receipts.

**Settings → Back up** saves chats and linked accounts, with optional media. Keep the passphrase.
SMS stays in Android's store and isn't included. When restoring on another phone, stop using the
old DumbTalk installation to avoid running the same linked session twice.

## Privacy

Services run on the phone; there's no DumbTalk server, account or tracking. SMS uses Android's
telephony APIs. The other services use TDLib, presage and whatsmeow. Debug logs are shared only
when you choose; check them for names and numbers before posting.

Unofficial; not affiliated with Signal, Telegram or WhatsApp.

## Build

Requires JDK 17, Android SDK 35, NDK 27.2.12479018, Go 1.26+, rustup, cargo-ndk, CMake, Ninja,
clang/libclang and protoc. Set `ANDROID_HOME` and `ANDROID_NDK_HOME`; Rust is pinned in the repo.

```sh
scripts/fetch-tdlib.sh
scripts/build-wabridge.sh
scripts/build-sigbridge.sh
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

Debug and DEMO builds install alongside the release. Build the demo with `./gradlew :app:assembleDemo`.
For signed builds, see [the local release script](scripts/build-local-release.sh); keep your signing
key outside Git and use the same key for updates.

## Licence

[AGPL-3.0](LICENSE). Fonts: Dot Matrix by Svein Kåre Gunnarson (freeware) and GNU Unifont
(SIL OFL 1.1 / GPL 2+ with the font embedding exception).
