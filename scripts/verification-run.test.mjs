import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { execFile, spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile, rm, stat, symlink, chmod } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import test from 'node:test';
import { Readable, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

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
  assert.equal(r.archiveResults, undefined);
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
// 新規の同UID・非dumpableな脱走fixtureだけを作る。既存プロセスは変更しない。
async function inaccessibleFixture(t, readOwner = waitFile) {
  const cleanups = [];
  const f = await fixture({ after: cleanup => cleanups.push(cleanup) });
  let running, owner;
  // spawn/owner読取より先に登録する。準備失敗でも固定の専用Eだけへ解放を通知する。
  t.after(async () => {
    if (!running) {
      for (const cleanup of cleanups) await cleanup();
      return;
    }
    let finished = false;
    running.then(() => { finished = true; });
    const finishDeadline = Date.now() + 15000;
    do {
      for (const name of ['release', 'finish-command']) {
        try { await writeFile(join(f.evidence, name), ''); }
        catch (e) { if (e.code !== 'ENOENT') throw e; }
      }
      if (!finished) await Promise.race([running, new Promise(resolve => setTimeout(resolve, 25))]);
    } while (!finished && Date.now() < finishDeadline);
    assert.ok(finished, 'fixture runnerの有界終了未確認ではcopyを撤去しない');
    await running;
    if (owner) {
      const deadline = Date.now() + 10000;
      let gone = false;
      while (Date.now() < deadline) {
        try {
          const raw = await readFile(`/proc/${owner.pid}/stat`, 'utf8');
          const fields = raw.slice(raw.lastIndexOf(')') + 2).split(' ');
          if (fields[19] !== owner.start || ['Z', 'X'].includes(fields[0])) { gone = true; break; }
        } catch (e) { if (e.code === 'ENOENT' || e.code === 'ESRCH') { gone = true; break; } throw e; }
        await new Promise(resolve => setTimeout(resolve, 10));
      }
      assert.ok(gone, '非dumpable fixtureの停止未確認ではcopyを撤去しない');
    }
    const r = await receipt(f);
    if (r.cleanup === 'retained') {
      assert.ok(owner, `owner未確定のcopyは安全保持する: ${r.scratch}`);
      const marker = JSON.parse(await readFile(join(r.scratch, '.verification-owner.json'), 'utf8'));
      assert.equal(marker.evidence, f.evidence);
      assert.equal(marker.scratch, r.scratch);
      assert.equal(marker.uid, process.getuid());
      assert.ok(marker.token);
      await rm(r.scratch, { recursive: true });
    }
    for (const cleanup of cleanups) await cleanup();
    return { ownerConfirmed: Boolean(owner), cleanup: r.cleanup, scratch: r.scratch };
  });
  const python = `import ctypes,os,time,json
assert ctypes.CDLL(None).prctl(4,0,0,0,0)==0
e=os.environ['VERIFICATION_EVIDENCE_DIR']
start=open('/proc/self/stat').read().rsplit(')',1)[1].split()[19]
with open(e+'/inaccessible.tmp','x') as out: out.write(json.dumps({'pid':os.getpid(),'start':start}))
os.rename(e+'/inaccessible.tmp',e+'/inaccessible')
while not os.path.exists(e+'/release'): time.sleep(.01)
`;
  const code = `const fs=require('fs'),{spawn}=require('child_process');const p=spawn('/usr/bin/python3',['-I','-B','-c',${JSON.stringify(python)}],{detached:true,stdio:'ignore'});p.unref();const e=process.env.VERIFICATION_EVIDENCE_DIR;const timer=setInterval(()=>{if(fs.existsSync(e+'/finish-command'))clearInterval(timer)},10)`;
  running = run(f, [process.execPath, '-e', code], { timeout: 12000 });
  const parsed = JSON.parse(await readOwner(join(f.evidence, 'inaccessible')));
  assert.ok(Number.isSafeInteger(parsed.pid) && parsed.pid > 1 && typeof parsed.start === 'string' && /^\d+$/.test(parsed.start));
  owner = parsed;
  // namespace内UID0でもptrace権限を落とした実参照監査で拒否されることを確認する。
  await assert.rejects(readFile(`/proc/${owner.pid}/maps`), { code: 'EACCES' });
  return { f, running, owner };
}
for (const failure of ['owner読取timeout', 'ownerJSON不正']) {
  test(`${failure}でも非dumpable fixtureを解放しrunnerがcopyを撤去する`, { skip: !linux }, async () => {
    const hooks = [];
    let processOwner, evidence;
    await assert.rejects(inaccessibleFixture({ after: hook => hooks.push(hook) }, async path => {
      // 実際にdetached fixtureが生存した後、owner準備の例外を注入する。
      evidence = dirname(path);
      processOwner = JSON.parse(await waitFile(path));
      if (failure === 'owner読取timeout') throw new Error('fixture owner読取timeout');
      return '{';
    }), failure === 'owner読取timeout' ? /owner読取timeout/ : SyntaxError);
    assert.equal(hooks.length, 1);
    const result = await hooks[0]();
    assert.equal(result.ownerConfirmed, false);
    assert.equal(result.cleanup, 'removed');
    await absent(result.scratch);
    await absent(evidence);
    try {
      const raw = await readFile(`/proc/${processOwner.pid}/stat`, 'utf8');
      const fields = raw.slice(raw.lastIndexOf(')') + 2).split(' ');
      assert.ok(fields[19] !== processOwner.start || ['Z', 'X'].includes(fields[0]));
    } catch (e) { if (e.code !== 'ENOENT' && e.code !== 'ESRCH') throw e; }
  });
}
test('一過性の新規EACCESは有界内の再監査で終了を確認して撤去する', { skip: !linux }, async t => {
  const { f, running } = await inaccessibleFixture(t);
  await writeFile(join(f.evidence, 'finish-command'), '');
  const release = new Promise(resolve => setTimeout(resolve, 200)).then(() => writeFile(join(f.evidence, 'release'), ''));
  const [result] = await Promise.all([running, release]);
  assert.equal(result.code, 0, result.stderr);
  const r = await receipt(f);
  assert.equal(r.cleanup, 'removed');
  await absent(r.scratch);
});
test('持続EACCESの新規生存プロセスは有界待機後も保持して非0にする', { skip: !linux }, async t => {
  const { f, running, owner } = await inaccessibleFixture(t);
  await writeFile(join(f.evidence, 'finish-command'), '');
  const result = await Promise.race([running, new Promise((_, reject) => setTimeout(() => reject(new Error('参照監査timeout')), 8000).unref())]);
  assert.notEqual(result.code, 0);
  const r = await receipt(f);
  assert.equal(r.cleanup, 'retained');
  assert.match(r.cleanupReason, new RegExp(`PID ${owner.pid}:.*EACCES`));
  await stat(r.scratch);
});
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
  assert.equal(r.archiveResults.tar.code, 23);
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
  const initial = await receipt(f);
  const attention = await mkdtemp(join(dirname(initial.scratch), 'run-'));
  const token = randomUUID();
  await writeFile(join(attention, '.verification-owner.json'), JSON.stringify({ uid: process.getuid(), pid: process.pid, start: '0', scratch: attention, evidence: f.evidence, token }), { mode: 0o600 });
  t.after(async () => {
    assert.equal(JSON.parse(await readFile(join(attention, '.verification-owner.json'), 'utf8')).token, token);
    await rm(attention, { recursive: true });
  });
  const check = await exec(process.execPath, [script, '--check']).catch(e => e);
  // --checkの終了値は管理親全体の要処置を表す。自身が稼働中でも別の保持copyがあれば1。
  assert.equal(check.code, 1);
  assert.match(check.stdout, new RegExp(`^稼働中: ${initial.scratch} baseline除外=\\d+$`, 'm'));
  assert.match(check.stdout, new RegExp(`^要処置: ${attention} `, 'm'));
  await stat(initial.scratch);
  await stat(attention);
  p.kill('SIGTERM');
  assert.notEqual(await Promise.race([done, new Promise((_, reject) => setTimeout(() => reject(new Error('停止処理timeout')), 8000).unref())]), 0);
  const r = await receipt(f);
  assert.equal(r.cleanup, 'retained');
  assert.match(r.cleanupReason, /group/);
  process.kill(pid, 'SIGKILL');
  await rm(r.scratch, { recursive: true });
});

