import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile, rm, stat, symlink, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import test from 'node:test';

const exec = promisify(execFile);
const script = fileURLToPath(new URL('./verification-run.mjs', import.meta.url));
const linux = process.platform === 'linux';
const git = (cwd, ...args) => exec('git', args, { cwd });
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'verification-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const source = join(root, 'source');
  await mkdir(source);
  await git(source, 'init', '-b', 'main');
  await git(source, 'config', 'user.name', '検証fixture');
  await git(source, 'config', 'user.email', 'verification@example.test');
  await writeFile(join(source, 'tracked'), 'original');
  await writeFile(join(source, '.gitignore'), 'build/\n');
  await git(source, 'add', '.');
  await git(source, 'commit', '-m', 'fixture');
  return { root, source, evidence: join(root, 'evidence') };
}
async function run(f, command = [process.execPath, '-e', ''], options = {}) {
  try {
    const result = await exec(process.execPath, [script, '--source', f.source, '--evidence-dir', f.evidence, '--', ...command], options);
    return { ...result, code: 0 };
  } catch (error) {
    return { code: error.code, stdout: error.stdout, stderr: error.stderr };
  }
}
const receipt = async f => JSON.parse(await readFile(join(f.evidence, 'receipt.json'), 'utf8'));
const absent = async path => assert.rejects(stat(path), { code: 'ENOENT' });

