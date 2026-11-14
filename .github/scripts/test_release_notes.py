from pathlib import Path
import unittest

from release_notes import extract_release_notes


def section(version="1.2.2", suffix=" - 2026-11-14", bracketed=True, prefix="v"):
    tag = f"[{prefix}{version}]" if bracketed else f"{prefix}{version}"
    return (
        f"## {tag}{suffix}\n\n"
        "### 中文\n\n"
        "#### 新增\n\n"
        "- 内容\n\n"
        "### English\n\n"
        "#### Added\n\n"
        "- Content\n"
    )


class ReleaseNotesTest(unittest.TestCase):
    def test_preserves_both_languages_and_nested_categories(self):
        wanted = section()
        self.assertEqual(extract_release_notes(wanted + "\n" + section("1.2.1"), "1.2.2"), wanted)

    def test_normalizes_crlf(self):
        self.assertEqual(extract_release_notes(section().replace("\n", "\r\n"), "1.2.2"), section())

    def test_does_not_match_similar_versions_or_mentions(self):
        source = "## Other [v1.2.2]\nMention v1.2.2\n" + section("1x2x2") + section("1.2.21") + section()
        self.assertEqual(extract_release_notes(source, "1.2.2"), section())

    def test_supports_prerelease_versions(self):
        self.assertEqual(extract_release_notes(section("1.2.2-rc.1"), "1.2.2-rc.1"), section("1.2.2-rc.1"))

    def test_accepts_unbracketed_and_without_v_prefix(self):
        for bracketed in (True, False):
            for prefix in ("v", ""):
                with self.subTest(bracketed=bracketed, prefix=prefix):
                    wanted = section(bracketed=bracketed, prefix=prefix)
                    self.assertEqual(extract_release_notes(wanted, "1.2.2"), wanted)

    def test_rejects_missing_section(self):
        with self.assertRaises(ValueError):
            extract_release_notes(section("1.2.1"), "1.2.2")

    def test_rejects_duplicate_sections(self):
        with self.assertRaises(ValueError):
            extract_release_notes(section() + section(), "1.2.2")

    def test_rejects_unreleased_section(self):
        for suffix in [" - Unreleased", " - UNRELEASED", " - 待发布"]:
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                extract_release_notes(section(suffix=suffix), "1.2.2")

    def test_rejects_missing_or_reversed_languages(self):
        for notes in [
            "## [v1.2.2]\n\n### 中文\n\n- 内容\n",
            "## [v1.2.2]\n\n### English\n\n- Content\n\n### 中文\n\n- 内容\n",
        ]:
            with self.subTest(notes=notes), self.assertRaises(ValueError):
                extract_release_notes(notes, "1.2.2")

    def test_rejects_empty_language_body(self):
        for notes in [
            "## [v1.2.2]\n\n### 中文\n\n### English\n\n- Content\n",
            "## [v1.2.2]\n\n### 中文\n\n- 内容\n\n### English\n",
        ]:
            with self.subTest(notes=notes), self.assertRaises(ValueError):
                extract_release_notes(notes, "1.2.2")

    def test_real_changelog_v1_2_1(self):
        changelog = Path(__file__).resolve().parents[2] / "CHANGELOG.md"
        notes = extract_release_notes(changelog.read_text(encoding="utf-8"), "1.2.1")
        self.assertIn("### 中文", notes)
        self.assertIn("### English", notes)


if __name__ == "__main__":
    unittest.main()
