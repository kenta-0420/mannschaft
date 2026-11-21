import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

// 必須チェック3本（Compile & Test / OpenAPI Drift Check / ER Diagram Drift Check）が
// GitHub merge queue（merge_group イベント）に対応しているかを workflow の YAML 本文から
// 静的に検査する（CMP-261121-1114）。依存パッケージを持ち込まないため、YAML は
// インデントに基づく最小限の読み取りで扱う。Task-List PR Gate は別テストで扱う。

const repository = fileURLToPath(new URL('../', import.meta.url));
const read = (path) => readFileSync(join(repository, path), 'utf8').replace(/\r\n/g, '\n');

const WORKFLOWS = [
  {
    file: '.github/workflows/backend-ci.yml',
    concurrencyPrefix: 'backend-ci-',
    requiredJob: { id: 'gate', name: 'Compile & Test', jobIf: 'always()' },
    detectJobId: 'detect',
  },
  {
    file: '.github/workflows/openapi-drift-check.yml',
    concurrencyPrefix: 'openapi-drift-check-',
    requiredJob: { id: 'drift-check', name: 'OpenAPI Drift Check', jobIf: null },
    detectJobId: 'drift-check',
  },
  {
    file: '.github/workflows/er-diagram-drift-check.yml',
    concurrencyPrefix: 'er-diagram-drift-check-',
    requiredJob: { id: 'drift-check', name: 'ER Diagram Drift Check', jobIf: null },
    detectJobId: 'drift-check',
  },
];

