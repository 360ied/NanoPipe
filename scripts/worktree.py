#!/usr/bin/env python3
# Copyright (C) 2026 PipePipe Contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""
scripts/worktree.py: Helper script for managing Git worktrees in PipePipe.

Worktrees allow developing on dedicated feature/bugfix branches without
modifying the main working tree (which remains permanently on 'main').
"""

import functools
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
from typing import Dict, List, Optional, TextIO, Tuple

# Configure unbuffered/line-buffered I/O so CLI output and child process
# output maintain strict ordering even when piped or redirected.
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except (AttributeError, OSError, ValueError):
        pass
if hasattr(sys.stderr, "reconfigure"):
    try:
        sys.stderr.reconfigure(line_buffering=True)
    except (AttributeError, OSError, ValueError):
        pass
# Line-buffered, flushed emitter. Named to avoid shadowing the print builtin
# for modules that import this file (e.g. test_worktree.py).
_emit = functools.partial(print, flush=True)


def print_usage(stream: TextIO) -> None:
    prog = "./scripts/worktree.py"
    _emit(f"""Usage:
  {prog} add <branch-name> [base-ref] [-p <custom-path>]
  {prog} list (alias: ls)
  {prog} remove <branch-name-or-path> [--force] [--keep-branch] (aliases: rm; -f for --force)
  {prog} cleanup [--dry-run] [--include-unmerged] [--force] [--keep-branch] [<branch-or-path>...]
            (alias: clean; -n for --dry-run, -f for --force)

Commands:
  add       Create a new worktree under .worktrees/<branch-name> (or custom path).
            If base-ref is omitted, it defaults to 'main'.
  list      List all active worktrees and their checked-out branches.
  remove    Safely remove a worktree. Refuses if there are uncommitted changes
            unless --force is specified. The worktree's local branch is deleted
            as well when fully merged into 'main' (safe `git branch -d`);
            pass --keep-branch to preserve it.
  cleanup   Remove secondary worktrees in bulk. Non-interactive and flag-driven:
            by default removes only clean worktrees whose branch is fully merged
            into 'main'. Add --include-unmerged to also remove clean worktrees
            with unmerged commits, and --force to also remove worktrees with
            uncommitted changes. Removing a dirty AND unmerged worktree requires
            both flags. A removed worktree's local branch is deleted as well when
            it is fully merged into 'main' (safe `git branch -d`; protected
            branches, detached checkouts, and unmerged branches are never
            deleted). Add --keep-branch to preserve branches. Use --dry-run to
            preview.

