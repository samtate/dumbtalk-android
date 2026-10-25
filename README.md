# DumbTalk for Android

Signal, Telegram, WhatsApp and SMS in one inbox, built for Android keypad phones and driven
entirely with the D-pad. Every messaging network runs on the phone itself: there's no server, no
account with us and no tracking.

> **Status: early.** Daily use works on all three networks (text, replies, reactions, edits,
> photos, stickers, voice notes, files, polls, groups). Some newer features haven't been tested
> with real accounts yet; see [what works](#what-works).

DumbTalk is unofficial and is not affiliated with Signal, Telegram or WhatsApp.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/inbox.png" width="200"><br><sub>Unified inbox</sub></td>
    <td align="center"><img src="docs/screenshots/chat.png" width="200"><br><sub>Conversation</sub></td>
    <td align="center"><img src="docs/screenshots/message-actions.png" width="200"><br><sub>Message actions</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/menu.png" width="200"><br><sub>Menu</sub></td>
    <td align="center"><img src="docs/screenshots/services.png" width="200"><br><sub>Services</sub></td>
    <td align="center"><img src="docs/screenshots/setup-qr.png" width="200"><br><sub>Linking</sub></td>
  </tr>
</table>

## Phones

Any Android 10 (API 29) or newer phone with a 64-bit ARM (arm64-v8a) processor. It's designed
around keypad phones with a D-pad and softkeys, and tested on the **Qin F22 Pro** (640×960). It
also works by touch on ordinary phones.

## Install

1. Download the APK from the [latest release](../../releases/latest), or add this repository to
   [Obtainium](https://github.com/ImranR98/Obtainium) to get updates automatically.
2. Install it (allow installing from your browser or Obtainium when Android asks).
3. Open DumbTalk. A short welcome explains the keys, then Android asks to allow notifications and
   to let DumbTalk ignore battery optimisation. Allow both, or messages arrive late.
4. Choose **Connect a service**, or **Try the demo** to try the keys with made-up chats first.

## Keys

| Key | Action |
| --- | --- |
| ▲ ▼ | Move |
| Centre | Open the focused item, or the action shown in the middle (Type, Send, …) |
| ◀ (D-pad Left) | The left softkey: Menu in the inbox, a message's actions in a chat |
| Back / right softkey | Back |

DumberOS keeps the phone's real left softkey for its notification shade, so D-pad Left stands in
for it wherever the screen doesn't need Left itself; the softkey bar marks it with ◀. Text boxes,
side-by-side buttons and the photo viewer keep Left for moving.

In a chat the newest message starts selected: Centre gives it a quick 👍, ◀ opens its actions
(reply, react, forward, …) and Down from the newest message reaches the message box. Typing uses
the phone's keypad keyboard (such as TT9); Centre sends once there's text, and Back leaves the
text box. **◀ Menu → Settings → Keys & help** shows the keys on the phone.

## Linking accounts

DumbTalk links to your existing accounts as an extra device, the way the desktop apps do. Your main
phone stays the main device.

- **Signal** links only by scanning a QR code, by design. If Signal (or Molly) is on the same
  phone, show the code on another screen and scan it from there. Signal doesn't copy old messages
  to new devices, so chats start from when you link.
- **Telegram** links by QR, or by phone number with a code sent to your other Telegram devices.
  It needs app credentials of your own, once: on a computer, sign in at
  [my.telegram.org](https://my.telegram.org), open **API development tools**, create an app (any
  name and platform) and enter its **App api_id** and **App api_hash** when DumbTalk asks.
- **WhatsApp** links by QR, or by phone number with a pairing code. The pairing code works with
  WhatsApp on the same phone.

Things to know:

- **WhatsApp signs linked devices out after about 14 days** without your main phone online, and
  **Signal unlinks devices after about 30 days** it hasn't heard from them. Link again if it
  happens.
- **Unofficial clients carry some risk of an account ban**, especially on WhatsApp.
- **Nothing is backed up.** Disconnecting a service, uninstalling or resetting the phone deletes
  DumbTalk's copy of your messages. Your chats stay on your main phone (and Telegram's servers),
  except Signal messages received only by DumbTalk.

## SMS

Open **◀ Menu → Settings → Services → SMS → Make default SMS app** and accept Android's prompts.
Your SMS messages stay in Android's own message store, so switching default SMS app later keeps
them. DumbTalk supports plain text (including long and Unicode messages), notifications, search and
sent/delivered/failed states, using the default SMS SIM. MMS and RCS aren't supported.

Incoming SMS is handled by a small separate process that doesn't start the other networks, and
messages that arrive before the phone is first unlocked are kept until it is.

## Privacy

- Each network runs on the phone: Signal through [presage](https://github.com/whisperfish/presage),
  Telegram through [TDLib](https://github.com/tdlib/td) and WhatsApp through
  [whatsmeow](https://github.com/tulir/whatsmeow). DumbTalk talks only to those networks.
- Messages, media and keys stay in DumbTalk's private app storage. Android backup is turned off
  so they're never copied off the phone.
- There's no analytics or crash reporting. **Settings → Share debug log** sends a log only when
  you choose to; it can contain names and numbers, so read it before posting it publicly.

## Staying connected

None of these networks will push messages to an unofficial app, so DumbTalk keeps one connection
per network open in a foreground service (the ongoing "DumbTalk" notification). It reconnects when
the network changes, checks every 15 minutes even in Doze, and checks again whenever you open the
app.

If messages arrive late:

1. **Settings → Background delivery** should say "On". If not, select it to fix the battery
   setting; "Restricted" battery use stops background delivery completely.
2. **Settings → Services** shows each network as On, Offline (linked but not connected right now)
   or Off.
3. Some phones kill background apps aggressively; see [dontkillmyapp.com](https://dontkillmyapp.com).

## What works

| | Signal | Telegram | WhatsApp |
| --- | --- | --- | --- |
| Text, replies, reactions, edits, deletes | ✅ | ✅ | ✅ |
| Photos, videos, stickers, voice notes, files | ✅ | ✅ | ✅ |
| Search, contacts, new chats, mute, archive | ✅ | ✅ | ✅ |
| Old messages after linking | ❌ (Signal's design) | ✅ | ✅ (recent, from your phone) |
| Pinned messages | ✅ ¹ | ✅ | ✅ ¹ |
| Polls | ✅ ¹ | ✅ | ✅ ¹ |
| Mentions, formatting, disappearing messages | ✅ ¹ | ✅ ¹ | ✅ ¹ |
| Message requests, blocking | ✅ ¹ | ✅ ¹ | blocking only ¹ |
| Groups: create, rename, add, remove, leave | ✅ ¹ | ✅ ¹ | ✅ ¹ |
| Calls | ❌ | ❌ | ❌ |

¹ Built but not yet tested with a real account. Forwarding works between any chats on any
network. See [docs/plan.md](docs/plan.md) for details and what's next.

## Reporting a problem

Open an issue with your phone model, Android version and DumbTalk version (**Settings → About**).
For anything about messages not arriving, attach **Settings → Share debug log** after checking it
for anything private.

## Build

You need JDK 17, the Android SDK (platform 35) with NDK **27.2.12479018**, Go 1.26+, and for the
Signal bridge rustup (it installs the pinned Rust 1.99 itself), `cargo install cargo-ndk`, CMake,
Ninja, clang/libclang and protoc. Set `ANDROID_HOME` and `ANDROID_NDK_HOME`.

Build the three native pieces first. Gradle won't configure the project until the Signal bridge
exists.

```sh
scripts/fetch-tdlib.sh       # Telegram: TDLib from this repo's TDLib release
scripts/build-wabridge.sh    # WhatsApp: Go bridge via gomobile
scripts/build-sigbridge.sh   # Signal: Rust bridge via cargo-ndk + UniFFI
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest   # unit and keypad tests
./gradlew :app:assembleDebug                               # app/build/outputs/apk/debug/
```

Debug builds are a separate app (`dev.dumbtalk.app.debug`) signed with a committed key, so they
install over each other and alongside a release. Local builds have version code 1; to install one
over a newer CI build use `adb install -r -d`.

To skip typing Telegram credentials on the phone during development, put your own in
`local.properties` (never committed):

```properties
telegram.api_id=12345678
telegram.api_hash=0123456789abcdef0123456789abcdef
```

The screenshots above come from the Robolectric keypad test (`KeypadFlowTest`), which drives the
app with hardware key events at the test phone's screen size.

### Releases

CI tests and builds a debug APK on every push. Pushing a `v*` tag (for example `v0.1.0`) also
builds a signed release APK and publishes it as a GitHub release; the version name comes from the
tag. Release signing needs the repository secrets `DUMBTALK_KEYSTORE_BASE64`,
`DUMBTALK_KEYSTORE_PASSWORD`, `DUMBTALK_KEY_ALIAS` and `DUMBTALK_KEY_PASSWORD`. TDLib is built by
its own workflow only when `tdlib.properties` changes.

## Layout

- `core/`: the messaging contract every network implements (`Contracts.kt`), the merged inbox
  (`Inbox.kt`), the shared half of the two native bridges (`bridge/`) and the demo service.
- `app/`: the Compose keypad UI. `ui/Softkeys.kt` is the softkey model and `ui/Components.kt`
  the focus handling; `background/` holds the connection service and notifications; `sms/` the
  SMS client.
- `svc-telegram/`: Telegram on TDLib. `svc-whatsapp/` + `bridge/whatsapp/`: a Go bridge on
  whatsmeow. `svc-signal/` + `bridge/signal/`: a Rust bridge on presage. Both bridges keep their
  own SQLite store of chats and messages and return JSON.

## Licence

AGPL-3.0. The Signal integration builds on AGPL-licensed Signal code.

The interface font is "Dot Matrix" by Svein Kåre Gunnarson (Dionaea Fonts), distributed as
freeware. Characters it lacks (accents, symbols, emoji, other scripts) use a subset of
[GNU Unifont](https://unifoundry.com/unifont/) 17.0.05, dual-licensed under the SIL Open Font
License 1.1 and GPL 2+ with the font embedding exception.