test('端末切断SIGHUPでも子を停止しreceipt保存後に撤去する', { skip: !linux }, async t => {
  const f = await fixture(t);
  const code = `require('fs').writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/ready',String(process.pid));setInterval(()=>{},1000)`;
  const p = spawn(process.execPath, [script, '--source', f.source, '--evidence-dir', f.evidence, '--', process.execPath, '-e', code], { stdio: 'ignore' });
  const done = new Promise(resolve => p.once('exit', (code, signal) => resolve({ code, signal })));
  const pid = Number(await waitFile(join(f.evidence, 'ready')));
  const initialReceipt = await receipt(f);
  // red時にも、このfixture自身が作った子とmarker照合済みscratchだけを撤去する。
  t.after(async () => {
    try { process.kill(pid, 'SIGKILL'); } catch (e) { if (e.code !== 'ESRCH') throw e; }
    const r = initialReceipt;
    try {
      const owner = JSON.parse(await readFile(join(r.scratch, '.verification-owner.json'), 'utf8'));
      assert.equal(owner.evidence, f.evidence);
      assert.equal(owner.scratch, r.scratch);
      assert.equal(owner.pid, p.pid);
      assert.equal(owner.uid, process.getuid());
      assert.ok(owner.token);
      await rm(r.scratch, { recursive: true });
    } catch (e) { if (e.code !== 'ENOENT') throw e; }
  });
  p.kill('SIGHUP');
  assert.notEqual((await done).code, 0);
  const r = await receipt(f);
  assert.equal(r.signal, 'SIGHUP');
  assert.equal(r.cleanup, 'removed');
  await absent(r.scratch);
});


