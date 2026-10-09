# Security policy

SelfBubbles is an Android client for your own SelfBubbles relay: the relay
runs on a Mac signed into Messages, and this app shows that Mac's message
history on your phone and sends through it. Everything the app protects is
one secret, the relay token (plus, optionally, a Cloudflare Access service
token pair), and the message history that secret unlocks. This document
says what the app defends against, how, where that is in the code, and what
it does **not** defend against.

The relay has its own
[SECURITY.md](https://github.com/Avtraang/selfbubbles-relay/blob/main/SECURITY.md)
and
[docs/privacy-and-logs.md](https://github.com/Avtraang/selfbubbles-relay/blob/main/docs/privacy-and-logs.md);
the server-side threat model lives there.

## Supported versions

Only the `main` branch is supported. There are no release branches or
backports. The author runs a debug build of `main` daily; it has been tested
on one Android phone and against a relay on macOS 27.0. Android 9 (API 28)
is the minimum the build accepts (`app/build.gradle.kts`, `minSdk`).

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository
(**Security** tab → **Report a vulnerability**). Please do not open a public
issue for anything that could expose someone's messages or token, and please
do not send reports by email.

If the **Report a vulnerability** button is missing, open a public issue
that says only "security contact requested", with no details, and the author
will set up a private channel with you.

Useful in a report: the last line of the app's Settings, also shown at the
bottom of the Connect to your relay screen (`Version … · build …`: the commit
the build was made from, followed by `+` and six more digits when its sources
were not all committed), the Android
version, how your relay is exposed (Tailscale Serve, Cloudflare Tunnel +
Access, something else), and the exact text the app showed. Never paste your
`secrets.properties`, a `logcat` dump that has not been read through, or an
APK: a built APK contains every value from `secrets.properties`.

This is a one-person project with no security team and no bounty programme.
The author reads reports personally and will reply as soon as they can.

## Threat model

What the app holds:

- the relay token, and optionally a Cloudflare Access client id and secret:
  baked into the APK from `secrets.properties` at build time, or typed
  into Settings → Relay and stored on the phone;
- whatever messages and attachments it has displayed or cached.

Who it is designed to keep out:

| Adversary | Control |
| --- | --- |
| Whoever runs the Wi-Fi you are on, or sits between the phone and the relay | HTTPS only, system CAs only; no cleartext and no LAN mode exist in the app |
| A host that is *almost* the relay (look-alike name, same name on http, another port, a redirect away from the relay) | credentials are attached only to the exact relay origin, and stripped from any hop that leaves it |
| A cloud backup or a device-to-device transfer of the phone | the preferences file holding the credentials is excluded from both |
| Someone picking up the unlocked phone | the app lock (fingerprint / face / PIN) and the optional secure-window flag |
| A crash report or a server echoing the request back | error and status messages name fields, never values; the probe never echoes a body |

Who it does **not** keep out (see [What is not protected](#what-is-not-protected)):
anyone who can unlock the phone, any computer authorised for `adb` on it,
anyone holding the token, and the relay itself.

## Controls, verified in the code

### 1. HTTPS only, system certificate store only

`app/src/main/AndroidManifest.xml` sets `android:usesCleartextTraffic="false"`
and points `android:networkSecurityConfig` at
[`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml),
whose `<base-config cleartextTrafficPermitted="false">` trusts
`<certificates src="system" />` and nothing else. There is deliberately no
`<debug-overrides>` block and no `src="user"` anchor, so:

- `http://` never connects, in any build;
- a certificate the user installed (a corporate or debugging CA) is **not**
  trusted: the app fails rather than letting that CA read the traffic;
- a self-signed or private-CA relay does not work. Use Tailscale Serve or a
  Cloudflare Tunnel, both of which give the relay a publicly trusted
  certificate. (The author uses the Cloudflare route daily; the Tailscale
  route follows Tailscale's documentation but has not been exercised end to
  end with this app.)

There is no certificate pinning: any CA in Android's system store is
accepted for the relay host, which is what lets Let's Encrypt and Tailscale
certificates rotate without an app update.

### 2. No cleartext or LAN mode, by design

An earlier design probed a LAN address over plain `http` before falling back
to the tunnel. On an untrusted Wi-Fi network that would hand the relay token
and both Cloudflare Access credentials to whatever answered at that address.
That path is gone: `RelayConfig` accepts an `https://` base URL and
a `wss://` WebSocket URL on the **same host and port**, with no query string,
userinfo or fragment; `RelayConfigValidator` refuses to save anything else
and `app/build.gradle.kts` fails the build on the same rules for values in
`secrets.properties`. The validator's messages name the field
(`"Relay URL must be a https:// URL with a host (the app only speaks TLS;
cleartext is not allowed)."`), never the value.

### 3. Credentials go to one origin and nowhere else

`RelayOrigin` (`Imsg.kt`) is the relay's scheme + host + port, compared on
the **parsed** URL, never a string prefix, so look-alike hosts, userinfo
tricks, other ports and plain `http` all fail `isRelayUrl`.

Two OkHttp interceptors are the only code that touches credentials:

- `relayAuthInterceptorFor` (application interceptor) first **removes** any
  `X-Imsg-Token`, `CF-Access-Client-Id` and `CF-Access-Client-Secret`
  header from the outgoing request, then adds them back only when the URL is
  the relay origin. The Cloudflare pair is added only when both values are
  set. It takes one snapshot of the configuration per request and pins it on
  the request, so a Save in Settings mid-flight can never pair a new token
  with an old host.
- `relayAuthStripInterceptorFor` (network interceptor) runs on **every hop**,
  including redirects, and strips the three headers from any hop that is not
  the relay origin. OkHttp keeps custom headers across a cross-host redirect
  on its own (it drops only `Authorization`), which is why this exists.

Every client in the app is built from these: the shared `http` client, the
upload client, Coil's image loader (`MainActivity.kt`), ExoPlayer's
`OkHttpDataSource` (`VideoPlayer.kt`), the throwaway client "Test
connection" uses (`RelayProbe.kt`) and the WebView proxy for the relay's own
map page (`MapScreen.kt`, `RelayWebAssets`). The probe client, the WebSocket
client and the WebView proxy never follow redirects at all.

Covered by JVM unit tests under `app/src/test/`: `RelayOriginTest`,
`RelayConfigTest`, `RelayConfigStoreTest`, `RelayProbeTest`,
`RelayWebAssetsTest`.

### 4. WebSocket authentication by header, never in the URL

`relayWsRequest` (`Imsg.kt`) builds the upgrade request from the saved
WebSocket URL and returns null unless that URL is on the relay origin;
the token travels as `X-Imsg-Token` on the upgrade through the same
interceptor. The relay accepts `?token=` for other clients, but this app
never produces a URL carrying a credential, and the validator refuses a
WebSocket URL with a query string for that reason.

### 5. Where the credentials live on the phone

- **Build-time values** from `secrets.properties` become `BuildConfig`
  fields, i.e. plain strings inside the APK. `.gitignore` excludes
  `secrets.properties`, `*.apk`, `*.aab`, `google-services.json` and
  `*.bak`; treat a built APK as a copy of your token.
- **Typed values** (Settings → Relay) are stored in the private
  `SharedPreferences` file `relay_config` (`RelayConfig.kt`,
  `RelayConfigStore`), and only the fields that differ from the build's
  values are written. The feature switches share that file.
- **Texts not yet delivered** are stored in a third private file,
  `outbox` (`Outbox.kt`): every text you send into a conversation is
  written there when you tap Send (or send a reply from a notification),
  before its request starts, and removed when the relay answers that it was
  delivered, normally a second or two later. A text that was not delivered
  stays there, with its chat's identifier and name, until you send it again
  or discard it, so that it cannot be lost with the app's process. (The
  first text of a new conversation is not stored: it stays in the New
  Message screen until the relay answers.) This is the only message text
  the app writes to its preferences.
- `relay_config.xml`, `app_prefs.xml` (the lock and shortcut
  settings, including the chat identifiers of published conversation
  shortcuts, and a SHA-256 digest of the relay address once a send has
  gone through BlueBubbles) and `outbox.xml` are **excluded from Auto
  Backup and from device-to-device transfer**:
  [`app/src/main/res/xml/backup_rules.xml`](app/src/main/res/xml/backup_rules.xml)
  for devices below API 31 and
  [`app/src/main/res/xml/data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml)
  (`<cloud-backup>` and `<device-transfer>`) for API 31+. The rest of the
  app's data stays eligible for backup (`android:allowBackup="true"`), but
  the message history itself is never written there: message text is kept
  in memory and fetched from the relay (the outbox above is the one
  exception, and it is excluded), and downloaded attachments sit in
  the app's cache directory, which Android does not back up.

The two ways of configuring the app are not equally protected. A typed
value sits in the app's private storage and is excluded from backup and
transfer. A value baked in at build time sits in the installed APK, which
stays on the phone as a file other apps can read (see
[What is not protected](#what-is-not-protected)). If that matters to you,
build with an empty `secrets.properties` and type the values in the app.

The preferences file is ordinary private app storage protected by the
Android sandbox and the device's file-based encryption; it is not
additionally encrypted by the app, and the app lock does not encrypt it
either (see below).

To remove the credentials from a phone, uninstall the app or clear its
storage. "Reset to build defaults" in Settings → Relay removes only the
typed values; a token baked in at build time stays in the APK until the app
is uninstalled.

### 6. Nothing prints a credential

- `RelayConfig.toString()` prints `token=set` / `unset`, never the value.
- `RelayProbe` classifies the `/health` response by its JSON keys and
  **never echoes the body** (a server, or whatever sits in front of it, may
  repeat the request headers back). Its outcomes are fixed strings:
  `Connected · relay protocol N · engines: … · features: …`,
  `The relay rejected the token`,
  `Cloudflare Access rejected the request — check the service-token id and secret`,
  `No trusted certificate at this address; the app only speaks HTTPS`,
  `Can't reach <host>` (the typed host),
  `HTTP <code> from that address`,
  `Not a relay: unexpected reply`,
  `Relay URL must be an https:// URL with a host`,
  `The token or the Cloudflare values contain characters a header cannot carry`,
  or `Connection failed (<exception class>)`. An exception's own text is
  never shown, because OkHttp's header-validation error quotes the offending
  value. The only text taken from the response is the engine and feature
  names in the `Connected` line, and only when they are plain identifiers
  (letters, digits, `_` and `-`, at most 24 characters).
- A token or Cloudflare value outside printable ASCII cannot be saved
  (`RelayConfigValidator.isHeaderSafe`), and the interceptor converts the
  `IllegalArgumentException` OkHttp would throw for one into an
  `IOException`, because that exception would otherwise kill the process
  with the credential in the crash log.
- The map WebView's diagnostics (written by `MapScreen.kt` through
  `sanitizeDiagnostic` and `safeUrl` in `MapDiagnostics.kt`) strip query
  strings and fragments from URLs and redact `Bearer …` values and
  JWT-shaped strings before logging.

### 7. App lock and secure window

`AppLock.kt`: a `BiometricPrompt` gate in front of `MainActivity`, accepting
`BIOMETRIC_STRONG | DEVICE_CREDENTIAL` on Android 11+ (`BIOMETRIC_WEAK |
DEVICE_CREDENTIAL` below, where the library forbids the STRONG combination),
so the phone's PIN / pattern / password is always a fallback. The switch is
Settings → App lock → "Require unlock". It is **on by default** on a fresh
install when the phone can authenticate at all; it re-locks after a grace
period of Immediately / 1 / 5 / 30 minutes in the background ("Re-lock
after", default 1 minute). Turning it off requires authenticating.
Transient errors from `BiometricManager` keep the lock in force rather than
letting content through.

Settings → Privacy → "Hide in Recents and block screenshots" sets
`WindowManager.LayoutParams.FLAG_SECURE` on the main window
(`applySecureWindow`), applied before the first frame: a blank card in
Recents, and no screenshots or screen recording of the app. It is off by
default.

Conversation shortcuts put the names and photos of your pinned and recent
chats in the share sheet and on the launcher icon's long-press menu, outside
the app and its lock, and they are **on by default** on a fresh install
(`AppPrefs.init`; they start off only when the secure-window switch is
already on). Turn them off with Settings → Privacy → "Show recent chats in
the share sheet and launcher". A chat already dragged to the home screen
stays there, disabled, until you remove it yourself.

### 8. The map WebView

The optional family map loads a page you name in a WebView with JavaScript
and DOM storage on, file and content access off, mixed content never
allowed, no cache, and remote debugging only in debuggable builds. Requests
the WebView makes **to the relay origin, by GET** are proxied through the
shared `http` client so they carry the relay headers; everything else
(Apple's MapKit CDN, a map hosted elsewhere) loads with no credentials.
`RelayWebAssets.shouldProxy` is the whole decision and is unit-tested.

### 9. Optional push, and what it carries

Firebase push is active only when you build with your own
`app/google-services.json`. The Firebase library is always compiled in, but
without that file it never initialises and the app relies on its WebSocket
while open (`app/build.gradle.kts`; `PushService.kt`, where
`Push.settingsNote` explains which in Settings). When push is active, the
app registers its FCM token with the relay (`POST /register_push`) each time
it starts, each time a Save or a Reset changes the relay settings, and each
time its live connection to the relay opens.

A push is a data message built by the relay: the chat identifier (for a
one-to-one chat that is the other person's phone number or email address),
the chat name, the sender, up to 300 characters of text, the message's row
number and id and, for a picture, the relay-relative path of the first image
(no hostname, no token). A FaceTime ring carries the call id and the
caller's address and name. It travels over TLS, but Google can read it: it
is not end-to-end encrypted. Leave `FCM_CREDS` unset on the relay if you do
not want that.

### 10. Permissions

Declared in the manifest: `INTERNET`; `POST_NOTIFICATIONS`; `READ_CONTACTS`,
used only to show photos from the phone's address book next to chats, in
notifications and on conversation shortcuts (`ContactPhotos.kt` makes no
network calls; the address book is not sent to the relay);
`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` for the attachment picker's
recent-photos grid, and `READ_EXTERNAL_STORAGE` for the same grid on Android
12 and older only (`maxSdkVersion="32"`; it is never requested on Android 13
and newer).

Libraries add more to the built APK, so the installed app lists them too:
`ACCESS_NETWORK_STATE`, `WAKE_LOCK` and
`com.google.android.c2dm.permission.RECEIVE` (Firebase Messaging),
`USE_BIOMETRIC` / `USE_FINGERPRINT` (the app lock) and
`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX's own
guard for non-exported receivers). None of them is a runtime permission
prompt.

Attachments are handed to other apps through a non-exported `FileProvider`
with temporary read grants, so no other app ever needs the token.

## What is not protected

- **Anyone who can unlock the phone.** The app lock is a gate in front of
  the main screen, not encryption. With the lock off, or during its grace
  period, the whole history is one tap away. The same person can open
  Settings → Relay and tap **Show** to read the relay token and the
  Cloudflare secret, with no second prompt: if someone you do not trust has
  had the unlocked app in hand, rotate the token on the relay.
  Notifications show message text, and replying from the notification shade
  is not locked (Settings says so; hide previews with the phone's
  lock-screen notification setting). The notification about a text that
  was not sent names its chat and never carries the text. Conversation shortcuts show chat names
  and photos outside the lock (control 7). `VoiceActivity` (the "Voice Text"
  launcher entry) and the FaceTime answer screen are not gated either.
- **`adb` and logcat.** The build this project documents is a debug build
  installed over USB (or wireless) debugging; `app/build.gradle.kts` has no
  release signing configuration. On a debug build, any computer authorised
  for USB debugging can read the app's private storage without root (`adb
  shell run-as <applicationId> cat shared_prefs/relay_config.xml`, which is
  the typed token and Cloudflare pair), pull the APK with its baked-in
  values, and attach a debugger to the map WebView. The app also writes
  debug lines to logcat. Its own lines carry no message content: a
  WebSocket frame is logged as its type and length (`wsFrameLogLine` in
  `Imsg.kt`, tag `ImsgWS`), a failure as the class of the error
  (`failureLabel`). Outside the family map, no line names a chat
  identifier, a sender, a host or a search term. With the map open, lines
  tagged `FamilyMap` (`MapScreen.kt`) name the address of the map page and
  of each resource it reports, as scheme, host and path (when the map is
  the relay's own `/map`, that is the relay's host), the page's title and
  status, and the page's console messages. `safeUrl` and
  `sanitizeDiagnostic` (`MapDiagnostics.kt`) cut every `http(s)://` and
  `ws(s)://` URL at the query string and drop a `user:password@` in front
  of the host, cut the query string off a relative URL, and mask bearer
  tokens, JWT-shaped strings and the value of a `token=`, `key=`,
  `secret=`, `password=`, `auth=`, `signature=` or `session=` pair; what
  is left is whatever that page prints. Libraries the app uses (the HTTP
  client, the video player, Firebase) may write lines of their own, which
  the app does not filter. Release builds do not strip the app's lines
  (`app/build.gradle.kts` disables code shrinking). After installing,
  turn USB debugging and wireless debugging off again and use Developer
  options → Revoke USB debugging authorisations. A raw logcat holds every
  other app's lines too: for an issue, attach only the app's own tags
  (`adb logcat -s ImsgWS Imsg Push FamilyMap`), and read them through
  first.
- **A rooted phone, an authorised `adb` host on a debug build, or a backup
  extracted by other means.** `relay_config` is plaintext inside the app's
  private storage; the exclusions above cover Android's own backup and
  transfer paths, nothing more. If the phone is lost, rotate the relay
  token (and the Cloudflare pair) on the relay side: see
  [Rotating the token](https://github.com/Avtraang/selfbubbles-relay/blob/main/SECURITY.md#rotating-the-token).
- **Anyone holding the token.** With it and network reach they can read
  everything, send as you, and register their own device for push. The app
  cannot limit that; the relay's
  [SECURITY.md](https://github.com/Avtraang/selfbubbles-relay/blob/main/SECURITY.md#what-the-token-unlocks)
  lists what the token unlocks and how to rotate it.
- **The relay and the Mac behind it.** The app trusts whatever its
  configured relay says and shows it. The Mac holds the real data.
- **Unsent texts on the phone.** A text that was not delivered waits in the
  private `outbox` file (control 5) in plain form, like the relay settings
  beside it, until it is sent again or discarded; whoever can open the
  unlocked app can read it above its chat's composer.
- **Cached attachments on the phone.** Images, videos, PDFs, contact cards
  and notification pictures are downloaded into the app's cache directory
  (`cacheDir/shared`), content shared into the app from another app is
  staged there too, and Coil keeps its own image cache there. Android may
  clear it at any time; clearing the app's storage removes it all.
  Attachments you explicitly save go to the phone's public Pictures /
  Movies / Downloads collections through `MediaStore`, outside the app's
  sandbox.
- **The APK.** If `secrets.properties` was filled in when it was built, the
  APK contains your relay URL, token and Cloudflare pair as plain strings.
  Do not share it or upload it anywhere. The installed APK also stays on the
  phone as a file that other apps can read (that is how APK-backup apps
  work), and a phone-to-phone transfer tool that copies sideloaded apps
  copies it too; the backup exclusions in control 5 do not cover the APK. A
  token baked in at build time is therefore less protected than one typed
  into Settings → Relay.
- **Independent review.** The app was written for one person and has been
  used daily since July 2026, with an AI coding assistant doing much of the
  typing. It has had no independent security review. Read the code before
  trusting it with your messages.
