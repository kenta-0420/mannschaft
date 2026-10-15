import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import test from 'node:test';

const execFileAsync = promisify(execFile);
const script = fileURLToPath(new URL('./worktree-cleanup.mjs', import.meta.url));

async function git(cwd, args, env = {}) {
  return execFileAsync('git', args, { cwd, env: { ...process.env, ...env } });
}

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'cmp109-worktree-'));
  await git(root, ['init', '-b', 'main']);
  await git(root, ['config', 'user.email', 'cmp109@example.test']);
  await git(root, ['config', 'user.name', 'CMP-109']);
  await writeFile(join(root, 'README.md'), 'fixture\n');
  await git(root, ['add', '.']);
  await git(root, ['commit', '-m', 'initial']);
  await mkdir(join(root, '.claude', 'worktrees'), { recursive: true });
  return root;
}

async function addWorktree(root, name, branch = `worktree-agent-${name}`, old = true) {
  const path = join(root, '.claude', 'worktrees', name);
  await git(root, ['worktree', 'add', '-b', branch, path]);
  if (old) {
    await writeFile(join(path, `${name}.txt`), 'old\n');
    await git(path, ['add', '.']);
    await git(path, ['commit', '-m', `old ${name}`], {
      GIT_AUTHOR_DATE: '2020-01-01T00:00:00Z',
      GIT_COMMITTER_DATE: '2020-01-01T00:00:00Z',
    });
  }
  return path;
}

async function run(root, ...args) {
  return execFileAsync(process.execPath, [script, '--repo', root, '--days', '1', '--json', ...args], { cwd: root });
}

function gitPath(worktree, path) {
  return isAbsolute(path) ? path : join(worktree, path);
}

test('clean stale agentだけをapplyで撤去し、feature/non-agentは保護する', async (t) => {
  const root = await fixture();
  t.after(() => rm(root, { recursive: true, force: true }));
  const agent = await addWorktree(root, 'agent-clean');
  const agentHead = (await git(agent, ['rev-parse', 'HEAD'])).stdout.trim();
  const feature = await addWorktree(root, 'agent-feature', 'feature/keep');
  const nonAgent = await addWorktree(root, 'manual-keep', 'worktree-agent-manual');

  const dry = JSON.parse((await run(root)).stdout);
  assert.equal(dry.counts.removable, 1);
  assert.equal(dry.entries.find((entry) => entry.path === agent).reason, 'clean-stale-agent');
  assert.equal(dry.entries.find((entry) => entry.path === feature).reason, 'protected-branch');
  assert.equal(dry.entries.find((entry) => entry.path === nonAgent).reason, 'non-agent-path');

  const applied = JSON.parse((await run(root, '--apply')).stdout);
  assert.equal(applied.counts.removed, 1);
  await assert.rejects(() => git(agent, ['status']));
  assert.equal((await git(root, ['rev-parse', 'refs/heads/worktree-agent-agent-clean'])).stdout.trim(), agentHead);
  await git(feature, ['status']);
  await git(nonAgent, ['status']);
});

test('dirty、locked、index.lockは理由付きで保持しindex.lockを削除しない', async (t) => {
  const root = await fixture();
  t.after(() => rm(root, { recursive: true, force: true }));
  const dirty = await addWorktree(root, 'agent-dirty');
  const locked = await addWorktree(root, 'agent-locked');
  const indexLocked = await addWorktree(root, 'agent-index');
  await writeFile(join(dirty, 'uncommitted.txt'), 'keep me\n');
  await git(root, ['worktree', 'lock', '--reason', 'operator lock', locked]);
  const gitdir = (await git(indexLocked, ['rev-parse', '--git-dir'])).stdout.trim();
  const gitdirPath = gitPath(indexLocked, gitdir);
  await writeFile(join(gitdirPath, 'index.lock'), 'do not remove\n');

  const result = JSON.parse((await run(root, '--apply')).stdout);
  assert.equal(result.entries.find((entry) => entry.path === dirty).reason, 'dirty');
  assert.equal(result.entries.find((entry) => entry.path === locked).reason, 'locked');
  assert.equal(result.entries.find((entry) => entry.path === indexLocked).reason, 'index-lock-unverified');
  assert.equal((await git(dirty, ['status', '--porcelain'])).stdout.includes('uncommitted.txt'), true);
  assert.equal((await git(indexLocked, ['rev-parse', '--git-path', 'index.lock'])).stdout.trim().length > 0, true);
  const { access } = await import('node:fs/promises');
  await access(join(gitdirPath, 'index.lock'));
});

