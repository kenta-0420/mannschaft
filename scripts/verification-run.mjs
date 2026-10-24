#!/usr/bin/env node
import { spawn, execFile } from 'node:child_process';
import { constants, readFileSync } from 'node:fs';
import { lstat, stat, realpath, mkdir, mkdtemp, readFile, writeFile, readdir, readlink, rm, open, rename } from 'node:fs/promises';
import { join, resolve, dirname, relative, isAbsolute } from 'node:path';
import { promisify } from 'node:util';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const exec = promisify(execFile);
const uid = process.getuid?.();
const parent = `/var/tmp/mannschaft-verification-${uid}`;
const markerName = '.verification-owner.json';
const inside = (root, path) => { const r = relative(root, path); return r === '' || (!r.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`) && r !== '..' && !isAbsolute(r)); };
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function parse(args) {
  if (args.length === 1 && args[0] === '--check') return { check: true };
  const split = args.indexOf('--');
  if (split < 0 || split === args.length - 1) throw new Error('使用法: --source <clean Git root> --evidence-dir <新規外部dir> -- <command> [args] / --check');
  const options = {};
  for (let i = 0; i < split; i += 2) {
    if (!['--source', '--evidence-dir'].includes(args[i]) || !args[i + 1] || i + 1 >= split || options[args[i]]) throw new Error('空・重複・不明な引数');
    options[args[i]] = args[i + 1];
  }
  if (!options['--source'] || !options['--evidence-dir']) throw new Error('source と evidence-dir は必須');
  return { source: options['--source'], evidence: options['--evidence-dir'], command: args.slice(split + 1) };
}
// archive本文や環境は保存せず、展開失敗の3結果だけを識別する。
export function archiveResultsForReceipt(results) {
  return Object.fromEntries(['pipeline', 'git', 'tar'].map((role, index) => {
    const result = results[index];
    const detail = result.status === 'rejected'
      ? { status: 'rejected', code: result.reason?.code ?? null, message: String(result.reason?.message ?? result.reason).slice(0, 256) }
      : { status: 'fulfilled', ...(role === 'pipeline' ? {} : { code: result.value?.code ?? null, signal: result.value?.signal ?? null }) };
    return [role, detail];
  }));
}
async function identity(path) {
  const s = await lstat(path);
  if (!s.isDirectory() || s.isSymbolicLink() || s.uid !== uid) throw new Error(`所有ディレクトリではない: ${path}`);
  return { dev: s.dev, ino: s.ino };
}
async function verify(path, expected) {
  const actual = await identity(path);
  if (actual.dev !== expected.dev || actual.ino !== expected.ino || await realpath(path) !== path) throw new Error(`所有identity変更: ${path}`);
}
async function managedParent(create) {
  if (create) {
    try { await mkdir(parent, { mode: 0o700 }); } catch (e) { if (e.code !== 'EEXIST') throw e; }
  }
  try {
    const id = await identity(parent);
    const s = await lstat(parent);
    if ((s.mode & 0o077) !== 0 || await realpath(parent) !== parent) throw new Error(`管理親が専用ではない: ${parent}`);
    return id;
  } catch (e) { if (!create && e.code === 'ENOENT') return null; throw e; }
}
async function processInfo(pid) {
  const raw = await readFile(`/proc/${pid}/stat`, 'utf8');
  const fields = raw.slice(raw.lastIndexOf(')') + 2).split(' ');
  return { state: fields[0], group: Number(fields[2]), start: fields[19] };
}
async function live(pid, start) {
  try { const p = await processInfo(pid); return p.state !== 'Z' && p.state !== 'X' && p.start === start; }
  catch (e) { if (e.code === 'ENOENT' || e.code === 'ESRCH') return false; throw e; }
}
// 同じUIDの生存プロセスだけを調べる。別UIDの領域は管理対象ではない。
export function baselineExclusion(error, pid, start, baseline) {
  return error.code === 'EACCES' && typeof start === 'string' && baseline.get(pid) === start;
}
async function inspectPaths(pid, scratch) {
  const found = [];
  for (const name of ['cwd', 'root', 'exe', ...(await readdir(`/proc/${pid}/fd`)).map(fd => `fd/${fd}`)]) {
    try { if (inside(scratch, (await readlink(`/proc/${pid}/${name}`)).replace(/ \(deleted\)$/, ''))) { found.push(`参照 PID ${pid}`); break; } }
    catch (e) { if (!['ENOENT', 'ESRCH', 'EINVAL'].includes(e.code)) throw e; }
  }
  const maps = await readFile(`/proc/${pid}/maps`, 'utf8');
  if (maps.split('\n').some(line => { const path = line.match(/\s(\/.*)$/)?.[1]; return path && inside(scratch, path.replace(/ \(deleted\)$/, '')); })) found.push(`参照 maps PID ${pid}`);
  return found;
}
async function snapshotInaccessible() {
  const baseline = new Map();
  for (const pid of (await readdir('/proc')).filter(p => /^\d+$/.test(p))) {
    let info;
    try {
      if ((await stat(`/proc/${pid}`)).uid !== uid) continue;
      info = await processInfo(pid);
      if (info.state === 'Z' || info.state === 'X') continue;
      await inspectPaths(pid, '/__verification_baseline_nonexistent__');
    } catch (e) {
      if (e.code === 'EACCES' && info?.start) baseline.set(pid, info.start);
      else if (!['ENOENT', 'ESRCH'].includes(e.code)) throw e;
    }
  }
  return baseline;
}
async function references(scratch, groups = [], baseline = new Map()) {
  const found = [];
  for (const pid of (await readdir('/proc')).filter(p => /^\d+$/.test(p))) {
    try {
      if ((await stat(`/proc/${pid}`)).uid !== uid) continue;
      const p = await processInfo(pid);
      if (p.state === 'Z' || p.state === 'X') continue;
      if (groups.includes(p.group)) { found.push(`group PID ${pid}`); continue; }
      found.push(...await inspectPaths(pid, scratch));
    } catch (e) {
      if (e.code === 'EACCES') {
        let current;
        try { current = await processInfo(pid); }
        catch (gone) { if (['ENOENT', 'ESRCH'].includes(gone.code)) continue; throw gone; }
        if (current.state === 'Z' || current.state === 'X' || baselineExclusion(e, pid, current.start, baseline)) continue;
      }
      if (!['ENOENT', 'ESRCH'].includes(e.code)) throw new Error(`停止/参照確認不能 PID ${pid}: ${e.message}`);
    }
  }
  return found;
}
async function check() {
  if (!await managedParent(false)) return 0;
  let attention = false;
  for (const name of await readdir(parent)) {
    const path = join(parent, name);
    try {
      if (!/^run-[A-Za-z0-9]+$/.test(name)) throw new Error('未知の名前（削除しない）');
      await identity(path);
      const owner = JSON.parse(await readFile(join(path, markerName), 'utf8'));
      if (owner.uid !== uid || owner.scratch !== path || !owner.token) throw new Error('所有marker不一致');
      if (await live(owner.pid, owner.start)) console.log(`稼働中: ${path} baseline除外=${owner.baselineInaccessibleCount ?? 0}`);
      else { attention = true; console.log(`要処置: ${path} owner=${owner.pid} evidence=${owner.evidence} baseline除外=${owner.baselineInaccessibleCount ?? 0}`); }
    } catch (e) { attention = true; console.log(`要処置: ${path} ${e.message}`); }
  }
  return attention ? 1 : 0;
}
async function run(options) {
  const source = await realpath(resolve(options.source));
  const git = async (...args) => (await exec('git', ['-C', source, ...args], { maxBuffer: 32 * 1024 * 1024 })).stdout.trim();
  if (await realpath(await git('rev-parse', '--show-toplevel')) !== source) throw new Error('sourceはGit rootを指定する');
  const sourceHead = await git('rev-parse', '--verify', 'HEAD^{commit}');
  if (await git('status', '--porcelain=v1', '--untracked-files=all')) throw new Error('dirty/untracked sourceは拒否');
  if ((await git('ls-tree', '-r', sourceHead)).split('\n').some(line => line.startsWith('160000 '))) throw new Error('submodule/gitlink sourceは未対応');
  const requested = resolve(options.evidence);
  // 最後の1要素だけを作成し、既存やsymlink先への上書きをしない。
  const evidence = join(await realpath(dirname(requested)), requested.split('/').at(-1));
  if (inside(source, evidence) || inside(parent, evidence)) throw new Error('証跡はsource/管理親の外部へ保存する');
  await mkdir(evidence, { mode: 0o700 });
  const evidenceId = await identity(evidence);
  const baseline = await snapshotInaccessible();
  const receipt = { source, sourceHead, evidence, scratch: null, exitCode: null, signal: null, cleanup: 'not-created', cleanupReason: null, error: null, baselineInaccessibleCount: baseline.size };
  async function save() {
    await verify(evidence, evidenceId);
    const temp = join(evidence, `.receipt-${process.pid}.tmp`);
    const fd = await open(temp, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try { await fd.writeFile(JSON.stringify(receipt, null, 2) + '\n'); await fd.sync(); } finally { await fd.close(); }
    try { await rename(temp, join(evidence, 'receipt.json')); }
    catch (e) { await rm(temp); throw e; }
  }
  let scratch, scratchId, parentId, owner, interrupted = null;
  let rejectInterrupted;
  const interruption = new Promise((_, reject) => { rejectInterrupted = reject; });
  interruption.catch(() => {});
  const active = new Map(), groups = [], groupStarts = new Map();
  function signalGroup(pid, signal) {
    const expected = groupStarts.get(pid);
    if (!expected) return;
    try {
      const raw = readFileSync(`/proc/${pid}/stat`, 'utf8');
      const start = raw.slice(raw.lastIndexOf(')') + 2).split(' ')[19];
      if (start !== expected) throw new Error(`PID世代変更（停止しない）: ${pid}`);
    } catch (e) { if (!['ENOENT', 'ESRCH'].includes(e.code)) throw e; }
    try { process.kill(-pid, signal); } catch (e) { if (e.code !== 'ESRCH') throw e; }
  }
  function handleSignal(signal) {
    interrupted = signal;
    receipt.signal = signal;
    rejectInterrupted(new Error('中断: ' + signal));
    for (const pid of active.keys()) {
      try { signalGroup(pid, signal); } catch (e) { receipt.error = e.message; }
    }
  }
  const onTerm = () => handleSignal('SIGTERM'), onInt = () => handleSignal('SIGINT'), onHup = () => handleSignal('SIGHUP');
  process.on('SIGTERM', onTerm); process.on('SIGINT', onInt); process.on('SIGHUP', onHup);
  function start(command, args, settings) {
    if (interrupted) throw new Error(`中断: ${interrupted}`);
    const child = spawn(command, args, { detached: true, ...settings });
    if (child.pid) {
      groups.push(child.pid); active.set(child.pid, child);
      try {
        const raw = readFileSync(`/proc/${child.pid}/stat`, 'utf8');
        groupStarts.set(child.pid, raw.slice(raw.lastIndexOf(')') + 2).split(' ')[19]);
      } catch (e) { if (!['ENOENT', 'ESRCH'].includes(e.code)) throw e; }
    }
    const result = new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('exit', (code, signal) => { active.delete(child.pid); resolve({ code, signal }); });
    });
    // pipeline組立中の起動失敗も未処理rejectionにしない。
    result.catch(() => {});
    return { child, result };
  }
  try {
    await save();
    parentId = await managedParent(true);
    scratch = await mkdtemp(join(parent, 'run-'));
    receipt.scratch = scratch;
    scratchId = await identity(scratch);
    owner = { uid, pid: process.pid, start: (await processInfo(process.pid)).start, scratch, evidence, baselineInaccessibleCount: baseline.size, token: crypto.randomUUID() };
    await writeFile(join(scratch, markerName), JSON.stringify(owner), { flag: 'wx', mode: 0o600 });
    await save();
    const tree = join(scratch, 'tree');
    await mkdir(tree);
    const archive = start('git', ['-C', source, 'archive', '--format=tar', sourceHead], { stdio: ['ignore', 'pipe', 'inherit'] });
    const unpack = start('tar', ['-x', '--ignore-zeros', '-f', '-', '-C', tree], { stdio: ['pipe', 'ignore', 'inherit'] });
    const results = await Promise.race([Promise.allSettled([pipeline(archive.child.stdout, unpack.child.stdin), archive.result, unpack.result]), interruption]);
    if (results.some(r => r.status === 'rejected' || (r.value && (r.value.code !== 0 || r.value.signal)))) {
      receipt.archiveResults = archiveResultsForReceipt(results);
      throw new Error('git archive/tar展開失敗');
    }
    const child = start(options.command[0], options.command.slice(1), { cwd: tree, env: { ...process.env, VERIFICATION_EVIDENCE_DIR: evidence }, stdio: 'inherit' });
    const result = await Promise.race([child.result, interruption]);
    receipt.exitCode = result.code;
    receipt.signal = result.signal;
  } catch (e) { receipt.error = e.message; }
  finally {
    receipt.signal = interrupted || receipt.signal;
    try {
      if (scratch) {
        // commandが孫を残して終了した場合も、自分のgroupだけを停止する。
        for (const pid of groups) signalGroup(pid, 'SIGTERM');
        const deadline = Date.now() + 2000;
        let refs;
        do { refs = await references(scratch, groups, baseline); if (!refs.some(r => r.startsWith('group'))) break; await delay(50); } while (Date.now() < deadline);
        if (refs.length) throw new Error(`停止/参照確認: ${refs.join(', ')}`);
        await verify(parent, parentId);
        await verify(scratch, scratchId);
        const current = JSON.parse(await readFile(join(scratch, markerName), 'utf8'));
        if (current.token !== owner.token || current.pid !== owner.pid || current.scratch !== scratch) throw new Error('所有marker変更');
        receipt.cleanup = 'pending';
        await save();
        await rm(scratch, { recursive: true });
        receipt.cleanup = 'removed';
      }
    } catch (e) { receipt.cleanup = 'retained'; receipt.cleanupReason = e.message; }
    try { await save(); }
    catch (e) { receipt.error = `${receipt.error || ''} receipt保存失敗: ${e.message}`; }
    for (const child of active.values()) { child.unref(); child.stdout?.destroy(); child.stdin?.destroy(); }
    process.removeListener('SIGTERM', onTerm); process.removeListener('SIGINT', onInt); process.removeListener('SIGHUP', onHup);
  }
  if (receipt.cleanup === 'retained') console.error(`保持: ${scratch} 理由: ${receipt.cleanupReason}`);
  if (receipt.error) console.error(receipt.error);
  console.log(`receipt: ${join(evidence, 'receipt.json')} cleanup=${receipt.cleanup}`);
  return receipt.error || receipt.cleanup === 'retained' || receipt.signal ? 1 : (receipt.exitCode ?? 1);
}
export async function main(args = process.argv.slice(2)) {
  try {
    const options = parse(args);
    if (process.platform !== 'linux' || Number(process.versions.node.split('.')[0]) < 22) throw new Error('Linux/WSL Node.js 22以降が必要');
    return options.check ? await check() : await run(options);
  } catch (e) { console.error(e.message); return 1; }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await main();
