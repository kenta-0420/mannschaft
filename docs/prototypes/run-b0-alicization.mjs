#!/usr/bin/env node
import fs from 'node:fs';
import assert from 'node:assert/strict';
import path from 'node:path';
import process from 'node:process';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(fileURLToPath(new URL('../..', import.meta.url)));
const coveragePath = path.join(root, 'docs/prototypes/beta-inventory-board-b0-coverage.json');
const outputDir = path.join(root, 'docs/prototypes/.b0-local');
const coverage = JSON.parse(fs.readFileSync(coveragePath, 'utf8'));
const args = process.argv.slice(2);
const mode = args[0] || 'list';
const selected = args.filter((value) => /^B0-J\d+$/.test(value));

function journeys() {
  return Object.entries(coverage.journeys).filter(([id]) => selected.length === 0 || selected.includes(id));
}

function validateManifest() {
  for (const [id, item] of journeys()) {
    if (!Array.isArray(item.specPaths) || item.specPaths.length === 0) throw new Error(`${id}: specPathsが空です`);
    if (new Set(item.specPaths).size !== item.specPaths.length) throw new Error(`${id}: specPathsが重複しています`);
    for (const spec of item.specPaths) {
      if (!spec.startsWith('frontend/')) throw new Error(`${id}: frontend外のspecは実行できません: ${spec}`);
      if (!fs.existsSync(path.join(root, spec))) throw new Error(`${id}: specが存在しません: ${spec}`);
    }
  }
}

async function requireRuntime() {
  const required = ['BASE_URL', 'API_BASE_URL', 'B0_REAL_DB', 'TEST_ADMIN_EMAIL', 'TEST_ADMIN_PASSWORD', 'TEST_USER_EMAIL', 'TEST_USER_PASSWORD', 'TEST_USER2_EMAIL', 'TEST_USER2_PASSWORD', 'B0_THREE_BROWSER_CONTEXTS'];
  const missing = required.filter((key) => !process.env[key]);
  if (missing.length) throw new Error(`実行条件不足（値は記録しません）: ${missing.join(', ')}`);
  if (process.env.B0_REAL_DB !== 'true') throw new Error('B0_REAL_DB=true が必要です。モックDBでは実測しません。');
  if (process.env.B0_THREE_BROWSER_CONTEXTS !== 'true') throw new Error('B0_THREE_BROWSER_CONTEXTS=true はオペレータ申告です。3利用者の別BrowserContextを保証する専用fixtureがないため、実測はblockedです。');
  throw new Error('B0_THREE_BROWSER_CONTEXTS=true はオペレータ申告に過ぎません。3利用者の別BrowserContextを実証する専用fixtureが未指定のため、実測はblockedです。');
  for (const state of ['tests/e2e/.auth/admin.json', 'tests/e2e/.auth/user.json']) if (!fs.existsSync(path.join(root, 'frontend', state))) throw new Error(`認証storageStateがありません: ${state}`);
  for (const [name, url] of [['BASE_URL', process.env.BASE_URL], ['API_BASE_URL', process.env.API_BASE_URL]]) {
    const response = await fetch(url, { signal: AbortSignal.timeout(5000) }).catch(() => null);
    if (!response || !response.ok) throw new Error(`${name} 到達不可またはHTTP ${response?.status || '接続失敗'}`);
  }
}

function printList() {
  validateManifest();
  for (const [id, item] of journeys()) console.log(`${id}\t${item.coverageStatus}\t${item.specPaths.join(' ')}`);
}

function summarizeSuites(suites, summary = { expected: 0, unexpected: 0, skipped: 0, results: 0 }) {
  for (const suite of suites || []) {
    for (const spec of suite.specs || []) for (const test of spec.tests || []) {
      for (const result of test.results || []) summary.results += 1;
      if (test.status === 'expected') summary.expected += 1;
      else if (test.status === 'unexpected' || test.status === 'failed') summary.unexpected += 1;
      else if (test.status === 'skipped' || test.status === 'pending') summary.skipped += 1;
    }
    summarizeSuites(suite.suites, summary);
  }
  return summary;
}

function normalizeSpecPath(spec) {
  if (!spec.startsWith('frontend/')) throw new Error(`frontend外のspecは実行できません: ${spec}`);
  return spec.slice('frontend/'.length);
}

