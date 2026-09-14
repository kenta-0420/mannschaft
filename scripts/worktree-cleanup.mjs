#!/usr/bin/env node
/**
 * 足軽 worktree の安全な棚卸し・撤去。
 * 既定は dry-run。--apply でも clean な agent-* + worktree-agent-* だけを撤去する。
 */
import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { lstat, readdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

function options(argv) {
  const result = { repo: process.cwd(), days: 7, limit: 60, apply: false, check: false, json: false };
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--apply') result.apply = true;
    else if (value === '--check') result.check = true;
    else if (value === '--json') result.json = true;
    else if (value === '--repo') result.repo = resolve(argv[++index]);
    else if (value === '--days') result.days = Number(argv[++index]);
    else if (value === '--limit') result.limit = Number(argv[++index]);
    else throw new Error(`未対応の引数: ${value}`);
  }
  if (!Number.isInteger(result.days) || result.days < 0) throw new Error('--days は0以上の整数です');
  if (!Number.isInteger(result.limit) || result.limit < 1) throw new Error('--limit は1以上の整数です');
  return result;
}

async function git(repo, args, allowFailure = false) {
  try {
    return await execFileAsync('git', ['-c', `safe.directory=${resolve(repo)}`, ...args], { cwd: repo, windowsHide: true });
  } catch (error) {
    if (allowFailure) return { stdout: error.stdout ?? '', stderr: error.stderr ?? '', failed: true };
    throw new Error(`git ${args.join(' ')}: ${(error.stderr || error.message).trim()}`);
  }
}

function parseWorktrees(text) {
  return text.trim().split(/\r?\n\r?\n/).filter(Boolean).map((block) => {
    const item = {};
    for (const line of block.split(/\r?\n/)) {
      const [key, ...rest] = line.split(' ');
      item[key] = rest.join(' ');
    }
    return item;
  });
}

