import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as validator from './completion-evidence.mjs';
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
  assert.match(workflow, /file\.status === 'removed'/);
  assert.match(workflow, /file\.previous_filename === validatorPathInBase/);
  assert.doesNotMatch(workflow, /execFileSync|execSync|pull\.head\.sha\s*\}\}/);
  assert.match(workflow, /github\.rest\.actions\.getWorkflowRun/);
});

test('AC-9: workflowはfilter all・paginate・世代判定・previous_filename・changed_files照合で実装PRを集める', () => {
  const workflow = readFileSync(new URL('../.github/workflows/task-list-pr-gate.yml', import.meta.url), 'utf8');
  assert.match(workflow, /github\.paginate\(github\.rest\.checks\.listForRef, \{ owner, repo, ref: implementationPr\.head\.sha, filter: 'all', per_page: 100 \}\)/);
  assert.match(workflow, /github\.paginate\(github\.rest\.repos\.listCommitStatusesForRef/);
  assert.match(workflow, /trustedValidator\.summarizeImplementationChecks\(\{ checkRuns, statuses, mergedAt: implementationPr\.merged_at \}\)/);
  assert.match(workflow, /previous_filename: file\.previous_filename/);
  assert.match(workflow, /implementationFiles\.length !== implementationPr\.changed_files/);
  assert.doesNotMatch(workflow, /latestChecks/);
  // 既存の信頼境界（validator名・legacy backfill の制約）を維持する
  assert.match(workflow, /validatorPathInBase = 'scripts\/completion-evidence\.mjs'/);
  assert.match(workflow, /context\.ref !== 'refs\/heads\/main'/);
  assert.match(workflow, /Date\.parse\(pr\.created_at\) < rolloutAt/);
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

const TEST_ONLY_3841 = [
  'backend/src/test/java/com/mannschaft/app/matching/MatchProposalCancelScopeContractIT.java',
  'backend/src/test/java/com/mannschaft/app/matching/MatchTemplateScopeContractIT.java',
  'backend/src/test/java/com/mannschaft/app/role/controller/MeControllerMembershipIntegrationTest.java',
  'backend/src/test/java/com/mannschaft/app/succession/controller/SuccessionCovenantScopeContractIT.java',
];
const naTestOnly = { status: 'not-applicable', reason: 'UIを持たないテストのみの変更', scope: 'test-only' };
const testOnlyEvidence = { ...evidence, scope: 'test-only', stages: { review: passed, real: naTestOnly, exploratory: naTestOnly } };
const withFiles = (files, extra = {}) => ({ ...validationOptions, implementation: { ...validationOptions.implementation, files, ...extra } });
const infer = (files) => validator.inferImplementationScope(files);
const summarizeChecks = (input) => validator.summarizeImplementationChecks(input);

test('AC-1: #3841の4ファイルはtest-onlyで、real/exploratoryをtest-onlyのn/aにすれば通る', () => {
  assert.equal(infer(TEST_ONLY_3841), 'test-only');
  assert.equal(infer(TEST_ONLY_3841.map(filename => ({ filename, status: 'added' }))), 'test-only');
  assert.doesNotThrow(() => validateEvidence(testOnlyEvidence, 'CMP-260101-0101', withFiles(TEST_ONLY_3841)));
});

test('AC-2: 同居spec・frontend/testsだけはtest-only、scriptsとテストの混在もtest-only、scriptsだけはtooling', () => {
  assert.equal(infer(['frontend/app/composables/useX.spec.ts', 'frontend/server/api/y.test.mjs', 'frontend/scripts/z.spec.ts', 'frontend/eslint-rules/r.test.mjs']), 'test-only');
  assert.equal(infer(['frontend/tests/unit/a.spec.ts', 'frontend/tests/e2e/b.spec.ts']), 'test-only');
  assert.equal(infer(['scripts/check.mjs', 'backend/src/test/java/A.java']), 'test-only');
  assert.equal(infer(['scripts/check.mjs', '.github/workflows/x.yml']), 'tooling');
});

test('AC-3: test-onlyのPRはapp申告なら通り、tooling申告は拒否、既存docs/evidence全件は新validatorを通る', () => {
  const appPassed = { ...evidence, scope: 'app', stages: { review: passed, real: passed, exploratory: passed } };
  assert.doesNotThrow(() => validateEvidence(appPassed, 'CMP-260101-0101', withFiles(TEST_ONLY_3841)));
  assert.throws(() => validateEvidence({ ...evidence, scope: 'tooling' }, 'CMP-260101-0101', withFiles(TEST_ONLY_3841)), /scope/);
  // 回帰: 既存の証拠JSONの構造検証。GitHub API照合（merge状態・check・CI run）はオフラインで再現できないため対象外とし、
  // 参照ファイルの存在は git HEAD の tree で確認する（sparse checkout でも判定できるように作業木ではなく tree を見る）。
  const evidenceDir = new URL('../docs/evidence/', import.meta.url);
  const jsonFiles = readdirSync(evidenceDir).filter(name => /^CMP-.*\.json$/.test(name));
  assert.ok(jsonFiles.length > 0);
  const repoRoot = fileURLToPath(new URL('..', import.meta.url));
  const tree = spawnSync('git', ['ls-tree', '-r', '--name-only', 'HEAD'], { cwd: repoRoot, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  assert.equal(tree.status, 0, tree.stderr);
  const headFiles = new Set(tree.stdout.split('\n').filter(Boolean));
  for (const name of jsonFiles) {
    const item = JSON.parse(readFileSync(new URL(name, evidenceDir), 'utf8'));
    assert.doesNotThrow(() => validateEvidence(item, name.replace(/\.json$/, ''), {
      repository: 'kenta-0420/mannschaft',
      fileExists: ref => headFiles.has(ref),
    }), name);
  }
});

test('AC-4: files空はthrow、null・数値・filename欠落はapp、check0件とmergedAt欠落は拒否', () => {
  assert.throws(() => infer([]), /変更ファイル/);
  assert.equal(infer([...TEST_ONLY_3841, null]), 'app');
  assert.equal(infer([...TEST_ONLY_3841, 42]), 'app');
  assert.equal(infer([...TEST_ONLY_3841, { status: 'added' }]), 'app');
  const mergedAt = '2026-10-13T04:12:10Z';
  const late = { id: 1, name: 'ci', status: 'completed', conclusion: 'success', started_at: '2026-10-13T05:00:00Z', completed_at: '2026-10-13T05:01:00Z' };
  assert.throws(() => summarizeChecks({ checkRuns: [late], statuses: [], mergedAt }), /check/);
  assert.throws(() => summarizeChecks({ checkRuns: [], statuses: [], mergedAt }), /check/);
  assert.throws(() => summarizeChecks({ checkRuns: [], statuses: [], mergedAt: null }), /mergedAt/);
  assert.throws(() => summarizeChecks({ checkRuns: [], statuses: [], mergedAt: 'not-a-date' }), /mergedAt/);
});

test('AC-5: completed_atがmergedAtと同時刻なら含め+1秒なら除外し、境界外のパスはapp', () => {
  const mergedAt = '2026-10-13T04:12:10Z';
  const at = { id: 1, name: 'ci', status: 'completed', conclusion: 'success', started_at: '2026-10-13T04:00:00Z', completed_at: mergedAt };
  assert.deepEqual(summarizeChecks({ checkRuns: [at], statuses: [], mergedAt }), ['success']);
  const plusOne = { ...at, started_at: '2026-10-13T04:12:11Z', completed_at: '2026-10-13T04:12:11Z' };
  assert.throws(() => summarizeChecks({ checkRuns: [plusOne], statuses: [], mergedAt }), /check/);
  const status = (state, created_at, id) => ({ id, context: 'ext', state, created_at });
  assert.deepEqual(summarizeChecks({ checkRuns: [at], statuses: [status('success', mergedAt, 1), status('failure', '2026-10-13T04:12:11Z', 2)], mergedAt }), ['success', 'success']);
  for (const file of ['backend/src/testdata/x', 'backend/src/test', 'frontend/tests-old/x', 'frontend/app/x.spec.ts.bak', 'frontend/app/x.spec.tsx']) {
    assert.equal(infer([file]), 'app', file);
  }
});

test('AC-6: 混在・大文字・正規化差・絶対パス・バックスラッシュ・main→testリネーム・main削除・テスト設定はapp、stage.scope不一致とreason空は拒否', () => {
  assert.equal(infer([...TEST_ONLY_3841, 'backend/src/main/java/A.java']), 'app');
  for (const file of ['Backend/src/Test/X.java', 'backend/src/test/../main/X.java', '/backend/src/test/x', 'backend\\src\\test\\x', 'backend/src/test//x.java', 'frontend/vitest.config.ts']) {
    assert.equal(infer([file]), 'app', file);
  }
  assert.equal(infer([{ filename: 'backend/src/test/java/A.java', previous_filename: 'backend/src/main/java/A.java', status: 'renamed' }]), 'app');
  assert.equal(infer([{ filename: 'backend/src/main/java/A.java', status: 'removed' }, ...TEST_ONLY_3841]), 'app');
  const wrongStageScope = { ...testOnlyEvidence, stages: { review: passed, real: n_a, exploratory: n_a } };
  assert.throws(() => validateEvidence(wrongStageScope, 'CMP-260101-0101', withFiles(TEST_ONLY_3841)), /not-applicable/);
  const emptyReason = { ...testOnlyEvidence, stages: { ...testOnlyEvidence.stages, real: { ...naTestOnly, reason: '  ' } } };
  assert.throws(() => validateEvidence(emptyReason, 'CMP-260101-0101', withFiles(TEST_ONLY_3841)), /reason/);
});

test('AC-7: マージ時点の世代でcheckを判定し、#3847の重複PRによるマージ後failureを採らない', () => {
  const mergedAt = '2026-10-13T04:12:10Z';
  const summarize = (checkRuns) => summarizeChecks({ checkRuns, statuses: [], mergedAt });
  const run = (id, conclusion, started_at, completed_at, suite = 1) => ({ id, name: 'ci', status: completed_at ? 'completed' : 'in_progress', conclusion, started_at, completed_at, check_suite: { id: suite } });
  // マージ時点で実行中（マージ前に開始し、未完了またはマージ後に完了）
  assert.throws(() => summarize([run(1, null, '2026-10-13T04:00:00Z', null)]), /実行中/);
  assert.throws(() => summarize([run(1, 'success', '2026-10-13T04:00:00Z', '2026-10-13T04:20:00Z')]), /実行中/);
  // マージ前failure → マージ前の再実行success
  assert.deepEqual(summarize([run(1, 'failure', '2026-10-13T03:00:00Z', '2026-10-13T03:10:00Z'), run(2, 'success', '2026-10-13T03:20:00Z', '2026-10-13T03:30:00Z')]), ['success']);
  // マージ前failure → マージ後success（同一suite・別suite）はfailureを採って拒否
  for (const suite of [1, 2]) {
    const checks = summarize([run(1, 'failure', '2026-10-13T03:00:00Z', '2026-10-13T03:10:00Z', 1), run(2, 'success', '2026-10-13T05:00:00Z', '2026-10-13T05:10:00Z', suite)]);
    assert.deepEqual(checks, ['failure']);
    assert.throws(() => validateEvidence(evidence, 'CMP-260101-0101', { ...validationOptions, implementation: { ...validationOptions.implementation, checks } }), /check/);
  }
  // マージ前success → マージ後failure
  assert.deepEqual(summarize([run(1, 'success', '2026-10-13T03:00:00Z', '2026-10-13T03:10:00Z'), run(2, 'failure', '2026-10-13T05:00:00Z', '2026-10-13T05:10:00Z')]), ['success']);
  // completed_at 同時刻は id 降順
  assert.deepEqual(summarize([run(2, 'success', '2026-10-13T03:00:00Z', '2026-10-13T03:10:00Z'), run(1, 'failure', '2026-10-13T03:00:00Z', '2026-10-13T03:10:00Z')]), ['success']);
  // #3847 再現: 02:36 success 完了、08:22 開始の failure、mergedAt 04:12:10Z
  const gate = (id, conclusion, started_at, completed_at) => ({ id, name: 'Task-List PR Gate', status: 'completed', conclusion, started_at, completed_at });
  const fixture = [gate(10, 'success', '2026-10-13T02:35:30Z', '2026-10-13T02:36:00Z'), gate(11, 'failure', '2026-10-13T08:22:00Z', '2026-10-13T08:22:40Z')];
  assert.deepEqual(summarize(fixture), ['success']);
});

test('AC-8: files 3000件の分類が100ms未満', () => {
  const files = Array.from({ length: 3000 }, (_, index) => ({ filename: `backend/src/test/java/com/mannschaft/app/T${index}Test.java`, status: 'modified' }));
  infer(files);
  const started = process.hrtime.bigint();
  assert.equal(infer(files), 'test-only');
  const elapsedMs = Number(process.hrtime.bigint() - started) / 1e6;
  assert.ok(elapsedMs < 100, `${elapsedMs}ms`);
});

test('開発規約backend/.claudecode.mdだけはtooling、テストとの混在はtest-only、類似pathや本番変更はapp', () => {
  const convention = 'backend/.claudecode.md';
  const testJava = 'backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitFreezeStoreIntegrityTest.java';
  assert.equal(infer([convention]), 'tooling');
  assert.equal(infer([convention, testJava]), 'test-only');
  for (const appPath of [
    'backend/.claudecode.md.js',
    'backend/src/main/A.java',
    'backend/build.gradle',
    'backend/src/main/README.md',
  ]) {
    assert.equal(infer([appPath]), 'app', appPath);
    assert.equal(infer([convention, testJava, appPath]), 'app', appPath);
  }
});