Examples:
  {prog} add feature/extractor-retry
  {prog} add bugfix/player-seek main
  {prog} list
  {prog} remove feature/extractor-retry
  {prog} cleanup --dry-run
  {prog} cleanup --include-unmerged
  {prog} cleanup --include-unmerged --force""", file=stream)


def get_repo_root(cwd: Optional[Path] = None) -> Path:
    """Resolve the root repository directory (common git dir)."""
    res = subprocess.run(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    common_dir = res.stdout.strip()
    if res.returncode != 0 or not common_dir:
        # Fallback: resolve the toplevel first, then ask for the common dir
        # from there. (Returning the toplevel directly would be wrong inside
        # a linked worktree, where toplevel is the worktree path itself.)
        res2 = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        if res2.returncode != 0 or not res2.stdout.strip():
            sys.stderr.write("Error: Not inside a git repository.\n")
            sys.exit(1)
        res3 = subprocess.run(
            ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
            cwd=res2.stdout.strip(),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        if res3.returncode != 0 or not res3.stdout.strip():
            sys.stderr.write("Error: Not inside a git repository.\n")
            sys.exit(1)
        common_dir = res3.stdout.strip()

    # Strip /.git and any trailing worktree path components
    # Equivalent to sed -E 's#/\.git(/.*)?$##'
    root_str = re.sub(r"/+\.git(/.*)?$", "", common_dir)
    return Path(root_str).resolve()



def find_worktree_path(repo_root: Path, target: str) -> Optional[Path]:
    """Find a worktree directory given a path or branch name."""
    target_path = Path(target)
    if target_path.is_dir():
        return target_path.resolve()

    candidate = repo_root / ".worktrees" / target
    if candidate.is_dir():
        return candidate.resolve()

    proc = subprocess.run(
        ["git", "worktree", "list", "--porcelain"],
        cwd=repo_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if proc.returncode == 0:
        current_wt = None
        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line:
                current_wt = None
            elif line.startswith("worktree "):
                current_wt = line.split(" ", 1)[1].strip()
            elif line.startswith("branch "):
                ref = line.split(" ", 1)[1].strip()
                if ref == f"refs/heads/{target}" and current_wt:
                    p = Path(current_wt)
                    if p.is_dir():
                        return p.resolve()
    return None


def cmd_add(args: List[str], repo_root: Path) -> int:
    branch = ""
    base_ref = ""
    custom_path = ""

    idx = 0
    while idx < len(args):
        arg = args[idx]
        if arg in ("-p", "--path"):
            if idx + 1 < len(args):
                custom_path = args[idx + 1]
                idx += 2
                continue
            else:
                sys.stderr.write("Error: --path requires a path argument.\n")
                return 1
        elif arg in ("-h", "--help"):
            print_usage(sys.stdout)
            return 0
        elif arg.startswith("-"):
            sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
            return 1
        else:
            if not branch:
                branch = arg
            elif not base_ref:
                base_ref = arg
            else:
                sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
                return 1
            idx += 1

    if not branch:
        sys.stderr.write("Error: Branch name is required.\n")
        sys.stderr.write("Usage: ./scripts/worktree.py add <branch-name> [base-ref] [-p <path>]\n")
        return 1

    if branch in ("main", "master"):
        sys.stderr.write(f"Error: Cannot create a worktree for '{branch}'. The root tree is dedicated to main.\n")
        return 1

    # Validate the ref name early so malformed input (path traversal,
    # leading dashes, illegal git characters) fails here with a clear
    # error instead of confusing git or worktree-path errors later.
    ref_check = subprocess.run(
        ["git", "check-ref-format", "--branch", branch],
        cwd=repo_root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if ref_check.returncode != 0:
        sys.stderr.write(f"Error: Invalid branch name '{branch}'.\n")
        return 1

    if custom_path:
        wt_path = Path(custom_path)
    else:
        wt_path = repo_root / ".worktrees" / branch

    if wt_path.exists():
        sys.stderr.write(f"Error: Target directory '{wt_path}' already exists.\n")
        return 1

    _emit("========================================")
    _emit(" 1. Creating Git Worktree")
    _emit("========================================")
    _emit(f"Branch: {branch}")
    _emit(f"Path:   {wt_path}")

    try:
        res_local = subprocess.run(
            ["git", "show-ref", "--verify", "--quiet", f"refs/heads/{branch}"],
            cwd=repo_root,
            check=False,
        )
        if res_local.returncode == 0:
            _emit(f"Branch '{branch}' already exists locally. Checking out in worktree...")
            subprocess.run(["git", "worktree", "add", str(wt_path), branch], cwd=repo_root, check=True)
        else:
            res_remote = subprocess.run(
                ["git", "show-ref", "--verify", "--quiet", f"refs/remotes/origin/{branch}"],
                cwd=repo_root,
                check=False,
            )
            if res_remote.returncode == 0:
                _emit(f"Branch '{branch}' exists on origin. Tracking in new worktree...")
                subprocess.run(["git", "worktree", "add", "-b", branch, str(wt_path), f"origin/{branch}"], cwd=repo_root, check=True)
            else:
                start_point = base_ref if base_ref else "main"
                _emit(f"Creating new branch '{branch}' from '{start_point}'...")
                subprocess.run(["git", "worktree", "add", "-b", branch, str(wt_path), start_point], cwd=repo_root, check=True)

        # PipePipe has no git submodules or large model blobs to copy,
        # so a fresh worktree is ready after creation.
        # Allow direnv if direnv is installed
        if shutil.which("direnv"):
            subprocess.run(["direnv", "allow"], cwd=str(wt_path), check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        _emit("")
        _emit("========================================")
        _emit(" WORKTREE READY!")
        _emit("========================================")
        _emit(f"Path:   {wt_path}")
        _emit(f"Branch: {branch}")
        _emit("")
        _emit("To start working in this worktree:")
        _emit(f"  cd \"{wt_path}\"")
        _emit("")
        _emit("To verify changes in this worktree:")
        _emit(f"  cd \"{wt_path}\" && ./scripts/check.sh")
        _emit("")
        _emit("To remove when finished:")
        _emit(f"  ./scripts/worktree.py remove \"{branch}\"")
        _emit("========================================")
        return 0
    except subprocess.CalledProcessError as e:
        return e.returncode


def cmd_list(repo_root: Optional[Path] = None) -> int:
    if repo_root is None:
        repo_root = get_repo_root()
    _emit("========================================")
    _emit(" Active Git Worktrees")
    _emit("========================================")
    proc = subprocess.run(["git", "worktree", "list"], cwd=repo_root)
    return proc.returncode


def cmd_remove(args: List[str], repo_root: Path) -> int:
    target = ""
    force = False
    keep_branch = False

    idx = 0
    while idx < len(args):
        arg = args[idx]
        if arg in ("-f", "--force"):
            force = True
            idx += 1
        elif arg == "--keep-branch":
            keep_branch = True
            idx += 1
        elif arg in ("-h", "--help"):
            print_usage(sys.stdout)
            return 0
        elif arg.startswith("-"):
            sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
            return 1
        else:
            if not target:
                target = arg
            else:
                sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
                return 1
            idx += 1

    if not target:
        sys.stderr.write("Error: Worktree branch or path is required.\n")
        sys.stderr.write("Usage: ./scripts/worktree.py remove <branch-name-or-path> [--force] [--keep-branch]\n")
        return 1

    wt_path = find_worktree_path(repo_root, target)
    if not wt_path or not wt_path.is_dir():
        sys.stderr.write(f"Error: Could not find worktree for '{target}'.\n")
        return 1

    if wt_path == repo_root:
        sys.stderr.write("Error: Cannot remove the primary root worktree!\n")
        return 1

    # Refuse to delete the shell's own footing: removing the worktree you are
    # currently inside would unlink the cwd (mirrors the cleanup guard).
    try:
        cwd = Path.cwd().resolve()
    except OSError:
        cwd = None
    if cwd is not None:
        try:
            cwd.relative_to(wt_path)
            sys.stderr.write(f"Error: Cannot remove the worktree you are currently inside ('{wt_path}').\n")
            return 1
        except ValueError:
            pass

    # Check for uncommitted changes (fail closed: an uninspectable
    # worktree is treated as dirty and refused without --force).
    dirty_state = is_worktree_dirty(wt_path)
    if dirty_state is None:
        sys.stderr.write(f"Error: Could not inspect worktree at '{wt_path}'; refusing to remove.\n")
        return 1
    dirty, status_out = dirty_state
    if dirty and not force:
        sys.stderr.write(f"Error: Worktree at '{wt_path}' contains uncommitted changes:\n")
        sys.stderr.write(status_out)
        sys.stderr.write("Commit or stash changes before removing, or use --force.\n")
        return 1

    branch_proc = subprocess.run(
        ["git", "-C", str(wt_path), "rev-parse", "--abbrev-ref", "HEAD"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    branch_name = branch_proc.stdout.strip() if branch_proc.returncode == 0 else ""

    _emit(f"Removing worktree at '{wt_path}'...")
    rc = remove_worktree_dir(repo_root, wt_path)
    if rc != 0:
        return rc

    _emit("Worktree removed successfully.")
    if branch_name and branch_name != "HEAD":
        _emit("")
        if keep_branch:
            _emit(f"Note: Local branch '{branch_name}' has been preserved (--keep-branch).")
        else:
            deleted, reason = delete_merged_branch(repo_root, branch_name)
            if deleted:
                _emit(f"Local branch '{branch_name}' deleted (fully merged into main).")
            else:
                _emit(f"Note: Local branch '{branch_name}' has been preserved ({reason}).")
                if branch_name not in PROTECTED_BRANCHES:
                    _emit("To delete it when fully merged, run:")
                    _emit(f"  git branch -d -- {shlex.quote(branch_name)}")
    return 0


def parse_worktrees(repo_root: Path) -> List[Dict[str, object]]:
    """Parse `git worktree list --porcelain` into worktree records.

    Each record has keys: path (Path), branch (str | None, None when
    detached), detached (bool), locked (bool).
    """
    proc = subprocess.run(
        ["git", "worktree", "list", "--porcelain"],
        cwd=repo_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    entries: List[Dict[str, object]] = []
    if proc.returncode != 0:
        return entries
    current: Optional[Dict[str, object]] = None
    for raw in proc.stdout.splitlines():
        # Only strip the line terminator: worktree paths may legally
        # contain leading/trailing spaces, which a full strip() would eat.
        line = raw.strip() if not raw.startswith("worktree ") else raw.rstrip("\r\n")
        if not line.strip():
            if current is not None:
                entries.append(current)
                current = None
            continue
        if line.startswith("worktree "):
            if current is not None:
                entries.append(current)
            # NOTE: the path segment is used verbatim (no strip) so paths
            # with surrounding whitespace survive; git C-quotes truly
            # exotic paths, which this parser leaves quoted (out of scope).
            current = {
                "path": Path(line.split(" ", 1)[1]).resolve(),
                "branch": None,
                "detached": False,
                "locked": False,
            }
        elif current is not None:
            if line.startswith("branch "):
                ref = line.split(" ", 1)[1].strip()
                if ref.startswith("refs/heads/"):
                    current["branch"] = ref[len("refs/heads/"):]
                else:
                    current["branch"] = ref
            elif line == "detached":
                current["detached"] = True
            elif line.startswith("locked"):
                current["locked"] = True
    if current is not None:
        entries.append(current)
    return entries


def is_branch_merged(branch: Optional[str], repo_root: Path) -> bool:
    """Check whether <branch> is fully merged into local 'main'."""
    if not branch:
        return False
    proc = subprocess.run(
        ["git", "merge-base", "--is-ancestor", "--", branch, "main"],
        cwd=repo_root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    return proc.returncode == 0


# Branches that worktree removal must never delete, even when merged.
PROTECTED_BRANCHES = ("master", "main", "HEAD")


def delete_merged_branch(repo_root: Path, branch: str) -> Tuple[bool, str]:
    """Delete local <branch> when it is fully merged into 'main'.

    Returns (deleted, reason). Deletion uses safe `git branch -d`, which
    refuses unmerged or in-use branches, so a failed delete is reported as
    preserved rather than forced.
    """
    if branch in PROTECTED_BRANCHES:
        return (False, "protected branch")
    if not is_branch_merged(branch, repo_root):
        return (False, "not fully merged into main")
    proc = subprocess.run(
        ["git", "branch", "-d", "--", branch],
        cwd=repo_root,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if proc.returncode == 0:
        return (True, "deleted")
    return (False, "in use or not fully merged")


def removal_branch_note(repo_root: Path, branch: Optional[str], keep_branch: bool) -> str:
    """Delete the branch when merged; describe branch fate (suffix or "")."""
    if not branch:
        return ""
    if keep_branch:
        return f"; branch '{branch}' preserved (--keep-branch)"
    deleted, reason = delete_merged_branch(repo_root, branch)
    if deleted:
        return f"; branch '{branch}' deleted (fully merged)"
    return f"; branch '{branch}' preserved ({reason})"


def preview_branch_note(repo_root: Path, branch: Optional[str], keep_branch: bool) -> str:
    """Predict branch fate for --dry-run (suffix or "", deletes nothing)."""
    if not branch:
        return ""
    if keep_branch:
        return f"; branch '{branch}' would be preserved (--keep-branch)"
    if branch in PROTECTED_BRANCHES:
        return f"; branch '{branch}' would be preserved (protected branch)"
    if not is_branch_merged(branch, repo_root):
        return f"; branch '{branch}' would be preserved (not fully merged into main)"
    return f"; branch '{branch}' would be deleted"


def is_worktree_dirty(wt_path: Path) -> Optional[Tuple[bool, str]]:
    """Check for staged/unstaged/untracked changes (ignored files excluded).

    Returns (dirty, porcelain_output), or None when `git status` itself
    fails (missing/corrupt worktree). Callers must fail closed on None:
    an uninspectable worktree is never treated as clean.
    """
    proc = subprocess.run(
        ["git", "-C", str(wt_path), "status", "--porcelain"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        return None
    out = proc.stdout
    return (bool(out.strip()), out)


def remove_worktree_dir(repo_root: Path, wt_path: Path) -> int:
    """Remove one worktree dir; prune metadata and sweep empty parents.

    Always passes --force to `git worktree remove` so removal works
    even with ignored build output inside. Callers enforce the safety
    policy (clean/merged checks) before invoking this helper. Locked
    worktrees need no explicit check here: git itself refuses them unless
    `--force` is given twice, so a single --force never deletes one.
    """
    wt_path = wt_path.resolve()
    if wt_path == repo_root.resolve():
        sys.stderr.write("Error: Cannot remove the primary root worktree!\n")
        return 1
    try:
        subprocess.run(
            ["git", "worktree", "remove", "--force", "--", str(wt_path)],
            cwd=repo_root,
            check=True,
        )
        subprocess.run(["git", "worktree", "prune"], cwd=repo_root, check=True)

        # Sweep leftover empty dirs under .worktrees/ so `remove` matches
        # `cleanup`: the removed worktree's empty ancestors, sibling empties,
        # and stray chains (e.g. Gradle residue) all go, non-empty dirs stay.
        sweep_empty_worktrees_dir(repo_root)
        return 0
    except subprocess.CalledProcessError as e:
        return e.returncode
    except OSError as e:
        sys.stderr.write(f"Error: Failed to remove worktree at '{wt_path}': {e}\n")
        return 1


def sweep_empty_worktrees_dir(repo_root: Path) -> None:
    """Remove leftover empty dirs under .worktrees/ (e.g. Gradle residue)."""
    worktrees_dir = repo_root / ".worktrees"
    if not worktrees_dir.is_dir():
        return
    # Repeat until fixpoint: os.walk snapshots dirnames, so a single
    # bottom-up pass skips parents whose only children were just removed.
    # Emptiness is re-checked live via iterdir(); never delete non-empty.
    def _is_empty_dir(path: Path) -> bool:
        try:
            return path.is_dir() and not any(path.iterdir())
        except OSError:
            return False

    changed = True
    while changed:
        changed = False
        for dirpath, _dirnames, _filenames in os.walk(worktrees_dir, topdown=False):
            candidate = Path(dirpath)
            if candidate == worktrees_dir:
                continue
            if _is_empty_dir(candidate):
                try:
                    candidate.rmdir()
                    changed = True
                except OSError:
                    pass
    try:
        worktrees_dir.rmdir()
    except OSError:
        pass


def cmd_cleanup(args: List[str], repo_root: Path) -> int:
    dry_run = False
    include_unmerged = False
    force = False
    keep_branch = False
    targets: List[str] = []

    idx = 0
    while idx < len(args):
        arg = args[idx]
        if arg in ("-n", "--dry-run"):
            dry_run = True
            idx += 1
        elif arg == "--include-unmerged":
            include_unmerged = True
            idx += 1
        elif arg == "--keep-branch":
            keep_branch = True
            idx += 1
        elif arg in ("-f", "--force"):
            force = True
            idx += 1
        elif arg in ("-h", "--help"):
            print_usage(sys.stdout)
            return 0
        elif arg.startswith("-"):
            sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
            return 1
        else:
            targets.append(arg)
            idx += 1

    try:
        cwd = Path.cwd().resolve()
    except OSError:
        cwd = None
    resolved_root = repo_root.resolve()

    # Resolve candidate set. All stored paths are resolved once here so
    # later comparisons (root, cwd, worktrees dir) cannot mismatch on
    # symlink-differing spellings (e.g. /tmp vs /private/tmp).
    candidates: List[Dict[str, object]] = []
    if targets:
        seen: set[str] = set()
        for target in targets:
            wt_path = find_worktree_path(repo_root, target)
            if not wt_path or not wt_path.is_dir():
                sys.stderr.write(f"Error: Could not find worktree for '{target}'.\n")
                return 1
            resolved = str(wt_path)
            if resolved in seen:
                continue
            seen.add(resolved)
            if wt_path == resolved_root:
                sys.stderr.write("Error: Cannot remove the primary root worktree!\n")
                return 1
            branch_proc = subprocess.run(
                ["git", "-C", str(wt_path), "rev-parse", "--abbrev-ref", "HEAD"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False,
            )
            branch = branch_proc.stdout.strip() if branch_proc.returncode == 0 else ""
            if branch == "HEAD":
                candidates.append({
                    "path": wt_path, "branch": None,
                    "detached": True, "locked": False,
                })
            else:
                candidates.append({
                    "path": wt_path, "branch": branch or None,
                    "detached": False, "locked": False,
                })
        # Fill in locked flags from porcelain when targets were explicit.
        for entry in parse_worktrees(repo_root):
            for cand in candidates:
                if entry["path"] == cand["path"]:
                    cand["locked"] = entry["locked"]
                    if cand.get("branch") is None and entry.get("branch"):
                        cand["branch"] = entry["branch"]
    else:
        for entry in parse_worktrees(repo_root):
            if entry["path"] == resolved_root:
                continue
            candidates.append(entry)

    if not candidates:
        _emit("No secondary worktrees found. Only the root worktree exists.")
        return 0

    removed: List[str] = []
    preserved: List[str] = []
    failed: List[str] = []
    explicit = bool(targets)

    for cand in candidates:
        wt_path = cand["path"]
        assert isinstance(wt_path, Path)
        branch = cand.get("branch")
        branch = str(branch) if branch else None
        label = branch or str(wt_path)
        detached = bool(cand.get("detached"))
        locked = bool(cand.get("locked"))

        if cwd is not None:
            try:
                cwd.relative_to(wt_path)
                msg = f"{label}: preserved (cannot remove the worktree you are currently inside)"
                _emit(msg)
                preserved.append(label)
                if explicit and not dry_run:
                    failed.append(label)
                continue
            except ValueError:
                pass

        if locked:
            msg = f"{label}: preserved (worktree is locked; unlock first)"
            _emit(msg)
            preserved.append(label)
            if explicit and not dry_run:
                failed.append(label)
            continue

        dirty_state = is_worktree_dirty(wt_path)
        if dirty_state is None:
            # Fail closed: an uninspectable worktree is never treated as clean.
            _emit(f"{label}: preserved (could not inspect working tree; treating as dirty)")
            preserved.append(label)
            if explicit and not dry_run:
                failed.append(label)
            continue
        dirty, _ = dirty_state
        merged = is_branch_merged(branch, repo_root)
        state = f"{'merged' if merged else 'unmerged'},{'dirty' if dirty else 'clean'}"
        if detached and not branch:
            state += ",detached"

        allowed = (merged or include_unmerged) and (not dirty or force)
        if not allowed:
            reasons = []
            if not merged and not include_unmerged:
                reasons.append("unmerged (add --include-unmerged)")
            if dirty and not force:
                reasons.append("dirty (add --force)")
            if dry_run:
                _emit(f"{label}: would preserve ({state}; {'; '.join(reasons)})")
            else:
                _emit(f"{label}: preserved ({state}; {'; '.join(reasons)})")
                preserved.append(label)
                if explicit:
                    failed.append(label)
            continue

        if dry_run:
            _emit(f"{label}: would remove ({state}){preview_branch_note(repo_root, branch, keep_branch)}")
            continue

        _emit(f"Removing worktree at '{wt_path}'... ({state})")
        rc = remove_worktree_dir(repo_root, wt_path)
        if rc == 0:
            _emit(f"{label}: removed ({state}){removal_branch_note(repo_root, branch, keep_branch)}")
            removed.append(label)
        else:
            sys.stderr.write(f"Error: Failed to remove worktree at '{wt_path}'.\n")
            failed.append(label)

    if not dry_run:
        subprocess.run(["git", "worktree", "prune"], cwd=repo_root, check=False)
        sweep_empty_worktrees_dir(repo_root)

    _emit("")
    if dry_run:
        _emit("Dry run: no worktrees removed.")
    else:
        _emit(f"Removed {len(removed)} worktree(s).", end="")
        if removed:
            _emit(f" [{', '.join(removed)}]", end="")
        _emit()
        if preserved and not explicit:
            _emit(f"Preserved {len(preserved)} worktree(s): [{', '.join(preserved)}]")
        if keep_branch and removed:
            _emit("Note: local branches were preserved (--keep-branch).")

    if failed:
        return 1
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    if argv is None:
        argv = sys.argv[1:]

    if not argv:
        print_usage(sys.stderr)
        return 1

    subcmd = argv[0]
    rest = argv[1:]

    if subcmd in ("-h", "--help"):
        print_usage(sys.stdout)
        return 0

    if subcmd == "add":
        repo_root = get_repo_root()
        return cmd_add(rest, repo_root)
    elif subcmd in ("list", "ls"):
        repo_root = get_repo_root()
        return cmd_list(repo_root)
    elif subcmd in ("remove", "rm"):
        repo_root = get_repo_root()
        return cmd_remove(rest, repo_root)
    elif subcmd in ("cleanup", "clean"):
        repo_root = get_repo_root()
        return cmd_cleanup(rest, repo_root)
    else:
        sys.stderr.write(f"Error: Unknown command '{subcmd}'.\n")
        print_usage(sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