function shortBranch(branch = '') {
  return branch.replace(/^refs\/heads\//, '');
}

async function hasIndexLock(path) {
  const result = await git(path, ['rev-parse', '--git-path', 'index.lock'], true);
  if (result.failed) return { ok: false, exists: false };
  return { ok: true, exists: existsSync(result.stdout.trim()) };
}

async function commitEpoch(path) {
  const result = await git(path, ['log', '-1', '--format=%ct'], true);
  const epoch = Number(result.stdout.trim());
  return Number.isFinite(epoch) ? { ok: true, epoch } : { ok: false, epoch: null };
}

async function isDirty(path) {
  const result = await git(path, ['status', '--porcelain'], true);
  return result.failed ? { ok: false, dirty: false } : { ok: true, dirty: result.stdout.trim().length > 0 };
}

async function nestedJunction(path) {
  const candidate = join(path, 'frontend', 'node_modules');
  try {
    const stat = await lstat(candidate);
    return stat.isSymbolicLink();
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    // 読み取り不能も、リンク先へ到達する削除を避けるため安全側に倒す。
    return true;
  }
}

async function diskEntries(root, registered) {
  const base = join(root, '.claude', 'worktrees');
  if (!existsSync(base)) return [];
  const names = await readdir(base);
  const entries = [];
  for (const name of names) {
    const path = join(base, name);
    const stat = await lstat(path);
    if (registered.has(resolve(path))) continue;
    if (stat.isSymbolicLink()) {
      entries.push({ path, name, kind: 'junction', reason: 'junction-link-only-manual', stale: false,
        detail: 'junction/シンボリックリンクはリンクだけを外してから再実行。リンク先は削除しない' });
    } else if (stat.isDirectory()) {
      entries.push({ path, name, kind: 'orphan', reason: name.startsWith('agent-') ? 'orphan-agent-manual' : 'non-agent-path', stale: false,
        detail: '未登録ディレクトリはGitのdirty判定ができないため自動削除しない' });
    }
  }
  return entries;
}

async function inspect(root, config) {
  const listed = parseWorktrees((await git(root, ['worktree', 'list', '--porcelain'])).stdout);
  const registered = new Set(listed.map((item) => resolve(item.worktree)));
  const cutoff = Math.floor(Date.now() / 1000) - config.days * 86400;
  const entries = [];
  for (const item of listed) {
    const path = resolve(item.worktree);
    const name = path.split(/[\\/]/).at(-1);
    const branch = shortBranch(item.branch);
    const agentPath = resolve(dirname(path)) === resolve(join(root, '.claude', 'worktrees')) && name.startsWith('agent-');
    const missing = !existsSync(path);
    const base = { path, name, branch, kind: 'worktree', stale: false };
    if (missing || item.prunable !== undefined) {
      entries.push({ ...base, kind: 'prunable', reason: agentPath ? 'prunable-missing' : 'non-agent-path', stale: agentPath,
        detail: 'git worktree prune で登録を整理可能。ディレクトリ実体は自動削除しない' });
      continue;
    }
    if (!agentPath) {
      entries.push({ ...base, reason: 'non-agent-path', detail: '対象は .claude/worktrees/agent-* のみ' });
      continue;
    }
    const epoch = await commitEpoch(path);
    if (!epoch.ok) {
      entries.push({ ...base, stale: true, reason: 'inspection-failed', detail: 'HEAD取得に失敗したため安全側で保持' });
      continue;
    }
    const stale = epoch.epoch < cutoff;
    base.stale = stale;
    if (!stale) {
      entries.push({ ...base, reason: 'recent', detail: '閾値以内のため保持' });
    } else if (item.locked !== undefined) {
      entries.push({ ...base, reason: 'locked', detail: 'worktree lock のため保持。稼働確認後に手動unlockが必要' });
    } else if (await nestedJunction(path)) {
      entries.push({ ...base, reason: 'nested-junction', detail: 'frontend/node_modules のjunction/シンボリックリンクを検出。リンクだけを外してから再実行し、リンク先は削除しない' });
    } else {
      const indexLock = await hasIndexLock(path);
      if (!indexLock.ok) {
        entries.push({ ...base, reason: 'inspection-failed', detail: 'index.lock確認に失敗したため安全側で保持' });
      } else if (indexLock.exists) {
      entries.push({ ...base, reason: 'index-lock-unverified', detail: 'index.lock を検出。関連gitプロセス未確認のため保持し、lockは削除しない' });
      } else {
        const dirty = await isDirty(path);
        if (!dirty.ok) {
          entries.push({ ...base, reason: 'inspection-failed', detail: 'dirty確認に失敗したため安全側で保持' });
        } else if (dirty.dirty) {
          entries.push({ ...base, reason: 'dirty', detail: '未コミット変更があるため保持' });
        } else if (!branch.startsWith('worktree-agent-')) {
          entries.push({ ...base, reason: 'protected-branch', detail: 'feature/feat等の業務ブランチは温存' });
        } else {
          entries.push({ ...base, reason: 'clean-stale-agent', detail: 'apply時に撤去可能' });
        }
      }
    }
  }
  entries.push(...await diskEntries(root, registered));
  return { cutoff, entries, totalWorktrees: listed.length };
}

function summarize(inspection, removed = []) {
  const removable = inspection.entries.filter((entry) => entry.reason === 'clean-stale-agent').length;
  const staleRemaining = inspection.entries.filter((entry) => entry.stale).length;
  const retained = inspection.entries.filter((entry) => !removed.includes(entry.path) && entry.reason !== 'clean-stale-agent').length;
  return {
    cutoff: new Date(inspection.cutoff * 1000).toISOString(),
    entries: inspection.entries,
    removed,
    counts: {
      totalWorktrees: inspection.totalWorktrees,
      targets: inspection.entries.filter((entry) => entry.name?.startsWith('agent-')).length,
      removable,
      removed: removed.length,
      retained,
      staleRemaining,
      failures: inspection.entries.filter((entry) => ['dirty', 'locked', 'nested-junction', 'index-lock-unverified', 'inspection-failed', 'prunable-missing', 'junction-link-only-manual', 'orphan-agent-manual'].includes(entry.reason)).map((entry) => ({ path: entry.path, reason: entry.reason })),
    },
  };
}

async function apply(root, inspection) {
  const removed = [];
  for (const entry of inspection.entries.filter((candidate) => candidate.reason === 'clean-stale-agent')) {
    const removal = await git(root, ['worktree', 'remove', '--force', entry.path], true);
    if (removal.failed) continue;
    removed.push(entry.path);
    await git(root, ['branch', '-D', entry.branch], true);
  }
  return removed;
}

function render(result, json) {
  if (json) return JSON.stringify(result, null, 2);
  const lines = [
    `対象数: ${result.counts.targets}`, `削除数: ${result.counts.removed}`, `保持数: ${result.counts.retained}`,
    `残存stale数: ${result.counts.staleRemaining}`, `全worktree数: ${result.counts.totalWorktrees}`,
  ];
  for (const entry of result.entries) lines.push(`${entry.reason}: ${entry.path}${entry.detail ? ` (${entry.detail})` : ''}`);
  return lines.join('\n');
}

async function main() {
  const config = options(process.argv.slice(2));
  // worktree 内から起動しても、本陣の共通 git directory を基準にする。
  const commonDir = (await git(config.repo, ['rev-parse', '--path-format=absolute', '--git-common-dir'])).stdout.trim();
  config.repo = resolve(dirname(commonDir));
  const before = await inspect(config.repo, config);
  const removed = config.apply ? await apply(config.repo, before) : [];
  const after = config.apply ? await inspect(config.repo, config) : before;
  const result = summarize(after, removed);
  console.log(render(result, config.json));
  if (config.check && (result.counts.totalWorktrees > config.limit || result.counts.staleRemaining > 0)) process.exitCode = 2;
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