/** トップレベルキー key: の配下（次のトップレベルキーまで）の行を返す。 */
function topLevelBlock(text, key) {
  const lines = text.split('\n');
  const start = lines.findIndex((line) => line === `${key}:` || line.startsWith(`${key}: `));
  assert.ok(start >= 0, `トップレベルの ${key}: が無い`);
  const block = [];
  for (const line of lines.slice(start + 1)) {
    if (/^[^\s#]/.test(line)) break;
    block.push(line);
  }
  return block;
}

/** jobs: 配下のジョブ id の定義行（2スペース字下げ）から、次のジョブまでの行を返す。 */
function jobBlock(text, jobId) {
  const jobs = topLevelBlock(text, 'jobs');
  const start = jobs.findIndex((line) => line === `  ${jobId}:`);
  assert.ok(start >= 0, `ジョブ ${jobId} が無い`);
  const block = [];
  for (const line of jobs.slice(start + 1)) {
    if (/^ {2}[^\s#]/.test(line)) break;
    block.push(line);
  }
  return block;
}

/** ジョブ直下（4スペース字下げ）のキーの値を返す。 */
function jobKey(block, key) {
  const line = block.find((l) => l.startsWith(`    ${key}:`));
  return line === undefined ? undefined : line.slice(`    ${key}:`.length).trim();
}

for (const wf of WORKFLOWS) {
  const text = read(wf.file);

  test(`${wf.file}: on: に merge_group（types: [checks_requested]）がある（AC-1）`, () => {
    const on = topLevelBlock(text, 'on');
    const idx = on.findIndex((line) => line === '  merge_group:');
    assert.ok(idx >= 0, 'on.merge_group が無い');
    const rest = [];
    for (const line of on.slice(idx + 1)) {
      if (/^ {2}[^\s#]/.test(line)) break;
      rest.push(line.trim());
    }
    assert.ok(
      rest.includes('types: [checks_requested]'),
      `merge_group.types に checks_requested が無い: ${rest.join(' | ')}`,
    );
    // PR 経路は従来どおり残す
    assert.ok(on.includes('  pull_request:'), 'on.pull_request が消えている');
    assert.ok(on.includes('  workflow_dispatch:'), 'on.workflow_dispatch が消えている');
  });

  test(`${wf.file}: 必須チェック名「${wf.requiredJob.name}」のジョブが merge_group でも報告される形を保つ（AC-2）`, () => {
    const block = jobBlock(text, wf.requiredJob.id);
    assert.equal(jobKey(block, 'name'), wf.requiredJob.name);
    const jobIf = jobKey(block, 'if');
    if (wf.requiredJob.jobIf === null) {
      assert.equal(jobIf, undefined, 'required ジョブに job-level if を付けてはならない（skipped な check になる）');
    } else {
      assert.equal(jobIf, wf.requiredJob.jobIf);
    }
    // 同名の required check を報告するジョブは1つだけ（workflow 名の name: は数えない）
    const sameName = topLevelBlock(text, 'jobs').filter((l) => l === `    name: ${wf.requiredJob.name}`);
    assert.equal(sameName.length, 1, `name: ${wf.requiredJob.name} が複数ある`);
  });

  test(`${wf.file}: concurrency group が PR 番号 → github.ref にフォールバックする（AC-3）`, () => {
    const block = topLevelBlock(text, 'concurrency');
    const group = block.find((l) => l.trim().startsWith('group:'));
    assert.ok(group, 'concurrency.group が無い');
    assert.equal(
      group.trim(),
      `group: ${wf.concurrencyPrefix}\${{ github.event.pull_request.number || github.ref }}`,
    );
  });

  test(`${wf.file}: 変更判定は共通スクリプト detect-changed-files.sh に merge_group の SHA を渡す（AC-4〜9）`, () => {
    const block = jobBlock(text, wf.detectJobId).join('\n');
    assert.match(block, /bash \.github\/scripts\/detect-changed-files\.sh/);
    assert.match(block, /github\.event\.merge_group\.base_sha/);
    assert.match(block, /github\.event\.merge_group\.head_sha/);
    assert.match(block, /github\.event\.pull_request\.base\.sha/);
    assert.match(block, /github\.event\.pull_request\.head\.sha/);
    // pull_request 以外を一律フル実行にする旧分岐が残っていない（merge_group が素通りでフル実行になる）
    assert.doesNotMatch(block, /github\.event_name \}\}" != "pull_request"/);
    // 判定スクリプトをインラインの git diff で迂回していない
    assert.doesNotMatch(block, /git diff --name-only "\$\{BASE_SHA\}\.\.\.\$\{HEAD_SHA\}"/);
    // merge_group はこのエントリ自身の差分で判定する旨を明記している（AC-6b 改訂）
    assert.match(block, /このエントリ自身の差分/);
  });

  test(`${wf.file}: 判定スクリプト自体の変更も実行条件に含める（AC-8b）`, () => {
    const block = jobBlock(text, wf.detectJobId).join('\n');
    assert.match(block, /\\\.github\/scripts\/detect-changed-files\\\.sh\$/);
  });
}

test('backend-ci.yml: migration 判定器（パターンファイル・判定スクリプト）の変更で backend_changed=true になる（AC-8b）', () => {
  const block = jobBlock(read('.github/workflows/backend-ci.yml'), 'detect').join('\n');
  assert.match(block, /\\\.github\/scripts\/migration-sensitive-paths\\\.regex\$/);
  assert.match(block, /\\\.github\/scripts\/detect-migration-changes\\\.sh\$/);
  assert.match(block, /\\\.github\/scripts\/detect-changed-files\\\.sh\$/);
});

test('migration-sensitive-paths.regex: 判定器自身の変更で migrations_changed=true になる（AC-8b）', () => {
  const patterns = read('.github/scripts/migration-sensitive-paths.regex')
    .split('\n')
    .filter((l) => l.length > 0)
    .map((l) => new RegExp(l));
  const matches = (path) => patterns.some((re) => re.test(path));
  for (const path of [
    '.github/scripts/detect-changed-files.sh',
    '.github/scripts/migration-sensitive-paths.regex',
    '.github/scripts/detect-migration-changes.sh',
    // 従来の対象も不変
    'backend/src/main/resources/db/migration/V1__x.sql',
    '.github/workflows/backend-ci.yml',
  ]) {
    assert.ok(matches(path), `${path} が migration-sensitive と判定されない`);
  }
  assert.ok(!matches('docs/guide.md'));
  assert.ok(!matches('backend/src/main/java/App.java'));
});

test('backend-ci.yml: 判定不能時は backend_changed=true・migrations_changed=true に倒す（AC-7）', () => {
  const block = jobBlock(read('.github/workflows/backend-ci.yml'), 'detect').join('\n');
  assert.match(block, /判定不能/);
  assert.match(block, /DETECT_STATUS/);
});

for (const file of ['.github/workflows/openapi-drift-check.yml', '.github/workflows/er-diagram-drift-check.yml']) {
  test(`${file}: 判定不能時は検査を実行する側に倒す（AC-7）`, () => {
    const block = jobBlock(read(file), 'drift-check').join('\n');
    assert.match(block, /判定不能/);
    assert.match(block, /DETECT_STATUS/);
  });
}

test('pr-light-checks.yml: merge queue 対応の試験2本が Harness に登録されている（AC-9b）', () => {
  const text = read('.github/workflows/pr-light-checks.yml');
  assert.match(text, /scripts\/detect-changed-files\.test\.mjs/);
  assert.match(text, /scripts\/ci-merge-group-workflows\.test\.mjs/);
  assert.match(text, /scripts\/ci-change-detection-steps\.test\.mjs/);
});
