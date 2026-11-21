"""各 runner の ArchUnit engine 実行を現ソースの inventory と照合する。"""
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def source_inventory(source):
    inventory = {}
    for path in sorted(Path(source).rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        # Java のコメント内の例を数えない。対象は行頭の実 annotation のみ。
        text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
        count = len(re.findall(r"^\s*@ArchTest\b", text, re.M))
        if not count:
            continue
        package = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
        declaration = re.search(r"^\s*(?:public\s+)?(?:abstract\s+)?class\s+(\w+)", text, re.M)
        assert package and declaration, f"ArchTest のクラスを解決できない: {path}"
        name = package[1] + "." + declaration[1]
        assert name not in inventory, f"重複 source クラス: {name}"
        inventory[name] = count
    assert inventory, "source の ArchTest inventory が空"
    return inventory


def verify(source, results):
    expected = source_inventory(source)
    actual = {}
    cases = set()
    files = sorted(Path(results).glob("TEST-*.xml"))
    assert files, f"archUnitTest XML が無い: {results}"
    for path in files:
        suite = ET.parse(path).getroot()
        assert suite.tag == "testsuite", f"未知の XML root: {path}"
        name = suite.attrib["name"]
        assert name not in actual, f"重複 suite: {name}"
        children = suite.findall("testcase")
        assert int(suite.attrib["tests"]) == len(children), f"suite 件数不一致: {name}"
        for key in ("failures", "errors", "skipped"):
            assert int(suite.attrib[key]) == 0, f"{name}: {key} が非0"
        for case in children:
            assert case.attrib["classname"] == name, f"case のクラス不一致: {name}"
            identity = (name, case.attrib["name"])
            assert identity not in cases, f"重複 case: {identity}"
            assert not any(case.find(tag) is not None for tag in ("failure", "error", "skipped")), f"未成功 case: {identity}"
            cases.add(identity)
        actual[name] = len(children)
    assert actual == expected, f"ArchUnit inventory 不一致: expected={expected}, actual={actual}"
    return {"status": "success", "classes": len(expected), "tests": len(cases), "inventory": expected}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default="src/test/java")
    parser.add_argument("--results", default="build/test-results/archUnitTest")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        report = verify(args.source, args.results)
    except (AssertionError, ET.ParseError, OSError, KeyError, ValueError) as error:
        report = {"status": "failure", "error": str(error)}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["status"] == "success" else 1


if __name__ == "__main__":
    sys.exit(main())
