import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  parseTaskList,
  selectEvidencePaths,
  validateCompletionChanges,
  validateEvidence,
} from './completion-evidence.mjs';

const headers = '| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|';
const row = (state, evidence = '説明') => `| CMP-260101-0101 | 戦役 | ${state} | — | 条件 | ${evidence} | ledger |`;
const base = `${headers}\n${row('実装中')}\n`;
const proofLink = '[証拠](evidence/CMP-260101-0101.json)';
const passed = { status: 'passed', evidence: 'scripts/check.mjs' };
const n_a = { status: 'not-applicable', reason: '対象外', scope: 'tooling' };
const evidence = {
  schemaVersion: 1,
  cmpId: 'CMP-260101-0101',
  scope: 'tooling',
  implementation: { pr: 42, sha: 'a'.repeat(40) },
  acceptanceCriteria: [{ id: 'AC-1', description: '検査する', tests: [{ name: 'unit', evidence: 'scripts/check.mjs' }] }],
  ci: [{ runId: 100 }],
  stages: { review: passed, real: n_a, exploratory: n_a },
  regressions: [],
};
const validationOptions = {
  repository: 'owner/repo',
  fileExists: (path) => path === 'scripts/check.mjs',
  implementation: { repository: 'owner/repo', merged: true, sha: 'a'.repeat(40), checks: ['success'], files: ['scripts/check.mjs'] },
  runs: new Map([[100, { repository: 'owner/repo', sha: 'a'.repeat(40), conclusion: 'success' }]]),
};

test('台帳をエスケープ済み縦棒込みで7列に分解しCMP重複を検出する', () => {
  const parsed = parseTaskList(`${headers}\n${row('実装中', '文字列 a\\|b')}\n`);
  assert.equal(parsed.rows[0].cells.length, 7);
  assert.equal(parsed.rows[0].cells[5], '文字列 a|b');
  assert.throws(() => parseTaskList(`${headers}\n${row('実装中')}\n${row('完了')}\n`), /重複/);
  assert.throws(() => parseTaskList(`${headers}\n| CMP-260101-0101 | x | 完了 | only | 5 |\n`), /7列/);
});

test('証拠列のMarkdown destinationを相対pathとして解決し、外部URL内の文字列を誤認しない', () => {
  const taskListRelativeLink = `${headers}\n${row('完了', '[証拠](evidence/CMP-260101-0101.json)')}\n`;
  assert.deepEqual(selectEvidencePaths(base, taskListRelativeLink), ['docs/evidence/CMP-260101-0101.json']);
  const external = `${headers}\n${row('実装中', '[外部](https://example.com/docs/evidence/CMP-260101-0101.json)')}\n`;
  assert.deepEqual(selectEvidencePaths(external, external), []);
});

test('未完了から完了への遷移に証拠が必要', () => {
  assert.throws(() => validateCompletionChanges(base, `${headers}\n${row('完了')}\n`, {}, validationOptions), /証拠JSON/);
  assert.throws(() => validateCompletionChanges(base, `${headers}\n${row('完了', proofLink)}\n`, { 'docs/evidence/CMP-260101-0101.json': { ...evidence, acceptanceCriteria: [] } }, validationOptions), /acceptanceCriteria/);
});

test('既存完了行の移動・注記変更は免責だが証拠JSON改変は再検査する', () => {
  const old = `${headers}\n${row('完了', proofLink)}\n`;
  const moved = `${headers}\n\n${row('完了', `${proofLink} 注記`)}\n`;
  assert.doesNotThrow(() => validateCompletionChanges(old, moved, {}, validationOptions));
  assert.throws(() => validateCompletionChanges(old, moved, { 'docs/evidence/CMP-260101-0101.json': { ...evidence, schemaVersion: 2 } }, { ...validationOptions, evidenceChanged: new Set(['docs/evidence/CMP-260101-0101.json']) }), /schemaVersion/);
});