test('nested junctionはapplyで撤去せず、リンク先を不変に保つ', async (t) => {
  const root = await fixture();
  t.after(() => rm(root, { recursive: true, force: true }));
  const agent = await addWorktree(root, 'agent-junctioned');
  const target = join(root, 'shared-node-modules');
  await mkdir(join(agent, 'frontend'), { recursive: true });
  await mkdir(target);
  await writeFile(join(target, 'sentinel.txt'), 'do not delete\n');
  await symlink(target, join(agent, 'frontend', 'node_modules'), 'junction').catch(async () => symlink(target, join(agent, 'frontend', 'node_modules'), 'dir'));

  const result = JSON.parse((await run(root, '--apply')).stdout);
  const entry = result.entries.find((candidate) => candidate.path === agent);
  assert.equal(entry.reason, 'nested-junction');
  assert.equal(result.counts.failures.some((failure) => failure.reason === 'nested-junction'), true);
  const { access } = await import('node:fs/promises');
  await access(join(agent, 'frontend', 'node_modules'));
  await access(join(target, 'sentinel.txt'));
});

test('git検査失敗はinspection-failedとして保持する', async (t) => {
  const root = await fixture();
  t.after(() => rm(root, { recursive: true, force: true }));
  const agent = await addWorktree(root, 'agent-inspection-failure');
  const gitdir = (await git(agent, ['rev-parse', '--git-dir'])).stdout.trim();
  const gitdirPath = gitPath(agent, gitdir);
  await rename(join(gitdirPath, 'HEAD'), join(gitdirPath, 'HEAD.broken'));

  const result = JSON.parse((await run(root, '--apply')).stdout);
  const entry = result.entries.find((candidate) => candidate.path === agent);
  assert.equal(entry.reason, 'inspection-failed');
  assert.equal(result.counts.removed, 0);
  assert.equal(result.counts.failures.some((failure) => failure.reason === 'inspection-failed'), true);
});

test('prunableとjunctionを分類し、checkは上限超過・stale残存を非0にする', async (t) => {
  const root = await fixture();
  t.after(() => rm(root, { recursive: true, force: true }));
  const stale = await addWorktree(root, 'agent-stale');
  const missing = await addWorktree(root, 'agent-missing');
  const nonAgentMissing = await addWorktree(root, 'manual-missing', 'worktree-agent-manual-missing');
  await rm(missing, { recursive: true, force: true });
  await rm(nonAgentMissing, { recursive: true, force: true });
  const junction = join(root, '.claude', 'worktrees', 'agent-junction');
  await symlink(root, junction, 'junction').catch(async () => symlink(root, junction, 'dir'));

  await assert.rejects(() => run(root, '--check', '--limit', '1'));
  try {
    await run(root, '--check', '--limit', '1');
  } catch (error) {
    const result = JSON.parse(error.stdout);
    assert.equal(result.entries.find((entry) => entry.path === missing).kind, 'prunable');
    assert.equal(result.entries.find((entry) => entry.path === junction).kind, 'junction');
    assert.equal(result.counts.staleRemaining > 0, true);
    assert.equal(result.counts.totalWorktrees > 1, true);
  }
  await run(root, '--apply');
  const registered = (await git(root, ['worktree', 'list', '--porcelain'])).stdout;
  assert.equal(registered.includes(nonAgentMissing.replaceAll('\\', '/')) || registered.includes(nonAgentMissing), true);
  await assert.rejects(() => git(stale, ['status']));
});
