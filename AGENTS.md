# AGENTS.md

## Dev environment (Nix flake)

This repo builds inside the Nix devshell defined in `flake.nix`. Enter it with:

```sh
nix develop
```

The shell provides everything needed — no system JDK, Gradle, or Android SDK required:

- **JDK**: Temurin 25.0.3 (`JAVA_HOME` is set)
- **Android SDK**: platform `android-37.0`, build-tools `36.0.0` + `37.0.0`,
  platform-tools, cmdline-tools (`ANDROID_HOME`/`ANDROID_SDK_ROOT` are set,
  licenses pre-accepted)
- AGP is forced onto the nixpkgs-patched SDK `aapt2` via
  `android.aapt2FromMavenOverride` (the Maven-downloaded `aapt2` cannot run on
  NixOS, so do not remove this override)

Gradle state stays inside the repo: `GRADLE_USER_HOME` points at
`./.gradle-home` (gitignored). Do not use or rely on `~/.gradle`.

## Building

```sh
cd PipePipeClient
./gradlew assembleDebug --stacktrace -DskipFormatKtlint
```

APKs land in `PipePipeClient/app/build/outputs/apk/debug/` (single universal APK).
Always use the Gradle wrapper (`./gradlew`); never a system `gradle`.

## Branching & Worktree Strategy

- **Mandatory Worktrees**: All feature, bugfix, and experimental development MUST be conducted in dedicated Git worktrees; never develop directly on `main`, and avoid switching branches in the root repository. The root repository remains permanently checked out on `main`.
- **Worktree Creation**: Create dedicated worktrees via `./scripts/worktree.py add <branch-name> [base-ref]`. This sets up the working directory under `.worktrees/<branch-name>` (gitignored). If `base-ref` is omitted it defaults to `main`. (Unlike the workflow this was ported from, PipePipe has no git submodules or large model blobs to initialize — a fresh worktree is ready immediately.)
- **Development & Verification**: Perform all code modifications, Gradle builds, and pre-completion verification (`./scripts/check.sh`) inside the dedicated worktree directory (from the repository root: `cd .worktrees/<branch-name>`).
- **Task Completion Boundary (NO AUTONOMOUS MERGING, PUSHING, OR DELETION)**: For code tasks, an agent's task is COMPLETE once changes are committed and verified (`./scripts/check.sh`) inside the dedicated worktree (for standalone documentation tasks on `main`, once committed via `scripts/commit.py` without pushing). Agents must **NEVER autonomously merge into `main`, push, or delete worktrees** upon completing a task. Always leave the branch and worktree intact and unpushed, report completion to the user, and wait for review.
- **Merging into Main (Explicit User Request Only)**: Merging a branch into `main` is a separate, user-initiated action that must be **explicitly requested by the user** (e.g., "merge into main", "land this branch"). Agents must never merge autonomously. When explicitly requested, follow the `pipepipe-merge` skill (`.agents/skills/pipepipe-merge/SKILL.md`).
- **Worktree Teardown (Explicit User Request Only)**: Removing worktrees (single or bulk) must be **explicitly requested by the user**. When requested, follow the `pipepipe-worktree-cleanup` skill (`.agents/skills/pipepipe-worktree-cleanup/SKILL.md`); always preview with `./scripts/worktree.py cleanup --dry-run` first.
- **Documentation Exception**: Standalone documentation changes (such as editing `AGENTS.md`, `README.md`, standalone documentation files under `docs/` (one directory per topic, `README.md` inside), or adding or updating agent skills under `.agents/skills/`) do not need the worktree process and should be made directly on `main`. They are exempt from `./scripts/check.sh` verification. Commit them via `scripts/commit.py`; do not push — report for review. Documentation that directly accompanies active feature or bugfix code development should remain in the corresponding feature worktree.

## Commit Strategy