test('JSONの空・null・AC重複・実装SHA差・CI失敗を拒否する', () => {
  assert.throws(() => validateEvidence(null, 'CMP-260101-0101', validationOptions), /JSON/);
  assert.throws(() => validateEvidence({ ...evidence, acceptanceCriteria: [] }, 'CMP-260101-0101', validationOptions), /acceptanceCriteria/);
  assert.throws(() => validateEvidence({ ...evidence, acceptanceCriteria: [evidence.acceptanceCriteria[0], evidence.acceptanceCriteria[0]] }, 'CMP-260101-0101', validationOptions), /重複/);
  assert.throws(() => validateEvidence({ ...evidence, implementation: { ...evidence.implementation, sha: 'b'.repeat(40) } }, 'CMP-260101-0101', validationOptions), /SHA/);
  const failedRuns = { ...validationOptions, runs: new Map([[100, { repository: 'owner/repo', sha: 'a'.repeat(40), conclusion: 'failure' }]]) };
  assert.throws(() => validateEvidence(evidence, 'CMP-260101-0101', failedRuns), /CI/);
  assert.throws(() => validateEvidence({ ...evidence, acceptanceCriteria: [{ ...evidence.acceptanceCriteria[0], tests: [{ name: 'escape', evidence: '../secret.txt' }] }] }, 'CMP-260101-0101', validationOptions), /パストラバーサル/);
  const skippedChecks = { ...validationOptions, implementation: { ...validationOptions.implementation, checks: ['success', 'skipped', 'neutral'] } };
  assert.doesNotThrow(() => validateEvidence(evidence, 'CMP-260101-0101', skippedChecks));
  const failedChecks = { ...validationOptions, implementation: { ...validationOptions.implementation, checks: ['success', 'failure'] } };
  assert.throws(() => validateEvidence(evidence, 'CMP-260101-0101', failedChecks), /check/);
});

test('review必須passed、実機UIでは実機と探索passed、成功stageは証拠必須', () => {
  assert.throws(() => validateEvidence({ ...evidence, stages: { ...evidence.stages, review: n_a } }, 'CMP-260101-0101', validationOptions), /review/);
  assert.throws(() => validateEvidence({ ...evidence, scope: 'app' }, 'CMP-260101-0101', validationOptions), /実機/);
  assert.throws(() => validateEvidence({ ...evidence, stages: { ...evidence.stages, real: { status: 'pending' } } }, 'CMP-260101-0101', validationOptions), /passedまたはnot-applicable/);
  assert.throws(() => validateEvidence({ ...evidence, stages: { ...evidence.stages, review: { status: 'passed', evidence: '' } } }, 'CMP-260101-0101', validationOptions), /参照/);
});

test('過去完了の証拠link削除・差替えを拒否する', () => {
  const old = `${headers}\n${row('完了', proofLink)}\n`;
  assert.throws(() => validateCompletionChanges(old, `${headers}\n${row('完了', '説明のみ')}\n`, {}, validationOptions), /削除/);
  assert.throws(() => validateCompletionChanges(old, `${headers}\n${row('完了', '[証拠](docs/evidence/CMP-260101-0101-alt.json)')}\n`, {}, validationOptions), /一致/);
  assert.throws(() => validateCompletionChanges(old, old, {}, { ...validationOptions, evidenceDeleted: new Set(['docs/evidence/CMP-260101-0101.json']) }), /JSONを削除/);
});

test('実装PRのアプリ変更はapp必須、既知の基盤変更だけtoolingにできる', () => {
  assert.throws(() => validateEvidence({ ...evidence, scope: 'tooling' }, 'CMP-260101-0101', {
    ...validationOptions,
    implementation: { ...validationOptions.implementation, files: ['backend/src/Main.java'] },
  }), /scope/);
  assert.doesNotThrow(() => validateEvidence(evidence, 'CMP-260101-0101', validationOptions));
  assert.throws(() => validateEvidence({ ...evidence, scope: 'tooling' }, 'CMP-260101-0101', {
    ...validationOptions,
    implementation: { ...validationOptions.implementation, files: ['unknown/custom.yml'] },
  }), /scope/);
});

test('検証対象の証拠だけを選び、数百件の無関係な完了証拠をAPI対象にしない', () => {
  const historicalRows = Array.from({ length: 300 }, (_, index) => {
    const id = `CMP-${String(index).padStart(3, '0')}`;
    return `| ${id} | 過去 | **完了**（注記） | — | 条件 | 旧証拠 | ledger |`;
  }).join('\n');
  const historical = `${headers}\n${historicalRows}\n`;
  assert.deepEqual(selectEvidencePaths(historical, historical, ['docs/evidence/CMP-260101-0101.json']), []);
  assert.deepEqual(selectEvidencePaths(base, `${headers}\n${row('完了', proofLink)}\n`), ['docs/evidence/CMP-260101-0101.json']);
  const linked = `${headers}\n${row('完了', proofLink)}\n`;
  assert.deepEqual(selectEvidencePaths(linked, linked, ['docs/evidence/CMP-260101-0101.json']), ['docs/evidence/CMP-260101-0101.json']);
});

