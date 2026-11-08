#!/usr/bin/env bash
# 全shard共通の観測。Gradleの引数・テスト設定は呼び出し元をそのまま使う。
set -euo pipefail
export CI_DIAGNOSTIC_DIR="${CI_DIAGNOSTIC_DIR:-$PWD/build/ci-diagnostics}"
mkdir -p "$CI_DIAGNOSTIC_DIR"

write_result() {
  python3 - "$CI_DIAGNOSTIC_DIR" "$1" "$2" <<'PYTHON'
import json, os, pathlib, sys, time
folder, raw_shell_exit, outcome = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
previous = json.loads((folder / 'result.json').read_text()) if (folder / 'result.json').exists() else {}
shell_exit = previous.get('shellExitCode') if raw_shell_exit == 'preserve' else json.loads(raw_shell_exit)
marker = folder / 'command-exit-code'
command_exit = json.loads(marker.read_text()) if marker.exists() else None
states = (folder / 'task-state.log').read_text().splitlines() if (folder / 'task-state.log').exists() else []
terminal = [line for line in states if ' task=:test ' in line]
cache_hit = len(terminal) == 1 and 'noSource=false' in terminal[0] and 'failed=false' in terminal[0] and any(
    flag in terminal[0] for flag in ['upToDate=true', 'skipMessage=FROM-CACHE', 'skipMessage=UP-TO-DATE'])
completed = not cache_hit and len(terminal) == 1 and 'skipped=false' in terminal[0] and 'noSource=false' in terminal[0] and 'upToDate=false' in terminal[0] and command_exit is not None
if completed and command_exit == 0 and 'failed=false' not in terminal[0]:
    completed = False
# cancellationではshellのEXIT値0が残る場合がある。step outcomeと正常終端も必要。
if command_exit == 0 and (shell_exit != 0 or (outcome and outcome != 'success')):
    completed = False
accepted_exit = command_exit if completed else None
started = folder / 'started-at-epoch'
def limit(env, key):
    value = os.environ.get(env)
    return int(value) if value else (previous.get(key) if raw_shell_exit == 'preserve' else None)
result = dict(testStepExitCode=accepted_exit, commandExitCode=command_exit, shellExitCode=shell_exit,
              completed=completed, cacheHit=cache_hit, testStepOutcome=outcome or None,
              startedAtEpoch=int(started.read_text()) if started.exists() else None,
              finishedAtEpoch=previous.get('finishedAtEpoch') if raw_shell_exit == 'preserve' else (int(time.time()) if shell_exit is not None else None),
              finalizedAtEpoch=int(time.time()) if outcome else None,
              jobLimitMinutes=limit('CI_JOB_TIMEOUT_MINUTES', 'jobLimitMinutes'),
              testStepLimitMinutes=limit('CI_TEST_TIMEOUT_MINUTES', 'testStepLimitMinutes'),
              artifactRecoveryGuaranteed=False)
(folder / 'result.json').write_text(json.dumps(result) + '\n')
PYTHON
}

# always stepが実際のGHA outcomeを付加する。未実行はnullのまま残す。
if [ "${1:-}" = '--finalize' ]; then
  write_result preserve "${2:?step outcomeが必要}"
  exit 0
fi
if [ "$#" -eq 0 ]; then
  write_result 125 ''
  exit 125
fi

date +%s > "$CI_DIAGNOSTIC_DIR/started-at-epoch"
printf 'null\n' > "$CI_DIAGNOSTIC_DIR/command-exit-code"
# この呼出し専用の観測ファイルを初期化し、過去の正常終端を流用しない。
for name in class-progress.log task-state.log case-intervals.log mfa-intervals.log worker-resources.log; do
  : > "$CI_DIAGNOSTIC_DIR/$name"
done
write_result null ''
INIT_FILE="$RUNNER_TEMP/class-progress.init.gradle"
cat > "$INIT_FILE" <<'GROOVY'
import org.gradle.api.tasks.testing.Test
import java.time.Instant

