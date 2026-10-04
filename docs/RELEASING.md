# Automated releases

Plyvanta uses Semantic Release to turn Conventional Commits on `main` into
production-signed, immutable GitHub Releases. The stable `v1.0.0` tag is the
release-history baseline.

## Release behavior

After the **Verify** job passes, the release job analyzes every commit since the
latest stable `vX.Y.Z` tag:

- `fix:`, `perf:`, and a conventional `revert:` publish a patch release.
- `feat:` publishes a minor release.
- A `!` after the type or a `BREAKING CHANGE:` footer publishes a major release.
- `build:`, `chore:`, `ci:`, `docs:`, `refactor:`, `style:`, and `test:` do not
  publish a release unless they declare a breaking change.

The GitHub Release body uses the qualifying Conventional Commit subjects as its
reader-facing update explanation, grouped into sections such as **Features** and
**Bug Fixes**. Keep those subjects specific and understandable to users. The
release-tooling tests generate representative notes and fail if the explanatory
entries disappear.

The bot runs only on `main`. Debug previews keep their existing
`vX.Y.Z-debug.N` convention and are not produced by this stable release job.

For a release, Semantic Release calculates the next version and calls the
existing stable Android packaging pipeline. The Android `versionCode` is
deterministic:

```text
major * 1,000,000 + minor * 1,000 + patch
```

Minor and patch components must not exceed 999, and the result must fit
Android's `2,100,000,000` limit. The first possible automated patch,
`1.0.1`, therefore uses version code `1000001`, which is newer than the
manually published `1.0.0` version code 4.

Every release is built with JDK 21 and Android Build Tools 36.0.0. Before a tag
can be created, the job runs unit tests, lint, shrinking, production signing,
metadata generation, checksum generation, APK identity checks, zip-alignment
verification, signature verification, and legal-asset verification.

The GitHub publisher creates a draft, uploads exactly these files, and only then
publishes the release:

```text
Plyvanta-X.Y.Z.apk
Plyvanta-X.Y.Z-update.json
SHA256SUMS
```

Publishing makes the release and tag immutable. The job then verifies GitHub's
signed release attestation, downloads all three assets, compares them byte for
byte with the packaged files, reruns the package checks, and confirms the
published digests. The updater and post-publication verifier anchor repository
trust to immutable GitHub repository ID `1313062669`; an owner or repository
rename must not be handled by replacing one hard-coded slug with another.

## One-time GitHub setup

The workflow's `release` environment must contain the permanent stable signing
identity. Do not use a newly generated key: Android updates must be signed by
the same certificate as `v1.0.0`.

Before Semantic Release can analyze or create a tag, the workflow verifies that
it is running on `main`, resolves GitHub repository ID `1313062669`, requires
the workflow repository to match that ID's canonical owner/name, and passes the
resolved Git URL to Semantic Release. It also verifies that all four signing
secrets are present and the latest stable release is published and immutable.
A missing control fails the job before publication starts. The workflow token
cannot read the repository's administration-only immutability setting itself,
so the new release is checked again immediately after publication.

Create a GitHub environment named `release`, restrict its deployment branches
to `main`, and add these environment secrets:

```text
PLYVANTA_RELEASE_STORE_BASE64
PLYVANTA_RELEASE_STORE_PASSWORD
PLYVANTA_RELEASE_KEY_ALIAS
PLYVANTA_RELEASE_KEY_PASSWORD
```

`PLYVANTA_RELEASE_STORE_BASE64` is the existing PKCS#12 keystore encoded as one
base64 string. For example:

```sh
openssl base64 -A -in /path/to/plyvanta-release.p12 |
  gh secret set PLYVANTA_RELEASE_STORE_BASE64 \
    --repo Plyvanta/Plyvanta \
    --env release
```

Set passwords interactively with `gh secret set NAME --env release` so they do
not appear in shell history. The release job decodes the keystore into its
temporary runner directory with mode `0600` and removes it when packaging
finishes. Pull-request jobs never receive the signing secrets.

Keep all of these repository controls enabled:

- Immutable GitHub Releases.
- Read-only default GitHub Actions token permissions.
- Full-SHA pins for every third-party workflow action.

Protect `main` with required review and the **Verify** check before allowing
qualifying commits to trigger production releases.

## Local validation

Install the locked release tooling and test both the commit rules and Android
version-code mapping:

```sh
npm ci --ignore-scripts
npm run audit:release
npm run test:release
```

The audit gate fails on every unapproved advisory. The lockfile refresh patches
the active `js-yaml` and `undici` dependencies. Two constrained exceptions
remain; neither is a general allowance for high-severity findings:

| Dependency | Why the exception applies |
| --- | --- |
| npm 11.21.0's bundled `brace-expansion`, `http-cache-semantics`, `ip-address`, and `undici` | Semantic Release installs the npm-publishing plugin, but the explicit GitHub-only plugin list disables it. Overrides cannot replace bundled files. The audit launches the host npm through `npm_execpath`, rejects a runner inside this repository's `node_modules`, and never launches the disabled bundle through PATH. |
| `braces` 3.0.3, [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) | No patched version is available. Its sole consumer is micromatch 4.0.8. The pinned Semantic Release branch matcher and commit analyzer use micromatch APIs that delegate to picomatch, bypassing the vulnerable brace walkers. The regression test replaces every braces API with a throwing sentinel and exercises branch expansion plus deeply nested commit input. |

The gate matches the exact GHSA URLs, severities, ranges, installed paths, and
locked versions in `scripts/audit-release-tooling.cjs`. GHSA URLs remain stable
when npm changes numeric advisory IDs. It also checks all dependency consumers,
the explicit plugin list, the plain `main` branch, and the default analyzer
rules. New advisories, additional paths or consumers, and changes to these
conditions fail CI. Remove each exception when upstream ships a fixed bundle
or parser, then recheck the release callers.

An authenticated dry run analyzes the real tag history without building,
tagging, or publishing:

```sh
GH_TOKEN="$(gh auth token)" npm run release -- --dry-run --no-ci
```

To exercise the next-version package locally with the protected macOS signing
setup:

```sh
scripts/package-semantic-release.sh 1.3.0
```

This produces and verifies ignored build outputs only. It does not create a tag
or GitHub Release.

## Failure recovery

Semantic Release creates the tag immediately before its GitHub publishing
step. If uploading an asset fails, GitHub may contain an unpublished draft and
an unprotected tag for that version.

Every workflow run also checks for a stable tag on its triggering commit and
verifies that release independently of Semantic Release. This includes retries
where commit analysis finds no work because the tag already exists, so a
stranded draft or failed publication cannot be hidden by a successful no-op.

Inspect both before changing anything:

```sh
gh release view vX.Y.Z --repo Plyvanta/Plyvanta
git ls-remote --tags origin vX.Y.Z
```

The final workflow step is deliberately read-only. It first proves that the
remote stable tag points exactly to the triggering commit, checks both
published releases and authenticated draft listings, and polls for GitHub to
report immutability. If the tag remains stranded, the release remains a draft,
or the release was published mutable, the job fails with that exact state and
leaves both the release and tag unchanged.

Inspect the release and tag manually before recovery. Never delete or move a tag
whose release became immutable: deleting an immutable release permanently
prevents reuse of that tag name. If immutable publication succeeded but a later
verification check failed, keep the release and investigate the verification
result; a retry will verify that same release instead of publishing another
version. Only remove a proven unpublished draft and its matching tag after
reviewing the failed run and confirming that GitHub never made the release
immutable.
