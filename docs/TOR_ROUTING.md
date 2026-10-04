# Tor routing verification

Verified locally on October 4, 2026. Tor uses upstream
[Orbot](https://orbot.app/en/download/); no Orbot fork is required.

## Routing contract

`AppNetwork` restores the saved route before NewPipe or update work starts.
Every production HTTP request uses its shared, dynamic call factories:

| Traffic | Factory profile |
| --- | --- |
| YouTube video/playlist extraction | EXTRACTOR |
| Playback, HLS/DASH manifests, keys and segments | PLAYBACK |
| Encrypted offline media downloads | OFFLINE |
| SponsorBlock | SPONSOR |
| Manual and background update checks/metadata | UPDATE |

Tor mode selects only a SOCKS proxy at `127.0.0.1` with a configurable port
(default 9050). Destination names remain unresolved until they reach SOCKS;
the local DNS callback rejects requests. Proxy failure cannot select a direct
route. Normal mode preserves the platform proxy selector.

Route changes save preferences synchronously, invalidate retained calls, cancel
requests and open response bodies, and replace connection pools. Online playback
stops and requires another Play action. Activity observers keep other app windows
and indicators synchronized. Encrypted offline playback can continue locally.
The online media source uses only OkHttp, including rejection of UDP URLs.

The HTTPS Tor Project check verifies a Tor exit for that request, without
displaying or logging its IP. It is a manual check, not continuous monitoring.
Browser downloads, report sharing and About links explicitly explain the other
app's independent connection before handoff while Tor is enabled.

## Evidence

- All **219 JVM tests** passed, including eight router tests and existing
  extractor interruption and offline redirect-boundary tests.
- Debug and release lint passed. Debug and minified release APK assembly passed.
- **10 deterministic phone tests** passed on Samsung SM-S938U, Android 16/API 36:
  all five profiles sent domain names to SOCKS with zero local DNS calls; a
  stopped proxy could not reach a direct-connection sentinel; stale calls were
  rejected; open HTTP/Media3 body reads were cancelled on route changes;
  settings persisted; foreground indicators updated after external mode changes;
  report handoff required confirmation; and the production player rejected UDP.
- The live Orbot test verified HTTPS Tor exits through both the Tor-check and
  playback factories. An intermittent SOCKS general failure during an earlier
  attempt failed closed; a subsequent live check passed.
- In the running app with Tor enabled, the YouTube video `aqz-KE-bpKQ` (Blender
  Foundation's Big Buck Bunny) resolved and reached Media3's ready state at 1080p.

Phone validation used the separate debug package; its preview and instrumentation
apps were removed after testing. These local results do not cover hosted CI or a
published release. A complete production offline download was not exercised on
the phone because the debug build intentionally disables that feature. Its
routing and redirect safeguards were verified in the tests above. Live HLS/DASH
playback was not separately exercised.

## Repeat the checks

Use JDK 21 and the configured Android SDK:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class app.plyvanta.network.AppNetworkInstrumentedTest,app.plyvanta.network.TorSettingsInstrumentedTest,app.plyvanta.playback.OnlineMediaSchemeInstrumentedTest \
  app.plyvanta.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Start Orbot and wait for its Tor connection before the opt-in live check:

```sh
adb shell am instrument -w \
  -e class app.plyvanta.network.RealOrbotInstrumentedTest#verifyRealOrbotExit \
  -e verifyRealOrbot true -e orbotPort 9050 \
  app.plyvanta.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Device tests target only the debug package and restore its network preferences.
Cleartext fixture domains are allowed only in the debug resource overlay;
the production network policy and TLS trust remain unchanged.
