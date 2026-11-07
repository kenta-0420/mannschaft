"""CI wrapper の終了判定と有界診断を、短い偽コマンドで検証する。"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

SCRIPT = Path(__file__).with_name('run-ci-tests-with-observability.sh').resolve()
BASH = shutil.which('bash') if os.name != 'nt' else r'C:/Program Files/Git/usr/bin/bash.exe'


class WrapperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=SCRIPT.parent)
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.bin = self.folder / 'bin'
        self.bin.mkdir()
        python = Path(os.sys.executable).as_posix()
        (self.bin / 'python3').write_text(f'#!/usr/bin/env bash\nexec "{python}" "$@"\n', encoding='utf-8')
        (self.bin / 'python3').chmod(0o755)
        self.env = dict(os.environ, CI_DIAGNOSTIC_DIR=self.folder.as_posix(), RUNNER_TEMP=self.folder.as_posix(),
                        CI_SHARD_INDEX='2', CI_JOB_TIMEOUT_MINUTES='120', CI_TEST_TIMEOUT_MINUTES='105')
        self.env['PATH'] = self.bin.as_posix() + os.pathsep + self.env['PATH']

    def run_wrapper(self, *args, env=None):
        launch = [BASH, str(SCRIPT), *args]
        if os.name == 'nt':
            launch = [BASH, '-c', 'export PATH="$(/usr/bin/cygpath -u "$1"):/usr/bin:/bin"; shift; exec bash "$@"',
                      '_', self.bin.as_posix(), SCRIPT.as_posix(), *args]
        return subprocess.run(launch, env=env or self.env,
                              capture_output=True, text=True, encoding='utf-8', timeout=15)

    def fake(self, exit_code=0):
        script = self.folder / 'fake.sh'
        script.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "2026-11-06T00:00:00Z [ci-test-task-state] task=:test skipped=false skipMessage=null upToDate=false noSource=false failed=false" > "$CI_DIAGNOSTIC_DIR/task-state.log"\nexit ' + str(exit_code) + '\n')
        return self.run_wrapper(BASH, script.as_posix())

    def result(self):
        return json.loads((self.folder / 'result.json').read_text())

    def test_finalize_keeps_limits_without_environment(self):
        self.assertEqual(self.fake().returncode, 0)
        env = {k: v for k, v in self.env.items() if k not in ['CI_JOB_TIMEOUT_MINUTES', 'CI_TEST_TIMEOUT_MINUTES']}
        self.assertEqual(self.run_wrapper('--finalize', 'cancelled', env=env).returncode, 0)
        result = self.result()
        self.assertEqual((result['jobLimitMinutes'], result['testStepLimitMinutes']), (120, 105))
        self.assertFalse(result['completed'])
        self.assertIsNone(result['testStepExitCode'])

    def test_new_run_does_not_keep_previous_limits_or_resources(self):
        (self.folder / 'worker-resources.log').write_text('OLD-SECRET\n')
        self.env.pop('CI_JOB_TIMEOUT_MINUTES')
        self.env.pop('CI_TEST_TIMEOUT_MINUTES')
        self.assertEqual(self.fake().returncode, 0)
        self.assertIsNone(self.result()['jobLimitMinutes'])
        self.assertNotIn('OLD-SECRET', (self.folder / 'worker-resources.log').read_text())

    def test_original_nonzero_is_kept(self):
        self.assertEqual(self.fake(17).returncode, 17)
        self.assertEqual(self.result()['commandExitCode'], 17)

    def test_success_requires_terminal(self):
        self.assertEqual(self.run_wrapper(BASH, '-c', 'exit 0').returncode, 125)
        self.assertFalse(self.result()['completed'])

    def test_term_keeps_interrupted_exit_and_does_not_claim_completion(self):
        self.assertEqual(self.run_wrapper(BASH, '-c', 'kill -TERM "$PPID"; exit 19').returncode, 143)
        self.assertEqual(self.result()['shellExitCode'], 143)
        self.assertFalse(self.result()['completed'])
        self.assertIsNone(self.result()['testStepExitCode'])

    def test_monitor_shards_execute_wrapper_and_reap_their_monitor(self):
        # wrapper自体のshard分岐を通す。外部Python monitorだけ短命Bashへ代替する。
        python = Path(os.sys.executable).as_posix()
        shim = '''#!/usr/bin/env bash
if [ "${1:-}" = '-u' ]; then
  trap 'printf "TERM\\n" > "$RUNNER_TEMP/monitor-ended"; exit 0' TERM
  printf '%s\\n' "$$" > "$RUNNER_TEMP/monitor-pid"
  sleep 3
  printf 'SELF\\n' > "$RUNNER_TEMP/monitor-ended"
  exit 0
fi
exec "''' + python + '''" "$@"
'''
        (self.bin / 'python3').write_text(shim, encoding='utf-8')
        # Git Bashにはpsがない。wrapper自身が起動した子の親を返す外部境界のstub。
        ps = self.bin / 'ps'
        ps.write_text('#!/usr/bin/env bash\nIFS= read -r owner < "$RUNNER_TEMP/wrapper-pid"\nprintf "%s\\n" "$owner"\n', encoding='utf-8')
        ps.chmod(0o755)
        command = self.folder / 'monitor-command.sh'
        command.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$PPID" > "$RUNNER_TEMP/wrapper-pid"
for ((i=0; i<60; i++)); do
  [ -f "$RUNNER_TEMP/monitor-pid" ] && break
  sleep 0.05
done
printf '%s\\n' '2026-11-06T00:00:00Z [ci-test-task-state] task=:test skipped=false skipMessage=null upToDate=false noSource=false failed=false' > "$CI_DIAGNOSTIC_DIR/task-state.log"
exit "$FAKE_EXIT"
''', encoding='utf-8')
        for shard in ('0', '1', '3', '5'):
            for code in (0, 17):
                with self.subTest(shard=shard, exit=code):
                    for name in ('monitor-pid', 'monitor-ended'):
                        (self.folder / name).unlink(missing_ok=True)
                    self.env.update(CI_SHARD_INDEX=shard, FAKE_EXIT=str(code))
                    result = self.run_wrapper(BASH, command.as_posix())
                    self.assertNotIn('PYTHON', result.stderr)
                    self.assertNotIn('command not found', result.stderr)
                    self.assertEqual(result.returncode, code, result.stderr)
                    self.assertEqual(self.result()['commandExitCode'], code)
                    self.assertEqual((self.folder / 'monitor-ended').read_text().strip(), 'TERM')


class MonitorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=SCRIPT.parent)
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        (self.folder / 'worker-resources.log').touch()
        (self.folder / 'class-progress.log').touch()
        source = SCRIPT.read_text(encoding='utf-8').split("<<'MONITOR' &\n", 1)[1].split('\nMONITOR', 1)[0]
        self.ns = {'__name__': 'monitor_test'}
        with mock.patch.dict(os.environ, CI_DIAGNOSTIC_DIR=str(self.folder), CI_SHARD_INDEX='5'):
            exec(compile(source, 'run-ci-tests-with-observability.sh:MONITOR', 'exec'), self.ns)

    def log(self):
        return (self.folder / 'worker-resources.log').read_text()

    def window(self, active=True):
        target = self.ns['R2']
        (self.folder / 'class-progress.log').write_text(
            f'2026-11-06T00:00:00Z [ci-class-start] task=:test class={target}\n' +
            ('' if active else f'2026-11-06T00:00:01Z [ci-class-end] task=:test class={target} elapsedMs=1000 result=SUCCESS\n'))

    def run_once(self, identities, active=True):
        self.window(active)
        for pid in (12, 13, 14):
            self.ns['append'](f'[test-jvm-heap] pid={pid} maxMemory=4096MB startTicks=100')
        calls = []
        def collect(argv, kind, pid=None, generation=None):
            calls.append((argv, kind, pid, generation))
            return True
        self.ns['collect'] = collect
        self.ns['identity'] = lambda pid: identities.get(pid)
        self.ns['resources'] = lambda: None
        self.ns['time'] = mock.Mock(monotonic=lambda: 100, time=lambda: 2000000000,
                                    sleep=lambda _: self.ns.update(stopping=True))
        with mock.patch.object(self.ns['signal'], 'signal'):
            self.ns['run']()
        return calls

    def test_registered_generation_only_and_six_pairs_maximum(self):
        calls = self.run_once({12: '100', 13: '100', 14: '101'})
        threads = [call for call in calls if call[1] == 'thread']
        self.assertEqual(len(threads), 6)
        self.assertEqual({call[2] for call in calls}, {12, 13})
        self.assertEqual(self.ns['attempts'], 6)
        self.assertIn('successfulPairs=6 missingPairs=0', self.log())
        self.assertIn('pid=14 status=ended-or-reused', self.log())

    def test_finished_window_does_not_follow_another_class(self):
        calls = self.run_once({12: '100'}, active=False)
        self.assertEqual(len([c for c in calls if c[1] == 'thread']), 0)
        self.assertIn('threadPairs=0 successfulPairs=0 missingPairs=6', self.log())

    def test_shard_three_keeps_start_and_end_samples_without_attach(self):
        self.ns['shard'] = '3'
        samples = []
        self.ns['resources'] = lambda: samples.append(True)
        self.ns['time'] = mock.Mock(monotonic=lambda: 100, sleep=lambda _: self.ns.update(stopping=True))
        with mock.patch.object(self.ns['signal'], 'signal'), mock.patch.object(subprocess, 'Popen') as popen:
            self.ns['run']()
        self.assertEqual(len(samples), 2)
        popen.assert_not_called()

    def test_slow_shards_collect_resources_immediately_every_300_seconds_without_attach(self):
        for shard in ('0', '1'):
            with self.subTest(shard=shard):
                self.ns.update(shard=shard, stopping=False)
                samples = []
                sleeps = []
                self.ns['resources'] = lambda: samples.append(True)
                self.ns['time'] = mock.Mock(monotonic=mock.Mock(side_effect=[100, 399, 400]))
                def sleep(seconds):
                    sleeps.append(seconds)
                    if len(sleeps) == 3:
                        self.ns['stopping'] = True
                self.ns['time'].sleep = sleep
                with mock.patch.object(self.ns['signal'], 'signal'), mock.patch.object(subprocess, 'Popen') as popen:
                    self.ns['run']()
                self.assertEqual(len(samples), 2)
                self.assertEqual(sleeps, [1, 1, 1])
                popen.assert_not_called()

    def test_resource_details_for_slow_shards_and_five_filter_noise_and_report_missing_files(self):
        # OSの読取境界だけを固定する。実ホストのpressure/cgroup値には依存しない。
        original_read = Path.read_text
        values = {'/proc/meminfo': 'MemTotal: 8000 kB\nMemAvailable: 4000 kB\nSwapFree: 0 kB\nPrivate: SECRET\n',
                  '/proc/pressure/cpu': 'some avg10=0.01 avg60=0.02 avg300=0.03 total=100\nSECRET!\n',
                  '/proc/pressure/memory': 'full avg10=0.00 avg60=0.00 avg300=0.00 total=0\n',
                  '/sys/fs/cgroup/cpu.max': '200000 100000\n',
                  '/sys/fs/cgroup/memory.max': 'max\n',
                  '/sys/fs/cgroup/memory.current': '1234\n'}
        def read(path, *args, **kwargs):
            name = path.as_posix()
            if name.startswith(('/proc/', '/sys/fs/cgroup/')):
                if name not in values:
                    raise FileNotFoundError(name)
                return values[name]
            return original_read(path, *args, **kwargs)
        for shard in ('0', '1', '5', '3'):
            with self.subTest(shard=shard):
                (self.folder / 'worker-resources.log').write_text('')
                self.ns['shard'] = shard
                with mock.patch.object(Path, 'read_text', read), mock.patch.object(os, 'cpu_count', return_value=2), \
                     mock.patch.dict(self.ns, collect=mock.Mock(return_value=True)):
                    self.ns['resources']()
                    self.ns['collect'].assert_called_once_with(
                        ['ps', '-C', 'java', '-o', 'pid,ppid,etimes,time,pcpu,rss,comm'], 'ps')
                log = self.log()
                self.assertNotIn('SECRET', log)
                if shard == '3':
                    self.assertNotIn('cpu-count=', log)
                    self.assertNotIn('cgroup/', log)
                    continue
                for expected in ('cpu-count=2', 'meminfo MemTotal: 8000 kB',
                                 'meminfo MemAvailable: 4000 kB', 'meminfo SwapFree: 0 kB',
                                 'pressure/cpu some avg10=', 'pressure/memory full avg10=',
                                 'pressure/io status=unavailable', 'cgroup/cpu.max 200000 100000',
                                 'cgroup/memory.max max', 'cgroup/memory.current 1234',
                                 'cgroup/cpu/cpu.cfs_quota_us status=unavailable'):
                    self.assertIn(expected, log)

    def test_pid_changed_before_attach_never_starts_command(self):
        self.ns['identity'] = lambda pid: '101'
        with mock.patch.object(subprocess, 'Popen') as popen:
            self.assertFalse(self.ns['collect'](['jcmd', '12', 'Thread.print', '-l'], 'thread', 12, '100'))
            popen.assert_not_called()
        self.assertIn('status=ended-or-reused', self.log())

    def test_log_cap_keeps_final_summary(self):
        (self.folder / 'worker-resources.log').write_bytes(b'x' * (self.ns['CAP'] - self.ns['RESERVE'] - 100))
        self.ns['append']('y' * 200)
        self.assertTrue(self.ns['full'])
        self.ns['append']('monitor-end reason=cap threadPairs=0 successfulPairs=0 missingPairs=6', final=True)
        self.assertLessEqual((self.folder / 'worker-resources.log').stat().st_size, 1572864)
        self.assertIn('monitor-end reason=cap', self.log())

    def test_attach_timeout_stops_child_and_does_not_record_stderr(self):
        self.ns['identity'] = lambda pid: '100'
        clock = iter([0, 0, 4, 4])
        self.ns['time'] = mock.Mock(monotonic=lambda: next(clock))
        child = mock.Mock(stdout=mock.Mock())
        child.wait.return_value = -9
        child.poll.return_value = None
        selector = mock.MagicMock()
        selector.__enter__.return_value = selector
        selector.select.return_value = []
        with mock.patch.object(subprocess, 'Popen', return_value=child) as popen, \
             mock.patch.object(self.ns['selectors'], 'DefaultSelector', return_value=selector):
            self.assertFalse(self.ns['collect'](['jcmd', '12', 'Thread.print', '-l'], 'thread', 12, '100'))
        child.kill.assert_called()
        child.wait.assert_called()
        self.assertIsNone(self.ns['active_child'])
        self.assertEqual(popen.call_args.kwargs['stderr'], subprocess.DEVNULL)
        self.assertIn('status=timeout', self.log())

    def test_monitor_process_exits_and_writes_summary(self):
        # 実際の短命Python子でmonitorのループ終了まで通す。JDK/OS採取境界のみ代替する。
        source = SCRIPT.read_text(encoding='utf-8').split("<<'MONITOR' &\n", 1)[1].split('\nMONITOR', 1)[0]
        child_program = self.folder / 'monitor.py'
        child_program.write_text(source.split("if __name__ == '__main__':", 1)[0] +
                                 '\nresources = lambda: None\ntime.sleep = lambda _: stop(None, None)\nrun()\n', encoding='utf-8')
        env = dict(os.environ, CI_DIAGNOSTIC_DIR=str(self.folder), CI_SHARD_INDEX='5')
        result = subprocess.run([os.sys.executable, str(child_program)], env=env, capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('monitor-start', self.log())
        self.assertIn('monitor-end reason=stopped', self.log())

    def test_thread_output_filters_names_and_noise_and_caps_bytes(self):
        self.ns['identity'] = lambda pid: '100'
        self.ns['time'] = mock.Mock(monotonic=lambda: 0)
        payload = (b'"SECRET-thread" #12 prio=5 tid=0xabc nid=0xdef waiting\n'
                   b'   java.lang.Thread.State: WAITING (parking)\n'
                   b'\tat java.lang.Object.wait(Native Method)\n'
                   b'credentials=SECRET\n')
        for output, expected in [(payload, True), (b'x' * 65537, False)]:
            with self.subTest(bytes=len(output)):
                child = mock.Mock(stdout=mock.Mock())
                child.poll.return_value = 0
                child.wait.return_value = 0
                selector = mock.MagicMock()
                selector.__enter__.return_value = selector
                selector.select.return_value = [True]
                with mock.patch.object(subprocess, 'Popen', return_value=child), \
                     mock.patch.object(self.ns['selectors'], 'DefaultSelector', return_value=selector), \
                     mock.patch.object(os, 'read', side_effect=[output, b'']):
                    self.assertEqual(self.ns['collect'](['jcmd', '12', 'Thread.print', '-l'], 'thread', 12, '100'), expected)
        self.assertNotIn('SECRET', self.log())
        self.assertIn('thread id=12 tid=0xabc nid=0xdef', self.log())
        self.assertIn('java.lang.Object.wait(Native Method)', self.log())
        self.assertIn('status=output-cap', self.log())


if __name__ == '__main__':
    unittest.main()
