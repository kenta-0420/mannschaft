"""新規GitHub-hosted専用jobのみ。所有Popen handle正常停止・group残存を実測する候補。"""
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[3]  # 配置先 .github/fixtures/village-history-real/
OUT = ROOT / 'frontend/build/village-history-real'
OUT.mkdir(parents=True, exist_ok=True)
children = []
handles = []
record = {'sourceVerified': False, 'processes': [], 'servicesCleanup': 'PENDING_PLATFORM_CLEANUP'}


def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def start(label, command, cwd, env):
    # logsは非公開tempのみ。source/cookie/PIIをartifactへ含めない。
    log = open('/tmp/vh-' + os.environ['GITHUB_RUN_ID'] + '-' + label + '.log', 'xb')
    handles.append(log)
    child = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                             stdin=subprocess.DEVNULL, start_new_session=True)
    children.append((label, child))
    record['processes'].append({'label': label, 'pid': child.pid, 'startMonotonic': time.monotonic()})
    return child


def ready(url, child, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if child.poll() is not None:
            raise RuntimeError('OWNED_SERVER_EARLY_EXIT')
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('OWNED_READY_DEADLINE')


def group_remaining(pid):
    # 固定所有groupへの存在確認だけ。全process列挙/unknown killは行わない。
    try:
        os.killpg(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return None


code = 125
try:
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise RuntimeError('DEDICATED_GITHUB_HOST_REQUIRED')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, timeout=10, text=True).strip()
    if head != os.environ['VH_EXPECTED_HEAD']:
        raise RuntimeError('SOURCE_HEAD_MISMATCH')
    for service, port_key, container_port in [('mysql', 'VH_MYSQL_PORT', '3306/tcp'),
                                               ('valkey', 'VH_VALKEY_PORT', '6379/tcp')]:
        service_id = os.environ['VH_' + service.upper() + '_SERVICE_ID']
        inspected = json.loads(subprocess.check_output(['docker', 'inspect', service_id], timeout=10, text=True))
        if len(inspected) != 1 or inspected[0]['Id'] != service_id or inspected[0]['State']['Running'] is not True:
            raise RuntimeError('PRESTART_SERVICE_IDENTITY_MISMATCH')
        if not any(int(p['HostPort']) == int(os.environ[port_key])
                   for p in inspected[0]['NetworkSettings']['Ports'].get(container_port, [])):
            raise RuntimeError('PRESTART_SERVICE_PORT_MISMATCH')
    for port in [18080, 13000]:
        with socket.socket() as check:
            check.settimeout(1)
            if check.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError('EXISTING_SERVER_BORROW_REFUSED')
    source_paths = ['frontend/app/pages/my/village-join-requests.vue',
                    'frontend/app/composables/village/useVillageJoinRequestHistory.ts',
                    'backend/src/main/java/com/mannschaft/app/village/controller/VillageJoinRequestController.java',
                    'backend/src/main/java/com/mannschaft/app/village/service/VillageMembershipService.java',
                    'backend/src/main/java/com/mannschaft/app/village/service/VillageJoinRequestService.java',
                    'backend/src/main/java/com/mannschaft/app/village/repository/VillageMembershipRepository.java',
                    'frontend/app/pages/villages/[id]/members.vue',
                    'frontend/app/pages/villages/[id]/join-request.vue',
                    'frontend/app/composables/village/useVillageMembershipApi.ts']
    blobs = []
    for relative in source_paths:
        blob = subprocess.check_output(['git', 'show', head + ':' + relative], cwd=ROOT, timeout=10)
        if (ROOT / relative).read_bytes() != blob:
            raise RuntimeError('SOURCE_BLOB_MISMATCH')
        blobs.append({'path': relative, 'sha256': hashlib.sha256(blob).hexdigest()})
    if b'/api/v1/village-join-requests/me' not in (ROOT / source_paths[2]).read_bytes():
        raise RuntimeError('INTEGRATED_HISTORY_API_ABSENT')
    jars = list((ROOT / 'backend/build/libs').glob('*.jar'))
    jars = [p for p in jars if not p.name.endswith('-plain.jar')]
    if len(jars) != 1:
        raise RuntimeError('OWNED_JAR_NOT_EXACT_ONE')
    build = json.loads((ROOT / 'backend/build/village-history-build-identity.json').read_text())
    jar_sha = hashlib.sha256(jars[0].read_bytes()).hexdigest()
    if build['head'] != head or build['jarSha256'] != jar_sha or build['sourceCleanAfterBuild'] is not True:
        raise RuntimeError('JAR_BUILD_SOURCE_IDENTITY_MISMATCH')
    record.update(sourceVerified=True, head=head, jarSha256=jar_sha, sourceBlobs=blobs)
    # 既CI profileのfake key/SES simulateを使い、専用job servicesに明示接続する。
    be_env = dict(os.environ, SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:' + os.environ['VH_MYSQL_PORT']
                  + '/' + os.environ['VH_DATABASE'] + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
                  SPRING_DATASOURCE_USERNAME=os.environ['VH_DB_USER'],
                  SPRING_DATASOURCE_PASSWORD=os.environ['VH_DB_PASSWORD'],
                  SPRING_DATA_REDIS_HOST='127.0.0.1', SPRING_DATA_REDIS_PORT=os.environ['VH_VALKEY_PORT'],
                  MANNSCHAFT_ALLOWED_ORIGINS='http://localhost:13000',
                  MANNSCHAFT_EMAIL_SIMULATE='true', MANNSCHAFT_EMAIL_OUTBOX_WORKER_ENABLED='false')
    be = start('be', ['java', '-Xmx2g', '-jar', str(jars[0]), '--spring.profiles.active=ci',
                     '--server.port=18080', '--server.shutdown=graceful',
                     '--spring.lifecycle.timeout-per-shutdown-phase=20s'], ROOT, be_env)
    ready('http://localhost:18080/actuator/health', be, 900)
    fe_env = dict(os.environ, NODE_OPTIONS='--dns-result-order=ipv4first --max-old-space-size=3072',
                  NUXT_PUBLIC_API_BASE='http://localhost:18080', NUXT_INTERNAL_API_BASE='http://localhost:18080',
                  NUXT_API_PROXY='true')
    # 直接Node+no-fork。npm shell/既server再利用をしない。
    fe = start('fe', ['node', 'node_modules/nuxt/bin/nuxt.mjs', 'dev', '--host', '127.0.0.1',
                     '--port', '13000', '--no-fork'], ROOT / 'frontend', fe_env)
    ready('http://localhost:13000/login', fe, 180)
    runner_env = dict(os.environ, BASE_URL='http://localhost:13000', API_BASE_URL='http://localhost:18080',
                      NODE_OPTIONS='--dns-result-order=ipv4first')
    runner = start('fixture-ui', ['node', '--experimental-strip-types',
                   'tests/e2e/real/run-village-history.ts'], ROOT / 'frontend', runner_env)
    code = runner.wait(timeout=900)
except subprocess.TimeoutExpired:
    record['failurePhase'] = 'OWNED_RUNTIME_TIMEOUT'
    code = 125
except Exception:
    record['failurePhase'] = 'SOURCE_OR_RUNTIME_ENVIRONMENT_UNPROVEN'
    code = 125
finally:
    cleanup = []
    for label, child in reversed(children):
        natural = child.poll() is not None
        normal = natural
        if not natural:
            # 取得済Popen handleだけ。PIDを読み直して別processを止めない。
            child.terminate()
            try:
                child.wait(timeout=30)
                normal = True
            except subprocess.TimeoutExpired:
                normal = False
                # 子残不明時はunknownを追ってkillしない。platform終了までUNPROVEN。
        remaining = group_remaining(child.pid)
        cleanup.append({'label': label, 'pid': child.pid, 'exit': child.poll(),
                        'normalStopConfirmed': normal, 'ownedGroupRemaining': remaining})
    for handle in handles:
        handle.close()
    clean = all(item['normalStopConfirmed'] and item['ownedGroupRemaining'] is False for item in cleanup)
    if not clean:
        code = 125
    record.update(exit=code, cleanup=cleanup, ownedProcessesRemaining=not clean,
                  ownedRuntimeCleanup='COMPLETE' if clean else 'UNPROVEN', finalCleanup='UNPROVEN')
    write('owned-runtime-receipt.json', record)
raise SystemExit(code)
