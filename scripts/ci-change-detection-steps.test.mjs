import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// 必須チェック3本の「変更パス検出」ステップ（id: changes）の run: 本体を workflow の YAML から
// 抜き出し、一時 git リポジトリで実際に実行して GITHUB_OUTPUT に書かれる値を検証する
// （CMP-261121-1114）。文字列の存在検査ではなく、判定結果（出力値）そのものを固定する。
// 経路: pull_request / merge_group × 関連変更あり / なし / 判定器のみ変更 / SHA 空 / diff 失敗、
// および workflow_dispatch。

const repository = fileURLToPath(new URL('../', import.meta.url));
const SCRIPT_FILES = [
  '.github/scripts/detect-changed-files.sh',
  '.github/scripts/detect-migration-changes.sh',
  '.github/scripts/migration-sensitive-paths.regex',
];
const MISSING_SHA = '0123456789abcdef0123456789abcdef01234567';

/** jobs.<jobId>.steps の id: changes ステップの run: | ブロックを字下げを外して返す。 */
export function extractChangesRun(yamlText, jobId) {
  const lines = yamlText.replace(/\r\n/g, '\n').split('\n');
  const jobStart = lines.indexOf(`  ${jobId}:`);
  assert.ok(jobStart >= 0, `ジョブ ${jobId} が無い`);
  let jobEnd = lines.findIndex((l, i) => i > jobStart && /^ {0,2}[^\s#]/.test(l));
  if (jobEnd < 0) jobEnd = lines.length;
  const idLine = lines.findIndex((l, i) => i > jobStart && i < jobEnd && l === '        id: changes');
  assert.ok(idLine >= 0, `${jobId} に id: changes のステップが無い`);
  let stepStart = idLine;
  while (!lines[stepStart].startsWith('      - ')) stepStart -= 1;
  let stepEnd = lines.findIndex((l, i) => i > idLine && (l.startsWith('      - ') || /^ {0,4}[^\s#]/.test(l)));
  if (stepEnd < 0) stepEnd = lines.length;
  const runLine = lines.findIndex((l, i) => i > stepStart && i < stepEnd && l === '        run: |');
  assert.ok(runLine >= 0, `${jobId} の changes ステップに run: | が無い`);
  const body = [];
  for (const line of lines.slice(runLine + 1, stepEnd)) {
    if (line.trim() !== '' && !line.startsWith('          ')) break;
    body.push(line.slice(10));
  }
  const script = body.join('\n').replace(/\s+$/, '') + '\n';
  // 式展開（\${{ }}）が run 本体に残っていると、ここでの実行と Actions 上の実行が食い違う
  assert.doesNotMatch(script, /\$\{\{/, 'run: 本体に \${{ }} が残っている（env 経由で渡すこと）');
  return script;
}

function git(cwd, ...args) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`git ${args.join(' ')} が失敗: ${result.stderr}`);
  return result.stdout.trim();
}

function put(root, path, content) {
  const full = join(root, path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, content);
}

function commitAll(root, message) {
  git(root, 'add', '-A');
  git(root, 'commit', '-q', '--allow-empty', '-m', message);
  return git(root, 'rev-parse', 'HEAD');
}

/** 一時リポジトリ。実際の判定スクリプト・パターンファイルを LF で持ち込む。 */
function makeRepo(t) {
  const root = mkdtempSync(join(tmpdir(), 'mannschaft-change-steps-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  git(root, 'init', '-q', '-b', 'main');
  git(root, 'config', 'user.email', 'test@example.invalid');
  git(root, 'config', 'user.name', 'test');
  git(root, 'config', 'core.autocrlf', 'false');
  for (const path of SCRIPT_FILES) {
    put(root, path, readFileSync(join(repository, path), 'utf8').replace(/\r\n/g, '\n'));
  }
  put(root, 'README.md', 'root\n');
  put(root, 'backend/src/main/java/App.java', 'class App {}\n');
  const initial = commitAll(root, 'initial');
  return { root, initial };
}

/**
 * 変更 files を積んだ head を作り、{ base, head } を返す（head をチェックアウトした状態にする）。
 * pull_request: base = 分岐元（main）。merge_group: base = 先行エントリの合成コミット。
 */
function scenario(root, initial, event, files, label) {
  git(root, 'checkout', '-q', '--detach', initial);
  let base = initial;
  if (event === 'merge_group') {
    // 先行エントリの合成コミット（無関係な変更）。base_sha はここを指す。
    put(root, `docs/preceding-${label}.md`, 'preceding\n');
    base = commitAll(root, `先行エントリ ${label}`);
  }
  for (const path of files) {
    if (SCRIPT_FILES.includes(path)) appendFileSync(join(root, path), '\n');
    else put(root, path, `${label}\n`);
  }
  const head = commitAll(root, `自エントリ ${label}`);
  return { base, head };
}

function runStep(root, script, env) {
  const stepFile = join(root, '..', `${root.split(/[\\/]/).pop()}-step.sh`);
  const outFile = join(root, '..', `${root.split(/[\\/]/).pop()}-output`);
  writeFileSync(stepFile, script);
  writeFileSync(outFile, '');
  try {
    const result = spawnSync('bash', [stepFile], {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, ...env, GITHUB_OUTPUT: outFile },
    });
    assert.equal(result.status, 0, `ステップが失敗: ${result.stdout}${result.stderr}`);
    const out = {};
    for (const line of readFileSync(outFile, 'utf8').split(/\r?\n/)) {
      const m = /^([a-z_]+)=(.*)$/.exec(line);
      if (m) {
        assert.equal(out[m[1]], undefined, `${m[1]} が二重に出力された`);
        out[m[1]] = m[2];
      }
    }
    return out;
  } finally {
    rmSync(stepFile, { force: true });
    rmSync(outFile, { force: true });
  }
}

const ALL = (keys, value) => Object.fromEntries(keys.map((k) => [k, value]));

const WORKFLOWS = [
  {
    file: '.github/workflows/backend-ci.yml',
    jobId: 'detect',
    keys: ['backend_changed', 'migrations_changed'],
    cases: [
      { label: 'backend-java', files: ['backend/src/main/java/B.java'], expect: { backend_changed: 'true', migrations_changed: 'false' } },
      { label: 'migration', files: ['backend/src/main/resources/db/migration/V2__x.sql'], expect: { backend_changed: 'true', migrations_changed: 'true' } },
      { label: 'docs-only', files: ['docs/guide.md'], expect: { backend_changed: 'false', migrations_changed: 'false' } },
      { label: 'detector-changed-files', files: ['.github/scripts/detect-changed-files.sh'], expect: { backend_changed: 'true', migrations_changed: 'true' } },
      { label: 'detector-migration', files: ['.github/scripts/detect-migration-changes.sh'], expect: { backend_changed: 'true', migrations_changed: 'true' } },
      { label: 'detector-regex', files: ['.github/scripts/migration-sensitive-paths.regex'], expect: { backend_changed: 'true', migrations_changed: 'true' } },
    ],
  },
  {
    file: '.github/workflows/openapi-drift-check.yml',
    jobId: 'drift-check',
    keys: ['openapi_related'],
    cases: [
      { label: 'controller', files: ['backend/src/main/java/com/x/controller/C.java'], expect: { openapi_related: 'true' } },
      { label: 'service-only', files: ['backend/src/main/java/com/x/service/S.java'], expect: { openapi_related: 'false' } },
      { label: 'docs-only', files: ['docs/guide.md'], expect: { openapi_related: 'false' } },
      { label: 'detector-changed-files', files: ['.github/scripts/detect-changed-files.sh'], expect: { openapi_related: 'true' } },
    ],
  },
  {
    file: '.github/workflows/er-diagram-drift-check.yml',
    jobId: 'drift-check',
    keys: ['er_related'],
    cases: [
      { label: 'entity', files: ['backend/src/main/java/com/x/entity/E.java'], expect: { er_related: 'true' } },
      { label: 'migration', files: ['backend/src/main/resources/db/migration/V2__x.sql'], expect: { er_related: 'true' } },
      { label: 'docs-only', files: ['docs/guide.md'], expect: { er_related: 'false' } },
      { label: 'detector-changed-files', files: ['.github/scripts/detect-changed-files.sh'], expect: { er_related: 'true' } },
    ],
  },
];

for (const wf of WORKFLOWS) {
  const script = extractChangesRun(readFileSync(join(repository, wf.file), 'utf8'), wf.jobId);

  for (const event of ['pull_request', 'merge_group']) {
    test(`${wf.file} [${event}]: 変更内容ごとの出力値`, async (t) => {
      const { root, initial } = makeRepo(t);
      for (const c of wf.cases) {
        await t.test(c.label, () => {
          const { base, head } = scenario(root, initial, event, c.files, `${event}-${c.label}`);
          const out = runStep(root, script, { EVENT_NAME: event, BASE_SHA: base, HEAD_SHA: head });
          assert.deepEqual(out, c.expect);
        });
      }
    });

    test(`${wf.file} [${event}]: SHA 空・diff 失敗は判定不能として全実行に倒す`, async (t) => {
      const { root, initial } = makeRepo(t);
      const { base, head } = scenario(root, initial, event, ['docs/guide.md'], `${event}-unknown`);
      for (const [label, env] of [
        ['base 空', { BASE_SHA: '', HEAD_SHA: head }],
        ['head 空', { BASE_SHA: base, HEAD_SHA: '' }],
        ['存在しない SHA', { BASE_SHA: base, HEAD_SHA: MISSING_SHA }],
      ]) {
        await t.test(label, () => {
          const out = runStep(root, script, { EVENT_NAME: event, ...env });
          assert.deepEqual(out, ALL(wf.keys, 'true'));
        });
      }
    });
  }

  test(`${wf.file} [workflow_dispatch]: 差分判定せず全実行`, (t) => {
    const { root, initial } = makeRepo(t);
    const out = runStep(root, script, { EVENT_NAME: 'workflow_dispatch', BASE_SHA: '', HEAD_SHA: '' });
    assert.deepEqual(out, ALL(wf.keys, 'true'));
    assert.ok(initial);
  });
}
