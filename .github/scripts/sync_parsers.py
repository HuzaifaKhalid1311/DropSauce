#!/usr/bin/env python3
"""
Auto-detect kotatsu-parsers version updates and sync to libs.versions.toml.
Adapted from Kototoro's sync_parsers.py for DropSauce.
"""
import pathlib
import re
import os
import subprocess
import sys

file_path = pathlib.Path("gradle/libs.versions.toml")
if not file_path.exists():
    print(f"::error file={file_path}::Version file not found")
    sys.exit(1)

targets = {
    "parsers": "https://github.com/YakaTeam/kotatsu-parsers.git",
}

text = file_path.read_text(encoding="utf-8")
updated = False
changes = []

for key, repo in targets.items():
    commit_full = (
        subprocess.check_output(["git", "ls-remote", repo, "HEAD"], text=True)
        .split()[0]
        .strip()
    )
    commit_short = commit_full[:10]
    pattern = rf'({re.escape(key)}\s*=\s*")([0-9a-fA-F]+)(")'
    match = re.search(pattern, text)
    if not match:
        print(f"::error file={file_path}::{key} not found in versions file")
        sys.exit(1)
    old = match.group(2)
    if old == commit_short:
        print(f"{key} is already up-to-date: {commit_short}")
        continue
    text = re.sub(pattern, rf"\g<1>{commit_short}\g<3>", text, count=1)
    updated = True
    repo_path = repo.split("github.com/")[-1].removesuffix(".git")
    changes.append((key, repo_path, commit_full, old))
    print(f"{key}: {old} -> {commit_short}")

if updated:
    file_path.write_text(text, encoding="utf-8")
    print("Updated libs.versions.toml")

    out = pathlib.Path(os.environ["GITHUB_OUTPUT"])
    with out.open("a", encoding="utf-8") as fh:
        fh.write("updated=true\n")
        lines = ["chore: sync parser commits"]
        for key, repo_path, commit_full, old in changes:
            short = commit_full[:10]
            repo_url = f"https://github.com/{repo_path}"
            lines.append(f"- {key}: {repo_path}@{short} ({repo_url}/commit/{commit_full})")
        fh.write("commit_message<<EOF\n")
        fh.write("\n".join(lines) + "\n")
        fh.write("EOF\n")
else:
    print("No updates needed")
    out = pathlib.Path(os.environ["GITHUB_OUTPUT"])
    with out.open("a", encoding="utf-8") as fh:
        fh.write("updated=false\n")
