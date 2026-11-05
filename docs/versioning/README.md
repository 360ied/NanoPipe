# Tag-driven versioning

How `versionName` is resolved from git tags (mumla-oled style),
without touching `versionCode`.

- Status: implemented.
- Scope: `PipePipeClient/app/build.gradle` (`appVersionName`,
  `universalVersionCode`), release-tagging workflow.
- Reference implementation: mumla-oled `app/build.gradle`
  (`git describe` via `providers.exec`, leading `v` stripped) and its
  `AGENTS.md` "Versioning" section.
- All paths below are relative to the repository root.

## Background

Before this change, `appVersionName` was a hardcoded string in
`PipePipeClient/app/build.gradle` (then `"5.5.0-beta3"`), and the
repository had no tags. Every build — release, worktree, or
three-commits-after-release — reported the same version in the About
screen, crash reports (`BuildConfig.VERSION_NAME`), and subscription
exports. Releasing meant hand-editing `build.gradle`, and the audit
notes under `docs/*` had to separately pin "audited at commit X, app
version Y" because the version was not derivable from git history.

Mumla-oled avoids this: `versionName` is resolved at configuration
time from `git describe --tags ... --always` with the leading `v`
stripped, so tags are the single source of truth, dev builds
self-identify (e.g. `0.26.1-5-gabc1234`), and releases are just
annotated tags. Its `versionCode`, by contrast, is a frozen constant
(`3070300`, never bumped in history) plus news-items bookkeeping —
vestigial, and not applicable here.

## Design (as implemented)

Ports the `versionName`-from-tag half (plus hardening), and derives
`versionCode` from the same tag — same git dependence, no new failure
modes for version-less checkouts.

### 1. `PipePipeClient/app/build.gradle` (~10 lines)

Replaced the hardcoded string:

```groovy
def baseVersionCode = 1110
def appVersionName = "5.5.0-beta3"
```

with mumla's resolution, verbatim (plus the `-debug` suffix decision
from below):

```groovy
def baseVersionCode = 1110
def gitDescribeRaw = providers.exec {
    // --always: never fail; falls back to a bare commit hash when no
    // tag is reachable (e.g. CI checkout without fetch-tags). The dual
    // --match accepts both v-prefixed and legacy bare tags.
    commandLine "git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--match", "[0-9]*.[0-9]*.[0-9]*", "--always"
}.standardOutput.asText.get().trim()
// Strip the leading 'v' so versionName stays bare semver.
def appVersionName = gitDescribeRaw.replaceFirst(/^v(?=\d)/, "")
```

Hardened after pedantic review: a `--dirty` flag and a `try/catch`
fallback to `0.0.0-unknown` — see the build file for the final form.

`versionCode` is derived from the same `gitDescribeRaw` (mapping:
`200000 + (major * 1000000 + minor * 1000 + patch) * 100 + slot`,
finals take slot 99, `betaN` takes slot N capped at 98). `-dirty` and
`-N-gHASH` distance are stripped first, so dev builds share their base
tag's code. No version info (bare hash, `0.0.0-unknown`) keeps the
`111000` sentinel — below every real code, so real releases always
install as upgrades. Tag-shaped but non-semver tags (e.g. `-rc1`) fail
the build loudly instead of silently shipping the sentinel.

Everything downstream keeps working unchanged: `versionName
appVersionName`, the APK filename
(`NanoPipe_${gitDescribeRaw}-${variant.name}.apk`, raw tag with the `v`
prefix), and all
`BuildConfig.VERSION_NAME` readers (About screen, crash reports,
subscription-export JSON).

Deliberately not ported from mumla:

- `VERSIONTAG` / `split("-")[0]`: would mangle prerelease tags
  (`5.5.0-beta3` splits to `5.5.0`). PipePipe has no `VERSIONTAG`
  consumer, so skip it.
- `versionCode 3070300` and the `XYYZZbb` / news-items bookkeeping:
  no equivalent exists here. Instead `versionCode` is tag-derived (see
  §1), so every release stays distinguishable to installers while
  remaining above all previously shipped codes.

