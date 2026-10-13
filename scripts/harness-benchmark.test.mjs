import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { aggregateBenchmark, validateBenchmark } from './harness-benchmark.mjs';

const scriptPath = fileURLToPath(new URL('./harness-benchmark.mjs', import.meta.url));

const ac = [{ id: 'AC-1', description: '保存できる' }, { id: 'AC-2', description: '一覧に表示される' }];
const run = (id, passedCriteria, overrides = {}) => ({
  id,
  completed: true,
  passedCriteria,
  defects: 0,
  interventions: 0,
  durationSeconds: 10,
  cost: 1,
  ...overrides,
});
const caseRecord = (overrides = {}) => ({
  id: 'case-a',
  startSha: 'a'.repeat(40),
  model: 'model-x',
  effort: 'medium',
  environment: 'node-24; windows; fixture-v1',
  costUnit: 'USD',
  acceptanceCriteria: ac,
  runs: [run('run-1', ['AC-1', 'AC-2'])],
  ...overrides,
});
const data = (cases = [caseRecord()]) => ({ schemaVersion: 1, cases });

test('最小スキーマを検証し、各caseを独立集計する', () => {
  const input = data([
    caseRecord({ runs: [run('pass', ['AC-1', 'AC-2']), run('fail', ['AC-1'], { defects: 2, interventions: 1, durationSeconds: 30, cost: 3 })] }),
    caseRecord({ id: 'case-b', startSha: 'b'.repeat(40), acceptanceCriteria: [{ id: 'AC-X', description: '別条件' }], runs: [run('other', ['AC-X'])] }),
  ]);
  const result = aggregateBenchmark(validateBenchmark(input));
  assert.equal(result.cases.length, 2);
  assert.equal(result.cases[0].completedRuns, 2);
  assert.equal(result.cases[0].successfulRuns, 1);
  assert.equal(result.cases[0].fullSuccessRate, 0.5);
  assert.equal(result.cases[0].criteriaAchievementRate, 0.75);
  assert.equal(result.cases[0].defects, 2);
  assert.equal(result.cases[0].interventions, 1);
  assert.equal(result.cases[0].durationSeconds, 40);
  assert.equal(result.cases[1].fullSuccessRate, 1);
});

test('runなしは率null、費用未測定はnullのまま確定総額を出さない', () => {
  const empty = aggregateBenchmark(validateBenchmark(data([caseRecord({ runs: [] })]))).cases[0];
  assert.equal(empty.fullSuccessRate, null);
  assert.equal(empty.criteriaAchievementRate, null);
  const missingCost = aggregateBenchmark(validateBenchmark(data([caseRecord({ runs: [run('r1', ['AC-1'], { cost: null }), run('r2', ['AC-1', 'AC-2'], { cost: 2 })] })]))).cases[0];
  assert.equal(missingCost.costKnownSubtotal, 2);
  assert.equal(missingCost.costMissingRuns, 1);
  assert.equal(missingCost.totalCost, null);
  assert.equal(empty.totalCost, 0);
  assert.equal(empty.costMissingRuns, 0);
  const zeroCost = aggregateBenchmark(validateBenchmark(data([caseRecord({ runs: [run('r1', ['AC-1', 'AC-2'], { cost: 0 })] })]))).cases[0];
  assert.equal(zeroCost.totalCost, 0);
});

test('未完了runも率・欠陥・介入・時間・費用の母数に含め、成功数にだけ含めない', () => {
  const result = aggregateBenchmark(validateBenchmark(data([caseRecord({ runs: [
    run('unfinished', ['AC-1'], { completed: false }),
    run('failed', ['AC-1'], { defects: 1 }),
    run('passed', ['AC-1', 'AC-2']),
  ] })]))).cases[0];
  assert.equal(result.totalRuns, 3);
  assert.equal(result.incompleteRuns, 1);
  assert.equal(result.completedRuns, 2);
  assert.equal(result.fullSuccessRate, 1 / 3);
  assert.equal(result.criteriaAchievementRate, 2 / 3);
  assert.equal(result.defects, 1);
  assert.equal(result.durationSeconds, 30);
});

test('case/AC/runの重複、空配列、SHA不正、未知AC、override、不正値を拒否する', () => {
  assert.throws(() => validateBenchmark(null), /object/);
  assert.throws(() => validateBenchmark(data([])), /cases/);
  assert.throws(() => validateBenchmark(data([caseRecord(), caseRecord()])), /case IDs.*重複/);
  assert.throws(() => validateBenchmark(data([caseRecord({ startSha: 'abc' })])), /SHA/);
  assert.throws(() => validateBenchmark(data([caseRecord({ acceptanceCriteria: [] })])), /acceptanceCriteria/);
  assert.throws(() => validateBenchmark(data([caseRecord({ acceptanceCriteria: [ac[0], ac[0]] })])), /AC.*重複/);
  assert.throws(() => validateBenchmark(data([caseRecord({ runs: [run('r', ['AC-unknown'])] })])), /未知/);
  assert.throws(() => validateBenchmark(data([caseRecord({ runs: [run('r', ['AC-1']), run('r', ['AC-1'])] })])), /run.*重複/);
  assert.throws(() => validateBenchmark(data([caseRecord({ model: '' })])), /model/);
  assert.throws(() => validateBenchmark(data([caseRecord({ runs: [run('r', ['AC-1'], { cost: -1 })] })])), /cost/);
  assert.throws(() => validateBenchmark(data([caseRecord({ runs: [run('r', ['AC-1'], { durationSeconds: NaN })] })])), /durationSeconds/);
  assert.throws(() => validateBenchmark(data([caseRecord({ runs: [run('r', ['AC-1'], { defects: null })] })])), /defects/);
  assert.throws(() => validateBenchmark(data([caseRecord({ runs: [run('r', ['AC-1'], { model: 'override' })] })])), /run/);
  assert.throws(() => validateBenchmark(data([caseRecord({ costUnit: '' })])), /costUnit/);
});

test('CLIはJSONを集計して出力し、空case入力を非0で拒否する', () => {
  const directory = mkdtempSync(join(tmpdir(), 'harness-benchmark-'));
  try {
    const validPath = join(directory, 'valid.json');
    const invalidPath = join(directory, 'invalid.json');
    writeFileSync(validPath, JSON.stringify(data()), 'utf8');
    writeFileSync(invalidPath, JSON.stringify(data([])), 'utf8');
    const valid = spawnSync(process.execPath, [scriptPath, validPath], { encoding: 'utf8' });
    assert.equal(valid.status, 0, valid.stderr);
    assert.equal(JSON.parse(valid.stdout).cases[0].fullSuccessRate, 1);
    const invalid = spawnSync(process.execPath, [scriptPath, invalidPath], { encoding: 'utf8' });
    assert.notEqual(invalid.status, 0);
    assert.match(invalid.stderr, /cases/);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
