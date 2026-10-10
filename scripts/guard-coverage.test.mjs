import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import test from 'node:test';

const execFileAsync = promisify(execFile);
const script = fileURLToPath(new URL('./guard-coverage.mjs', import.meta.url));

async function fixture(t, yaml) {
  const root = await mkdtemp(join(tmpdir(), 'guard-coverage-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, 'docs', 'inventory'), { recursive: true });
  await mkdir(join(root, 'frontend', 'app', 'pages', 'alpha'), { recursive: true });
  await mkdir(join(root, 'frontend', 'app', 'pages', 'beta'), { recursive: true });
  await writeFile(join(root, 'frontend', 'app', 'pages', 'index.vue'), '<template/>');
  await writeFile(join(root, 'frontend', 'app', 'pages', 'alpha', 'index.vue'), '<template/>');
  await writeFile(join(root, 'frontend', 'app', 'pages', 'beta', '[slug].vue'), '<template/>');
  await writeFile(join(root, 'docs', 'inventory', 'page-reachability.yaml'), yaml);
  return root;
}

const validYaml = `tracked_paths:\n  - /\n  - /alpha\n  - /beta/[slug]\npages:\n  - path: /\n    classification: intentional-direct-only\n    reason: fixture\n  - path: /alpha\n    classification: reachable\n    reason: fixture\n  - path: /beta/[slug]\n    classification: reachable\n    reason: fixture\n`;

async function run(root, ...args) {
  return execFileAsync(process.execPath, [script, '--repo', root, ...args], { cwd: root });
}

test('全Vue数・追跡数・未検査数を数え、index.vueを親routeへ変換する', async (t) => {
  const root = await fixture(t, validYaml);
  const { stdout } = await run(root, '--json');
  const result = JSON.parse(stdout);
  assert.equal(result.counts.vueFiles, 3);
  assert.equal(result.counts.trackedPaths, 3);
  assert.equal(result.counts.untrackedRoutes, 0);
  assert.deepEqual(result.routes.sort(), ['/', '/alpha', '/beta/[slug]']);
});

test('未追跡routeはdefault要約に留め、JSONで詳細を返す', async (t) => {
  const root = await fixture(t, validYaml);
  await writeFile(join(root, 'frontend', 'app', 'pages', 'unreviewed.vue'), '<template/>');
  const { stdout } = await run(root);
  assert.match(stdout, /未検査route: 1件/);
  assert.doesNotMatch(stdout, /\/unreviewed/);
  const detailed = JSON.parse((await run(root, '--json')).stdout);
  assert.deepEqual(detailed.untrackedRoutes, ['/unreviewed']);
});

test('台帳の不存在route・重複・不正schemaは非0になる', async (t) => {
  const root = await fixture(t, `tracked_paths:\n  - /missing\n  - /alpha\npages:\n  - path: /alpha\n    classification: reachable\n    reason: fixture\n  - path: /alpha\n    classification: reachable\n    reason: duplicate\n`);
  await assert.rejects(() => run(root, '--json'));
  const badRoot = await fixture(t, 'tracked_paths: []\npages:\n  - path: /\n    classification: unknown\n    reason: fixture\n');
  await assert.rejects(() => run(badRoot, '--json'));
  const unparsable = await fixture(t, 'pages: []\n');
  await assert.rejects(() => run(unparsable, '--json'));
});

test('複数Vueが同一routeへ変換される場合はschema違反にせずcollisionとして数える', async (t) => {
  const root = await fixture(t, validYaml);
  await writeFile(join(root, 'frontend', 'app', 'pages', 'alpha.vue'), '<template/>');
  const result = JSON.parse((await run(root, '--json')).stdout);
  assert.equal(result.counts.vueFiles, 4);
  assert.equal(result.counts.uniqueRoutes, 3);
  assert.equal(result.counts.routeCollisions, 1);
  assert.deepEqual(result.duplicateRoutes, ['/alpha']);
  assert.equal(result.counts.schemaErrors, 0);
});
