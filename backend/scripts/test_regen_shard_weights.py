"""重み表再生成の XML 選別を実際の Python 集計処理で検証する。"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class RegenShardWeightsTest(unittest.TestCase):
    def run_generator(self, fixtures):
        script = Path(__file__).with_name("regen-shard-weights.sh").read_text(encoding="utf-8")
        program = script.split("<<'PYEOF'\n", 1)[1].split("\nPYEOF", 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, seconds in fixtures.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f'<testsuite time="{seconds}"/>', encoding="utf-8")
            output = root / "weights.properties"
            result = subprocess.run(
                [sys.executable, "-c", program, str(root), str(output), "123", "1",
                 "main", "success", "2026-10-21T21:29:43Z", "backend-nightly-full.yml"],
                capture_output=True, text=True, encoding="utf-8",
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
            )
            weights = {}
            if output.exists():
                for line in output.read_text(encoding="utf-8").splitlines():
                    if line and not line.startswith("#"):
                        key, value = line.split("=", 1)
                        weights[key] = float(value)
            return result, weights

    def test_legacy_flat_xml_and_nested_classes_are_aggregated(self):
        result, weights = self.run_generator({
            "s0/TEST-example.Flat.xml": 2,
            "s1/TEST-example.Flat$Nested.xml": 3,
        })
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(weights, {"example.Flat": 5})

    def test_only_normal_test_task_contributes_weights(self):
        result, weights = self.run_generator({
            "s0/test/TEST-example.Normal.xml": 2,
            "s0/test/TEST-example.Normal$Nested.xml": 3,
            "s0/archUnitTest/TEST-example.Dedicated.xml": 100,
            "s0/archUnitFreezeStoreIntegrityTest/TEST-example.Integrity.xml": 100,
            "s0/archTest/TEST-example.Arch.xml": 100,
        })
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(weights, {"example.Normal": 5})

    def test_unknown_task_layout_fails_without_writing_weights(self):
        result, weights = self.run_generator({
            "s0/test/TEST-example.Normal.xml": 3,
            "s0/unknownTask/TEST-example.Other.xml": 2,
        })
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknownTask", result.stderr)
        self.assertEqual(weights, {})


if __name__ == "__main__":
    unittest.main()