On the configuration-cache concern: the existing comment in
`build.gradle` warns against *eager* git probing at configuration
time. `providers.exec` is Gradle's lazy provider API — the value is
tracked as a build input, not an untracked side effect — which is why
mumla gets away with it under the same
`org.gradle.configuration-cache=true`. Verified with consecutive
cached builds during review: Gradle re-runs the provider when
checking the cache fingerprint, so new commits and tags correctly
invalidate the entry (observed: "cannot be reused because output of
the external process 'git' has changed").

### 2. Tag convention and bootstrapping

`appVersionName` has been `"5.5.0-beta3"` since the repo's squashed
import, and there are no tags. The change shipped with a
genesis anchor tag — created *before* the implementation landed, so
`git describe` resolved from the start:

```sh
git tag -a v0.0.0 -m "Release v0.0.0"
```

`v0.0.0`, not `v5.5.0-beta3`: current `main` was never cut as a
release, so backdating a release tag would rewrite history. Until
the first real release tag, builds honestly report `0.0.0-N-g...`.
After that: `v5.5.0-beta4`, `v5.5.0`, and so on — annotated tags,
`v` prefix canonical, bare match kept only as fallback. Release
process becomes tag plus build. Builds from a tag yield an exact
`versionName`; worktree/dev builds self-identify as
`<tag>-N-gabc1234`.

Also included (decided, not optional): mumla's `versionNameSuffix
"-debug"` in the `debug` block. One line, and it makes
About-screen identification airtight for dev builds. It does not
affect the APK filename (which interpolates the raw describe variable, not
the suffixed name).

### 3. `AGENTS.md` "Versioning" section

Document the mechanism alongside the implementation, mirroring
mumla's section minus the code parts:

```md
## Versioning
- **Dynamic versionName**: resolved at configuration time via
  `git describe --tags --match "v[0-9]*..." --match "[0-9]*..." --always`,
  leading `v` stripped. Never hardcode `appVersionName`.
- **Release tagging**: `git tag -a v<version> -m "Release v<version>"`
  (e.g. `v5.5.0-beta4`). The `v` prefix is canonical.
- **versionCode**: derived from the release tag in
  `PipePipeClient/app/build.gradle` (`200000 + (major*1000000 +
  minor*1000 + patch)*100 + slot`; `slot` 99 for finals, N for `betaN`
  capped at 98). `-dirty` and describe distance do not affect the code.
  Checkouts with no version info fall back to the `111000` sentinel;
  tag-shaped but non-semver tags fail the build.
```

## Verification

This landed as a worktree change, verified before review:

- `./scripts/check.sh --quick`, then the default Fast Gate
  (`assembleDebug` + `lintDebug`).
- Confirm the About-screen version and APK filename from a worktree
  at the anchor tag versus a few commits later (`0.0.0` vs.
  `0.0.0-N-g...`, plus `-debug` for debug builds).
- Two back-to-back `./gradlew assembleDebug` runs to confirm the
  configuration cache stays clean (`problems=warn` surfaces any
  complaint without failing).

## Limitations (accepted, shared with mumla)

- No-tags / shallow-clone builds report a hash version. Fine for dev,
  never ships.
- A source tarball without `.git` (or a missing `git` binary) falls
  back to `0.0.0-unknown` with a logged warning instead of failing
  configuration. F-Droid builds from git, so this path is a
  backstop, not the norm.
- `PipePipeExtractor/build.gradle`'s `version 'v5.4.0'` is a
  separate Maven-style coordinate and out of scope.

## Decisions (resolved)

- Derive `versionCode` from the tag at build time (audit P0-2): the
  build already depends on git history for `versionName`, so this adds
  no new dependence — and keeping it in Gradle (not baked at release
  time) means no human has to remember a number. Distance/`-dirty`
  are ignored so dev↔release reinstalls always succeed.

- Bootstrap with a `v0.0.0` genesis tag rather than backdating
  `v5.5.0-beta3`: current `main` was never a release cut.
- Include the `-debug` `versionNameSuffix`: saving a single line is
  not worth dev builds that look exactly like releases.