- **Atomic Commits**: Single logical unit per commit.
- **Working State**: Every code commit must leave the codebase working and passing `./scripts/check.sh` (the gate). Standalone documentation commits on `main` are exempt from this gate.
- **Commit Messages & Detailed Descriptions**:
  - **Commit Wrapper**: Use `python3 scripts/commit.py -m "<message>"` to automatically format to the 50/72 rule, validate the body format (the wrapper hard-fails if the body does not use the three labeled sections below, exactly and in order), and execute `git commit`. Stage changes with `git add` first — the wrapper runs plain `git commit` and only records what's staged.
  - **Subject Line**: Concise and imperative with a scope prefix (e.g., `app:`, `extractor:`, `client:`, `build:`, `nix:`, `docs:`, `util:`; `chore:` for merge commits), max 50 chars (merge commits are exempt from the 50-character limit).
  - **Detailed Body**: Always include a descriptive body separated by a blank line from the subject. Separate the three sections from each other with blank lines so each `Label:` starts its own paragraph:
    ```
    Context & Motivation: <why this change is needed>

    Technical Approach: <how it is implemented>

    Edge Cases & Impact: <boundary conditions, blast radius>
    ```
    Explain:
    - **Context & Motivation**: Why the change is needed and what problem it solves.
    - **Technical Approach**: Architectural decisions, algorithmic details, and notable changes across components.
    - **Edge Cases & Impact**: Handled boundary conditions, defensive checks, or compatibility considerations.
- **Public Repository & Sensitive Information**: This is a publicly accessible repository. Never commit sensitive information, secrets, credentials, API tokens, personal data, physical hardware identifiers (such as device serial numbers, MAC addresses, or private IPs), signing key material, or local machine paths (such as `/home/...` or `file:///...`) to Git history or documentation. (`release.keystore` and `keystore.properties` are gitignored — keep them that way.)
- **Forward-Only History**: Never rewrite, rebase, squash, amend, or force-push commits — pushed or not. Fix mistakes with a new commit.

## Verification

- **No CI (by design)**: this repository has no CI. No GitHub Actions or
other automation runs these gates; enforcement is manual — running
`./scripts/check.sh` before completing a code task is the gate.
- **Pre-Completion Check**: Run `./scripts/check.sh` inside the dedicated worktree before completing any code task. Passing verification signifies that the branch is ready for user review — it does NOT trigger or authorize merging into `main`. Standalone documentation-only changes (committed on `main`) are exempt from `./scripts/check.sh`.
- **Fast Gate** (default): Python unit tests for `scripts/` plus `assembleDebug` + `lintDebug` (`-DskipFormatKtlint`) in `PipePipeClient/`:
  `nix develop --command ./gradlew assembleDebug lintDebug --stacktrace -DskipFormatKtlint` (run from `PipePipeClient/` inside the worktree).
- **Quick Loop** (`./scripts/check.sh --quick`, iteration only): Python unit tests plus `assembleDebug` (single universal APK) with no lint. Use freely while iterating; always run the default Fast Gate once before completing a task.
- **Full Gate** (`./scripts/check.sh --full`): Fast gate plus JVM unit tests (`testDebugUnitTest` in `PipePipeClient/`).

## Versioning

- **Dynamic versionName**: `PipePipeClient/app/build.gradle` resolves
  `appVersionName` at configuration time via
  `git describe --tags --dirty --match "v[0-9]*.[0-9]*.[0-9]*" --match "[0-9]*.[0-9]*.[0-9]*" --always`
  (lazy `providers.exec`, configuration-cache safe), with the leading
  `v` stripped. Never hardcode `appVersionName`. Uncommitted changes
  append `-dirty`; checkouts without git fall back to `0.0.0-unknown`.
- **Release tagging**: tag releases with annotated tags, `v` prefix
  canonical: `git tag -a v<version> -m "Release v<version>"`
  (e.g. `git tag -a v5.5.0-beta4 -m "Release v5.5.0-beta4"`).
  Debug builds append `-debug` via `versionNameSuffix`.
- **APK filename** interpolates the raw tag
  (`NanoPipe_<tag>-<variant>.apk`), not the runtime `versionName`, so
  it never carries `-debug`/`-dirty`; the variant name already
  disambiguates.
- **versionCode**: derived from the release tag in
  `PipePipeClient/app/build.gradle`: `200000 + (major*1000000 +
  minor*1000 + patch)*100 + slot` (`slot` 99 for finals, N for `betaN`
  capped at 98). `-dirty` and describe distance do not affect the code.
  Checkouts with no version info fall back to the `111000` sentinel;
  tag-shaped but non-semver tags fail the build.

## Code Review

For exhaustive line-by-line inspection, follow the `pipepipe-pedantic-code-review` skill (`.agents/skills/pipepipe-pedantic-code-review/SKILL.md`): diff against the fork-point (`main...HEAD`, never `main` tip directly), size single vs. fan-out review, and report `[DEFECT]` / `[PEDANTIC]` tiers with exact file:line links.