// Linux の process group と /proc を実測するため Windows では実行しない。
test('clean HEADだけ展開し生成物を除外、証跡保存後に撤去する', { skip: !linux }, async t => {
  const f = await fixture(t);
  await mkdir(join(f.source, 'build'));
  await writeFile(join(f.source, 'build', 'cache'), 'ignored');
  const head = (await git(f.source, 'rev-parse', 'HEAD')).stdout.trim();
  const result = await run(f, [process.execPath, '-e', `const fs=require('fs');fs.writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/result',JSON.stringify({cwd:process.cwd(),tracked:fs.readFileSync('tracked','utf8'),git:fs.existsSync('.git'),build:fs.existsSync('build')}))`]);
  assert.equal(result.code, 0, result.stderr);
  const proof = JSON.parse(await readFile(join(f.evidence, 'result')));
  assert.deepEqual({ ...proof, cwd: undefined }, { cwd: undefined, tracked: 'original', git: false, build: false });
  const r = await receipt(f);
  assert.equal(r.sourceHead, head);
  assert.equal(r.cleanup, 'removed');
  await absent(proof.cwd);
  assert.equal(await readFile(join(f.source, 'tracked'), 'utf8'), 'original');
  assert.equal((await git(f.source, 'status', '--porcelain')).stdout, '');
});
for (const kind of ['dirty', 'untracked', 'gitlink']) {
  test(`${kind} sourceは作成前に拒否`, { skip: !linux }, async t => {
    const f = await fixture(t);
    if (kind === 'dirty') await writeFile(join(f.source, 'tracked'), 'changed');
    if (kind === 'untracked') await writeFile(join(f.source, 'new'), 'new');
    if (kind === 'gitlink') {
      const head = (await git(f.source, 'rev-parse', 'HEAD')).stdout.trim();
      await git(f.source, 'update-index', '--add', '--cacheinfo', `160000,${head},sub`);
      await git(f.source, 'commit', '-m', 'gitlink');
    }
    assert.notEqual((await run(f)).code, 0);
    await absent(f.evidence);
  });
}
for (const [name, command, expected] of [
  ['非0終了', [process.execPath, '-e', 'process.exit(7)'], 7],
  ['起動失敗', ['/nonexistent-verification-command'], null],
]) {
  test(`${name}でもreceiptと撤去結果を残す`, { skip: !linux }, async t => {
    const f = await fixture(t);
    assert.notEqual((await run(f, command)).code, 0);
    const r = await receipt(f);
    assert.equal(r.exitCode, expected);
    assert.equal(r.cleanup, 'removed');
    await absent(r.scratch);
  });
}
test('evidence既存・source内・管理親内と空/不正引数を拒否', { skip: !linux }, async t => {
  const f = await fixture(t);
  for (const path of [f.root, join(f.source, 'proof'), `/var/tmp/mannschaft-verification-${process.getuid()}/proof`]) {
    assert.notEqual((await run({ ...f, evidence: path })).code, 0);
  }
  for (const args of [[], ['--source', ''], ['--wat'], ['--check', '--source', f.source]]) {
    await assert.rejects(exec(process.execPath, [script, ...args]));
  }
});
test('tracked symlinkの外部ターゲットを削除しない', { skip: !linux }, async t => {
  const f = await fixture(t);
  const target = join(f.root, 'outside');
  await mkdir(target);
  await writeFile(join(target, 'valuable'), 'keep');
  await symlink(target, join(f.source, 'link'));
  await git(f.source, 'add', 'link');
  await git(f.source, 'commit', '-m', 'symlink');
  assert.equal((await run(f)).code, 0);
  assert.equal(await readFile(join(target, 'valuable'), 'utf8'), 'keep');
});
test('並行実行は別scratchを持ち互いを撤去しない', { skip: !linux }, async t => {
  const f = await fixture(t);
  const other = { ...f, evidence: join(f.root, 'other-proof') };
  const command = [process.execPath, '-e', `require('fs').writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/cwd',process.cwd());setTimeout(()=>{},200)`];
  const results = await Promise.all([run(f, command), run(other, command)]);
  for (const result of results) assert.equal(result.code, 0, result.stderr);
  const a = await receipt(f), b = await receipt(other);
  assert.notEqual(a.scratch, b.scratch);
  await absent(a.scratch); await absent(b.scratch);
});
test('receipt保存不能ではscratchを保持し正確な理由を表示', { skip: !linux }, async t => {
  const f = await fixture(t);
  const result = await run(f, [process.execPath, '-e', `const fs=require('fs');fs.unlinkSync(process.env.VERIFICATION_EVIDENCE_DIR+'/receipt.json');fs.mkdirSync(process.env.VERIFICATION_EVIDENCE_DIR+'/receipt.json')`]);
  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /receipt|証跡/);
  const match = result.stderr.match(/保持: (\/var\/tmp\/mannschaft-verification-\d+\/run-[^\s]+)/);
  assert.ok(match, result.stderr);
  await stat(match[1]);
  // このテストが作成したことをreceipt失敗出力で確認した個別パスだけを後始末する。
  await rm(match[1], { recursive: true });
});
async function waitFile(path) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    try { return await readFile(path, 'utf8'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    await new Promise(resolve => setTimeout(resolve, 25));
  }
  throw new Error(`fixture準備timeout: ${path}`);
}
test('SIGTERMでは子孫groupを停止してから撤去する', { skip: !linux }, async t => {
  const f = await fixture(t);
  const code = `const {spawn}=require('child_process');const fs=require('fs');const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});fs.writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/ready',String(child.pid));setInterval(()=>{},1000)`;
  const p = spawn(process.execPath, [script, '--source', f.source, '--evidence-dir', f.evidence, '--', process.execPath, '-e', code]);
  let stderr = ''; p.stderr.on('data', chunk => stderr += chunk);
  const done = new Promise(resolve => p.on('exit', (code, signal) => resolve({ code, signal })));
  const childPid = Number(await waitFile(join(f.evidence, 'ready')));
  p.kill('SIGTERM');
  assert.notEqual((await done).code, 0, stderr);
  const r = await receipt(f);
  assert.equal(r.signal, 'SIGTERM');
  assert.equal(r.cleanup, 'removed');
  await absent(r.scratch);
  // zombie は停止済み。実行中の孫が残らないことを実測する。
  try { const s = await readFile(`/proc/${childPid}/stat`, 'utf8'); assert.equal(s.slice(s.lastIndexOf(')') + 2).split(' ')[0], 'Z'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
});
test('脱走daemonがscratchを参照中なら保持しcheckが検知する', { skip: !linux }, async t => {
  const f = await fixture(t);
  const code = `const {spawn}=require('child_process');const p=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{detached:true,stdio:'ignore'});require('fs').writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/pid',String(p.pid));p.unref()`;
  assert.notEqual((await run(f, [process.execPath, '-e', code])).code, 0);
  const r = await receipt(f);
  assert.equal(r.cleanup, 'retained');
  assert.match(r.cleanupReason, /参照/);
  const check = await exec(process.execPath, [script, '--check']).catch(error => error);
  assert.equal(check.code, 1);
  assert.match(check.stdout, new RegExp(r.scratch));
  process.kill(Number(await readFile(join(f.evidence, 'pid'))), 'SIGKILL');
  await rm(r.scratch, { recursive: true });
});

test('開始前EACCESだけをPID世代一致で除外し新規/途中失敗は除外しない', async () => {
  const { baselineExclusion } = await import('./verification-run.mjs');
  const baseline = new Map([['100', '1234']]);
  assert.equal(baselineExclusion({ code: 'EACCES' }, '100', '1234', baseline), true);
  for (const [pid, start, code] of [['100', '5678', 'EACCES'], ['101', '1234', 'EACCES'], ['100', undefined, 'EACCES'], ['100', '1234', 'EIO']]) {
    assert.equal(baselineExclusion({ code }, pid, start, baseline), false);
  }
});
test('展開失敗でも作成済みscratchを撤去する', { skip: !linux }, async t => {
  const f = await fixture(t);
  const bin = join(f.root, 'bin');
  await mkdir(bin);
  await writeFile(join(bin, 'tar'), '#!/bin/sh\nexit 23\n');
  await chmod(join(bin, 'tar'), 0o755);
  const result = await run(f, undefined, { env: { ...process.env, PATH: `${bin}:${process.env.PATH}` } });
  assert.notEqual(result.code, 0);
  const r = await receipt(f);
  assert.match(r.error, /展開/);
  assert.equal(r.cleanup, 'removed');
  await absent(r.scratch);
});
test('HEADは実行途中でsourceが更新されても最初のSHAに固定', { skip: !linux }, async t => {
  const f = await fixture(t);
  const head = (await git(f.source, 'rev-parse', 'HEAD')).stdout.trim();
  const code = `const fs=require('fs'),cp=require('child_process');fs.writeFileSync(${JSON.stringify(join(f.source, 'tracked'))},'next');cp.execFileSync('git',['-C',${JSON.stringify(f.source)},'commit','-am','next']);fs.writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/snapshot',fs.readFileSync('tracked'))`;
  assert.equal((await run(f, [process.execPath, '-e', code])).code, 0);
  assert.equal((await receipt(f)).sourceHead, head);
  assert.equal(await readFile(join(f.evidence, 'snapshot'), 'utf8'), 'original');
});
test('所有marker変更は削除せず理由を返す', { skip: !linux }, async t => {
  const f = await fixture(t);
  const code = `require('fs').writeFileSync('../.verification-owner.json','{}')`;
  const result = await run(f, [process.execPath, '-e', code]);
  assert.notEqual(result.code, 0);
  const r = await receipt(f);
  assert.equal(r.cleanup, 'retained');
  assert.match(r.cleanupReason, /marker/);
  await stat(r.scratch);
  await rm(r.scratch, { recursive: true });
});
test('SIGTERM停止不能は有界待機で保持し終了、動作中checkは削除しない', { skip: !linux }, async t => {
  const f = await fixture(t);
  const code = `process.on('SIGTERM',()=>{});require('fs').writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/ready',String(process.pid));setInterval(()=>{},1000)`;
  const p = spawn(process.execPath, [script, '--source', f.source, '--evidence-dir', f.evidence, '--', process.execPath, '-e', code], { stdio: 'ignore' });
  const done = new Promise(resolve => p.once('exit', code => resolve(code)));
  const pid = Number(await waitFile(join(f.evidence, 'ready')));
  t.after(() => { try { process.kill(pid, 'SIGKILL'); } catch (e) { if (e.code !== 'ESRCH') throw e; } });
  const check = await exec(process.execPath, [script, '--check']).catch(e => e);
  assert.equal(check.code, undefined);
  assert.match(check.stdout, /稼働中:/);
  p.kill('SIGTERM');
  assert.notEqual(await Promise.race([done, new Promise((_, reject) => setTimeout(() => reject(new Error('停止処理timeout')), 8000).unref())]), 0);
  const r = await receipt(f);
  assert.equal(r.cleanup, 'retained');
  assert.match(r.cleanupReason, /group/);
  process.kill(pid, 'SIGKILL');
  await rm(r.scratch, { recursive: true });
});