test('pull_request_targetはbase trusted validatorだけを実行しPR headを実行しない', () => {
  const workflow = readFileSync(new URL('../.github/workflows/task-list-pr-gate.yml', import.meta.url), 'utf8');
  assert.match(workflow, /pull_request_target:/);
  assert.match(workflow, /actions: read/);
  assert.match(workflow, /ref: \$\{\{ github\.event\.pull_request\.base\.sha \|\| github\.sha \}\}/);
  assert.match(workflow, /persist-credentials: false/);
  assert.match(workflow, /await import\(pathToFileURL\(validatorPath\)\.href\)/);
  assert.match(workflow, /selectEvidencePaths\(baseTaskList, headTaskList, changedEvidencePaths\)/);
  assert.match(workflow, /github\.rest\.git\.getTree/);
  assert.doesNotMatch(workflow, /github\.rest\.repos\.getContent\(\{ owner, repo, path: ref/);
  assert.doesNotMatch(workflow, /execFileSync|execSync|pull\.head\.sha\s*\}\}/);
  assert.match(workflow, /github\.rest\.actions\.getWorkflowRun/);
});

test('CLIはgit show経由の正例を通し、過去完了JSON削除を拒否する', async (t) => {
  const repo = await mkdtemp(join(tmpdir(), 'completion-evidence-cli-'));
  t.after(() => rm(repo, { recursive: true, force: true }));
  const git = (...args) => {
    const result = spawnSync('git', args, { cwd: repo, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git('init', '-q');
  await mkdir(join(repo, 'scripts'), { recursive: true });
  await mkdir(join(repo, 'docs', 'evidence'), { recursive: true });
  await writeFile(join(repo, 'scripts', 'acceptance.mjs'), 'export const checked = true;\n');
  await writeFile(join(repo, 'docs', 'evidence', 'review.md'), 'review record\n');
  await writeFile(join(repo, 'docs', 'task-list.md'), `${headers}\n${row('実装中')}\n`);
  git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'add', '.');
  git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture implementation');
  const implementationSha = git('rev-parse', 'HEAD');
  const baseRef = implementationSha;
  await writeFile(join(repo, 'docs', 'task-list.md'), `${headers}\n${row('完了', '[証拠](evidence/CMP-260101-0101.json)')}\n`);
  await writeFile(join(repo, 'docs', 'evidence', 'CMP-260101-0101.json'), `${JSON.stringify({
    schemaVersion: 1,
    cmpId: 'CMP-260101-0101',
    scope: 'tooling',
    implementation: { pr: 42, sha: implementationSha },
    acceptanceCriteria: [{ id: 'AC-1', description: 'CLI fixture', tests: [{ name: 'positive', evidence: 'scripts/acceptance.mjs' }] }],
    ci: [{ runId: 123 }],
    stages: { review: { status: 'passed', evidence: 'docs/evidence/review.md' }, real: n_a, exploratory: n_a },
    regressions: [],
  }, null, 2)}\n`);
  git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'add', '.');
  git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'complete with evidence');
  const goodRef = git('rev-parse', 'HEAD');
  const cli = fileURLToPath(new URL('./completion-evidence.mjs', import.meta.url));
  const positive = spawnSync(process.execPath, [cli, '--base', baseRef, '--head', goodRef], { cwd: repo, encoding: 'utf8' });
  assert.equal(positive.status, 0, positive.stderr);
  assert.match(positive.stdout, /GitHub API上のPR\/CI状態を検証していません/);
  git('rm', 'docs/evidence/CMP-260101-0101.json');
  git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'remove old proof');
  const badRef = git('rev-parse', 'HEAD');
  const negative = spawnSync(process.execPath, [cli, '--base', goodRef, '--head', badRef], { cwd: repo, encoding: 'utf8' });
  assert.notEqual(negative.status, 0);
  assert.match(negative.stderr, /証拠JSONを削除/);
});
