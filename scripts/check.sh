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

# No flags: there is a single gate (assemble + unit tests). Old --quick/--full
# invocations are accepted and ignored so muscle memory keeps working.

echo "========================================"
echo " 1. Checking Git Branch"
echo "========================================"
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
GIT_DIR=$(git rev-parse --git-dir 2>/dev/null || true)
GIT_COMMON_DIR=$(git rev-parse --git-common-dir 2>/dev/null || true)

if [ "$GIT_DIR" != "$GIT_COMMON_DIR" ] && [ -n "$GIT_COMMON_DIR" ]; then
  echo "OK: Running inside Git worktree for branch '$CURRENT_BRANCH'."
elif [ "$CURRENT_BRANCH" == "main" ]; then
  echo "WARNING: Currently on 'main' branch! AGENTS.md mandates dedicated worktrees."
else
  echo "OK: On branch '$CURRENT_BRANCH'."
fi

# PipePipe has no git submodules; nothing to initialize here.

echo ""
echo "========================================"
echo " 2. Running Python Unit Tests"
echo "========================================"
python3 -m unittest discover -s scripts -p "test_*.py" -v

echo ""
echo "========================================"
echo " 3. Running Gradle Verification"
echo "========================================"
# The Gradle wrapper lives in PipePipeClient/ (the extractor has its own
# separate Gradle build that check.sh does not cover). `nix develop` finds
# the flake by walking up to the worktree root, so run it from PipePipeClient/.
echo "Running verification (assemble + unit tests)..."
(cd PipePipeClient && nix develop --command ./gradlew assembleDebug testDebugUnitTest --stacktrace -DskipFormatKtlint)

echo ""
echo "========================================"
echo " ALL CHECKS PASSED SUCCESSFULLY!"
echo "========================================"
