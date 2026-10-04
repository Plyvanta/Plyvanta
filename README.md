# Plyvanta

Plyvanta is an unofficial Android video player for watching a YouTube video or
public playlist without the interruptions normally added around each video. It
resolves direct, content-only media streams instead of embedding the official
YouTube player, then uses community-maintained SponsorBlock timestamps to skip
sponsored sections inside each video.

Plyvanta does not modify the YouTube app and is not a system-wide ad blocker.
Playback must happen inside Plyvanta.

## Install Plyvanta

Production-signed stable builds are available from
[GitHub Releases](https://github.com/Plyvanta/Plyvanta/releases). Download the
APK from the latest non-prerelease release.

Debug previews use the separate package `app.plyvanta.debug`. The stable app is
`app.plyvanta`, so the first stable release installs alongside Preview 4 instead
of updating it in place. Preview settings do not transfer automatically; the
preview can be removed after the stable app is installed and checked.

Open **Settings → Check for updates now** to run an immediate compatible-release
check. Settings shows inline checking, up-to-date, or error status and opens the
existing update prompt when a compatible newer version is found. Independently,
Plyvanta continues to check the public GitHub Releases feed about every six hours
while a network is available. A periodic check posts one Android notification
for each compatible newer version. Tapping the notification opens Plyvanta's
update prompt; **Open GitHub release** then opens the verified release page in
the user's browser. The APK can be downloaded from that page, which remains
available for a retry if the browser's download prompt is canceled. Android can
delay periodic background work, so notifications are update alerts rather than
instantaneous server pushes. Android 13 and later also require the user to allow
notifications.

## What it does

- Accepts a YouTube video or public-playlist URL pasted into the app.
- Opens links shared from another Android app, including YouTube.
- Handles supported YouTube links opened through Android's link chooser.
- Preserves playlist order, starts at a shared video occurrence, advances
  automatically, and provides **Previous** and **Next** controls.
- Plays progressive, separate audio/video, HLS, or DASH media with AndroidX
  Media3.
- Official upstream releases save technically eligible finite videos for authorized
  personal offline playback in an encrypted, device-bound private vault with no
  share, export, cast, backup, or migration path.
- Avoids the ad slots used by the official player by requesting the video's
  direct content stream.
- Fetches crowdsourced SponsorBlock segments and automatically seeks over enabled
  categories.
- Lets you undo a sponsor skip.
- Provides 360p, 720p, 1080p, and 2160p quality limits.
- Provides a user-reviewed bug-report flow with optional app/device diagnostics
  and a separately optional current video link.

Paid sponsor segments are enabled by default. Self-promotion, interaction
reminders, intros, and outros can be enabled from **Settings**.

## Use

1. Install and open Plyvanta.
2. Paste a YouTube video or public-playlist URL and tap **Play**.

Alternatively, choose **Share** in YouTube or another app, then select Plyvanta.
You can also select Plyvanta when Android asks which app should open a supported
YouTube link.

For a playlist, Plyvanta shows its name and the current position, then plays
each available video in order. Use **Previous** or **Next** to move through the
queue. A shared watch link carrying playlist context starts at that video; an
accompanying index disambiguates duplicate occurrences. A playlist-only index
is not used because unavailable entries may be omitted before Plyvanta receives
the public queue.

Plyvanta resolves each video only when it is ready to play, starts its direct
media stream, and loads the available SponsorBlock segments independently. When
an enabled segment is reached, playback seeks to its end and briefly offers
**Undo**.

### Tor routing with Orbot

Install [Orbot](https://orbot.app/en/download/), start it, and wait for it to
connect to Tor. Enable its SOCKS proxy (normally port **9050**). In Plyvanta,
open **Settings → Use Tor via Orbot**, then tap **Check Tor connection**. The
check contacts the Tor Project through that same proxy and reports whether it
observed a Tor exit. **Open Orbot** opens the companion app; **Orbot SOCKS port**
can be changed to match its configuration. Orbot VPN mode is optional; in current
Orbot versions, **General → Power User Mode** allows using its proxy without
turning on the device VPN. Orbot may request its background-service permissions.

Tor routing covers video and playlist lookups, media playback (including HLS
and DASH manifests and segments), SponsorBlock, offline media downloads, and
foreground/background update checks. Destination hostnames are resolved through
the SOCKS proxy. When Tor is enabled, Plyvanta connects only through Orbot on
`127.0.0.1`; if the proxy stops, requests fail with no direct fallback. Changing
the route cancels existing requests and stops online playback; tap **Play** to
start again. The setting persists across app restarts and applies to background
checks from application startup.

Tor can be slower and YouTube may reject Tor exits. It hides the network IP
seen by contacted services; it does not remove identifying information you
share. A successful connection check describes that request, not continuous
monitoring. Browser update downloads, bug reports opened in another app, and
other external links use that app’s own network settings; Plyvanta explains
this before handing off an update or report while Tor is enabled.

### Secure offline playback

#### Why the restrictions are so strict

Plyvanta is already an unofficial client that resolves direct media streams
from a third-party source service. Adding reusable media files increases the
project's copyright and service-terms risk. The applicable service terms may
restrict accessing, reproducing, downloading, or distributing content unless
the service and relevant rights holders permit it. Copyright owners also
generally hold exclusive reproduction and distribution rights, subject to legal
exceptions; the
[U.S. Copyright Office overview](https://copyright.gov/what-is-copyright/)
provides one jurisdiction's summary.

Official Plyvanta therefore offers downloading only for authorized personal
offline entertainment. Personal or noncommercial use is the project's intended
use, not an assurance that a download is permitted. The feature is deliberately
not a general-purpose downloader: device binding, encryption, and the absence of
app-provided playable-file access, export, share, cast, backup, and migration
paths are meant to keep the official app from becoming a convenient way to
redistribute free copies of media that may be protected by copyright.

Plyvanta is open source under GPLv3, so another person can fork the code and
remove these safeguards, subject to the license and applicable law. The
source-code license grants rights in Plyvanta's code, not rights to download or
redistribute third-party media, and a modified fork's choices are not official
upstream policy. That possibility does not make the upstream controls
pointless. Official source, builds, and accepted contributions retain a
meaningful technical and intentional barrier against redistribution. This is a
project policy and risk-reduction measure, not legal advice or a claim that the
controls make any particular download lawful.

While a technically eligible finite video is loaded, tap **Save offline**,
affirm that the platform's terms and applicable law permit you to download that
content, and confirm the device credential.
Keep Plyvanta in the foreground until encryption finishes; leaving the app
cancels the network request and removes the incomplete item. Tap **Downloads**
and confirm the device credential again to list or play saved items. The
library offers only playback and deletion—there is no app-provided
playable-file access, share, save-as, cast, backup, migration, or recovery
action.

Offline storage is deliberately stricter than ordinary streaming. It is
available only in a non-debuggable production build on Android 9 or newer with
a secure device lock, StrongBox security hardware, and the app's integrity
checks passing. Finite progressive streams and finite separate audio/video
pairs are eligible; live, upcoming, HLS, and DASH sources are rejected. If any
required control is unavailable, Plyvanta leaves offline storage disabled
instead of using weaker protection.

Saved tracks are encrypted directly into 256 KiB authenticated chunks in the
app's no-backup private storage. Each item has an independent content key, and
only the same device's non-exportable, StrongBox-backed Android Keystore key can
unwrap it after device authentication. Plaintext media is supplied to Media3
from small authenticated memory buffers and is never written as a playable
file. Leaving the foreground closes active readers and clears their keys and
decrypted buffers.

To report a problem, open **Settings → Report a bug**. The support action and
installed app version appear at the top of Settings so the active build can be
checked immediately. Playback errors also show **Report this issue** next to the
error. Plyvanta previews the complete report before opening a public GitHub
issue or the Android share sheet.

## Requirements

- Android 8.0 (API 26) or later
- Secure offline playback additionally requires Android 9 (API 28), a
  production build, secure device lock, and StrongBox hardware
- JDK 21 for local builds
- Android SDK Platform 36

## Build

On macOS, select JDK 21 and run the checked-in Gradle wrapper:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew clean test lint packageDebugPreview
```

On other operating systems, configure `JAVA_HOME` to a JDK 21 installation and
run the equivalent wrapper command (`gradlew.bat` on Windows). The Android SDK
must contain Platform 36; Android Studio can install it through **SDK Manager**.

The preview packaging task writes a versioned APK so downloads from different
previews cannot be confused. It also writes the release metadata that future
installed versions require before trusting an update:

```text
app/build/outputs/preview/Plyvanta-1.3.0-debug.4.apk
app/build/outputs/preview/Plyvanta-1.3.0-debug.4-update.json
```

Install it on a connected device or emulator with:

```sh
adb install -r app/build/outputs/preview/Plyvanta-1.3.0-debug.4.apk
```

With one emulator or device connected, verify the exact packaged artifact before
distribution:

```sh
scripts/smoke-test-apk.sh \
  app/build/outputs/preview/Plyvanta-1.3.0-debug.4.apk \
  app/build/outputs/preview/Plyvanta-1.3.0-debug.4-update.json \
  f316b684e87b4df6deb4c9fc987e530e7c3fae9810e6a3371b0cc0ea05f179f1
```

The API 36 device test posts the real update notification, follows its content
intent into a cold and already-running activity, and verifies the trusted APK
download intent:

```sh
./gradlew connectedDebugAndroidTest
```

**Immutable releases** are enabled for this repository and must remain enabled.
Each release must include its APK, matching `-update.json`, and `SHA256SUMS`
from the same packaging run. The updater ignores mutable releases and releases
without metadata. It rejects metadata unless its package, preview/stable
channel, monotonic Android `versionCode`, tag, minimum SDK, APK filename,
trusted GitHub URL, and SHA-256 all agree with the immutable published GitHub
asset. Repository trust is anchored to GitHub repository ID `1313062669`, so an
owner or repository rename cannot silently disable update discovery. Future
releases must increment `versionCode` before publication.

An install still on stable `1.1.0` needs one manual update from the official
GitHub Releases page. That immutable version recognizes only the repository's
pre-transfer owner URLs, so its own update check cannot accept `1.2.0` even
though GitHub redirects the API request.

The stable packaging script runs a clean build, both unit-test variants, lint,
release shrinking, production signing, metadata generation, and checksum
generation:

```sh
scripts/package-stable-release.sh
```

On the release Mac, the script reads the protected keystore from Application
Support and its password from macOS Keychain. Other release environments must
set all four variables below:

```text
PLYVANTA_RELEASE_STORE_FILE
PLYVANTA_RELEASE_STORE_PASSWORD
PLYVANTA_RELEASE_KEY_ALIAS
PLYVANTA_RELEASE_KEY_PASSWORD
```

The release keystore is the permanent identity for updating `app.plyvanta`.
Keep an encrypted backup of the keystore and a separately protected backup of
its password; losing either prevents future APKs from updating stable installs.

The task refuses a partially configured signing identity and writes the three
upload-ready assets to:

```text
app/build/outputs/stable/Plyvanta-1.3.0.apk
app/build/outputs/stable/Plyvanta-1.3.0-update.json
app/build/outputs/stable/SHA256SUMS
```

Before distribution, install and exercise the exact packaged artifact:

```sh
scripts/smoke-test-apk.sh \
  app/build/outputs/stable/Plyvanta-1.3.0.apk \
  app/build/outputs/stable/Plyvanta-1.3.0-update.json \
  2085e2b0c5bbd6273203f2aa0064b0f6f291a43746f9989dd0cea30e6cec4d8e
```

## Automated releases

Plyvanta uses Semantic Release on `main`. Conventional `fix:`, `perf:`, and
`revert:` commits publish a patch, `feat:` publishes a minor release, and
breaking changes publish a major release. Documentation, tests, maintenance,
and CI commits do not publish by themselves.

Every automated release runs the Android test, lint, shrink, production-signing,
metadata, checksum, package-integrity, and GitHub attestation checks. It creates
an immutable `vX.Y.Z` GitHub Release containing the versioned APK, matching
update metadata, and `SHA256SUMS`. The bot does not publish debug previews.

See [the automated release guide](docs/RELEASING.md) for the signing-environment
setup, exact version rules, local validation, and failure recovery.

## Architecture

Plyvanta is a single-activity Java Android app:

- `MainActivity` owns the link/share UI, player lifecycle, fullscreen mode,
  settings, playback recovery, and sponsor-skip feedback.
- `YouTubeUrlParser` accepts only trusted YouTube hosts and supported video or
  playlist forms, then returns a typed canonical source.
- `NewPipePlaylistResolver` reads public playlist metadata and stable video-page
  references, preserving order and intentional duplicates across continuation
  pages.
- `PlaylistQueue` chooses the shared starting occurrence and owns deterministic
  Previous/Next positioning. Direct media URLs are never stored in the queue.
- `NewPipeVideoResolver` uses NewPipe Extractor to obtain public video metadata
  and direct media URLs. It selects a progressive stream when available,
  otherwise separate audio/video, HLS, or DASH.
- `OkHttpDownloader` is the network adapter used by NewPipe Extractor.
- `PlaybackSourceFactory` maps resolved streams to Media3 sources and merges
  separate audio and video when necessary, including opaque encrypted offline
  sources.
- `OfflineDownloadManager` accepts only bounded finite media responses from
  trusted HTTPS delivery hosts and streams them directly into an encrypted
  download session.
- `OfflineMediaStore`, `EncryptedChunkFile`, and
  `EncryptedMediaDataSource` own the no-backup vault, authenticated random
  access, lifecycle-bound readers, and atomic item publication. Every activity
  uses one process-wide store so multi-window instances cannot bypass active
  download, playback, reset, or cleanup coordination.
- `DeviceBoundKeyManager` wraps each random item key with a StrongBox-only,
  device-authenticated Android Keystore key; `OfflineSecurityPolicy` disables
  the feature when its fail-closed device and build requirements are not met.
- `PlaybackProtection` installs secure-window, secure-surface, overlay, recents,
  screen-sharing, and audio-capture defenses before protected playback.
- `SponsorBlockClient` hashes the video ID, performs a K-anonymous hash-prefix
  request, validates the matching response locally, and returns normalized skip
  ranges.
- `SponsorSkipController` watches the Media3 playback position and seeks over
  enabled ranges while supporting an undo grace period.
- `PreferenceStore` keeps skip-category and maximum-quality settings in Android
  local preferences.
- `GitHubReleaseClient` reads bounded, anonymous release metadata from the
  official repository and accepts only a compatible APK from an immutable
  release with matching metadata and GitHub SHA-256.
- `UpdateChecker` serializes the validated release fetch-and-store operation
  shared by Settings and `UpdateCheckWorker`.
- `UpdateScheduler` creates unique, network-constrained periodic and immediate
  WorkManager requests. `UpdateCheckWorker` retains notification deduplication
  by Android version code and posts the app-update notification after the shared
  check finds a compatible release.
- `DiagnosticReport` builds a bounded, text-only report from an explicit
  allowlist and defensively redacts links, credentials, email addresses, and
  local paths from technical values.

There is no Plyvanta server. Stream extraction, response validation, playback,
and skipping all happen on the device.

## Privacy

Plyvanta has no user accounts, sign-in flow, analytics, advertising identifier,
or viewing-history database. It stores the selected skip categories, maximum
video quality, whether the update-notification explanation or permission request
has been shown, the last version that produced an alert, a random private token
used only to authenticate notification navigation, and the validated metadata
for a currently available update in local Android preferences. WorkManager also
keeps its scheduling state in the app's private database. A current source URL,
playlist position, video ID, and playback position may be retained temporarily
by Android to restore the activity after a configuration or process-state
change; they are not presented as history. The playlist queue otherwise remains
in memory and contains only public page metadata, never direct media URLs.
Android cloud backup and device-to-device transfer are disabled for Plyvanta's
app data.

Saved offline metadata and media are retained only until the user deletes an
item, resets the vault, clears app data, or uninstalls Plyvanta. They live in
the app's no-backup private directory as authenticated ciphertext and a
device-bound wrapped key; direct media URLs and plaintext tracks are not
persisted. Resetting the vault deletes the wrapping key first, intentionally
making any leftover ciphertext unrecoverable. Plyvanta has no recovery,
escrow, export, or device-migration key.

Bug reports are entirely user-initiated. Plyvanta creates the report locally and
shows its full text before handing anything to another app or website. Technical
details are off by default; if selected, they include only the app version and
package, debug/release status, Android version, device model, locale,
orientation, Plyvanta quality and sponsor settings, playback/source state,
SponsorBlock lookup status, retry status, and a structured error type. They do
not include logs, stack traces, throwable messages, accounts, device
identifiers, network details, titles, uploader names, direct media URLs, or
viewing history. The current canonical YouTube link has its own separate option
and is also off by default.

While the report editor or review is open, Android's saved-instance state may
temporarily retain the bounded draft, the two inclusion choices, the allowlisted
technical snapshot, and the exact preview so an interrupted configuration or
process can restore the user's work. That local, system-managed state is used
only for the in-progress report; it is not added to preferences or a report
history.

Nothing is sent until the user chooses an action from the review screen.
Choosing GitHub sends the reviewed text to GitHub to prefill a public issue that
the user can still edit or abandon. Android asks which browser or app should
handle that action. If a report is too long to prefill safely, Plyvanta opens
the Android share sheet instead of putting report contents in an oversized URL.
Choosing Share also hands the report to an app selected in that share sheet.
GitHub, the selected browser, or the selected sharing app receives only what the
user chose to include and handles it under its own privacy practices.

Playing a link makes these network requests:

- **YouTube and its media hosts:** For a playlist, NewPipe Extractor first
  requests its public metadata and continuation pages. It then requests each
  selected video's public page/player metadata only when that item is ready to
  play, and Media3 requests the selected direct stream. If the user explicitly
  saves an eligible video, Plyvanta requests that selected finite stream again
  while the app remains in the foreground and encrypts the response directly
  into private storage. The source service and its delivery providers therefore
  receive the network address and ordinary request metadata needed to serve the
  playlist and videos.
- **SponsorBlock:** Plyvanta computes the SHA-256 hash of the video ID and sends
  only the first four hexadecimal characters to SponsorBlock's hash-prefix API.
  This places the lookup in a bucket with other video IDs. The full hash is
  not sent; Plyvanta matches the exact video ID inside the returned bucket before
  using any segment. Enabled category names are also included in the request. No
  SponsorBlock request is made when every skip category is disabled.

Separately, WorkManager periodically makes anonymous, read-only requests to
GitHub's public repository and Releases APIs for immutable Plyvanta repository
ID `1313062669`. Choosing **Settings → Check for updates now** explicitly
initiates the same anonymous GitHub requests. Plyvanta resolves the repository's
current canonical name, then downloads a release's small metadata asset from
GitHub and validates it locally. The requests use no GitHub account, token,
device identifier, app usage, video link, or viewing history.

Plyvanta does not proxy these requests, upload an account, or send a local
viewing history to its own service. Network operators and the services contacted
can still observe requests according to their own privacy practices.

## Limitations

- Plyvanta is unofficial and is not affiliated with, endorsed by, or part of
  YouTube, Google, SponsorBlock, or NewPipe.
- It is a separate player. It does not remove ads from the official YouTube app,
  a browser, casting sessions, or other apps.
- YouTube changes its site and playback responses regularly. Extraction can stop
  working until NewPipe Extractor or Plyvanta is updated.
- Private, members-only, paid, DRM-protected, sign-in-required, and
  age-restricted videos are not supported. Region restrictions still apply.
- Private and sign-in-required playlists such as Watch Later are not supported.
  Known unavailable entries are omitted. If a later continuation page fails,
  repeats, or reaches the 100-page safety limit, the entries already loaded
  remain playable and the app labels the queue as partial.
- YouTube Mixes can continue indefinitely, so one load is bounded to 200
  playable entries or 20 pages. Some regions require YouTube consent before a
  Mix can be viewed, which Plyvanta cannot provide without signing in.
- Some live streams or uncommon media formats may not resolve or play.
- Sponsor skipping depends on community submissions. A video may have no
  segments, incomplete segments, or inaccurate timing.
- Direct media URLs expire. Plyvanta retries a failed stream once, but a link may
  still need to be opened again.
- Secure offline storage raises the cost of copying Plyvanta's saved files and
  makes those ciphertext files unusable on another device, but no Android app
  can make displayed or audible media literally impossible to copy. A
  compromised operating system or privileged capture tool may bypass app
  controls, and a camera, microphone, or external capture device can record
  playback. The same public video can also be fetched independently outside
  Plyvanta.
- Offline downloads are unavailable in debug builds and on devices without the
  required StrongBox and integrity posture. Live, HLS, DASH, and unbounded
  sources remain streaming-only. Device loss, app uninstall, key invalidation,
  or storage corruption can make saved items permanently unrecoverable by
  design.
- Update notifications are periodic rather than real-time and can be delayed by
  Android battery, network, app-standby, notification-permission, or force-stop
  behavior.
- Debug previews follow only compatible `app.plyvanta.debug` prereleases.
  Production-signed `app.plyvanta` builds follow non-prerelease stable releases.
- Availability and use of YouTube content remain subject to applicable law and
  the service's terms.

## License and attribution

Plyvanta is distributed under the GNU General Public License, version 3. See
[LICENSE](LICENSE).

That software license grants rights in Plyvanta's source code. It does not grant
permission to download, copy, or redistribute videos, audio, or other
third-party media.

This license is required in particular by the app's use of
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), which is
GPL-licensed. SponsorBlock segment data is provided by
[SponsorBlock](https://sponsor.ajay.app/) under
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).
AndroidX Media3 and OkHttp are used under their respective Apache License 2.0
terms.

See [NOTICE.md](NOTICE.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the complete runtime
dependency and data-source notices. The same legal bundle is embedded in every
APK. YouTube and related marks belong to their respective owners.
