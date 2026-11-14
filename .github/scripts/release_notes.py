"""Extract one complete bilingual Changelog section for a BitLockerDroid release."""

import argparse
from pathlib import Path
import re


def _normalize_heading_version(token: str) -> str:
    if token.startswith("[") and token.endswith("]"):
        return token[1:-1].strip()
    return token


def extract_release_notes(changelog: str, version: str) -> str:
    lines = changelog.splitlines()
    sections = []
    for start, line in enumerate(lines):
        heading = re.fullmatch(r"##[ \t]+([^ \t]+)(?:[ \t]+.*)?", line)
        if heading is None:
            continue
        token = _normalize_heading_version(heading[1])
        if token not in (version, "v" + version):
            continue
        end = next((i for i in range(start + 1, len(lines)) if re.match(r"^##(?:[ \t]|$)", lines[i])), len(lines))
        sections.append(lines[start:end])
    if len(sections) != 1:
        raise ValueError(f"Expected one Changelog section for {version}, found {len(sections)}")
    section = sections[0]
    if "unreleased" in section[0].casefold() or "待发布" in section[0]:
        raise ValueError(f"Changelog section for {version} is still marked unreleased")
    body = "\n".join(section[1:])
    zh_match = re.search(r"^###[ \t]+中文[ \t]*$", body, flags=re.M)
    en_match = re.search(r"^###[ \t]+English[ \t]*$", body, flags=re.M)
    if zh_match is None or en_match is None or zh_match.start() > en_match.start():
        raise ValueError("Release notes must contain '### 中文' followed by '### English'")
    chinese_body = body[zh_match.end():en_match.start()].strip()
    english_body = body[en_match.end():].strip()
    if not chinese_body or not english_body:
        raise ValueError("Both release-note languages must be non-empty")
    return "\n".join(section).rstrip() + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("changelog", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        notes = extract_release_notes(args.changelog.read_text(encoding="utf-8"), args.version)
    except ValueError as error:
        parser.exit(1, f"Release notes error: {error}\n")
    args.output.write_text(notes, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
