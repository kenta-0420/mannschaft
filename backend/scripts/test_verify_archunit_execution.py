"""実行 inventory の欠落・未成功・重複を純 XML fixture で検査する。"""
import tempfile
import unittest
from pathlib import Path
from verify_archunit_execution import verify


class ArchUnitExecutionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.results = self.root / "results"
        self.source.mkdir()
        self.results.mkdir()
        (self.source / "Rules.java").write_text("package example;\nclass Rules {\n @ArchTest\n static ArchRule first;\n @ArchTest\n static ArchRule second;\n // @ArchTest\n /*\n @ArchTest\n */\n}", encoding="utf-8")

    def xml(self, cases=("first", "second"), skipped=0, body="", failures=0, errors=0):
        entries = "".join(f'<testcase classname="example.Rules" name="{name}">{body}</testcase>' for name in cases)
        (self.results / "TEST-example.Rules.xml").write_text(f'<testsuite name="example.Rules" tests="{len(cases)}" failures="{failures}" errors="{errors}" skipped="{skipped}">{entries}</testsuite>', encoding="utf-8")

    def test_complete_inventory(self):
        self.xml()
        self.assertEqual(verify(self.source, self.results)["tests"], 2)

    def test_partial_and_zero_cases_fail(self):
        for cases in (("first",), ()):
            with self.subTest(cases=cases):
                self.xml(cases)
                with self.assertRaisesRegex(AssertionError, "inventory 不一致"):
                    verify(self.source, self.results)

    def test_missing_xml_fails(self):
        with self.assertRaisesRegex(AssertionError, "XML が無い"):
            verify(self.source, self.results)

    def test_duplicate_case_fails(self):
        self.xml(("first", "first"))
        with self.assertRaisesRegex(AssertionError, "重複 case"):
            verify(self.source, self.results)

    def test_unsuccessful_summary_and_case_fail(self):
        for args in ({"skipped": 1}, {"failures": 1}, {"errors": 1}, {"body": "<skipped/>"}, {"body": "<failure/>"}, {"body": "<error/>"}):
            with self.subTest(args=args):
                self.xml(**args)
                with self.assertRaises(AssertionError):
                    verify(self.source, self.results)

    def test_wrong_class_and_duplicate_suite_fail(self):
        self.xml()
        path = self.results / "TEST-example.Rules.xml"
        (self.results / "TEST-duplicate.xml").write_bytes(path.read_bytes())
        with self.assertRaisesRegex(AssertionError, "重複 suite"):
            verify(self.source, self.results)

    def test_case_class_must_match_suite(self):
        self.xml()
        path = self.results / "TEST-example.Rules.xml"
        path.write_text(path.read_text(encoding="utf-8").replace('classname="example.Rules"', 'classname="example.Other"'), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "クラス不一致"):
            verify(self.source, self.results)

    def test_added_source_rule_requires_another_case(self):
        self.xml()
        path = self.source / "Rules.java"
        path.write_text(path.read_text(encoding="utf-8") + "\n @ArchTest\n static ArchRule third;\n", encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "inventory 不一致"):
            verify(self.source, self.results)


if __name__ == "__main__":
    unittest.main()
