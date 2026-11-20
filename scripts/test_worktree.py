#!/usr/bin/env python3
"""
Unit tests for scripts/worktree.py.
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# Allow importing worktree directly for unit tests
sys.path.insert(0, os.path.dirname(__file__))
import worktree

SCRIPT_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "worktree.py")
)


class TestWorktreeCLI(unittest.TestCase):
    def test_help_exits_zero(self):
        for flag in ["-h", "--help"]:
            proc = subprocess.run(
                [SCRIPT_PATH, flag],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            self.assertEqual(proc.returncode, 0)
            self.assertIn("Usage:", proc.stdout)
            self.assertIn("add", proc.stdout)
            self.assertIn("remove", proc.stdout)
            self.assertIn("cleanup", proc.stdout)

    def test_no_args_exits_one(self):
        proc = subprocess.run(
            [SCRIPT_PATH],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Usage:", proc.stderr)

    def test_unknown_command_exits_one(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "foobar"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Unknown command 'foobar'", proc.stderr)

    def test_add_missing_branch(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "add"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Branch name is required", proc.stderr)

    def test_add_trunk_disallowed(self):
        for trunk in ("main", "master"):
            proc = subprocess.run(
                [SCRIPT_PATH, "add", trunk],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            self.assertEqual(proc.returncode, 1)
            self.assertIn(f"Cannot create a worktree for '{trunk}'", proc.stderr)

    def test_remove_missing_target(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "remove"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Worktree branch or path is required", proc.stderr)


class TempRepoTestBase(unittest.TestCase):
    """Shared fixture: an isolated temp git repo on `main` with one commit.

    `addCleanup` is armed immediately after creating the TemporaryDirectory
    so the temp dir is removed even if the rest of setUp raises.
    """
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.repo_dir = Path(self.temp_dir.name) / "repo"
        self.repo_dir.mkdir()

        subprocess.run(["git", "init", "-b", "main", str(self.repo_dir)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", str(self.repo_dir), "config", "user.name", "Test Agent"], check=True)
        subprocess.run(["git", "-C", str(self.repo_dir), "config", "user.email", "agent@example.com"], check=True)

        (self.repo_dir / "README.md").write_text("# Mock Repo\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.repo_dir), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.repo_dir), "commit", "-m", "initial commit"], check=True, stdout=subprocess.DEVNULL)

    @property
    def repo_str(self) -> str:
        return str(self.repo_dir)


class TestWorktreeLifecycle(TempRepoTestBase):

    def test_add_list_remove_worktree(self):
        # 1. Add a worktree with nested path
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/deep/nested-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        self.assertIn("WORKTREE READY!", proc_add.stdout)

        expected_wt = os.path.join(self.repo_dir, ".worktrees", "feature", "deep", "nested-test")
        self.assertTrue(os.path.isdir(expected_wt))
        self.assertTrue(os.path.isfile(os.path.join(expected_wt, ".git")))

        # 2. List worktrees
        proc_list = subprocess.run(
            [SCRIPT_PATH, "list"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_list.returncode, 0)
        self.assertIn("feature/deep/nested-test", proc_list.stdout)
        header_pos = proc_list.stdout.find("Active Git Worktrees")
        entry_pos = proc_list.stdout.find("feature/deep/nested-test")
        self.assertGreater(header_pos, -1, "Header 'Active Git Worktrees' not found in stdout")
        self.assertGreater(entry_pos, header_pos, "Header must appear before worktree entries")

        # 3. Refuse removal when dirty without force
        dirty_file = os.path.join(expected_wt, "dirty.txt")
        with open(dirty_file, "w", encoding="utf-8") as f:
            f.write("untracked work\n")

        proc_rm_fail = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/deep/nested-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm_fail.returncode, 1)
        self.assertIn("contains uncommitted changes", proc_rm_fail.stderr)
        self.assertTrue(os.path.isdir(expected_wt))

        # 4. Remove dirty file and test clean removal WITHOUT --force
        os.remove(dirty_file)
        proc_rm_clean = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/deep/nested-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm_clean.returncode, 0, msg=proc_rm_clean.stderr)
        self.assertFalse(os.path.isdir(expected_wt))
        self.assertFalse(os.path.isdir(os.path.join(self.repo_dir, ".worktrees")))

        # 5. Verify the merged branch was deleted with the worktree
        self.assertIn("deleted (fully merged", proc_rm_clean.stdout, msg=proc_rm_clean.stdout)
        branch_check = subprocess.run(
            ["git", "-C", self.repo_dir, "show-ref", "--verify", "refs/heads/feature/deep/nested-test"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertNotEqual(branch_check.returncode, 0, "Merged branch should be deleted")

    def test_add_worktree_needs_no_submodules_or_models(self):
        # PipePipe has no submodules or model blobs: a fresh worktree
        # must come up clean with no extra copy step.
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/plain-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        self.assertIn("WORKTREE READY!", proc_add.stdout)
        wt_path = os.path.join(self.repo_dir, ".worktrees", "feature", "plain-test")
        self.assertTrue(os.path.isdir(wt_path))


class TestWorktreeUnit(TempRepoTestBase):

    def test_get_repo_root(self):
        resolved = worktree.get_repo_root(cwd=self.repo_dir)
        self.assertEqual(resolved, self.repo_dir.resolve())

    def test_find_worktree_path_direct_dir(self):
        self.assertEqual(
            worktree.find_worktree_path(self.repo_dir, str(self.repo_dir)),
            self.repo_dir.resolve()
        )

    def test_find_worktree_path_nonexistent(self):
        self.assertIsNone(
            worktree.find_worktree_path(self.repo_dir, "nonexistent-branch")
        )

    def test_find_worktree_path_repo_isolation(self):
        other_repo = Path(self.temp_dir.name) / "other_repo"
        other_repo.mkdir()
        subprocess.run(["git", "init", "-b", "isolated-branch", str(other_repo)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", str(other_repo), "config", "user.name", "Test Agent"], check=True)
        subprocess.run(["git", "-C", str(other_repo), "config", "user.email", "agent@example.com"], check=True)
        (other_repo / "file.txt").write_text("isolated\n")
        subprocess.run(["git", "-C", str(other_repo), "add", "."], check=True)
        subprocess.run(["git", "-C", str(other_repo), "commit", "-m", "isolated commit"], check=True, stdout=subprocess.DEVNULL)

        # Calling find_worktree_path for self.repo_dir looking for 'isolated-branch'
        # must return None even when the current process working directory is other_repo
        old_cwd = os.getcwd()
        try:
            os.chdir(str(other_repo))
            self.assertIsNone(worktree.find_worktree_path(self.repo_dir, "isolated-branch"))
        finally:
            os.chdir(old_cwd)

    def test_cmd_list_piped_order(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "list"],
            cwd=str(self.repo_dir),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 0)
        header_pos = proc.stdout.find("Active Git Worktrees")
        main_pos = proc.stdout.find("main")
        self.assertGreater(header_pos, -1, "Header 'Active Git Worktrees' not found")
        self.assertGreater(main_pos, header_pos, "Header must precede worktree entries")


class TestWorktreeCleanup(TempRepoTestBase):

    def _commit_on_branch(self, branch, filename, content, message):
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "-b", branch], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(os.path.join(self.repo_dir, filename), "w", encoding="utf-8") as f:
            f.write(content)
        subprocess.run(["git", "-C", self.repo_dir, "add", filename], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", message], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "main"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _add_worktree(self, branch):
        proc = subprocess.run(
            [SCRIPT_PATH, "add", branch],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        return os.path.join(self.repo_dir, ".worktrees", branch)

    def _run_cleanup(self, *args, subcommand="cleanup"):
        return subprocess.run(
            [SCRIPT_PATH, subcommand, *args],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )

    def _branch_exists(self, branch):
        return subprocess.run(
            ["git", "-C", self.repo_dir, "show-ref", "--verify", f"refs/heads/{branch}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        ).returncode == 0

    def test_cleanup_removes_merged_clean(self):
        self._commit_on_branch("feature/merged", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/merged", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/merged")
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/merged: removed (merged,clean)", proc.stdout, msg=proc.stdout)
        self.assertIn("branch 'feature/merged' deleted (fully merged)", proc.stdout, msg=proc.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(self._branch_exists("feature/merged"))

    def test_cleanup_preserves_unmerged_by_default(self):
        self._commit_on_branch("feature/unmerged", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/unmerged")
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/unmerged: preserved (unmerged,clean", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged"))
        # Opt-in flag removes it; branch is still preserved.
        proc2 = self._run_cleanup("--include-unmerged")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertIn("feature/unmerged: removed (unmerged,clean)", proc2.stdout, msg=proc2.stdout)
        self.assertIn("branch 'feature/unmerged' preserved (not fully merged", proc2.stdout, msg=proc2.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged"))

    def test_cleanup_explicit_unmerged_target_fails_without_flag(self):
        self._commit_on_branch("feature/unmerged2", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/unmerged2")
        proc = self._run_cleanup("feature/unmerged2")
        self.assertEqual(proc.returncode, 1, msg=proc.stderr)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged2"))
        proc2 = self._run_cleanup("--include-unmerged", "feature/unmerged2")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged2"))

    def test_cleanup_dirty_needs_force(self):
        self._commit_on_branch("feature/dirty-merged", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/dirty-merged", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/dirty-merged")
        with open(os.path.join(wt, "dirty.txt"), "w", encoding="utf-8") as f:
            f.write("untracked\n")
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dirty-merged: preserved (merged,dirty", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        proc2 = self._run_cleanup("--force")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertIn("branch 'feature/dirty-merged' deleted (fully merged)", proc2.stdout, msg=proc2.stdout)
        self.assertFalse(self._branch_exists("feature/dirty-merged"))

    def test_cleanup_dirty_unmerged_needs_both_flags(self):
        self._commit_on_branch("feature/dirty-unmerged", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/dirty-unmerged")
        with open(os.path.join(wt, "dirty.txt"), "w", encoding="utf-8") as f:
            f.write("untracked\n")
        proc_default = self._run_cleanup()
        self.assertEqual(proc_default.returncode, 0, msg=proc_default.stderr)
        self.assertTrue(os.path.isdir(wt))
        proc_unmerged = self._run_cleanup("--include-unmerged")
        self.assertEqual(proc_unmerged.returncode, 0, msg=proc_unmerged.stderr)
        self.assertTrue(os.path.isdir(wt))
        proc_force = self._run_cleanup("--force")
        self.assertEqual(proc_force.returncode, 0, msg=proc_force.stderr)
        self.assertTrue(os.path.isdir(wt))
        proc = self._run_cleanup("--include-unmerged", "--force")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dirty-unmerged: removed (unmerged,dirty)", proc.stdout, msg=proc.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/dirty-unmerged"))

    def test_cleanup_dry_run_is_noop(self):
        self._commit_on_branch("feature/dry", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/dry", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/dry")
        proc = self._run_cleanup("--dry-run")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dry: would remove (merged,clean)", proc.stdout, msg=proc.stdout)
        self.assertIn("branch 'feature/dry' would be deleted", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/dry"))

    def test_cleanup_explicit_dry_run_skip_exits_zero(self):
        # Previewing a skipped target deletes nothing, so it must not fail.
        self._commit_on_branch("feature/dry-skip", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/dry-skip")
        proc = self._run_cleanup("--dry-run", "feature/dry-skip")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dry-skip: would preserve (unmerged,clean", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/dry-skip"))

    def test_cleanup_unknown_target_fails(self):
        proc = self._run_cleanup("nonexistent-branch")
        self.assertEqual(proc.returncode, 1, msg=proc.stderr)
        self.assertIn("Could not find worktree", proc.stderr)

    def test_cleanup_no_secondary_worktrees(self):
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("No secondary worktrees", proc.stdout)

    def test_cleanup_short_flags_and_alias(self):
        self._commit_on_branch("feature/shorthand", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/shorthand", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/shorthand")
        proc = self._run_cleanup("-n", subcommand="clean")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/shorthand: would remove (merged,clean)", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        with open(os.path.join(wt, "dirty.txt"), "w", encoding="utf-8") as f:
            f.write("untracked\n")
        proc2 = self._run_cleanup("-f", subcommand="clean")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(self._branch_exists("feature/shorthand"))

    def test_cleanup_path_target(self):
        self._commit_on_branch("feature/by-path", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/by-path", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/by-path")
        proc = self._run_cleanup(wt)
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(self._branch_exists("feature/by-path"))

    def test_cleanup_detached_needs_include_unmerged(self):
        wt = os.path.join(self.repo_str, ".worktrees", "detached-one")
        subprocess.run(
            ["git", "-C", self.repo_str, "worktree", "add", "--detach", wt, "main"],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn(",detached", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        proc2 = self._run_cleanup("--include-unmerged")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))

    def test_cleanup_locked_worktree_preserved(self):
        self._commit_on_branch("feature/locked", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/locked", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/locked")
        subprocess.run(["git", "-C", self.repo_str, "worktree", "lock", wt], check=True, stdout=subprocess.DEVNULL)
        try:
            proc = self._run_cleanup()
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            self.assertIn("feature/locked: preserved (worktree is locked", proc.stdout, msg=proc.stdout)
            self.assertTrue(os.path.isdir(wt))
            proc_explicit = self._run_cleanup("feature/locked")
            self.assertEqual(proc_explicit.returncode, 1, msg=proc_explicit.stdout)
            self.assertTrue(os.path.isdir(wt))
        finally:
            subprocess.run(["git", "-C", self.repo_str, "worktree", "unlock", wt], check=False, stdout=subprocess.DEVNULL)
        proc2 = self._run_cleanup()
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(self._branch_exists("feature/locked"))

    def test_cleanup_inside_worktree_preserved(self):
        self._commit_on_branch("feature/inside", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/inside", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/inside")
        old_cwd = os.getcwd()
        try:
            os.chdir(wt)
            proc = subprocess.run(
                [SCRIPT_PATH, "cleanup"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            )
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            self.assertIn("currently inside", proc.stdout, msg=proc.stdout)
        finally:
            os.chdir(old_cwd)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/inside"))

    def test_cleanup_sweeps_nested_empty_dirs(self):
        self._commit_on_branch("feature/sweep", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/sweep", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/sweep")
        # Simulate Gradle residue: nested empty chains git does not track.
        residue = os.path.join(self.repo_str, ".worktrees", "stray", "a", "b")
        os.makedirs(residue)
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(os.path.isdir(os.path.join(self.repo_str, ".worktrees")))
        self.assertFalse(self._branch_exists("feature/sweep"))

    def test_remove_sweeps_stray_and_sibling_empty_dirs(self):
        self._commit_on_branch("feature/rm-sweep", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/rm-sweep", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/rm-sweep")
        # Simulate Gradle residue plus an empty sibling next to the removed
        # worktree's parent: `remove` must sweep both like `cleanup` does.
        os.makedirs(os.path.join(self.repo_str, ".worktrees", "stray", "a", "b"))
        os.makedirs(os.path.join(self.repo_str, ".worktrees", "feature", "sibling-empty"))
        # A non-empty sibling must survive the sweep.
        keep_file = os.path.join(self.repo_str, ".worktrees", "keep", "data.txt")
        os.makedirs(os.path.dirname(keep_file))
        with open(keep_file, "w", encoding="utf-8") as f:
            f.write("keep\n")
        proc = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/rm-sweep"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(os.path.isdir(os.path.join(self.repo_str, ".worktrees", "stray")))
        self.assertFalse(os.path.isdir(os.path.join(self.repo_str, ".worktrees", "feature", "sibling-empty")))
        self.assertTrue(os.path.isfile(keep_file))
        self.assertTrue(os.path.isdir(os.path.join(self.repo_str, ".worktrees")))
        self.assertFalse(self._branch_exists("feature/rm-sweep"))

    def test_command_aliases_ls_and_rm(self):
        proc_ls = subprocess.run(
            [SCRIPT_PATH, "ls"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc_ls.returncode, 0, msg=proc_ls.stderr)
        self.assertIn("Active Git Worktrees", proc_ls.stdout)
        self._commit_on_branch("feature/alias-rm", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/alias-rm", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/alias-rm")
        proc_rm = subprocess.run(
            [SCRIPT_PATH, "rm", "feature/alias-rm"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc_rm.returncode, 0, msg=proc_rm.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(self._branch_exists("feature/alias-rm"))


    def test_cleanup_keep_branch_preserves_merged_branch(self):
        self._commit_on_branch("feature/keep", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/keep", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/keep")
        proc = self._run_cleanup("--keep-branch")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("branch 'feature/keep' preserved (--keep-branch)", proc.stdout, msg=proc.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/keep"))

    def test_cleanup_never_deletes_trunk_branch(self):
        # A branch can only live in one worktree: park the main checkout on a
        # holder so a secondary worktree can hold `main`.
        wt = os.path.join(self.repo_str, ".worktrees", "on-main")
        try:
            subprocess.run(["git", "-C", self.repo_dir, "checkout", "-b", "temp-holder"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(
                ["git", "-C", self.repo_str, "worktree", "add", wt, "main"],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            proc = self._run_cleanup()
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            self.assertIn("branch 'main' preserved (protected branch)", proc.stdout, msg=proc.stdout)
            self.assertFalse(os.path.isdir(wt))
            self.assertTrue(self._branch_exists("main"))
        finally:
            # Best-effort restore: teardown must never mask the test's own assertions.
            subprocess.run(["git", "-C", self.repo_str, "worktree", "remove", "--force", wt], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["git", "-C", self.repo_dir, "checkout", "main"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["git", "-C", self.repo_dir, "branch", "-D", "temp-holder"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def test_remove_deletes_merged_branch_but_keeps_unmerged(self):
        self._commit_on_branch("feature/rm-merged", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/rm-merged", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        self._add_worktree("feature/rm-merged")
        proc1 = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/rm-merged"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc1.returncode, 0, msg=proc1.stderr)
        self.assertIn("deleted (fully merged", proc1.stdout, msg=proc1.stdout)
        self.assertFalse(self._branch_exists("feature/rm-merged"))

        self._commit_on_branch("feature/rm-unmerged", "feat.txt", "unmerged\n", "feat")
        wt2 = self._add_worktree("feature/rm-unmerged")
        proc2 = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/rm-unmerged"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertIn("has been preserved (not fully merged", proc2.stdout, msg=proc2.stdout)
        self.assertFalse(os.path.isdir(wt2))
        self.assertTrue(self._branch_exists("feature/rm-unmerged"))

    def test_remove_keep_branch(self):
        self._commit_on_branch("feature/rm-keep", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/rm-keep", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/rm-keep")
        proc = subprocess.run(
            [SCRIPT_PATH, "remove", "--keep-branch", "feature/rm-keep"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("has been preserved (--keep-branch)", proc.stdout, msg=proc.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/rm-keep"))


    def test_remove_from_inside_worktree_refused(self):
        self._commit_on_branch("feature/rm-inside", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/rm-inside", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/rm-inside")
        old_cwd = os.getcwd()
        try:
            os.chdir(wt)
            proc = subprocess.run(
                [SCRIPT_PATH, "remove", "feature/rm-inside"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            )
            self.assertEqual(proc.returncode, 1, msg=proc.stderr)
            self.assertIn("currently inside", proc.stderr, msg=proc.stderr)
        finally:
            os.chdir(old_cwd)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/rm-inside"))


class TestDeleteMergedBranch(TempRepoTestBase):

    def _branch_with_commit(self, branch):
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "-b", branch], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(os.path.join(self.repo_dir, "feat.txt"), "w", encoding="utf-8") as f:
            f.write("x\n")
        subprocess.run(["git", "-C", self.repo_dir, "add", "feat.txt"], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", "feat"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "main"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _branch_exists(self, branch):
        return subprocess.run(
            ["git", "-C", self.repo_dir, "show-ref", "--verify", f"refs/heads/{branch}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        ).returncode == 0

    def test_protected_branches_never_deleted(self):
        for branch in ("master", "main", "HEAD"):
            deleted, _reason = worktree.delete_merged_branch(self.repo_dir, branch)
            self.assertFalse(deleted)
        self.assertTrue(self._branch_exists("main"))

    def test_unmerged_branch_preserved(self):
        self._branch_with_commit("feature/live")
        deleted, reason = worktree.delete_merged_branch(self.repo_dir, "feature/live")
        self.assertFalse(deleted)
        self.assertIn("not fully merged", reason)
        self.assertTrue(self._branch_exists("feature/live"))

    def test_merged_branch_deleted(self):
        self._branch_with_commit("feature/done")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/done", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        deleted, _reason = worktree.delete_merged_branch(self.repo_dir, "feature/done")
        self.assertTrue(deleted)
        self.assertFalse(self._branch_exists("feature/done"))


if __name__ == "__main__":
    unittest.main()


