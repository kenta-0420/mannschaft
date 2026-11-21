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


def fixture(total=6, run_id=123, head="a", elapsed=None):
    """total shard の nightly run を作る。最後の shard に migration/Flyway クラスを置く。

    elapsed は {shard番号: ミリ秒} で、その shard のクラス実時間を上書きする（既定 1000）。
    """
    elapsed = elapsed or {}
    head_sha = head * 40
    run = dict(id=run_id, head_sha=head_sha, run_attempt=1, status="completed", conclusion="success",
               path=".github/workflows/backend-nightly-full.yml", head_branch="main", created_at="2026-10-24T00:00:00Z")
    jobs, shards = [], {}
    for n in range(total):
        jobs.append(dict(run_id=run_id, run_attempt=1, head_sha=head_sha, name=f"Full test (shard {n})", status="completed", conclusion="success",
                         steps=[dict(name=f"Run tests (shard {n} / {total}, 除外なし)", conclusion="success")]))
        result = dict(completed=True, cacheHit=False, testStepExitCode=0, commandExitCode=0,
                      shellExitCode=0, testStepOutcome="success", startedAtEpoch=1, finishedAtEpoch=9,
                      jobLimitMinutes=120, testStepLimitMinutes=105)
        name = f"example.Class{n}" if n != total - 1 else "example.migration.FlywayTest"
        files = {"class-progress.log": events(name, elapsed.get(n, 1000)).encode(),
                 "task-state.log": b"2026-10-24T00:00:04Z [ci-test-task-state] task=:test skipped=false skipMessage=null upToDate=false noSource=false failed=false\n",
                 "result.json": json.dumps(result).encode(), "command-exit-code": b"0\n", "started-at-epoch": b"1\n"}
        meta = dict(name=f"nightly-runtime-diag-shard-{n}-attempt-1", expired=False,
                    workflow_run=dict(id=run_id, head_sha=head_sha))
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

    def test_optional_mfa_resources_does_not_change_weights(self):
        for observation in [b"", b"[mfa-resources] threadCpuNanos=999999 gcCount=-1 gcTimeMillis=-1\n"]:
            with self.subTest(observation=observation), tempfile.TemporaryDirectory() as d:
                data = fixture()
                target = Path(d) / "weights"
                expected = self.generate(data, target)
                expected_output = target.read_bytes()
                for _, files in data[2].values():
                    files["mfa-resources.log"] = observation
                    stream = io.BytesIO()
                    with zipfile.ZipFile(stream, "w") as archive:
                        for name, content in files.items():
                            archive.writestr("runtime/" + name, content)
                    payload = stream.getvalue()
                    meta = dict(size_in_bytes=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest())
                    parsed = self.program["safe_zip"](meta, payload)
                    self.assertEqual(parsed["mfa-resources.log"], observation)
                    files.clear()
                    files.update(parsed)
                self.assertEqual(self.generate(data, target), expected)
                self.assertEqual(target.read_bytes(), expected_output)

    def test_mfa_resources_keeps_zip_guards_and_existing_output(self):
        link = zipfile.ZipInfo("runtime/mfa-resources.log")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        invalid_members = {
            "unknown": ([("runtime/mfa-resources.log.txt", b"x")], "unknown/duplicate/oversized"),
            "traversal": ([("../mfa-resources.log", b"x")], "unsafe zip path/type"),
            "absolute": ([("/mfa-resources.log", b"x")], "unsafe zip path/type"),
            "symlink": ([(link, b"target")], "unsafe zip path/type"),
            "duplicate": ([("runtime/mfa-resources.log", b"x"), ("other/mfa-resources.log", b"x")], "unknown/duplicate/oversized"),
            "oversized": ([("runtime/mfa-resources.log", b"x" * (2 * 1024 * 1024 + 1))], "unknown/duplicate/oversized"),
            "digest": ([("runtime/mfa-resources.log", b"x")], "runtime digest mismatch"),
        }
        for name, (extra, error) in invalid_members.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                data = fixture()
                target = Path(d) / "weights"
                target.write_bytes(b"existing-output\n")
                stream = io.BytesIO()
                with zipfile.ZipFile(stream, "w") as archive:
                    for member, content in data[2][0][1].items():
                        archive.writestr("runtime/" + member, content)
                    for member, content in extra:
                        archive.writestr(member, content)
                payload = stream.getvalue()
                meta = dict(size_in_bytes=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest())
                if name == "digest":
                    meta["digest"] = "sha256:bad"
                with self.assertRaisesRegex(ValueError, error):
                    data[2][0] = (data[2][0][0], self.program["safe_zip"](meta, payload))
                    self.generate(data, target)
                self.assertEqual(target.read_bytes(), b"existing-output\n")

    def test_replace_failure_preserves_existing_output_and_cleans_temp(self):
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "weights"
            target.write_bytes(b"original")
            with mock.patch.object(self.program["os"], "replace", side_effect=OSError("replace failed")):
                with self.assertRaises(OSError):
                    self.program["atomic_write"](target, "new")
            self.assertEqual(target.read_bytes(), b"original")
            self.assertEqual(list(Path(d).iterdir()), [target])


