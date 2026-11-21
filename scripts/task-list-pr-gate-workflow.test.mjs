import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// CMP-261121-1114: Task-List PR Gate の merge queue（merge_group）対応の静的検査。
// merge_group 経路は別 workflow（task-list-pr-gate-merge-group.yml）に分離し、必須チェック名
// "Task-List PR Gate" を報告すること、信頼 base（merge_group.base_sha）の検査器だけを使うこと、
// 権限が contents: read のみであること、PR 側の task-list-pr-gate.yml が merge_group を持たないことを固定する。

const read = (name) => readFileSync(fileURLToPath(new URL('../.github/workflows/' + name, import.meta.url)), 'utf8').replace(/\r\n/g, '\n');
const mergeGroupWorkflow = read('task-list-pr-gate-merge-group.yml');
const prWorkflow = read('task-list-pr-gate.yml');

function topLevelBlock(workflow, key) {
  const lines = workflow.split('\n');
  const start = lines.findIndex((line) => line === key + ':');
  assert.notEqual(start, -1, key + ': ブロックが無い');
  const body = [];
  for (const line of lines.slice(start + 1)) {
    if (/^\S/.test(line)) break;
    body.push(line);
  }
  return body.join('\n');
}

function steps(workflow) {
  const jobs = topLevelBlock(workflow, 'jobs');
  const stepsAt = jobs.indexOf('\n    steps:\n');
  assert.notEqual(stepsAt, -1, 'steps が無い');
  return jobs.slice(stepsAt).split(/\n(?=      - name: )/).slice(1).map((block) => ({
    block,
    name: block.match(/^      - name: (.+)$/m)[1].trim(),
    if: block.match(/^        if: (.+)$/m)?.[1].trim() ?? null,
    uses: block.match(/^        uses: (.+)$/m)?.[1].trim() ?? null,
    ref: block.match(/^          ref: (.+)$/m)?.[1].trim() ?? null,
    path: block.match(/^          path: (.+)$/m)?.[1].trim() ?? null,
  }));
}

test('AC-1/2: merge_group 用 workflow は on: merge_group（checks_requested）だけで起動する', () => {
  assert.equal(topLevelBlock(mergeGroupWorkflow, 'on'), '  merge_group:\n    types: [checks_requested]\n');
});

test('AC-1/2: ジョブは1つで、名前は必須チェック名 "Task-List PR Gate"', () => {
  const jobs = topLevelBlock(mergeGroupWorkflow, 'jobs');
  const jobKeys = jobs.split('\n').filter((line) => /^  [A-Za-z0-9_-]+:$/.test(line));
  assert.equal(jobKeys.length, 1, jobKeys.join(','));
  assert.match(jobs, /^    name: Task-List PR Gate$/m);
  assert.doesNotMatch(jobs, /^    if:/m, 'ジョブ単位の if で skip させない');
  assert.doesNotMatch(jobs, /^    permissions:/m, '権限は workflow 単位の contents: read だけにする');
});

test('AC-12: 権限は contents: read のみ、concurrency は github.ref ベース', () => {
  assert.equal(topLevelBlock(mergeGroupWorkflow, 'permissions'), '  contents: read\n');
  assert.match(topLevelBlock(mergeGroupWorkflow, 'concurrency'), /^  group: \S*\$\{\{ github\.ref \}\}$/m);
});

test('AC-12: 検査器は base_sha から直下へ、台帳だけを head_sha から別ディレクトリへ取得する', () => {
  const all = steps(mergeGroupWorkflow);
  for (const step of all) assert.equal(step.if, null, 'step 単位の if は不要: ' + step.name);
  const checkouts = all.filter((step) => /^actions\/checkout@/.test(step.uses ?? ''));
  assert.equal(checkouts.length, 2);
  const rootCheckouts = checkouts.filter((step) => step.path === null);
  assert.equal(rootCheckouts.length, 1, 'ワークスペース直下へ checkout するのは信頼 base の1回だけ');
  assert.match(rootCheckouts[0].ref, /^\$\{\{ github\.event\.merge_group\.base_sha \}\}$/);
  const head = checkouts.find((step) => /merge_group\.head_sha/.test(step.ref ?? ''));
  assert.ok(head, 'head_sha の取得が無い');
  assert.notEqual(head.path, null, 'head_sha をワークスペース直下へ checkout してはならない');
  assert.match(head.block, /sparse-checkout: \|\n\s+docs\/task-list\.md\n/);
  assert.match(head.block, /sparse-checkout-cone-mode: false/);
  for (const step of checkouts) assert.match(step.block, /persist-credentials: false/);
  assert.ok(checkouts.indexOf(rootCheckouts[0]) < checkouts.indexOf(head), 'base を先に checkout してから head を別ディレクトリへ取得する');
  const run = all.find((step) => /node scripts\/task-list-structure-check\.mjs/.test(step.block));
  assert.ok(run, '検査器を実行していない');
  assert.ok(run.block.includes('node scripts/task-list-structure-check.mjs ' + head.path + '/docs/task-list.md'), '検査対象は head から取得した台帳、検査器は base 側');
  assert.doesNotMatch(topLevelBlock(mergeGroupWorkflow, 'jobs'), /github-script|pull_request/, 'merge_group 経路で PR 本文検査を行わない');
});

test('AC-10: PR 側の task-list-pr-gate.yml は merge_group を持たず、pull_request_target 経路は従来どおり', () => {
  const on = topLevelBlock(prWorkflow, 'on');
  assert.doesNotMatch(prWorkflow, /merge_group/);
  assert.match(on, /^  pull_request_target:\n    branches: \[main\]\n    types: \[opened, synchronize, reopened, ready_for_review, edited\]$/m);
  assert.match(on, /^  workflow_dispatch:$/m);
  assert.equal(topLevelBlock(prWorkflow, 'permissions'), '  contents: read\n  actions: read\n  checks: write\n  pull-requests: read\n');
  const checkout = steps(prWorkflow).find((step) => step.name === 'Checkout trusted base');
  assert.match(checkout.ref, /^\$\{\{ github\.event\.pull_request\.base\.sha \|\| github\.sha \}\}$/);
  assert.match(topLevelBlock(prWorkflow, 'jobs'), /^    name: Task-List PR Gate$/m);
});