def directory = new File(System.getenv('CI_DIAGNOSTIC_DIR'))
directory.mkdirs()
def lock = new Object()
def emit = { String name, String message ->
    def line = Instant.now().toString() + ' ' + message
    synchronized (lock) { new File(directory, name).append(line + '\n', 'UTF-8') }
    println(line)
}
def mfaClass = 'com.mannschaft.app.auth.mfa.MfaBackupCodeLatencyIT'
def billingClass = 'com.mannschaft.app.billing.WithdrawalPayerHandoverAuthzIT'
gradle.allprojects { project ->
    project.tasks.withType(Test).configureEach { task ->
        def classBoundary = { descriptor -> descriptor.className != null && descriptor.parent?.className != descriptor.className }
        task.beforeSuite { descriptor ->
            if (classBoundary(descriptor)) emit('class-progress.log', "[ci-class-start] task=${task.path} class=${descriptor.className}")
        }
        task.afterSuite { descriptor, result ->
            if (classBoundary(descriptor)) emit('class-progress.log', "[ci-class-end] task=${task.path} class=${descriptor.className} elapsedMs=${result.endTime - result.startTime} result=${result.resultType}")
        }
        // MFA/billingのcase窓だけを記録する。表示名や引数は収録しない。
        task.beforeTest { descriptor ->
            if (descriptor.className in [mfaClass, billingClass]) emit('case-intervals.log', "[ci-case-start] task=${task.path} class=${descriptor.className} startEpochMillis=${System.currentTimeMillis()}")
        }
        task.afterTest { descriptor, result ->
            if (descriptor.className in [mfaClass, billingClass]) emit('case-intervals.log', "[ci-case-end] task=${task.path} class=${descriptor.className} startEpochMillis=${result.startTime} endEpochMillis=${result.endTime} result=${result.resultType}")
        }
        task.onOutput { descriptor, event ->
            // 既存の通常:testのheap行だけを登録する。PID再利用をOS開始tickで識別する。
            if (task.path == ':test' && System.getenv('CI_SHARD_INDEX') == '5') {
                event.message.readLines().findAll { it ==~ /\[test-jvm-heap\] pid=[1-9]\d* maxMemory=\d+MB/ }.each { message ->
                    try {
                        def pid = (message =~ /pid=(\d+)/)[0][1]
                        def stat = new File('/proc/' + pid + '/stat').text
                        def startTicks = stat.substring(stat.lastIndexOf(')') + 2).split(' ')[19]
                        synchronized (lock) {
                            def file = new File(directory, 'worker-resources.log')
                            // monitorと競合しても1行分と終了要約の余白を残す。
                            if (file.length() < 1572864 - 8192) file.append(Instant.now().toString() + ' ' + message + ' startTicks=' + startTicks + '\n', 'UTF-8')
                        }
                    } catch (Exception ignored) {
                        try {
                            synchronized (lock) {
                                def file = new File(directory, 'worker-resources.log')
                                if (file.length() < 1572864 - 8192) file.append(Instant.now().toString() + ' worker-registration status=unavailable\n', 'UTF-8')
                            }
                        } catch (Exception recordingFailure) {
                            println('worker-registration status=unavailable')
                        }
                    }
                }
            }
            if (descriptor.className == mfaClass) {
                event.message.readLines().findAll { it ==~ /\[mfa-latency\] pathKind=(all-mismatch|last-match) startEpochMillis=\d+ endEpochMillis=\d+ elapsedMillis=\d+ limitMillis=\d+ pid=\d+/ }.each {
                    emit('mfa-intervals.log', it)
                }
            }
        }
    }
}
gradle.taskGraph.afterTask { task, state ->
    if (task instanceof Test) emit('task-state.log', "[ci-test-task-state] task=${task.path} skipped=${state.skipped} skipMessage=${state.skipMessage} upToDate=${state.upToDate} noSource=${state.noSource} failed=${state.failure != null}")
}
GROOVY

COMMAND_PID=''
MONITOR_PID=''
finish_diagnostics() {
  local shell_exit=$?
  trap - EXIT
  set +e
  # 停止対象はこのshellが起動した正の子PIDだけ（Gradle管理はrunnerへ委ねる）。
  if [[ "$MONITOR_PID" =~ ^[0-9]+$ ]] && [ "$MONITOR_PID" -gt 1 ] && \
     [ "$(ps -o ppid= -p "$MONITOR_PID" | tr -d ' ')" = "$$" ]; then
    kill -TERM "$MONITOR_PID"
    wait "$MONITOR_PID"
  fi
  write_result "$shell_exit" ''
  local collector_exit=$?
  if [ "$shell_exit" -eq 0 ] && [ "$collector_exit" -ne 0 ]; then shell_exit=125; fi
  exit "$shell_exit"
}
interrupt_command() {
  trap - INT TERM
  if [[ "$COMMAND_PID" =~ ^[0-9]+$ ]] && [ "$COMMAND_PID" -gt 1 ] && \
     [ "$(ps -o ppid= -p "$COMMAND_PID" | tr -d ' ')" = "$$" ]; then
    kill -TERM "$COMMAND_PID" 2>/dev/null || true
  fi
  exit "$1"
}
trap finish_diagnostics EXIT
trap 'interrupt_command 130' INT
trap 'interrupt_command 143' TERM
# 遅延したshard0/1の数値資源、shard3の既存ps、shard5の限定診断を同じmonitorで実行する。
if [[ "${CI_SHARD_INDEX:-}" =~ ^(0|1|3|5)$ ]]; then
  python3 -u - <<'MONITOR' &
