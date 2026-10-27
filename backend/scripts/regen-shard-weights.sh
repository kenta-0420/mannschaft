#!/usr/bin/env bash
# 同一run/attemptの全6 fresh runtimeから通常:testのクラス実時間を再生成する。
# CLI形式は従来どおり。backend-ciはfull profileの証明不足として拒否し、既定は最新成功nightly。
# runtime artifactは保持7日。XMLへのfallbackやfailed runのwarn続行はしない。
# 未観測/削除/arch/perf/skipと現在sourceとの差は別manifestで検分する。
# historical runと現在HEADの一致は要求しない。割当と4profile guardは既存のまま。
set -euo pipefail
REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_FILE="${SCRIPT_DIR}/../src/test/resources/shard-weights.properties"
python3 - "$REPO" "${1:-}" "${2:-nightly-full}" "$OUT_FILE" <<'PYEOF'
import collections
import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile
import zipfile

WORKFLOWS = {'nightly-full': 'backend-nightly-full.yml', 'backend-ci': 'backend-ci.yml'}
ALLOWED = {'class-progress.log', 'task-state.log', 'result.json', 'command-exit-code',
           'started-at-epoch', 'case-intervals.log', 'mfa-intervals.log', 'worker-resources.log'}
REQUIRED = {'class-progress.log', 'task-state.log', 'result.json', 'command-exit-code', 'started-at-epoch'}
AUXILIARY = {':archTest', ':archUnitTest', ':archUnitFreezeStoreIntegrityTest', ':archSelfTest'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_zip(meta, data):
    require(len(data) == meta['size_in_bytes'] <= 4 * 1024 * 1024, 'runtime zip size mismatch/limit')
    require(meta.get('digest') == 'sha256:' + hashlib.sha256(data).hexdigest(), 'runtime digest mismatch')
    files = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(sum(v.file_size for v in archive.infolist()) <= 12 * 1024 * 1024, 'runtime expanded limit')
        for entry in archive.infolist():
            path = PurePosixPath(entry.filename)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in entry.filename
                    and ':' not in entry.filename and not stat.S_ISLNK(entry.external_attr >> 16), 'unsafe zip path/type')
            if entry.is_dir():
                continue
            require(path.name in ALLOWED and path.name not in files and entry.file_size <= 2 * 1024 * 1024,
                    'unknown/duplicate/oversized runtime member')
            files[path.name] = archive.read(entry)
    require(REQUIRED <= files.keys(), 'runtime files missing; XML fallback is unsupported')
    return files


def parse_shard(files):
    require(REQUIRED <= files.keys(), 'runtime files missing')
    result = json.loads(files['result.json'])
    require(result.get('completed') is True and result.get('cacheHit') is False, 'runtime not fresh/completed')
    for field in ['testStepExitCode', 'commandExitCode', 'shellExitCode']:
        require(type(result.get(field)) is int and result[field] == 0, 'runtime exit invalid: ' + field)
    require(result.get('testStepOutcome') == 'success' and files['command-exit-code'].strip() == b'0', 'step/marker invalid')
    require(type(result.get('startedAtEpoch')) is int and type(result.get('finishedAtEpoch')) is int
            and result['finishedAtEpoch'] >= result['startedAtEpoch'] == int(files['started-at-epoch']), 'runtime epoch invalid')
    terminal = [line for line in files['task-state.log'].decode('utf-8').splitlines() if ' task=:test ' in line]
    require(len(terminal) == 1 and all(flag in terminal[0] for flag in
            ['skipped=false', 'upToDate=false', 'noSource=false', 'failed=false']), 'normal test terminal invalid')
    starts, ends = {}, {}
    pattern = re.compile(r'^(\S+) \[ci-class-(start|end)\] task=(:\S+) class=(\S+)(?: elapsedMs=(\d+) result=(SUCCESS|FAILURE|SKIPPED))?$')
    for line in files['class-progress.log'].decode('utf-8').splitlines():
        match = pattern.fullmatch(line)
        require(match is not None, 'malformed class event')
        stamp, kind, task, name, millis, outcome = match.groups()
        if task != ':test':
            require(task in AUXILIARY, 'unknown runtime task: ' + task)
            continue
        instant = datetime.datetime.fromisoformat(stamp.replace('Z', '+00:00'))
        if kind == 'start':
            require(millis is None and name not in starts, 'duplicate/invalid class start')
            starts[name] = instant
        else:
            require(millis is not None and outcome == 'SUCCESS' and name not in ends, 'duplicate/failed/skipped class end')
            ends[name] = (instant, int(millis))
    require(starts and starts.keys() == ends.keys(), 'empty shard or unmatched class events')
    for name in starts:
        require(starts[name] <= ends[name][0], 'class event time reversed')
    # outerがある場合は子を加算せず、nested-onlyは独立した最外suiteだけを合算する。
    groups = collections.defaultdict(list)
    for name in ends:
        groups[name.split('$')[0]].append(name)
    return {top: sum(ends[name][1] for name in names if not any(
        name.startswith(other + '$') for other in names if other != name)) for top, names in groups.items()}


