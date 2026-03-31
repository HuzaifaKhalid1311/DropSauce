from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]


SKIP_DIR_NAMES = {
    ".git",
    ".gradle",
    ".idea",
    ".vscode",
    "build",
    "out",
    ".cache",
}

# Skip Android/Gradle build output folders explicitly
SKIP_DIR_SUFFIXES = {
    str(Path("app") / "build"),
    str(Path("build")),
}

TEXT_EXTS = {
    ".kt",
    ".kts",
    ".java",
    ".xml",
    ".gradle",
    ".properties",
    ".md",
    ".yml",
    ".yaml",
    ".toml",
    ".txt",
    ".json",
    ".pro",
}


@dataclass(frozen=True)
class Replacement:
    name: str
    pattern: re.Pattern[str]
    repl: str


def should_skip_dir(path: Path) -> bool:
    parts = {part for part in path.parts if part}
    if parts.intersection(SKIP_DIR_NAMES):
        return True
    # cheap suffix check on posix-ish string
    s = str(path).replace("\\", "/")
    for suf in SKIP_DIR_SUFFIXES:
        if s.endswith(suf.replace("\\", "/")) or f"/{suf.replace('\\', '/')}/" in s:
            return True
    return False


def iter_text_files(root: Path) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        d = Path(dirpath)
        if should_skip_dir(d):
            dirnames[:] = []
            continue
        # prune skipped subdirs early
        dirnames[:] = [n for n in dirnames if n not in SKIP_DIR_NAMES]
        for fn in filenames:
            p = d / fn
            if p.suffix.lower() in TEXT_EXTS:
                yield p


# External dependencies / endpoints we must NOT touch (per user request).
# These are intentionally left even if they contain "kotatsu".
PROTECTED_SUBSTRINGS = [
    "kotatsu-parsers",  # dependency artifact
    "sync.kotatsu.app",  # external sync service URL
    "kotatsu_backup_bot",  # Telegram bot username
    "hosted.weblate.org/engage/kotatsu",  # external translation project
    "kotatsu.app/manuals",  # external manual site
    "t.me/kotatsuapp",  # external telegram group
    "tg://resolve?domain=kotatsuapp",  # external telegram deep link
]


def is_protected_line(line: str) -> bool:
    return any(s in line for s in PROTECTED_SUBSTRINGS)


REPLACEMENTS: list[Replacement] = [
    Replacement(
        name="package-org",
        pattern=re.compile(r"\borg\.koitharu\.kotatsu\b"),
        repl="org.haziffe.dropsauce",
    ),
    Replacement(
        name="package-org-prefix",
        pattern=re.compile(r"\borg\.koitharu\.kotatsu\."),
        repl="org.haziffe.dropsauce.",
    ),
    Replacement(
        name="devname-skepsun",
        pattern=re.compile(r"\bskepsun\b", flags=re.IGNORECASE),
        repl="haziffe",
    ),
    Replacement(
        name="devname-koitharu",
        pattern=re.compile(r"\bkoitharu\b", flags=re.IGNORECASE),
        repl="haziffe",
    ),
    # App branding (identifier + display)
    Replacement(
        name="appname-Kotatsu",
        pattern=re.compile(r"\bKotatsu\b"),
        repl="DropSauce",
    ),
    Replacement(
        name="appname-Yukimi",
        pattern=re.compile(r"\bYukimi\b"),
        repl="DropSauce",
    ),
    Replacement(
        name="appname-Yumemi",
        pattern=re.compile(r"\bYumemi\b"),
        repl="DropSauce",
    ),
    Replacement(
        name="appname-Kototoro",
        pattern=re.compile(r"\bKototoro\b"),
        repl="DropSauce",
    ),
]


def apply_replacements_to_text(text: str) -> tuple[str, dict[str, int]]:
    counts: dict[str, int] = {}
    lines = text.splitlines(keepends=True)
    out_lines: list[str] = []
    for line in lines:
        if is_protected_line(line):
            out_lines.append(line)
            continue
        new_line = line
        for r in REPLACEMENTS:
            new_line, n = r.pattern.subn(r.repl, new_line)
            if n:
                counts[r.name] = counts.get(r.name, 0) + n
        out_lines.append(new_line)
    return "".join(out_lines), counts


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        try:
            return path.read_text(encoding="utf-8-sig")
        except UnicodeDecodeError:
            return None


def write_text(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    changed_files: list[tuple[Path, dict[str, int]]] = []
    total_counts: dict[str, int] = {}

    for p in iter_text_files(REPO_ROOT):
        old = read_text(p)
        if old is None:
            continue
        new, counts = apply_replacements_to_text(old)
        if new != old:
            changed_files.append((p, counts))
            for k, v in counts.items():
                total_counts[k] = total_counts.get(k, 0) + v
            if not dry_run:
                write_text(p, new)

    print("== Rebrand report ==")
    print(f"repo: {REPO_ROOT}")
    print(f"mode: {'DRY RUN' if dry_run else 'APPLY'}")
    print(f"files_changed: {len(changed_files)}")
    if total_counts:
        print("replacement_counts:")
        for k in sorted(total_counts):
            print(f"  {k}: {total_counts[k]}")
    else:
        print("replacement_counts: (none)")

    if changed_files:
        print("changed_files:")
        for p, counts in changed_files[:2000]:
            rel = p.relative_to(REPO_ROOT)
            summary = ", ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "?"
            print(f"  - {rel} [{summary}]")
        if len(changed_files) > 2000:
            print(f"  ... truncated ({len(changed_files) - 2000} more)")

    if PROTECTED_SUBSTRINGS:
        print("protected_substrings (not modified):")
        for s in PROTECTED_SUBSTRINGS:
            print(f"  - {s}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

