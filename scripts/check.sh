#!/usr/bin/env bash
#
# scripts/check.sh: Fast pre-commit and post-implementation verification.
#
# Runs inside a dedicated worktree (see `./scripts/worktree.py add` and
# AGENTS.md "Branching & Worktree Strategy"). Passing verification means the
# branch is ready for user review -- it does NOT authorize merging into
# `main`, pushing, or deleting the worktree.
#
set -eo pipefail

# Run from the repository root so suite paths work regardless of the
# caller's working directory; the root tree and every worktree have their own
# scripts/, flake.nix, and PipePipeClient/.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/scripts/$(basename "${BASH_SOURCE[0]}")"
cd "$ROOT_DIR"

# No flags: there is a single gate (assemble, androidTest assemble, unit
# tests). Old --quick/--full invocations are accepted and ignored so muscle
# memory keeps working.

echo "========================================"
echo " 1. Checking Git Branch"
echo "========================================"
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)
GIT_DIR=$(git rev-parse --git-dir 2>/dev/null || true)
GIT_COMMON_DIR=$(git rev-parse --git-common-dir 2>/dev/null || true)

if [ -z "$CURRENT_BRANCH" ]; then
  echo "ERROR: Not inside a git checkout; run from the repository." >&2
  exit 1
fi

if [ "$GIT_DIR" != "$GIT_COMMON_DIR" ] && [ -n "$GIT_COMMON_DIR" ]; then
  echo "OK: Running inside Git worktree for branch '$CURRENT_BRANCH'."
elif [ "$CURRENT_BRANCH" = "main" ]; then
  echo "WARNING: Currently on 'main' branch! AGENTS.md mandates dedicated worktrees."
else
  echo "OK: On branch '$CURRENT_BRANCH'."
fi

# No submodule check here: PipePipe has no git submodules to initialize.

# Steps 2 and 3 need the dev shell (python3, JDK, Android SDK, patched aapt2).
# Enter it once for the whole run instead of once per step: re-exec this
# script under `nix develop` when invoked from outside. Past the guard
# (already inside, or re-execed as shown by the marker) the steps run
# directly. The marker doubles as the exec-loop guard. Note: if `nix`
# itself is missing, exec fails and `set -e` exits here.
if [ -z "${IN_NIX_SHELL:-}" ] && [ -z "${_CHECK_SH_REEXEC:-}" ]; then
  _CHECK_SH_REEXEC=1 exec nix develop --command "$SCRIPT_PATH" "$@"
fi

echo ""
echo "========================================"
echo " 2. Running Python Unit Tests"
echo "========================================"
python3 -m unittest discover -s "$ROOT_DIR/scripts" -p "test_*.py" -v

echo ""
echo "========================================"
echo " 3. Running Gradle Verification"
echo "========================================"
# The Gradle wrapper lives in PipePipeClient/ (the extractor has its own
# separate Gradle build that check.sh does not cover). GRADLE_USER_HOME is
# resolved from the git common dir by the flake shellHook, so worktrees share
# the root repository's cache.
echo "Running verification (assemble + androidTest assemble + unit tests)..."
# assembleDebugAndroidTest only builds the instrumentation test APK; running it
# would need a device or emulator, so it stays a compile-time check that keeps
# app/src/androidTest from rotting unnoticed.
(cd "$ROOT_DIR/PipePipeClient" && ./gradlew assembleDebug assembleDebugAndroidTest \
  testDebugUnitTest --stacktrace -DskipFormatKtlint)

echo ""
echo "========================================"
echo " ALL CHECKS PASSED SUCCESSFULLY!"
echo "========================================"