def build_weights(run, jobs, shards, kind):
    require(kind == 'nightly-full', 'backend-ci full profile cannot be established; use nightly-full')
    require(kind in WORKFLOWS and run.get('path', '').split('@')[0].split('/')[-1] == WORKFLOWS[kind], 'workflow mismatch')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'completed successful run required')
    require(re.fullmatch(r'[0-9a-f]{40}', run['head_sha']) and type(run['run_attempt']) is int and run['run_attempt'] > 0,
            'run head/attempt invalid')
    require(set(shards) == set(range(6)), 'all six shards required')
    prefix = 'Full test' if kind == 'nightly-full' else 'Test'
    weights = {}
    for n in range(6):
        selected = [j for j in jobs if j['name'] == f'{prefix} (shard {n})']
        require(len(selected) == 1 and selected[0]['status'] == 'completed' and selected[0]['conclusion'] == 'success', 'shard job invalid')
        require(selected[0]['run_id'] == run['id'] and selected[0]['run_attempt'] == run['run_attempt']
                and selected[0]['head_sha'] == run['head_sha'], 'shard job run/head/attempt mismatch')
        steps = [s for s in selected[0]['steps'] if s['name'].startswith(f'Run tests (shard {n} / 6')]
        require(len(steps) == 1 and steps[0]['conclusion'] == 'success', 'shard test step invalid')
        meta, files = shards[n]
        expected = (rf'nightly-runtime-diag-shard-{n}-attempt-{run["run_attempt"]}' if kind == 'nightly-full' else
                    rf'backend-runtime-diag-pr-(?:manual|\d+)-shard-{n}-attempt-{run["run_attempt"]}')
        require(re.fullmatch(expected, meta['name']) and not meta['expired'], 'artifact shard/attempt/expiry invalid')
        require(meta['workflow_run']['id'] == run['id'] and meta['workflow_run']['head_sha'] == run['head_sha'], 'artifact run/head mismatch')
        current = parse_shard(files)
        require(not weights.keys() & current.keys(), 'cross-shard class/nested split duplicate')
        weights.update(current)
    require(any(".migration." in name and "Flyway" in name.rsplit(".", 1)[-1] for name in weights),
            "migration/Flyway runtime missing; incomplete input")
    return weights


def render(run, weights):
    migration = {k: v for k, v in weights.items() if '.migration.' in k and 'Flyway' in k.rsplit('.', 1)[-1]}
    header = (f'# CI shard weights: metric=class-suite-elapsed-ms (normal :test)\n'
              f'# run={run["id"]} head={run["head_sha"]} attempt={run["run_attempt"]} conclusion=success\n'
              f'# workflow={run["path"]} branch={run["head_branch"]} created={run["created_at"]}\n'
              '# 全6 fresh runtime。outer suiteは子を二重加算せず、nested-onlyは独立最外suiteを合算。\n'
              '# XML testsuite.timeやMFA内部サービス計測値とは異なる。historical sourceの実測。\n'
              f'# classes={len(weights)} migration+Flyway={len(migration)} seconds={sum(migration.values()) / 1000:.3f}\n'
              '# 現在sourceの未計測/削除/arch/perf/skip分類と適用判断は別manifestで検分する。\n'
              '# backend/build.gradle.ktsの既存LPT/hashと4profile guardを維持する。\n'
              '# 再生成: backend/scripts/regen-shard-weights.sh [RUN_ID] [nightly-full|backend-ci]\n')
    return header + ''.join(f'{name}={weights[name] / 1000:.3f}\n' for name in sorted(weights))


def atomic_write(target, content):
    target = Path(target)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', newline='\n', dir=target.parent,
                                         prefix=target.name + '.', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(repo, run_id, kind, target):
    require(kind in WORKFLOWS, 'source kind must be nightly-full or backend-ci')
    require(kind == 'nightly-full', 'backend-ci full profile cannot be established; use nightly-full')
    def gh(*args):
        result = subprocess.run(['gh', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        require(result.returncode == 0, 'GitHub read failed')
        return result.stdout
    def api(path):
        return json.loads(gh('api', f'repos/{repo}/{path}'))
    if not run_id:
        candidates = json.loads(gh('run', 'list', '-R', repo, '--workflow', WORKFLOWS[kind], '--status', 'success',
                                   '--limit', '1', '--json', 'databaseId'))
        require(candidates, 'successful run not found')
        run_id = str(candidates[0]['databaseId'])
    require(run_id.isdecimal(), 'numeric run ID required')
    run = api(f'actions/runs/{run_id}')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'successful completed run required')
    jobs = api(f'actions/runs/{run_id}/attempts/{run["run_attempt"]}/jobs?per_page=100')['jobs']
    artifacts, page = [], 1
    while True:
        response = api(f'actions/runs/{run_id}/artifacts?per_page=100&page={page}')
        artifacts.extend(response['artifacts'])
        if len(artifacts) >= response['total_count']:
            break
        page += 1
    shards = {}
    for n in range(6):
        pattern = (rf'nightly-runtime-diag-shard-{n}-attempt-{run["run_attempt"]}' if kind == 'nightly-full' else
                   rf'backend-runtime-diag-pr-(?:manual|\d+)-shard-{n}-attempt-{run["run_attempt"]}')
        matches = [m for m in artifacts if re.fullmatch(pattern, m['name'])]
        require(len(matches) == 1, f'shard {n} runtime missing/duplicate; no XML fallback')
        meta = matches[0]
        require(not meta['expired'] and meta['size_in_bytes'] <= 4 * 1024 * 1024, 'artifact expired/oversized')
        data = gh('api', f'repos/{repo}/actions/artifacts/{meta["id"]}/zip')
        shards[n] = (meta, safe_zip(meta, data))
    weights = build_weights(run, jobs, shards, kind)
    atomic_write(target, render(run, weights))
    print(f'[OK] run={run_id} attempt={run["run_attempt"]} classes={len(weights)} metric=class-suite-elapsed-ms')


if __name__ == '__main__':
    try:
        main(*sys.argv[1:])
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print('[ERROR] ' + str(error), file=sys.stderr)
        sys.exit(1)
PYEOF
