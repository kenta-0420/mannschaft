import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const exactKeys = (value, keys, label) => {
  if (!object(value)) throw new Error(`${label} はobjectでなければなりません`);
  const allowed = new Set(keys);
  const extra = Object.keys(value).filter((key) => !allowed.has(key));
  if (extra.length) throw new Error(`${label} に未定義の項目があります: ${extra.join(', ')}`);
  for (const key of keys) if (!(key in value)) throw new Error(`${label}.${key} が必要です`);
};
const nonEmpty = (value, label) => {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`${label} は空でない文字列が必要です`);
};
const finiteNonNegative = (value, label, integer = false) => {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || (integer && !Number.isInteger(value))) {
    throw new Error(`${label} は有限の0以上の${integer ? '整数' : '数値'}が必要です`);
  }
};
const unique = (values, label) => {
  if (new Set(values).size !== values.length) throw new Error(`${label} に重複があります`);
};

export function validateBenchmark(input) {
  exactKeys(input, ['schemaVersion', 'cases'], 'benchmark');
  if (input.schemaVersion !== 1) throw new Error('schemaVersion は1でなければなりません');
  if (!Array.isArray(input.cases) || input.cases.length === 0) throw new Error('cases は1件以上必要です');
  const caseIds = [];
  for (const [caseIndex, record] of input.cases.entries()) {
    const label = `cases[${caseIndex}]`;
    exactKeys(record, ['id', 'startSha', 'model', 'effort', 'environment', 'costUnit', 'acceptanceCriteria', 'runs'], label);
    for (const key of ['id', 'model', 'effort', 'environment']) nonEmpty(record[key], `${label}.${key}`);
    caseIds.push(record.id);
    if (typeof record.startSha !== 'string' || !/^[0-9a-f]{40}$/i.test(record.startSha)) throw new Error(`${label}.startSha は40桁のGit SHAが必要です`);
    nonEmpty(record.costUnit, `${label}.costUnit`);
    if (!Array.isArray(record.acceptanceCriteria) || record.acceptanceCriteria.length === 0) throw new Error(`${label}.acceptanceCriteria は1件以上必要です`);
    const acIds = [];
    for (const [acIndex, criterion] of record.acceptanceCriteria.entries()) {
      const acLabel = `${label}.acceptanceCriteria[${acIndex}]`;
      exactKeys(criterion, ['id', 'description'], acLabel);
      nonEmpty(criterion.id, `${acLabel}.id`);
      nonEmpty(criterion.description, `${acLabel}.description`);
      acIds.push(criterion.id);
    }
    unique(acIds, `${label} AC`);
    if (!Array.isArray(record.runs)) throw new Error(`${label}.runs must be an array`);
    const runIds = [];
    for (const [runIndex, run] of record.runs.entries()) {
      const runLabel = `${label}.runs[${runIndex}]`;
      exactKeys(run, ['id', 'completed', 'passedCriteria', 'defects', 'interventions', 'durationSeconds', 'cost'], runLabel);
      nonEmpty(run.id, `${runLabel}.id`);
      runIds.push(run.id);
      if (typeof run.completed !== 'boolean') throw new Error(`${runLabel}.completed はbooleanが必要です`);
      if (!Array.isArray(run.passedCriteria)) throw new Error(`${runLabel}.passedCriteria は配列が必要です`);
      unique(run.passedCriteria, `${runLabel}.passedCriteria`);
      for (const acId of run.passedCriteria) {
        nonEmpty(acId, `${runLabel}.passedCriteria item`);
        if (!acIds.includes(acId)) throw new Error(`${runLabel} に未知のAC ${acId} があります`);
      }
      finiteNonNegative(run.defects, `${runLabel}.defects`, true);
      finiteNonNegative(run.interventions, `${runLabel}.interventions`, true);
      finiteNonNegative(run.durationSeconds, `${runLabel}.durationSeconds`);
      if (run.cost !== null) finiteNonNegative(run.cost, `${runLabel}.cost`);
    }
    unique(runIds, `${label} run`);
  }
  unique(caseIds, 'case IDs');
  return input;
}

export function aggregateBenchmark(benchmark) {
  return {
    schemaVersion: 1,
    cases: benchmark.cases.map((record) => {
      const successful = record.runs.filter((run) => run.completed && record.acceptanceCriteria.every((ac) => run.passedCriteria.includes(ac.id)));
      const criteriaCount = record.runs.length * record.acceptanceCriteria.length;
      const passedCriteriaCount = record.runs.reduce((sum, run) => sum + run.passedCriteria.length, 0);
      const costs = record.runs.map((run) => run.cost);
      const costMissingRuns = costs.filter((cost) => cost === null).length;
      const costKnownSubtotal = costs.reduce((sum, cost) => sum + (cost ?? 0), 0);
      return {
        id: record.id,
        startSha: record.startSha,
        model: record.model,
        effort: record.effort,
        environment: record.environment,
        costUnit: record.costUnit,
        totalRuns: record.runs.length,
        incompleteRuns: record.runs.filter((run) => !run.completed).length,
        completedRuns: record.runs.filter((run) => run.completed).length,
        successfulRuns: successful.length,
        criteriaAchievementRate: criteriaCount === 0 ? null : passedCriteriaCount / criteriaCount,
        fullSuccessRate: record.runs.length === 0 ? null : successful.length / record.runs.length,
        defects: record.runs.reduce((sum, run) => sum + run.defects, 0),
        interventions: record.runs.reduce((sum, run) => sum + run.interventions, 0),
        durationSeconds: record.runs.reduce((sum, run) => sum + run.durationSeconds, 0),
        costKnownSubtotal,
        costMissingRuns,
        totalCost: costMissingRuns === 0 ? costKnownSubtotal : null,
      };
    }),
  };
}

async function main(args) {
  if (args.length !== 1) throw new Error('使い方: node scripts/harness-benchmark.mjs <benchmark.json>');
  const input = JSON.parse(await readFile(args[0], 'utf8'));
  process.stdout.write(`${JSON.stringify(aggregateBenchmark(validateBenchmark(input)), null, 2)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`harness-benchmark: ${error.message}\n`);
    process.exitCode = 1;
  });
}