# ---------------------------------------------------------------------------
# CMP-261111-0508 試練: shard 数の照合（AC-6a）、複数 run 平均（AC-6b）、
# 失敗時の既存出力保持と来歴（AC-6c）、複数 run への安全検査の適用（AC-6d）。
# 出陣側が実装する契約:
#   expected_shard_total(jobs, kind) -> int
#   build_weights(run, jobs, shards, kind)  … N は jobs から決め、artifact index 集合が 0..N-1 と一致しなければ拒否
#   build_weights_multi(inputs, kind) -> (weights, observed)  … inputs は [(run, jobs, shards), ...]
#   render_multi(runs, weights, observed) -> str
#   main(repo, "RUN_ID[,RUN_ID...]", kind, target)
# ---------------------------------------------------------------------------
def zip_runtime(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, content in files.items():
            archive.writestr("runtime/" + name, content)
    return stream.getvalue()


class FakeGh:
    """main() の gh 呼び出しを fixture で応答する。fail_run の run に関する呼び出しは失敗させる。"""

    def __init__(self, repo, inputs, fail_run=None):
        self.repo, self.fail_run, self.calls = repo, fail_run, []
        self.runs, self.jobs, self.artifacts, self.payloads = {}, {}, {}, {}
        for run, jobs, shards in inputs:
            rid = run["id"]
            self.runs[rid], self.jobs[rid], self.artifacts[rid] = run, jobs, []
            for n, (meta, files) in sorted(shards.items()):
                payload = zip_runtime(files)
                aid = rid * 100 + n
                self.payloads[aid] = payload
                self.artifacts[rid].append(dict(meta, id=aid, size_in_bytes=len(payload),
                                                digest="sha256:" + hashlib.sha256(payload).hexdigest()))

    def replace_payload(self, rid, index, payload):
        meta = self.artifacts[rid][index]
        self.payloads[meta["id"]] = payload
        meta.update(size_in_bytes=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest())

    def __call__(self, args, **kwargs):
        import re
        import subprocess
        self.calls.append(list(args))
        prefix = f"repos/{self.repo}/actions/"
        path = args[2][len(prefix):] if len(args) > 2 and args[1] == "api" and args[2].startswith(prefix) else None
        body = None
        if path is not None:
            if m := re.fullmatch(r"runs/(\d+)", path):
                rid = int(m.group(1))
                body = None if rid == self.fail_run or rid not in self.runs else json.dumps(self.runs[rid]).encode()
            elif m := re.fullmatch(r"runs/(\d+)/attempts/(\d+)/jobs\?per_page=100", path):
                rid = int(m.group(1))
                body = None if rid == self.fail_run or rid not in self.jobs else json.dumps({"jobs": self.jobs[rid]}).encode()
            elif m := re.fullmatch(r"runs/(\d+)/artifacts\?per_page=100&page=1", path):
                rid = int(m.group(1))
                if rid != self.fail_run and rid in self.artifacts:
                    body = json.dumps({"artifacts": self.artifacts[rid], "total_count": len(self.artifacts[rid])}).encode()
            elif m := re.fullmatch(r"artifacts/(\d+)/zip", path):
                aid = int(m.group(1))
                if aid // 100 != self.fail_run:
                    body = self.payloads.get(aid)
        if body is None:
            return subprocess.CompletedProcess(args, 1, stdout=b"", stderr=b"not found")
        return subprocess.CompletedProcess(args, 0, stdout=body, stderr=b"")

    def touched(self, rid):
        return any(f"runs/{rid}" in " ".join(call) for call in self.calls)


class ShardTotalDerivationTest(unittest.TestCase):
    """AC-6a: 期待 shard 数 N を run の job/step 情報から決め、artifact index 集合と照合する。"""

    @classmethod
    def setUpClass(cls):
        cls.program = load_program()

    def generate(self, data, target):
        run, jobs, shards = data
        weights = self.program["build_weights"](run, jobs, shards, "nightly-full")
        self.program["atomic_write"](target, self.program["render"](run, weights))
        return weights

    def test_ac6a_expected_total_is_derived_from_job_and_step_names(self):
        for total in (6, 8):
            with self.subTest(total=total):
                _, jobs, _ = fixture(total)
                self.assertEqual(self.program["expected_shard_total"](jobs, "nightly-full"), total)

    def test_ac6a_six_and_eight_shard_runs_are_accepted(self):
        for total in (6, 8):
            with self.subTest(total=total), tempfile.TemporaryDirectory() as d:
                weights = self.generate(fixture(total), Path(d) / "weights")
                self.assertEqual(len(weights), total)
                self.assertIn("example.migration.FlywayTest", weights)

    def test_ac6a_index_set_or_total_mismatch_is_rejected_and_output_kept(self):
        def extra_index(data):
            meta, files = data[2][0]
            data[2][8] = (dict(meta, name="nightly-runtime-diag-shard-8-attempt-1"), dict(files))

        def drop_last_job(data):
            data[1].pop()

        def step_total_disagrees(data):
            data[1][3]["steps"][0]["name"] = "Run tests (shard 3 / 6, 除外なし)"

        cases = {
            "missing_6_and_7": (lambda x: (x[2].pop(6), x[2].pop(7)), "shard index set mismatch"),
            "missing_middle": (lambda x: x[2].pop(3), "shard index set mismatch"),
            "extra_index": (extra_index, "shard index set mismatch"),
            "duplicate_job": (lambda x: x[1].append(json.loads(json.dumps(x[1][2]))), "duplicate shard"),
            "job_count_vs_step_total": (drop_last_job, "shard total mismatch"),
            "step_total_disagrees": (step_total_disagrees, "shard total mismatch"),
        }
        for name, (mutate, message) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                data = fixture(8)
                mutate(data)
                target = Path(d) / "weights"
                target.write_bytes(b"existing-output\n")
                with self.assertRaisesRegex(ValueError, message):
                    self.generate(data, target)
                self.assertEqual(target.read_bytes(), b"existing-output\n")


class MultiRunAverageTest(unittest.TestCase):
    """AC-6b〜6d: 複数 run の平均・来歴・失敗時の保持・run ごとの安全検査。"""

    REPO = "owner/repo"

    @classmethod
    def setUpClass(cls):
        cls.program = load_program()

    def two_runs(self, total=8):
        first = fixture(total, run_id=123, head="a", elapsed={0: 1000})
        second = fixture(total, run_id=456, head="b", elapsed={0: 3000})
        first[2][1][1]["class-progress.log"] += events("example.OnlyRun1", 5000).encode()
        return first, second

    def run_main(self, fake, target):
        with mock.patch.object(self.program["subprocess"], "run", side_effect=fake):
            self.program["main"](self.REPO, "123,456", "nightly-full", str(target))

    def test_ac6b_average_uses_per_class_observed_runs_as_denominator(self):
        first, second = self.two_runs()
        weights, observed = self.program["build_weights_multi"]([first, second], "nightly-full")
        self.assertEqual(weights["example.Class0"], 2000)
        self.assertEqual(observed["example.Class0"], 2)
        # run 2 で観測されないクラスを 0 秒として平均に入れない（5000 のまま、分母 1）
        self.assertEqual(weights["example.OnlyRun1"], 5000)
        self.assertEqual(observed["example.OnlyRun1"], 1)
        text = self.program["render_multi"]([first[0], second[0]], weights, observed)
        self.assertIn("example.Class0=2.000", text)
        self.assertIn("example.OnlyRun1=5.000", text)
        self.assertTrue(any(line.startswith("#") and "example.OnlyRun1" in line and "1/2" in line
                            for line in text.splitlines()), "分母（観測 run 数）が来歴/コメント行に出ること")

    def test_ac6c_success_records_every_run_id_head_and_attempt(self):
        first, second = self.two_runs()
        with tempfile.TemporaryDirectory() as d:
            target = Path(d) / "weights"
            self.run_main(FakeGh(self.REPO, [first, second]), target)
            text = target.read_text(encoding="utf-8")
            for run in (first[0], second[0]):
                self.assertRegex(text, rf"#.*run={run['id']}\b.*head={run['head_sha']}.*attempt={run['run_attempt']}")
            self.assertIn("example.Class0=2.000", text)

    def test_ac6c_second_run_failure_keeps_existing_output_byte_identical(self):
        def fetch_fails(first, second):
            return FakeGh(self.REPO, [first, second], fail_run=456)

        def validation_fails(first, second):
            second[0]["conclusion"] = "failure"
            return FakeGh(self.REPO, [first, second])

        for name, build in {"fetch": fetch_fails, "validation": validation_fails}.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                first, second = self.two_runs()
                fake = build(first, second)
                target = Path(d) / "weights"
                target.write_bytes(b"existing-output\r\n\x00keep")
                with self.assertRaisesRegex(ValueError, "456"):
                    self.run_main(fake, target)
                self.assertEqual(target.read_bytes(), b"existing-output\r\n\x00keep")
                self.assertTrue(fake.touched(123), "1 本目の run は処理されていること")
                self.assertEqual(sorted(p.name for p in Path(d).iterdir()), ["weights"])

    def test_ac6d_existing_safety_checks_apply_to_each_run(self):
        def unsafe_zip(fake):
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w") as archive:
                archive.writestr("../class-progress.log", b"x")
            fake.replace_payload(456, 0, stream.getvalue())

        cases = {
            "artifact_head": (lambda f: f.artifacts[456][0]["workflow_run"].update(head_sha="c" * 40), "artifact run/head mismatch"),
            "job_head": (lambda f: f.jobs[456][0].update(head_sha="c" * 40), "shard job run/head/attempt mismatch"),
            "digest": (lambda f: f.artifacts[456][0].update(digest="sha256:bad"), "runtime digest mismatch"),
            "unsafe_path": (unsafe_zip, "unsafe zip path/type"),
            "backend_ci_run": (lambda f: f.runs[456].update(path=".github/workflows/backend-ci.yml"), "workflow mismatch"),
        }
        for name, (mutate, message) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                first, second = self.two_runs()
                fake = FakeGh(self.REPO, [first, second])
                mutate(fake)
                target = Path(d) / "weights"
                target.write_bytes(b"existing-output\n")
                with self.assertRaisesRegex(ValueError, message) as caught:
                    self.run_main(fake, target)
                self.assertIn("456", str(caught.exception), "どの run で落ちたかをメッセージに含めること")
                self.assertEqual(target.read_bytes(), b"existing-output\n")


# ---------------------------------------------------------------------------
# CMP-261111-0508 試練: workflow の静的検査（AC-1 / AC-9 / AC-11）
# ---------------------------------------------------------------------------
REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"
BUILD_LOGIC = REPO_ROOT / "backend" / "build-logic"


def job_block(workflow, job):
    """workflow ファイルから jobs.<job> のブロック（次のジョブ見出しまで）を取り出す。"""
    import re
    lines = (WORKFLOWS_DIR / workflow).read_text(encoding="utf-8").splitlines()
    start = lines.index(f"  {job}:")
    end = next((i for i in range(start + 1, len(lines)) if re.fullmatch(r"  [A-Za-z0-9_-]+:\s*", lines[i])), len(lines))
    return "\n".join(lines[start:end])


class CiShardWorkflowStaticTest(unittest.TestCase):
    # (workflow, shard ジョブ名, ジョブ上限, テスト step 上限) — 上限は現状値から変えない（AC-9）
    # PR Check は CMP-261114-0713 で採択した実行上限を維持し、8 shard 化で巻き戻さない。
    TARGETS = [
        ("backend-ci.yml", "shard",
         "timeout-minutes: ${{ needs.detect.outputs.migrations_changed == 'true' && 120 || 110 }}",
         "timeout-minutes: ${{ needs.detect.outputs.migrations_changed == 'true' && 110 || 95 }}"),
        ("backend-nightly-full.yml", "full-test", "timeout-minutes: 120", "timeout-minutes: 105"),
        ("backend-deploy.yml", "test",
         "timeout-minutes: ${{ needs.detect.outputs.migrations_changed == 'true' && 120 || 100 }}",
         "timeout-minutes: ${{ needs.detect.outputs.migrations_changed == 'true' && 105 || 85 }}"),
    ]

    def test_ac1_shard_total_is_eight_and_matrix_is_zero_to_seven_in_all_workflows(self):
        for workflow, job, _, _ in self.TARGETS:
            with self.subTest(workflow=workflow):
                text = (WORKFLOWS_DIR / workflow).read_text(encoding="utf-8")
                self.assertIn('  SHARD_TOTAL: "8"', text)
                self.assertNotIn('  SHARD_TOTAL: "6"', text)
                self.assertIn("shard: [0, 1, 2, 3, 4, 5, 6, 7]", job_block(workflow, job))

    def test_ac9_job_and_step_limits_are_unchanged(self):
        for workflow, job, job_limit, step_limit in self.TARGETS:
            with self.subTest(workflow=workflow):
                lines = job_block(workflow, job).splitlines()
                self.assertIn("    " + job_limit, lines, "ジョブ上限は現状値のまま")
                start = next(i for i, line in enumerate(lines) if line.strip().startswith("- name: Run tests (shard"))
                end = next((i for i in range(start + 1, len(lines)) if lines[i].strip().startswith("- name:")), len(lines))
                step_timeouts = [line.strip() for line in lines[start:end] if line.strip().startswith("timeout-minutes:")]
                self.assertEqual(step_timeouts, [step_limit], "テスト step 上限は現状値のまま")

    def test_ac9_required_check_name_compile_and_test_remains(self):
        self.assertIn("    name: Compile & Test", job_block("backend-ci.yml", "gate"))

    def test_ac11_build_logic_unit_tests_are_configured_and_run_in_shard_coverage_guard(self):
        import re
        build = (BUILD_LOGIC / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("useJUnitPlatform()", build)
        self.assertTrue(list((BUILD_LOGIC / "src" / "test" / "kotlin").glob("*Test.kt")), "build-logic の単体テストが存在すること")
        guard = job_block("backend-ci.yml", "shard-coverage-guard")
        self.assertRegex(guard, r"gradlew\b[^\n]*(?:-p\s+build-logic\b[^\n]*\btest\b|:(?:mannschaft-)?build-logic:test\b)",
                         "shard-coverage-guard ジョブで build-logic の test タスクを実行すること")
        self.assertIn("    needs: detect", guard)


# ---------------------------------------------------------------------------
# CMP-261111-0508 試練（甲）K-7: regen 互換。
# runtime-diag artifact に context-progress.log（[ci-ctx] 行）が入っても regen が通り、
# 重み表は同ファイルの有無で一致する。REQUIRED にはしない（無い ZIP も従来どおり通る）。
# 未知ファイルの拒否は従来どおり。
# ---------------------------------------------------------------------------
CTX_SAMPLE = (
    b"[ci-ctx] jvm=1234 seq=1 class=example.Class0 key=000000000000 event=load loadMs=4210 cacheSize=1\n"
    b"[ci-ctx] jvm=1234 seq=2 class=example.Class1 key=000000000000 event=hit loadMs=0 cacheSize=1\n"
)


def runtime_zip(files, extra=()):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, content in files.items():
            archive.writestr("runtime/" + name, content)
        for name, content in extra:
            archive.writestr(name, content)
    payload = stream.getvalue()
    return dict(size_in_bytes=len(payload), digest="sha256:" + hashlib.sha256(payload).hexdigest()), payload


class ContextProgressLogCompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.program = load_program()

    def generate(self, data, target):
        run, jobs, shards = data
        weights = self.program["build_weights"](run, jobs, shards, "nightly-full")
        self.program["atomic_write"](target, self.program["render"](run, weights))
        return weights

    def test_k7_context_progress_log_is_allowed_but_not_required(self):
        self.assertIn("context-progress.log", self.program["ALLOWED"], "ZIP 許可リストに context-progress.log を足すこと")
        self.assertNotIn("context-progress.log", self.program["REQUIRED"], "REQUIRED にはしない（無い ZIP も通す）")

    def test_k7_weights_are_identical_with_and_without_context_progress_log(self):
        for observation in [b"", CTX_SAMPLE]:
            with self.subTest(observation=observation), tempfile.TemporaryDirectory() as d:
                data = fixture()
                target = Path(d) / "weights"
                expected = self.generate(data, target)
                expected_output = target.read_bytes()
                for _, files in data[2].values():
                    files["context-progress.log"] = observation
                    meta, payload = runtime_zip(files)
                    parsed = self.program["safe_zip"](meta, payload)
                    self.assertEqual(parsed["context-progress.log"], observation)
                    files.clear()
                    files.update(parsed)
                self.assertEqual(self.generate(data, target), expected)
                self.assertEqual(target.read_bytes(), expected_output)

    def test_k7_unknown_and_unsafe_members_are_still_rejected(self):
        invalid_members = {
            "unknown": ([("runtime/context-progress.log.txt", b"x")], "unknown/duplicate/oversized"),
            "unknown-sibling": ([("runtime/context-other.log", b"x")], "unknown/duplicate/oversized"),
            "traversal": ([("../context-progress.log", b"x")], "unsafe zip path/type"),
            "duplicate": ([("runtime/context-progress.log", b"x"), ("other/context-progress.log", b"x")],
                          "unknown/duplicate/oversized"),
            "oversized": ([("runtime/context-progress.log", b"x" * (2 * 1024 * 1024 + 1))], "unknown/duplicate/oversized"),
        }
        for name, (extra, error) in invalid_members.items():
            with self.subTest(name=name):
                meta, payload = runtime_zip(fixture()[2][0][1], extra)
                with self.assertRaisesRegex(ValueError, error):
                    self.program["safe_zip"](meta, payload)


if __name__ == "__main__":
    unittest.main()
