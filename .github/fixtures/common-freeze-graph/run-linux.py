"""固定7caseのGradle公開task graphだけを専用Linux runnerで実測する。"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

SOURCE = Path(__file__).resolve().parent
ROOT = Path(os.environ['RUNNER_TEMP']) / 'common-freeze-graph'
EVIDENCE = ROOT / 'evidence'


def source_pins():
    files = [f for f in SOURCE.iterdir() if f.is_file()]
    files.append(SOURCE.parents[1] / 'workflows/common-freeze-graph.yml')
    return {str(f.relative_to(SOURCE.parents[2])): sha(f) for f in files}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def execute(args, cwd, env, destination, deadline):
    """自隊session leaderをWNOWAITで保持し、PID再利用前に自隊groupだけ片付ける。"""
    destination.mkdir()
    record = {'argv': args, 'cwd': str(cwd), 'startedNs': time.time_ns()}
    with (destination / 'stdout.log').open('wb') as stdout, (destination / 'stderr.log').open('wb') as stderr:
        process = subprocess.Popen(args, cwd=cwd, env=env, stdout=stdout, stderr=stderr,
                                   start_new_session=True)
        record['ownedSessionLeader'] = process.pid
        failure = None
        try:
            while True:
                observed = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if observed:
                    break
                if time.monotonic() >= deadline:
                    raise TimeoutError('有限command deadline超過')
                time.sleep(0.05)
        except BaseException as error:
            failure = error
        finally:
            # poll()/wait()でleaderを先にreapしない。自隊PIDはまだ再利用されない。
            try:
                os.killpg(process.pid, signal.SIGTERM)
                end = time.monotonic() + 2
                while time.monotonic() < end:
                    if os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT):
                        break
                    time.sleep(0.05)
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            record['exit'] = process.wait(timeout=5)
            end = time.monotonic() + 5
            while True:
                try:
                    os.killpg(process.pid, 0)
                except ProcessLookupError:
                    record['ownedGroupRemaining'] = False
                    break
                if time.monotonic() >= end:
                    record['ownedGroupRemaining'] = True
                    failure = failure or RuntimeError('自隊group残存。再解釈したPIDを停止しない')
                    break
                time.sleep(0.05)
            record['finishedNs'] = time.time_ns()
            record['failure'] = repr(failure) if failure else None
            save(destination / 'process.json', record)
        if failure:
            raise failure
    return record


def xml_records(project, task, destination):
    records = []
    expected_class = {'archUnitTest': 'fixture.ArchProbeTest',
                      'archUnitFreezeStoreIntegrityTest': 'fixture.GuardProbeTest',
                      'test': 'fixture.BusinessProbeTest'}[task]
    sources = sorted((project / 'build/test-results' / task).glob('TEST-*.xml'))
    require(len(sources) <= 1, task + ' XML suite重複')
    for source in sources:
        data = source.read_bytes()
        root = ET.fromstring(data)
        require(root.get('name') == expected_class, task + ' XML root suite不一致')
        require(all(c.get('classname') == expected_class for c in root.findall('testcase')),
                task + ' XML testcase classname不一致')
        cases = [{'classname': c.get('classname'), 'name': c.get('name'),
                  'failures': len(c.findall('failure')), 'errors': len(c.findall('error')),
                  'skipped': len(c.findall('skipped')),
                  'failureDetails': [{'message': f.get('message', ''), 'text': f.text or ''}
                                     for f in c.findall('failure')]} for c in root.findall('testcase')]
        record = {'suite': root.get('name'), 'sha256': hashlib.sha256(data).hexdigest().upper(),
                  **{key: int(root.get(key, '0')) for key in ['tests', 'failures', 'errors', 'skipped']},
                  'cases': cases}
        require(record['tests'] == len(cases), 'XML case数不一致')
        for key in ['failures', 'errors', 'skipped']:
            require(record[key] == sum(c[key] for c in cases), 'XML集計不一致')
        require(len({(c['classname'], c['name']) for c in cases}) == len(cases), 'XML case重複')
        target = destination / (task + '-' + source.name)
        target.write_bytes(data)
        records.append(record)
    return records


def marker(project):
    file = project / 'business-ran'
    return {'sha256': sha(file), 'mtimeNs': file.stat().st_mtime_ns} if file.exists() else None


def outcomes(log, task):
    return [item or 'EXECUTED' for item in re.findall(
        r'^> Task :' + task + r'(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?\s*$', log, re.M)]


def assert_step(case, step, result, log):
    require(result['process']['exit'] != 0 if case.get('exitNonzero')
            else result['process']['exit'] == case['exit'], 'command exitが期待と異なる')
    for task, key in [('archUnitTest', 'archCases'), ('archUnitFreezeStoreIntegrityTest', 'guardCases')]:
        expected = case.get(key, 0)
        actual = result['xml'][task]
        require(sum(x['tests'] for x in actual) == expected, task + ' XML数不一致')
        if expected:
            require(result['outcomes'][task] in [['EXECUTED'], ['FAILED']], task + ' actual実行なし')
            failure_key = 'archFailures' if key == 'archCases' else 'guardFailures'
            require(sum(x['failures'] for x in actual) == case.get(failure_key, 0), task + ' failure数不一致')
            require(sum(x['errors'] + x['skipped'] for x in actual) == 0, task + ' error/skipあり')
    if case['id'] == 'missing-class':
        require(case['requiredLog'] in log, '必須class欠落理由なし')
        require(result['xml']['archUnitFreezeStoreIntegrityTest'] == [], '欠落guard XMLは0必須')
        require(result['outcomes']['archUnitFreezeStoreIntegrityTest'] == ['FAILED'], '欠落guard非FAIL')
    if case['id'] in ['arch-fail', 'guard-fail']:
        task, method, failure_marker = (
            ('archUnitTest', 'actualTestTaskFailure', 'OWNED_FIXTURE_ARCH_FAILURE')
            if case['id'] == 'arch-fail' else
            ('archUnitFreezeStoreIntegrityTest', 'explicitFailure', 'OWNED_FIXTURE_GUARD_FAILURE'))
        failed_cases = [c for suite in result['xml'][task] for c in suite['cases'] if c['failures']]
        require(len(failed_cases) == 1, '負case固有failureが一意でない')
        failed = failed_cases[0]
        require(failed['name'] in [method, method + '()'], '負caseの失敗methodがfixtureと異なる')
        require(any(failure_marker in f['message'] or failure_marker in f['text']
                    for f in failed['failureDetails']), '負case固有failure markerなし')
    if case.get('archCases') and case.get('guardCases'):
        require(log.index('> Task :archUnitTest') < log.index('> Task :archUnitFreezeStoreIntegrityTest'), 'Arch→Guard順序不正')
    business = result['xml']['test']
    actual = result['actualBusinessCases']
    if case['businessCases'] == 0:
        require(business == [] and result['markerAfter'] is None, '負case/direct/CLIで業務実行')
    else:
        require(result['outcomes']['test'] in [['EXECUTED'], ['UP-TO-DATE'], ['FROM-CACHE']], '業務task outcome不正')
        if step == 0 or case['id'] != 'cache-repeat' or result['outcomes']['test'] == ['EXECUTED']:
            require(actual == 1 and result['markerAfter'] is not None, '業務actual1/markerなし')
            require(sum(x['failures'] + x['errors'] + x['skipped'] for x in business) == 0, '業務F/E/Sあり')
            if step:
                require(result['markerBefore'] is None or result['markerBefore']['mtimeNs'] != result['markerAfter']['mtimeNs'], '業務新実行marker不変')
        else:
            require(actual is None, 'cache残存XMLを新実行へ数えた')
    if result['outcomes']['test'] == ['EXECUTED']:
        require(log.index('> Task :archUnitFreezeStoreIntegrityTest') < log.index('> Task :test\n'), 'Guard→業務順序不正')


def main():
    require(sys.platform == 'linux', '専用Linux runner以外は実行しない')
    require(not EVIDENCE.exists(), '既存証拠上書き拒否')
    EVIDENCE.mkdir(parents=True)
    results = []
    failure = None
    checked_out = None
    projects = ROOT / 'projects'
    project_pins = {}
    source_before = source_pins()
    save(EVIDENCE / 'source-before.json', source_before)
    try:
        checked_out = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, timeout=5).decode().strip()
        require(checked_out == os.environ['GITHUB_SHA'], 'checkout merge SHA不一致')
        save(EVIDENCE / 'source-identity.json', {'checkedOutCommit': checked_out,
             'eventMergeSHA': os.environ['GITHUB_SHA'], 'prHeadSHA': os.environ['PR_HEAD_SHA'],
             'runID': os.environ['GITHUB_RUN_ID'], 'runAttempt': os.environ['GITHUB_RUN_ATTEMPT'],
             'claim': 'PR headとcheckout merge SHAを分離。本番クラス/Windows Job成功へ転用しない。'})
        pins = json.loads((SOURCE / 'pins.json').read_text(encoding='utf-8'))
        expectations = json.loads((SOURCE / 'expectations-v2.json').read_text(encoding='utf-8'))
        require(expectations['version'] == 2, 'cache規範v2以外')
        for name, digest in pins['fixtureSHA256'].items():
            require(sha(SOURCE / name) == digest, '固定fixture SHA不一致: ' + name)
        require(sha(SOURCE / 'expectations-v2.json') == pins['expectationsSHA256'], '期待v2 SHA不一致')
        require(len(pins['jarPins']) == 7 and len(pins['cases']) == 7, '閉集合件数不一致')
        require(sum(c.get('repeat', 1) for c in pins['cases']) == 8, '8command以外')
        jars = ROOT / 'jars'
        jars.mkdir()
        download_end = time.monotonic() + 120
        downloaded = []
        for row in pins['jarPins']:
            group, artifact, version = row['coordinate'].split(':')
            name = artifact + '-' + version + '.jar'
            url = 'https://repo.maven.apache.org/maven2/' + group.replace('.', '/') + '/' + artifact + '/' + version + '/' + name
            remaining = download_end - time.monotonic()
            require(remaining > 0, '7jar取得120秒超過')
            with urllib.request.urlopen(url, timeout=min(remaining, 20)) as response:
                require(response.geturl() == url, 'Maven取得先redirectを拒否')
                data = response.read(row['bytes'] + 1)
            require(time.monotonic() <= download_end, '閉7jar取得120秒超過')
            require(len(data) == row['bytes'] and hashlib.sha256(data).hexdigest().upper() == row['sha256'], 'primary jar SHA/size不一致')
            (jars / name).write_bytes(data)
            downloaded.append({**row, 'url': url})
        save(EVIDENCE / 'jar-pins.json', downloaded)
        require(sum(p.stat().st_size for p in jars.iterdir()) == 1161754, '閉7jar容量不一致')
        env = dict(os.environ)
        env['GRADLE_USER_HOME'] = str(ROOT / 'gradle-home')
        for key in ['CLASSPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
            env.pop(key, None)
        env['GRADLE_OPTS'] = ''
        gradle = shutil.which('gradle')
        require(gradle is not None, 'Gradle配布なし')
        version = execute([gradle, '--version'], ROOT, env, EVIDENCE / 'gradle-version', time.monotonic() + 20)
        require(version['exit'] == 0 and re.search(r'^Gradle 8\.14\.4$', (EVIDENCE / 'gradle-version/stdout.log').read_text(), re.M), 'Gradle版不一致')
        java = execute([str(Path(env['JAVA_HOME']) / 'bin/java'), '-version'], ROOT, env, EVIDENCE / 'java-version', time.monotonic() + 10)
        require(java['exit'] == 0 and 'version "21' in (EVIDENCE / 'java-version/stderr.log').read_text(), 'Java21版不一致')
        projects.mkdir()
        for case in pins['cases']:
            project = projects / case['id']
            (project / 'src/test/java/fixture').mkdir(parents=True)
            shutil.copyfile(SOURCE / 'fixture.build.gradle', project / 'build.gradle')
            for name in ['ArchProbeTest.java', 'GuardProbeTest.java', 'BusinessProbeTest.java']:
                shutil.copyfile(SOURCE / name, project / 'src/test/java/fixture' / name)
            shutil.copytree(jars, project / 'jars')
            (project / 'settings.gradle').write_text("rootProject.name = 'finite-graph'\n", encoding='utf-8')
            (project / 'store').write_bytes(b'owned\n')
            require(not marker(project) and not (project / 'build').exists(), '初回残存fixture拒否')
            project_pins[case['id']] = {str(f.relative_to(project)): sha(f) for f in project.rglob('*') if f.is_file()}
        save(EVIDENCE / 'project-before.json', project_pins)
        deadline = time.monotonic() + 480
        for case in pins['cases']:
            project = projects / case['id']
            for step in range(case.get('repeat', 1)):
                destination = EVIDENCE / (case['id'] + '-' + str(step + 1))
                before = marker(project)
                args = [gradle, *case['args'], '--offline', '--no-daemon', '--max-workers=1', '--console=plain', '-Dorg.gradle.jvmargs=-Xmx256m -Dfile.encoding=UTF-8']
                if case['id'] == 'missing-class':
                    args.append('--stacktrace')
                process = execute(args, project, env, destination, min(deadline, time.monotonic() + 60))
                log = (destination / 'stdout.log').read_text(encoding='utf-8', errors='replace')
                combined = log + (destination / 'stderr.log').read_text(encoding='utf-8', errors='replace')
                task_outcomes = {t: outcomes(log, t) for t in ['archUnitTest', 'archUnitFreezeStoreIntegrityTest', 'test']}
                xml = {t: xml_records(project, t, destination) for t in task_outcomes}
                result = {'case': case['id'], 'step': step + 1, 'process': process, 'outcomes': task_outcomes,
                          'xml': xml, 'markerBefore': before, 'markerAfter': marker(project),
                          'actualBusinessCases': sum(x['tests'] for x in xml['test']) if task_outcomes['test'] == ['EXECUTED'] else None}
                results.append(result)
                save(destination / 'assertions.json', result)
                assert_step(case, step, result, combined)
                for relative, digest in project_pins[case['id']].items():
                    require(sha(project / relative) == digest, 'project原本変更: ' + relative)
        require(len(results) == 8, '8command未完了')
    except BaseException as error:
        failure = repr(error)
    finally:
        final_projects = {}
        for case_id, expected in project_pins.items():
            actual = {}
            for relative, digest in expected.items():
                file = projects / case_id / relative
                actual[relative] = sha(file) if file.is_file() else None
                if actual[relative] != digest:
                    failure = failure or 'project pre/post原本不一致: ' + case_id + '/' + relative
            final_projects[case_id] = actual
        save(EVIDENCE / 'project-after.json', final_projects)
        after = source_pins()
        save(EVIDENCE / 'source-after.json', after)
        if after != source_before:
            failure = failure or 'source pre/post SHA不一致'
        try:
            final_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, timeout=5).decode().strip()
            save(EVIDENCE / 'git-after.json', {'commit': final_commit, 'sameAsBefore': final_commit == checked_out})
            if checked_out is not None and final_commit != checked_out:
                failure = failure or 'git source HEAD変更'
        except Exception as error:
            failure = failure or 'git after確認不能: ' + repr(error)
        save(EVIDENCE / 'results.json', {'cases': 7, 'commands': len(results), 'results': results,
              'failure': failure, 'passed': failure is None and len(results) == 8,
              'claim': 'Gradle公開graphのみ。Windows Job Object/lease・本番Arch ruleの代用ではない。'})
        save(EVIDENCE / 'manifest.json', [{'path': str(f.relative_to(EVIDENCE)), 'bytes': f.stat().st_size,
             'sha256': sha(f)} for f in sorted(EVIDENCE.rglob('*')) if f.is_file() and f.name != 'manifest.json'])
    if failure:
        raise RuntimeError(failure)


if __name__ == '__main__':
    main()
