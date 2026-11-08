"""実runtime入力の検証と原子出力を、既存generator本体で検証する。"""
import hashlib
import io
import json
import tempfile
import stat
import unittest
import zipfile
from pathlib import Path
from unittest import mock


def load_program():
    script = Path(__file__).with_name("regen-shard-weights.sh").read_text(encoding="utf-8")
    program = script.split("<<'PYEOF'\n", 1)[1].split("\nPYEOF", 1)[0]
    namespace = {"__name__": "generator_test"}
    exec(compile(program, "regen-shard-weights.sh:PYEOF", "exec"), namespace)
    return namespace


def events(name, elapsed=1000, task=":test"):
    return (
        f"2026-10-24T00:00:01Z [ci-class-start] task={task} class={name}\n"
        f"2026-10-24T00:00:03Z [ci-class-end] task={task} class={name} elapsedMs={elapsed} result=SUCCESS\n"
    )


def fixture():
    run = dict(id=123, head_sha="a" * 40, run_attempt=1, status="completed", conclusion="success",
               path=".github/workflows/backend-nightly-full.yml", head_branch="main", created_at="2026-10-24T00:00:00Z")
    jobs, shards = [], {}
    for n in range(6):
        jobs.append(dict(run_id=123, run_attempt=1, head_sha="a" * 40, name=f"Full test (shard {n})", status="completed", conclusion="success",
                         steps=[dict(name=f"Run tests (shard {n} / 6, 除外なし)", conclusion="success")]))
        result = dict(completed=True, cacheHit=False, testStepExitCode=0, commandExitCode=0,
                      shellExitCode=0, testStepOutcome="success", startedAtEpoch=1, finishedAtEpoch=9,
                      jobLimitMinutes=120, testStepLimitMinutes=105)
        files = {"class-progress.log": events(f"example.Class{n}" if n != 5 else "example.migration.FlywayTest").encode(),
                 "task-state.log": b"2026-10-24T00:00:04Z [ci-test-task-state] task=:test skipped=false skipMessage=null upToDate=false noSource=false failed=false\n",
                 "result.json": json.dumps(result).encode(), "command-exit-code": b"0\n", "started-at-epoch": b"1\n"}
        meta = dict(name=f"nightly-runtime-diag-shard-{n}-attempt-1", expired=False,
                    workflow_run=dict(id=123, head_sha="a" * 40))
        shards[n] = (meta, files)
    return run, jobs, shards


class RegenShardWeightsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.program = load_program()

    def generate(self, data, target):
        run, jobs, shards = data
        weights = self.program["build_weights"](run, jobs, shards, "nightly-full")
        self.program["atomic_write"](target, self.program["render"](run, weights))
        return weights

    def test_full_six_normal_task_and_outer_are_counted_once(self):
        data = fixture()
        files = data[2][0][1]
        files["class-progress.log"] += events("example.Class0$Nested", 300).encode()
        files["class-progress.log"] += events("example.Dedicated", 99000, ":archUnitTest").encode()
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "weights.properties"
            weights = self.generate(data, target)
            self.assertEqual(len(weights), 6)
            self.assertEqual(weights["example.Class0"], 1000)
            self.assertIn("metric=class-suite-elapsed-ms", target.read_text(encoding="utf-8"))
            self.assertIn("example.Class0=1.000", target.read_text(encoding="utf-8"))

    def test_backend_ci_without_full_profile_proof_is_rejected(self):
        run, jobs, shards = fixture()
        with self.assertRaisesRegex(ValueError, "full profile cannot be established"):
            self.program["build_weights"](run, jobs, shards, "backend-ci")

    def test_nested_only_sibling_suites_are_summed(self):
        data = fixture()
        data[2][1][1]["class-progress.log"] = (events("example.Class1$A", 20) + events("example.Class1$B", 30)).encode()
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(self.generate(data, Path(d) / "weights")["example.Class1"], 50)

    def test_invalid_inputs_leave_existing_output_unchanged(self):
        def result_change(data, **fields):
            files = data[2][0][1]
            result = json.loads(files["result.json"])
            result.update(fields)
            files["result.json"] = json.dumps(result).encode()
        mutations = {
            "failed_run": lambda x: x[0].update(conclusion="failure"),
            "active_run": lambda x: x[0].update(status="in_progress"),
            "job_attempt": lambda x: x[1][0].update(run_attempt=2),
            "expired": lambda x: x[2][0][0].update(expired=True),
            "bad_head": lambda x: x[2][0][0]["workflow_run"].update(head_sha="b" * 40),
            "old_attempt": lambda x: x[2][0][0].update(name="nightly-runtime-diag-shard-0-attempt-2"),
            "no_migration": lambda x: x[2][5][1].update({"class-progress.log": events("example.Class5").encode()}),
            "missing_shard": lambda x: x[2].pop(5),
            "failed_step": lambda x: x[1][0]["steps"][0].update(conclusion="failure"),
            "cache": lambda x: result_change(x, cacheHit=True),
            "unknown": lambda x: result_change(x, testStepExitCode=None),
            "nonzero": lambda x: result_change(x, commandExitCode=1),
            "no_terminal": lambda x: x[2][0][1].update({"task-state.log": b""}),
            "duplicate_terminal": lambda x: x[2][0][1].update({"task-state.log": x[2][0][1]["task-state.log"] * 2}),
            "up_to_date": lambda x: x[2][0][1].update({"task-state.log": x[2][0][1]["task-state.log"].replace(b"upToDate=false", b"upToDate=true")}),
            "no_source": lambda x: x[2][0][1].update({"task-state.log": x[2][0][1]["task-state.log"].replace(b"noSource=false", b"noSource=true")}),
            "wrong_marker": lambda x: x[2][0][1].update({"command-exit-code": b"1"}),
            "missing_end": lambda x: x[2][0][1].update({"class-progress.log": events("example.Class0").splitlines(True)[0].encode()}),
            "duplicate_event": lambda x: x[2][0][1].update({"class-progress.log": events("example.Class0").encode() * 2}),
            "negative_elapsed": lambda x: x[2][0][1].update({"class-progress.log": events("example.Class0", -1).encode()}),
            "reversed_time": lambda x: x[2][0][1].update({"class-progress.log": events("example.Class0").replace("00:00:03Z", "00:00:00Z").encode()}),
            "split_nested": lambda x: x[2][1][1].update({"class-progress.log": events("example.Class0$Sibling").encode()}),
            "xml_only": lambda x: x[2][0][1].pop("class-progress.log"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                data = fixture()
                mutate(data)
                target = Path(d) / "weights"
                target.write_bytes(b"existing-output\n")
                with self.assertRaises((ValueError, KeyError)):
                    self.generate(data, target)
                self.assertEqual(target.read_bytes(), b"existing-output\n")

    def test_zip_digest_paths_and_members_are_checked(self):
        def archive(names):
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w") as z:
                for name in names:
                    z.writestr(name, b"x")
            return stream.getvalue()
        for names in [["../class-progress.log"], ["/result.json"], ["unknown.xml"], ["result.json", "x/result.json"]]:
            with self.subTest(names=names):
                payload = archive(names)
                meta = dict(size_in_bytes=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest())
                with self.assertRaises(ValueError):
                    self.program["safe_zip"](meta, payload)
        stream = io.BytesIO()
        files = fixture()[2][0][1]
        with zipfile.ZipFile(stream, "w") as z:
            for name, content in files.items():
                z.writestr("runtime/" + name, content)
        payload = stream.getvalue()
        meta = dict(size_in_bytes=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest())
        self.assertEqual(self.program["safe_zip"](meta, payload), files)
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as z:
            link = zipfile.ZipInfo("result.json")
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(link, "target")
        payload = stream.getvalue()
        meta = dict(size_in_bytes=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest())
        with self.assertRaises(ValueError):
            self.program["safe_zip"](meta, payload)
        payload = archive(["result.json"])
        with self.assertRaises(ValueError):
            self.program["safe_zip"](dict(size_in_bytes=len(payload), digest="sha256:bad"), payload)

    def test_replace_failure_preserves_existing_output_and_cleans_temp(self):
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "weights"
            target.write_bytes(b"original")
            with mock.patch.object(self.program["os"], "replace", side_effect=OSError("replace failed")):
                with self.assertRaises(OSError):
                    self.program["atomic_write"](target, "new")
            self.assertEqual(target.read_bytes(), b"original")
            self.assertEqual(list(Path(d).iterdir()), [target])


if __name__ == "__main__":
    unittest.main()