test('archive診断は正常とpipeline失敗とgit/tar非0を役割別に保存する', async () => {
  const { archiveResultsForReceipt } = await import('./verification-run.mjs');
  const fulfilled = value => ({ status: 'fulfilled', value });
  const success = [fulfilled(undefined), fulfilled({ code: 0, signal: null }), fulfilled({ code: 0, signal: null })];
  assert.deepEqual(archiveResultsForReceipt(success), {
    pipeline: { status: 'fulfilled' },
    git: { status: 'fulfilled', code: 0, signal: null },
    tar: { status: 'fulfilled', code: 0, signal: null },
  });
  const pipe = pipeline(Readable.from(['fixture']), new Writable({
    write(chunk, encoding, callback) { callback(Object.assign(new Error('synthetic pipe failure'), { code: 'EPIPE' })); },
  }));
  const actual = await Promise.allSettled([pipe, Promise.resolve({ code: 0, signal: null }), Promise.resolve({ code: 0, signal: null })]);
  assert.deepEqual(archiveResultsForReceipt(actual).pipeline,
    { status: 'rejected', code: 'EPIPE', message: 'synthetic pipe failure' });
  for (const [index, role, code] of [[1, 'git', 17], [2, 'tar', 23]]) {
    const results = [...success];
    results[index] = fulfilled({ code, signal: null });
    assert.deepEqual(archiveResultsForReceipt(results)[role], { status: 'fulfilled', code, signal: null });
  }
  const signal = [...success];
  signal[1] = fulfilled({ code: null, signal: 'SIGTERM' });
  assert.deepEqual(archiveResultsForReceipt(signal).git, { status: 'fulfilled', code: null, signal: 'SIGTERM' });
  const rejected = [...success];
  rejected[0] = { status: 'rejected', reason: new Error('x'.repeat(1000)) };
  assert.equal(archiveResultsForReceipt(rejected).pipeline.message.length, 256);
});


test('git archive非0をreceiptへ識別しscratchを撤去する', { skip: !linux }, async t => {
  const f = await fixture(t);
  const bin = join(f.root, 'bin');
  await mkdir(bin);
  await writeFile(join(bin, 'git'), '#!/bin/sh\ncase " $* " in *" archive "*) exit 17;; esac\nexec /usr/bin/git "$@"\n');
  await chmod(join(bin, 'git'), 0o755);
  const result = await run(f, undefined, { env: { ...process.env, PATH: `${bin}:${process.env.PATH}` } });
  assert.notEqual(result.code, 0);
  const r = await receipt(f);
  assert.equal(r.archiveResults.git.code, 17);
  assert.equal(r.cleanup, 'removed');
  await absent(r.scratch);
});


test('tarはEOAで早期終了せずgit archiveの実EOFまで読み切る', { skip: !linux }, async t => {
  const f = await fixture(t);
  const bin = join(f.root, 'bin');
  await mkdir(bin);
  // 有効な固定HEAD archiveの後にEOFだけを遅延し、本文や終了値は変更しない。
  await writeFile(join(bin, 'git'), '#!/bin/sh\ncase " $* " in *" archive "*) /usr/bin/git "$@"; result=$?; sleep 0.1; exit "$result";; esac\nexec /usr/bin/git "$@"\n');
  await chmod(join(bin, 'git'), 0o755);
  const code = `const fs=require('fs'),crypto=require('crypto');fs.writeFileSync(process.env.VERIFICATION_EVIDENCE_DIR+'/payload-sha256',crypto.createHash('sha256').update(fs.readFileSync('tracked')).digest('hex'))`;
  const result = await run(f, [process.execPath, '-e', code], { env: { ...process.env, PATH: `${bin}:${process.env.PATH}` } });
  assert.equal(result.code, 0, result.stderr);
  assert.equal(await readFile(join(f.evidence, 'payload-sha256'), 'utf8'), createHash('sha256').update('original').digest('hex'));
  const r = await receipt(f);
  assert.equal(r.error, null);
  assert.equal(r.cleanup, 'removed');
  await absent(r.scratch);
});
