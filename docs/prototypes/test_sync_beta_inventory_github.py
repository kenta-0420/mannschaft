"""台帳のGitHub参照抽出を、実際のPR/Actionsリンクで検証する。"""
import importlib.util
from pathlib import Path
import unittest
import json
import subprocess

spec = importlib.util.spec_from_file_location("board_sync", Path(__file__).with_name("sync-beta-inventory-github.py"))
board_sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(board_sync)


spec = importlib.util.spec_from_file_location("board_generator", Path(__file__).with_name("generate-beta-inventory-board-data.py"))
board_generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(board_generator)
validator_source = Path(__file__).with_name("validate-beta-inventory-board.mjs").read_text(encoding="utf8")
validator_extractor = validator_source.split("const sourceRefs = new Map();", 1)[1].split("if (sourceRefs.size", 1)[0]


class GitHub参照抽出試験(unittest.TestCase):
    def assertReferences(self, source, expected):
        self.assertEqual(board_sync.cmp_refs(source), expected)
        self.assertEqual({row["id"]: row["githubRefs"] for row in board_generator.parse_campaigns(source)}, expected)
        script = "const fs=require('node:fs');const taskList=fs.readFileSync(0,'utf8');const sourceRefs=new Map();" + validator_extractor + "console.log(JSON.stringify(Object.fromEntries(sourceRefs)));"
        actual = subprocess.check_output(["node", "-e", script], input=source, encoding="utf8")
        self.assertEqual(json.loads(actual), expected)
    def test_抽出_実台帳のActionsリンク_PR番号だけ保持(self):
        source = "| CMP-260903-0652 | [PR #3175](https://github.com/kenta-0420/mannschaft/pull/3175) [Backend CI #37062334549](https://github.com/kenta-0420/mannschaft/actions/runs/37062334549) |\n| CMP-260903-0656 | PR #3086 [PR #3603](https://github.com/kenta-0420/mannschaft/pull/3603) [CI #37105977154](https://github.com/kenta-0420/mannschaft/actions/runs/37105977154) |"
        self.assertReferences(source, {"CMP-260903-0652": [3175], "CMP-260903-0656": [3086, 3603]})

    def test_抽出_PRとIssueリンク_両参照を保持(self):
        source = "| CMP-1 | [PR #3572](https://github.com/kenta-0420/mannschaft/pull/3572) [Issue #1074](https://github.com/kenta-0420/mannschaft/issues/1074) |"
        self.assertReferences(source, {"CMP-1": [1074, 3572]})

    def test_抽出_plain参照と内部手順番号_実参照だけ保持(self):
        self.assertReferences("| CMP-1 | PR #3572・#1074・手順#6・#7 |", {"CMP-1": [1074, 3572]})

    def test_抽出_Actionsと同値の別PR_別PRを保持(self):
        source = "| CMP-1 | [CI #37062334549](https://github.com/kenta-0420/mannschaft/actions/runs/37062334549) PR #37062334549 |"
        self.assertReferences(source, {"CMP-1": [37062334549]})


if __name__ == "__main__":
    unittest.main()