function selfTestFixtures() {
  assert.deepEqual(summarizeSuites([{ specs: [{ tests: [{ status: 'expected', results: [{}] }] }], suites: [{ specs: [{ tests: [{ status: 'unexpected', results: [{}] }] }] }] }]), { expected: 1, unexpected: 1, skipped: 0, results: 2 });
  assert.deepEqual(summarizeSuites([]), { expected: 0, unexpected: 0, skipped: 0, results: 0 });
  assert.deepEqual(summarizeSuites([{ specs: [{ tests: [{ status: 'skipped', results: [] }] }] }]), { expected: 0, unexpected: 0, skipped: 1, results: 0 });
  assert.deepEqual(summarizeSuites([{ specs: [{ tests: [{ status: 'unexpected', results: [{}] }] }] }]).unexpected, 1);
  assert.deepEqual(summarizeSuites([{ specs: [{ tests: [{ status: 'expected', results: [{}] }] }] }]), { expected: 1, unexpected: 0, skipped: 0, results: 1 });
  assert.equal(normalizeSpecPath('frontend/tests/e2e/x.spec.ts'), 'tests/e2e/x.spec.ts');
  assert.throws(() => normalizeSpecPath('backend/tests/x.spec.ts'));
  const blocked = { status: 'blocked', recordedAt: new Date().toISOString(), selectedJourneys: ['B0-J1'] };
  assert.equal(blocked.status, 'blocked');
  console.log('run-b0-alicization fixture self-test: ok');
}

async function run() {
  validateManifest();
  await requireRuntime();
  if (!journeys().length) throw new Error('実行対象journeyが0件です');
  fs.mkdirSync(outputDir, { recursive: true });
  const startedAt = new Date().toISOString();
  const result = { schemaVersion: 1, startedAt, mode: 'real-ui-real-db', selectedJourneys: [], conditions: { baseUrlConfigured: true, apiBaseUrlConfigured: true, realDb: true, threeUsers: 'operator-declared', separateBrowserContexts: 'not-proven' }, journeys: [] };
  for (const [id, item] of journeys()) {
    const jsonPath = path.join(outputDir, `${id}-${Date.now()}.json`);
    const playwrightCli = path.join(root, 'frontend/node_modules/@playwright/test/cli.js');
    if (!fs.existsSync(playwrightCli)) throw new Error('Playwright依存がありません: frontend/node_modules/@playwright/test/cli.js');
    const specs = item.specPaths.map(normalizeSpecPath);
    const child = spawnSync(process.execPath, [playwrightCli, 'test', ...specs, '--reporter=json'], { cwd: path.join(root, 'frontend'), env: { ...process.env }, encoding: 'utf8' });
    const stdout = child.stdout || '';
    const parsed = (() => { try { return JSON.parse(stdout); } catch { return null; } })();
    const summary = summarizeSuites(parsed?.suites);
    const status = child.status === 0 && summary.results > 0 && summary.expected > 0 && summary.unexpected === 0 && summary.skipped === 0 ? 'test-passed' : 'test-failed';
    fs.writeFileSync(jsonPath, JSON.stringify(parsed || { error: 'Playwright JSONを解釈できません', exitCode: child.status }, null, 2));
    result.journeys.push({ id, status, specPaths: item.specPaths, summary, evidencePath: path.relative(root, jsonPath).replaceAll('\\', '/') });
  }
  result.finishedAt = new Date().toISOString();
  const resultPath = path.join(outputDir, `run-${startedAt.replaceAll(':', '-')}.json`);
  fs.writeFileSync(resultPath, JSON.stringify(result, null, 2));
  console.log(JSON.stringify({ resultPath: path.relative(root, resultPath), journeys: result.journeys }, null, 2));
  if (result.journeys.some((journey) => journey.status !== 'test-passed')) process.exitCode = 1;
}

try {
  if (mode === '--self-test-fixture') selfTestFixtures();
  else if (mode === 'list' || mode === 'dry-run') printList();
  else if (mode === 'run') await run();
  else throw new Error('使い方: node run-b0-alicization.mjs list|dry-run|run [B0-J1 ...]');
} catch (error) {
  console.error(`[blocked] ${error.message}`);
  if (mode === 'run') {
    fs.mkdirSync(outputDir, { recursive: true });
    fs.writeFileSync(path.join(outputDir, `blocked-${Date.now()}.json`), JSON.stringify({ schemaVersion: 1, status: 'blocked', recordedAt: new Date().toISOString(), selectedJourneys: journeys().map(([id]) => id), reason: error.message }, null, 2));
  }
  process.exitCode = 2;
}