import datetime, os, pathlib, re, selectors, signal, subprocess, time

CAP = 1572864
RESERVE = 8192
R2 = 'com.mannschaft.app.common.testing.R2MockContextSharingIT'
folder = pathlib.Path(os.environ['CI_DIAGNOSTIC_DIR'])
resource = folder / 'worker-resources.log'
shard = os.environ['CI_SHARD_INDEX']
stopping = False
full = False
attempts = 0
successes = 0
active_child = None

def stop(signum, frame):
    global stopping
    stopping = True
    if active_child is not None:
        active_child.kill()

def stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def append(message, final=False):
    global full
    data = (stamp() + ' ' + message + '\n').encode('utf-8')
    if resource.stat().st_size + len(data) > CAP - (0 if final else RESERVE):
        full = True
        return
    with resource.open('ab') as output:
        output.write(data)

def identity(pid):
    try:
        stat = pathlib.Path('/proc', str(pid), 'stat').read_text()
        fields = stat[stat.rindex(')') + 2:].split()
        return fields[19] if fields[0] != 'Z' else None
    except (OSError, ValueError, IndexError):
        return None

def collect(argv, kind, pid=None, generation=None):
    # 出力・待ち時間とも有界。stderrや例外本文（接続情報等）は保存しない。
    global active_child
    if stopping or full:
        return False
    if pid is not None and identity(pid) != generation:
        append(f'{kind} pid={pid} status=ended-or-reused')
        return False
    lines = []
    size = 0
    status = 'ok'
    try:
        active_child = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        with selectors.DefaultSelector() as selector:
            selector.register(active_child.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 3
            while not stopping:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    status = 'timeout'
                    break
                if not selector.select(min(remaining, 0.1)):
                    continue
                chunk = os.read(active_child.stdout.fileno(), 8192)
                if not chunk:
                    break
                size += len(chunk)
                if size > 65536:
                    status = 'output-cap'
                    break
                lines.append(chunk)
        if status != 'ok' or stopping:
            active_child.kill()
        code = active_child.wait(timeout=max(0.1, deadline - time.monotonic()))
        if status == 'ok' and code != 0:
            status = 'failed'
    except (OSError, subprocess.TimeoutExpired):
        status = 'unavailable'
    finally:
        if active_child is not None:
            if active_child.poll() is None:
                active_child.kill()
            active_child.wait()
            active_child.stdout.close()
        active_child = None
    if stopping:
        status = 'stopped'
    elif pid is not None and identity(pid) != generation:
        status = 'ended-or-reused'
    append(f'{kind} pid={pid if pid is not None else 0} status={status}')
    valid_lines = 0
    if status == 'ok':
        for line in b''.join(lines).decode('utf-8', errors='replace').splitlines():
            # Thread名/引数を除き、ID・stack・state・lockの構造だけを保存する。
            if kind == 'thread':
                header = re.match(r'^".*" #(\d+) .*?tid=(0x[0-9a-f]+) nid=(0x[0-9a-f]+)\b', line)
                if header:
                    append(f'thread id={header[1]} tid={header[2]} nid={header[3]}')
                safe = re.fullmatch(r'\s*(?:at [\w.$/<>]+\([\w.$: /<>+-]*\)|java.lang.Thread.State: [A-Z_]+(?: \([\w ]+\))?|- (?:(?:locked|waiting to lock|parking to wait for|waiting on)\s+)?<0x[0-9a-f]+> \(a [\w.$]+\)|Locked ownable synchronizers:|- None)', line)
            elif kind == 'gc':
                safe = re.fullmatch(r'\s*(?:[\d.]+|-)(?:\s+(?:[\d.]+|-))*\s*', line)
            else:
                safe = re.fullmatch(r'[\s\d.:%A-Za-z_-]+', line)
            if safe:
                append(kind + ' ' + line)
                valid_lines += 1
        if pid is not None and valid_lines == 0:
            append(f'{kind} pid={pid} status=empty-or-filtered')
            return False
    return status == 'ok'

def resources():
    append('resource-sample')
    collect(['ps', '-C', 'java', '-o', 'pid,ppid,etimes,time,pcpu,rss,comm'], 'ps')
    if shard not in ('0', '1', '5'):
        return
    append(f'cpu-count={os.cpu_count()}')
    for name in ['meminfo', 'pressure/cpu', 'pressure/memory', 'pressure/io']:
        try:
            for line in pathlib.Path('/proc', name).read_text().splitlines():
                if name == 'meminfo' and not line.startswith(('MemTotal:', 'MemAvailable:', 'SwapFree:')):
                    continue
                if re.fullmatch(r'[A-Za-z0-9_.:= /-]+', line):
                    append(name + ' ' + line)
        except OSError:
            append(name + ' status=unavailable')
    for name in ['cpu.max', 'memory.max', 'memory.current', 'cpu/cpu.cfs_quota_us',
                 'cpu/cpu.cfs_period_us', 'memory/memory.limit_in_bytes']:
        try:
            value = pathlib.Path('/sys/fs/cgroup', name).read_text().strip()
            if re.fullmatch(r'(?:\d+|max)(?: \d+)?', value):
                append('cgroup/' + name + ' ' + value)
        except OSError:
            append('cgroup/' + name + ' status=unavailable')

def run():
    global attempts, successes, stopping
    workers = {}
    seen = set()
    window = None
    reached = set()
    active = False
    next_resource = 0
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    append(f'monitor-start pid={os.getpid()} shard={shard}')
    with resource.open(encoding='utf-8') as registrations, (folder / 'class-progress.log').open(encoding='utf-8') as classes:
        while not stopping and not full:
            now = time.monotonic()
            for line in registrations.readlines():
                match = re.fullmatch(r'\S+ \[test-jvm-heap\] pid=([1-9]\d*) maxMemory=\d+MB startTicks=(\d+)\n', line)
                if not match:
                    continue
                pid, generation = int(match[1]), match[2]
                if (pid, generation) in seen:
                    continue
                seen.add((pid, generation))
                if pid <= 1 or identity(pid) != generation:
                    append(f'worker pid={pid} status=ended-or-reused')
                else:
                    workers[pid] = generation
                    append(f'worker pid={pid} startTicks={generation} status=registered')
            workers = {pid: generation for pid, generation in workers.items() if identity(pid) == generation}
            for line in classes.readlines():
                match = re.fullmatch(r'(\S+) \[ci-class-(start|end)\] task=:test class=' + re.escape(R2) + r'(?: elapsedMs=\d+ result=[A-Z]+)?\n', line)
                if match:
                    active = match[2] == 'start'
                    if window is None and active:
                        window = datetime.datetime.fromisoformat(match[1].replace('Z', '+00:00')).timestamp()
            if now >= next_resource:
                resources()
                if shard == '5':
                    for pid, generation in sorted(workers.items())[:2]:
                        collect(['jstat', '-gcutil', str(pid)], 'gc', pid, generation)
                next_resource = now + (60 if shard == '5' else 300)
            if shard == '5' and window is not None:
                for offset in (60, 75, 90):
                    if offset in reached or time.time() - window < offset:
                        continue
                    reached.add(offset)
                    selected = sorted(workers.items())[:2] if active else []
                    append(f'window offset={offset} active={str(active).lower()} workers={len(selected)}')
                    for pid, generation in selected:
                        if attempts >= 6 or stopping or full:
                            break
                        attempts += 1
                        thread = collect(['jcmd', str(pid), 'Thread.print', '-l'], 'thread', pid, generation)
                        gc = collect(['jstat', '-gcutil', str(pid)], 'gc', pid, generation)
                        successes += int(thread and gc)
            time.sleep(0.2 if shard == '5' else 1)
    # shard3の終了時psも同じmonitor内で維持する（最大3秒、cap到達時は採取しない）。
    if shard == '3' and not full:
        stopping = False
        resources()
        stopping = True
    append(f'monitor-end reason={"cap" if full else "stopped"} threadPairs={attempts} successfulPairs={successes} missingPairs={6 - successes} windowReached={len(reached)}', final=True)

if __name__ == '__main__':
    try:
        run()
    except Exception:
        # 診断自体の失敗はテストのexitを変えない。例外本文は収録しない。
        append('monitor-end reason=diagnostic-failure', final=True)
MONITOR
  MONITOR_PID=$!
fi
set +e
"$@" --init-script "$INIT_FILE" &
COMMAND_PID=$!
wait "$COMMAND_PID"
COMMAND_EXIT=$?
COMMAND_PID=''
set -e
printf '%s\n' "$COMMAND_EXIT" > "$CI_DIAGNOSTIC_DIR/command-exit-code"
# 正当なcache/up-to-dateは元の0を保つが、freshな採取完了とは扱わない。
# NO-SOURCE/終端欠損は採取無効125。元のGradle非0はそのまま保持する。
if [ "$COMMAND_EXIT" -eq 0 ]; then
  if ! grep -Eq ' task=:test skipped=false .*upToDate=false noSource=false failed=false$| task=:test .*upToDate=true noSource=false failed=false$| task=:test .*skipMessage=(FROM-CACHE|UP-TO-DATE) .*noSource=false failed=false$' "$CI_DIAGNOSTIC_DIR/task-state.log"; then
    exit 125
  fi
fi
exit "$COMMAND_EXIT"
