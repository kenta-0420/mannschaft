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
result = dict(testStepExitCode=accepted_exit, commandExitCode=command_exit, shellExitCode=shell_exit,
              completed=completed, cacheHit=cache_hit, testStepOutcome=outcome or None,
              startedAtEpoch=int(started.read_text()) if started.exists() else None,
              finishedAtEpoch=previous.get('finishedAtEpoch') if raw_shell_exit == 'preserve' else (int(time.time()) if shell_exit is not None else None),
              finalizedAtEpoch=int(time.time()) if outcome else None,
              jobLimitMinutes=int(os.environ['CI_JOB_TIMEOUT_MINUTES']) if os.environ.get('CI_JOB_TIMEOUT_MINUTES') else None,
              testStepLimitMinutes=int(os.environ['CI_TEST_TIMEOUT_MINUTES']) if os.environ.get('CI_TEST_TIMEOUT_MINUTES') else None,
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
for name in class-progress.log task-state.log case-intervals.log mfa-intervals.log; do
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
  if [ "${CI_SHARD_INDEX:-}" = '3' ]; then
    ps -C java -o pid,ppid,etimes,time,pcpu,rss,comm >> "$CI_DIAGNOSTIC_DIR/worker-resources.log" || true
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
# 元shard3の数値ps監視だけを維持する（全環境・全引数を出さない）。
if [ "${CI_SHARD_INDEX:-}" = '3' ]; then
  python3 -u - > "$CI_DIAGNOSTIC_DIR/worker-resources.log" 2>&1 <<'PYTHON' &
import datetime, subprocess, time
while True:
    print(datetime.datetime.now(datetime.timezone.utc).isoformat(), flush=True)
    subprocess.run(['ps', '-C', 'java', '-o', 'pid,ppid,etimes,time,pcpu,rss,comm'], check=False)
    time.sleep(300)
PYTHON
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
