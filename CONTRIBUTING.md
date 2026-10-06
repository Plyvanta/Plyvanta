# Contributing to Plyvanta

Thank you for helping improve Plyvanta. Contributions of focused code, tests,
documentation, bug reports, and feature proposals are welcome.

Plyvanta is a privacy-conscious Android app with several important trust
boundaries. Please read this guide before opening a pull request, especially if
your change affects networking, diagnostics, updates, dependencies, or release
automation.

## Contents

- [Project scope](#project-scope)
- [Before you start](#before-you-start)
- [Development setup](#development-setup)
- [Repository map](#repository-map)
- [Making a change](#making-a-change)
- [Privacy and security requirements](#privacy-and-security-requirements)
- [Testing](#testing)
- [Commits, versions, and releases](#commits-versions-and-releases)
- [Pull requests](#pull-requests)
- [Licensing and dependencies](#licensing-and-dependencies)
- [Community expectations](#community-expectations)

## Project scope

Plyvanta is an unofficial, standalone player for supported public YouTube
videos and playlists. It resolves direct media streams, plays them with
AndroidX Media3, and uses read-only SponsorBlock data to skip selected in-video
segments.

Changes should preserve these boundaries:

- Plyvanta does not modify the official YouTube app and is not a system-wide ad
  blocker.
- It does not sign in to YouTube, bypass DRM or paid access, proxy user traffic,
  or support private and account-gated content.
- It has no Plyvanta backend, user accounts, analytics, advertising identifier,
  or viewing-history database.
- SponsorBlock integration is read-only. Missing segments or a SponsorBlock
  outage must not prevent normal playback.
- Bug reporting remains user-initiated, fully reviewable, and opt-in.
- Update checks remain anonymous. Plyvanta offers an update only after
  validating immutable official-release metadata and confirming that its
  SHA-256 matches GitHub's reported asset digest.

If a proposal would change one of these boundaries, open an issue before
implementing it. Explain the user problem, why the change belongs in Plyvanta,
and its privacy, security, legal, and maintenance implications.

## Before you start

### Bugs

Search [open and closed issues][issues] before filing a new one. For an app
problem, **Settings → Report a bug** creates a report that you can review before
anything leaves the device.

A useful bug report includes:

- the installed Plyvanta version and whether it is a stable or debug build;
- the Android version and device or emulator model;
- concise steps that reproduce the problem;
- what you expected and what happened instead;
- whether the problem is consistent or intermittent; and
- a public example video, playlist, or Mix URL only when it is necessary and
  you are comfortable sharing it.

Do not post credentials, account data, device identifiers, raw unreviewed logs,
or expiring direct media URLs. If a small log excerpt is essential, remove
unrelated data and redact URLs, tokens, local paths, and personal information
first. This is a manually reviewed attachment; Plyvanta's generated diagnostics
never collect logs.

### Features and larger changes

Open an issue before investing in a large feature, architectural change, new
network service, dependency, permission, or user-data flow. A strong proposal
describes:

- the user problem rather than only a preferred implementation;
- the intended interaction and failure behavior;
- alternatives you considered;
- new data stored or transmitted, including its retention; and
- the tests and documentation the change would require.

Small, uncontroversial fixes can go directly to a pull request.

## Development setup

### Prerequisites

- Git
- JDK 21
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Android SDK Platform-Tools for `adb`
- Android SDK Command-line Tools when using `sdkmanager`
- An API 26 or newer device or emulator for manual testing
- Node.js 24 (`>=24.10.0 <25`) only when changing release automation

The build uses Java 17 source and bytecode compatibility, but Gradle and the
Android toolchain run on JDK 21. Use the checked-in Gradle wrapper; installing a
separate Gradle version is unnecessary.

On macOS, select JDK 21 with:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version
```

If `/usr/libexec/java_home` does not find a Homebrew installation, use:

```sh
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
java -version
```

Confirm that the reported major version is 21 before running Gradle.

Install Platform 36 and Build Tools 36.0.0 through Android Studio's **SDK
Manager**, or with `sdkmanager`:

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

Let Android Studio create the ignored `local.properties` file, or set
`ANDROID_HOME`/`ANDROID_SDK_ROOT` so Gradle can find the SDK. Do not commit
machine-specific SDK paths.

### Check out and build

External contributors can fork the repository and add the main repository as
`upstream`:

```sh
git clone https://github.com/YOUR-ACCOUNT/Plyvanta.git
cd Plyvanta
git remote add upstream https://github.com/Plyvanta/Plyvanta.git
git switch -c fix/short-description  # Example topic-branch name
./gradlew assembleDebug
```

Collaborators with repository access can create a topic branch directly instead
of forking. Branch prefixes are descriptive examples, not a required naming
scheme.

The command blocks in this guide use POSIX shell syntax. On Windows, use
`gradlew.bat` and place multiline commands on one line or translate their
continuations and quoting for PowerShell or Command Prompt.

With one device or emulator connected, install the development build with:

```sh
./gradlew installDebug
```

The debug application ID is `app.plyvanta.debug`, so it can be installed beside
the production app. Local builds and published debug previews use that same
application ID but may have different signing certificates. If Android reports
a signature mismatch, use a clean test device or uninstall the existing debug
preview first; uninstalling removes that preview's local app data.

## Repository map

| Path | Purpose |
| --- | --- |
| `app/src/main/java/app/plyvanta/` | Java application code |
| `app/src/main/res/` | Strings, themes, icons, and Android configuration |
| `app/src/test/` | Fast JVM unit tests |
| `app/src/androidTest/` | Tests that require an Android device or emulator |
| `app/build.gradle.kts` | Android configuration, dependencies, and packaging tasks |
| `scripts/` | Package, release, integrity, and smoke-test tooling |
| `docs/OFFLINE_SECURITY.md` | Mandatory offline-media security contract |
| `docs/RELEASING.md` | Maintainer-facing automated release procedure |
| `.github/workflows/release.yml` | Pull-request verification and main-branch release workflow |
| `NOTICE.md`, `THIRD_PARTY_NOTICES.md`, `licenses/` | Project and dependency attribution |

The app uses a single programmatic `MainActivity`. Keep reusable parsing,
networking, validation, storage, and formatting logic in focused classes rather
than growing activity code unnecessarily. Put user-facing text in
`app/src/main/res/values/strings.xml`.

## Making a change

Start from the current `main` branch and keep each pull request focused. Create
a short, descriptive topic branch; `feat/short-description` below is an example,
not a required prefix:

```sh
git fetch upstream
git switch main
git merge --ff-only upstream/main
git switch -c feat/short-description
```

If a direct clone does not have an `upstream` remote, fetch and fast-forward from
`origin/main` instead.

Match the surrounding style:

- use four-space indentation in Java and avoid unrelated reformatting;
- prefer small, testable classes and explicit validation at trust boundaries;
- keep network and extraction work off the main thread;
- cancel or ignore stale asynchronous work when an activity or playback
  generation changes;
- preserve useful causes internally, but show users concise, actionable errors;
- use Android resources for visible text and content descriptions; and
- add or update tests with every behavior change.

Plyvanta supports Android 8.0 (API 26). A Java API compiling under JDK 21 does
not guarantee that the corresponding Android framework API exists on API 26.
Keep the minimum SDK in mind and let Android lint check every change.

Input, network, and parsing code should:

- accept only the intended schemes, hosts, routes, and data shapes;
- use HTTPS and preserve the no-cleartext network policy;
- bound response sizes, collection sizes, cache sizes, and text fields;
- apply timeouts and handle cancellation;
- reject ambiguous or mismatched data; and
- degrade safely when an optional service is unavailable.

Android intent filters are routing hints, not input validation. Shared and
opened URLs must still pass through `YouTubeUrlParser` before use.

## Privacy and security requirements

Privacy and update integrity are product behavior, not optional polish. Changes
must preserve all of the following.

### Offline media

Any change that creates, reads, plays, lists, or deletes offline media must
follow the complete [`docs/OFFLINE_SECURITY.md`](docs/OFFLINE_SECURITY.md)
contract. Offline behavior is deliberately fail-closed: do not add a weaker
device, key, storage, source-format, recovery, export,
share, cast, or capture fallback when a required protection is unavailable.
Opted-in channel downloads may encrypt finite media in background work, but
workers must never unwrap saved keys, decrypt or list saved vault items, or play
media.
Foreground device authentication remains mandatory to unlock saved items.

This strictness is intentional official-project policy, not just cryptographic
hardening. Media extraction and offline copying add copyright and
platform-terms risk to an already unofficial client. Upstream supports offline
saving only for authorized personal entertainment and deliberately keeps the
official app from becoming a convenient redistribution tool for free copies of
media that may be copyrighted. Personal or noncommercial use alone is not proof
of permission.

The GPL permits forks to modify the code, and a fork can remove these controls
subject to the license and applicable law. Those code rights grant no rights in
downloaded media, and a fork's product choices are not official upstream
policy. That does not justify weakening the barrier in official Plyvanta.
Contributions must preserve both the technical restrictions and the user-facing
explanation of the personal-use, anti-redistribution, and official-upstream
policy. A proposal to remove either belongs outside the official project.

Treat changes to eligibility checks, StrongBox key policy, encrypted file
format, persistent metadata, download lifecycle, deletion, Media3 data sources,
manifest components, backup behavior, or playback output as
security-boundary changes. They require focused negative tests and explicit
review against every invariant and known limitation in the contract.

### Playback and SponsorBlock

- Treat the URLs in `ResolvedVideo` as expiring, sensitive bearer-style links.
  Never log, persist, serialize, or include `videoUrl`, `audioUrl`, or
  `thumbnailUrl` in diagnostics.
- Keep extraction behind the resolver abstraction so upstream breakage can be
  handled without spreading extractor-specific behavior through the UI.
- SponsorBlock lookups must send only the first four hexadecimal characters of
  the raw 11-character video ID's SHA-256 hash, never the full video ID.
- Locally exact-match the returned `videoID`, validate and normalize every
  segment, and treat a 404 or no exact matching video bucket or segments as an
  empty result.
- Discard segments from a malformed, timed-out, or failed lookup while allowing
  playback to continue without skipping.
- When every skip category is disabled, make no SponsorBlock request.

### Diagnostics and user data

- Do not add silent telemetry, crash uploads, analytics, accounts, or viewing
  history.
- Bug reports must remain explicitly initiated by the user.
- Technical diagnostics and the current canonical video link must remain
  separate choices, both off by default.
- Show the complete report and an explicit Edit action before opening GitHub or
  another app.
- Diagnostics must use a bounded allowlist. Never include logs, stack traces,
  throwable messages, credentials, identifiers, network details, titles,
  uploader names, local paths, or direct media URLs.
- Apply defensive redaction only to allowlisted technical values. User-authored
  text and the separately opted-in video link are intentionally not scrubbed,
  which is why the full review step must remain mandatory.
- Do not enable Android cloud backup or device-to-device transfer for app data.

### Updates and exported entry points

- Keep update checks anonymous, read-only, bounded, and restricted to
  `Plyvanta/Plyvanta`.
- Do not weaken validation of release immutability, package name, channel,
  semantic tag, increasing `versionCode`, minimum SDK, asset filename, official
  GitHub URL, update metadata, or SHA-256.
- Treat exported activities and external intents as untrusted. Preserve the
  private token check for update-notification navigation and never trust a
  download URL supplied by an incoming intent.
- Never commit signing keys, passwords, tokens, local configuration, generated
  APKs, or release credentials.
- Keep third-party GitHub Actions pinned to full commit SHAs, retain
  least-privilege/default-deny workflow permissions, and never expose release
  signing secrets to pull-request jobs.

Add focused negative tests whenever a trust check changes. Tests should cover
malformed, oversized, mismatched, stale, and adversarial inputs—not only the
successful path.

## Testing

Run the narrowest relevant test while developing. For example:

```sh
./gradlew testDebugUnitTest \
  --tests 'app.plyvanta.update.GitHubReleaseClientTest'
```

Before opening a code pull request, run the same Android verification used by
GitHub Actions:

```sh
./gradlew --no-daemon --no-configuration-cache \
  clean test lint assembleDebug assembleRelease
```

This checks both unit-test variants, Android lint, the debug build, and the
shrunk release build. It does not require the production signing key.

If a change affects Android notifications, exported intents, lifecycle
navigation, or other device-only behavior, run the instrumentation suite on an
API 36 device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

For APK packaging or update-metadata changes, build a versioned debug preview:

```sh
./gradlew clean test lint packageDebugPreview
```

Then use the exact APK, matching `-update.json`, and debug certificate with the
[`scripts/smoke-test-apk.sh` procedure in the README](README.md#build). The
smoke test installs the package and therefore requires one connected device or
emulator.

Also exercise the changed user flow manually. Record the Android version,
device or emulator, and result in the pull request. For UI work, check light and
dark themes, portrait and landscape where relevant, fullscreen transitions,
and accessibility labels. For compatibility-sensitive work, test both API 26
and a current API when practical.

Changes to `package.json`, `package-lock.json`, `release.config.cjs`,
`.github/workflows/release.yml`, or release scripts also require Node.js 24 and:

```sh
npm ci --ignore-scripts
npm run audit:release
npm run test:release
```

Do not run a real release to validate a pull request. Production packaging
requires protected signing material and publication is owned by the
main-branch workflow.

For documentation-only changes, check every edited link and command and run:

```sh
git diff --check
```

GitHub Actions still runs the full verification job on the pull request.

## Commits, versions, and releases

Use [Conventional Commits][conventional-commits] for commit subjects. Keep the
pull request title in the same format so a squash merge retains the intended
release meaning. Release automation analyzes commits that reach `main`, so the
type must describe the user-visible impact accurately. The pull request title's
release level must match the aggregate highest-impact change in the pull request.

Examples:

```text
fix(playback): recover after an expired stream
feat(settings): add a sponsor-category control
test(updates): reject mismatched asset metadata
docs: clarify emulator setup
```

The release effect is:

- `fix:`, `perf:`, and conventional `revert:` → patch release;
- `feat:` → minor release;
- `type!:` or a `BREAKING CHANGE:` footer → major release; and
- `build:`, `chore:`, `ci:`, `docs:`, `refactor:`, `style:`, and `test:` → no
  release by themselves.

Use the smallest accurate type. Do not label documentation or test-only work as
a product fix merely to trigger a release.

### Required source-version bump

After **Verify** passes, Semantic Release analyzes every release-triggering
commit merged to `main` and publishes the next version automatically. A pull
request that changes shipped behavior must therefore update the app's default
source version in `app/build.gradle.kts`; do not rely only on the version that
release automation injects during packaging.

Choose the next semantic version from the highest-impact Conventional Commit
across all commits since the latest stable tag:

| Highest impact | Version bump | Example from `1.2.3` |
| --- | --- | --- |
| `fix:`, `perf:`, or `revert:` | Patch | `1.2.4` |
| `feat:` | Minor | `1.3.0` |
| `type!:` or `BREAKING CHANGE:` | Major | `2.0.0` |
| `build:`, `chore:`, `ci:`, `docs:`, `refactor:`, `style:`, or `test:` only | No bump | `1.2.3` |

Update the string passed to both Gradle-property defaults:

- `providers.gradleProperty("plyvantaVersionName").orElse("X.Y.Z")`; and
- `providers.gradleProperty("plyvantaVersionCode").orElse("VERSION_CODE")`.

For automated stable releases, `VERSION_CODE` is:

```text
major * 1,000,000 + minor * 1,000 + patch
```

Generate the required Android version code instead of calculating it by hand:

```sh
scripts/semantic-version-code.sh X.Y.Z
```

For example, version `1.2.4` must use version code `1002004`. The major version
must be at least 1, minor and patch must each be between 0 and 999, and the
result must not exceed Android's `2,100,000,000` limit. The manual `v1.0.0`
baseline intentionally used historical version code `4`; the formula applies
to subsequent automated releases.

After assembling the unsigned release build, verify that its manifest reports
the new source `versionName` and `versionCode`. Set `PLYVANTA_ANDROID_SDK` to
the SDK path from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in
`local.properties`:

```sh
export PLYVANTA_ANDROID_SDK=/absolute/path/to/Android/sdk
./gradlew assembleRelease
"$PLYVANTA_ANDROID_SDK/build-tools/36.0.0/aapt" dump badging \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

Do not change `debugPreviewNumber` as part of an ordinary stable-version bump.
Update version-specific filenames and examples in `README.md` or other
documentation when the source bump makes them stale.

Release-bearing merges must be serialized. Before final review:

1. Confirm that the preceding `main` release and stable tag are published,
   immutable, and fully verified.
2. Rebase or fast-forward from that released `main`.
3. Confirm that the source defaults on the base `main` match the latest stable
   release, then make the pull-request branch contain exactly the next version
   calculated from every unreleased commit.
4. Put the expected version name and version code in the pull request
   description so reviewers can compare them with the Conventional Commit
   impact.

If the source defaults on base `main` are ahead of the latest stable tag, a
release is pending or failed. Stop and let a maintainer resolve it using
[`docs/RELEASING.md`](docs/RELEASING.md) before another release-bearing pull
request is merged. Maintainers should not merge the next release-bearing pull
request until the prior immutable release is verified.

If a pull request contains multiple commits, bump once for the aggregate
highest-impact change and use that same release level in a squash-merge title. A
shipped behavior change must not use a non-release type merely to avoid the
required bump.

Documentation, tests, refactoring, maintenance, and CI-only pull requests do not
bump the app version unless they declare a breaking change. Their commits still
run verification on `main`, but current release automation does not publish a
new version for them by themselves.

Do not manually create tags or GitHub Releases, publish APKs, or add generated
release artifacts. Stable releases are production-signed and published
automatically from `main`; see
[`docs/RELEASING.md`](docs/RELEASING.md) for the maintainer procedure.

## Pull requests

Open pull requests against `main`.

In the pull request description:

- explain the problem and the chosen solution;
- link the related issue, if one exists;
- call out privacy, security, network, permission, dependency, or compatibility
  effects;
- list the exact automated checks you ran and their results;
- describe manual testing, including the Android API and device or emulator;
- include before-and-after screenshots or a short recording for visible UI
  changes; and
- note any remaining limitation or follow-up work.

Before requesting review, confirm that:

- the diff is focused and contains no unrelated formatting or generated files;
- new behavior has positive and negative test coverage;
- user-facing text, README guidance, and legal notices are updated when needed;
- a release-bearing change bumps both source version defaults at the correct
  Conventional Commit level;
- the minimum API 26 contract still holds;
- `git diff --check` is clean;
- the GitHub **Verify** check passes and review conversations are resolved;
- no secret, signing material, personal data, raw log, or direct media URL is
  present; and
- the branch is current enough with `main` for CI results to be meaningful.

A passing build is necessary, but it does not override the project's privacy,
security, scope, licensing, or maintainability requirements. Reviewers may ask
for a smaller change, additional tests, or a different design. Maintainers
perform the final merge.

## Licensing and dependencies

Plyvanta is distributed under
[GPL-3.0-only](LICENSE), in part because NewPipe Extractor is GPL-licensed.
Submit only work that you have the right to contribute, and identify any code or
assets derived from another source.

Before adding or updating a runtime dependency:

- explain why existing platform or project code is insufficient;
- identify its license and provenance, and flag any compatibility uncertainty
  for maintainer review;
- review its transitive runtime dependencies and network behavior;
- pin the intended version rather than using a floating version; and
- update `NOTICE.md`, `THIRD_PARTY_NOTICES.md`, and `licenses/` as applicable.

The complete legal bundle is embedded in every APK. Do not remove notices or
assume that a dependency's repository URL is a substitute for required license
text and attribution.

The source-code license does not grant rights to YouTube videos, audio,
metadata, trademarks, SponsorBlock data, or other third-party content. Do not
add copyrighted samples, service credentials, or content snapshots to tests or
documentation unless their use and redistribution rights are clear.

SponsorBlock data is licensed under CC BY-NC-SA 4.0. A change that redistributes
or transforms that data must preserve its attribution, noncommercial, and
share-alike requirements and should be discussed before implementation.

## Community expectations

Be respectful, specific, and patient. Discuss ideas and code without attacking
people, respect privacy, and avoid discriminatory, harassing, or disruptive
behavior. Assume good intent while remaining open to evidence and correction.

[conventional-commits]: https://www.conventionalcommits.org/en/v1.0.0/
[issues]: https://github.com/Plyvanta/Plyvanta/issues
