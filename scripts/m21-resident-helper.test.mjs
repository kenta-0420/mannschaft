import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, readFile, writeFile, rename, rm, readdir, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import net from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const helper = fileURLToPath(new URL('./m21-resident-helper.cjs', import.meta.url));
const { atomicReplace } = createRequire(import.meta.url)(helper);
function atomicFixture(options = {}) {
  let time = 0, attempts = 0, writes = 0;
  const waits = [], reports = [], pairs = [];
  const io = {
    writeFileSync() { writes++; if (options.writeError) throw { code: options.writeError }; },
    renameSync(from, to) { pairs.push([from, to]); attempts++; if (attempts <= (options.failures ?? 0)) throw { code: options.code ?? 'EPERM' }; if (options.renameElapsed) time += options.renameElapsed; }
  };
  const run = () => atomicReplace('fixed.next', 'fixed.json', Buffer.from('{}'), { io, platform: options.platform ?? 'win32', now: () => time, wait: ms => { waits.push(ms); time += ms; }, deadline: () => options.deadline ?? Infinity, report: value => reports.push(value) });
  return { run, observed: () => ({ time, attempts, writes, waits, reports, pairs }) };
}
for (const code of ['EPERM', 'EACCES', 'EBUSY']) test(`atomic unit: ${code}の最初2回だけ失敗してrename回復`, () => {
  const f = atomicFixture({ failures: 2, code }); f.run(); const o = f.observed();
  assert.equal(o.writes, 1); assert.equal(o.attempts, 3); assert.ok(o.time > 0 && o.time <= 250);
  assert.ok(o.waits.length === 2 && o.pairs.every(pair => pair[0] === 'fixed.next' && pair[1] === 'fixed.json'));
  assert.ok(o.reports.some(r => r.stage === 'RECOVERED' && r.retries === 2));
});
test('atomic unit: 永続EPERMは固定250ms以内にnonGreen', () => {
  const f = atomicFixture({ failures: Infinity }); assert.throws(f.run, /M21_ATOMIC/);
  const o = f.observed(); assert.ok(o.attempts > 1); assert.ok(o.time <= 250); assert.equal(o.writes, 1);
});
test('atomic unit: write硬errorとrename硬errorと非Windowsはretry0', () => {
  for (const options of [{ writeError: 'ENOSPC' }, { failures: Infinity, code: 'EIO' }, { failures: Infinity, code: 'ERR_UNKNOWN' }, { failures: Infinity, platform: 'linux' }]) {
    const f = atomicFixture(options); assert.throws(f.run, /M21_ATOMIC/); const o = f.observed();
    assert.equal(o.waits.length, 0); assert.equal(o.writes, 1); assert.equal(o.attempts, options.writeError ? 0 : 1);
    if (options.code === 'ERR_UNKNOWN') assert.equal(o.reports.at(-1).code, 'UNKNOWN');
  }
});
test('atomic unit: 他active接続の最早deadlineを越えてretryしない', () => {
  const f = atomicFixture({ failures: Infinity, deadline: 17 }); assert.throws(f.run, /M21_ATOMIC/);
  assert.ok(f.observed().time <= 17); assert.ok(f.observed().attempts > 0);
});
test('atomic unit: 成功syscall中のdeadline超過も成功へしない', () => {
  const f = atomicFixture({ deadline: 5, renameElapsed: 6 }); assert.throws(f.run, /M21_ATOMIC/); assert.equal(f.observed().attempts, 1);
});
const codes = ['system', 'admin-de', 'member'];
const actors = codes.map((code, i) => ({ code, userId: i + 1, email: `${code}@example.invalid`, password: `秘密-${randomUUID()}`, locale: code === 'admin-de' ? 'de' : 'ja' }));
async function deadline(promise, ms = 20000) {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('試験期限超過')), ms); })]); }
  finally { clearTimeout(timer); }
}
async function poll(read, accept, ms = 20000) {
  const until = Date.now() + ms;
  do { const value = await read(); if (accept(value)) return value; await delay(20); } while (Date.now() < until);
  throw new Error('状態待機期限超過');
}
async function fixture(t, options = {}) {
  const started = new Date().toISOString();
  const runId = `m21-${randomUUID()}`;
  const dir = join(tmpdir(), runId); await mkdir(dir);
  let entry = helper;
  if (options.permanentStateFailure) {
    entry = join(dir, 'fixture-bootstrap.cjs');
    // 固定local試験bootstrap。helperのIPC/CLI/envに故障注入入口は追加しない。
    await writeFile(entry, `const fs = require('node:fs'); const original = fs.renameSync; fs.renameSync = (a,b) => { if (b.endsWith('exploration-helper-state.json') && fs.existsSync(b)) { const e = new Error('固定試験'); e.code = 'EPERM'; throw e; } return original(a,b); }; require(${JSON.stringify(helper)}).run();`);
  }
  const child = spawn(process.execPath, [entry], { env: { SystemRoot: process.env.SystemRoot, WINDIR: process.env.WINDIR, TEMP: tmpdir(), TMP: tmpdir(), M21_RUN_DIRECTORY: dir, M21_ACTORS_JSON: options.rawActors ?? JSON.stringify(actors) }, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  let out = '', err = ''; child.stdout.on('data', b => { out += b; }); child.stderr.on('data', b => { err += b; });
  const ended = once(child, 'close');
  const sockets = new Set();
  const clients = [];
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    if (child.exitCode === null && child.signalCode === null) child.kill();
    await deadline(ended);
    try {
      const files = await readdir(dir);
      const stateText = files.includes('exploration-helper-state.json') ? await readFile(join(dir, 'exploration-helper-state.json'), 'utf8') : null;
      let stateSummary = null;
      if (stateText) { try { const s = JSON.parse(stateText); stateSummary = { failed: s.failed === true, servedCount: Array.isArray(s.servedCodes) ? s.servedCodes.length : -1 }; } catch { stateSummary = { invalid: true }; } }
      // 定型コードと時刻/phaseだけ。raw stderr/actor/pipe応答は診断へ渡さない。
      const recovered = [...err.matchAll(/^M21_RENAME_RECOVERED_(\d+)_(\d+)$/gm)].slice(0, 8).map(m => ({ retries: Number(m[1]), elapsedMs: Number(m[2]) }));
      const atomicFailures = [...err.matchAll(/^M21_ATOMIC_(WRITE|RENAME|DEADLINE)_([A-Z]+)_(\d+)_(\d+)$/gm)].slice(0, 8).map(m => ({ stage: m[1], code: m[2], retries: Number(m[3]), elapsedMs: Number(m[4]) }));
      const diagnostic = JSON.stringify({ started, ended: new Date().toISOString(), runId, exitCode: child.exitCode, signal: child.signalCode, stdoutEOF: child.stdout.readableEnded, stderrEOF: child.stderr.readableEnded, codes: (err.match(/M21_[A-Z_]+/g) ?? []).slice(0, 8), recovered, atomicFailures, readyExists: files.includes('exploration-ready.json'), stateExists: files.includes('exploration-helper-state.json'), state: stateSummary, clients });
      assert.ok(diagnostic.length <= 4096, '診断は最大4096文字'); t.diagnostic(diagnostic);
      assert.ok(child.exitCode !== null || child.signalCode !== null, '自身の子が実終了');
      assert.ok(child.stdout.readableEnded && child.stderr.readableEnded, '両stream EOF');
      for (const actor of actors) { assert.ok(!out.includes(actor.password)); assert.ok(!err.includes(actor.password)); }
      for (const name of files) {
        const value = await readFile(join(dir, name), 'utf8');
        for (const actor of actors) { assert.ok(!value.includes(actor.password)); assert.ok(!value.includes(actor.email)); }
        if (name.startsWith('exploration-')) assert.ok((await stat(join(dir, name))).size <= 16384);
      }
    } finally {
      assert.equal(dirname(resolve(dir)), resolve(tmpdir())); assert.match(runId, /^m21-[a-f0-9-]{36}$/);
      await rm(dir, { recursive: true });
      await assert.rejects(stat(dir), { code: 'ENOENT' });
    }
  });
  const owner = { pid: child.pid, creation: new Date().toISOString(), runId, executable: process.execPath, ...options.owner };
  if (!options.noOwner) {
    const body = JSON.stringify(owner);
    const target = join(dir, 'resident-helper-owner.json');
    await writeFile(`${target}.next`, options.ownerBytes ? body.padEnd(options.ownerBytes, ' ') : body);
    await rename(`${target}.next`, target);
  }
  const json = async name => { try { return JSON.parse(await readFile(join(dir, name), 'utf8')); } catch (error) { if (error.code === 'ENOENT') return null; throw error; } };
  const ready = async () => poll(async () => {
    const value = await json('exploration-ready.json');
    if (!value && (child.exitCode !== null || child.signalCode !== null)) throw new Error('helperがready前に実終了');
    return value;
  }, Boolean);
  const state = async () => json('exploration-helper-state.json');
  const connect = async () => {
    const metadata = await ready();
    const events = []; clients.push(events); let phase = 'connecting';
    const mark = event => { if (events.length < 12) events.push({ event, at: new Date().toISOString(), phase }); };
    const socket = net.createConnection(metadata.pipe); sockets.add(socket);
    for (const event of ['connect', 'end', 'close', 'error']) socket.on(event, () => mark(event));
    await deadline(once(socket, 'connect'));
    phase = 'claim';
    let buffered = ''; const lines = []; const waiters = [];
    socket.on('data', b => { mark('data'); buffered += b.toString('utf8'); while (buffered.includes('\n')) { const n = buffered.indexOf('\n'); let value; try { value = JSON.parse(buffered.slice(0, n)); } catch { throw new Error('秘密応答のJSON不正'); } phase = value.op === 'actor' ? 'actor-received' : value.op === 'delivered' ? 'delivered-received' : 'invalid-reply'; buffered = buffered.slice(n + 1); if (waiters.length) waiters.shift()(value); else lines.push(value); } });
    return { socket, received: () => phase === 'delivered-received', read: () => deadline(lines.length ? Promise.resolve(lines.shift()) : new Promise(resolve => waiters.push(resolve))), send: value => { phase = value?.op === 'ack-delivered' ? 'ack-sent' : 'claim-sent'; socket.write(`${JSON.stringify(value)}\n`); } };
  };
  const message = (actorCode = 'system', extra = {}) => ({ op: 'claim-actor', runId, actorCode, requestId: randomUUID(), residentId: randomUUID(), ...extra });
  return { child, dir, runId, ready, state, connect, message, ended, owner, failed: () => poll(async () => {
    const value = await state();
    if (value?.failed !== true && (child.exitCode !== null || child.signalCode !== null)) throw new Error('helperがfailed保存前に実終了');
    return value;
  }, s => s?.failed === true) };
}

test('M21-R4: 固定3actorを一度だけメモリpipeへ渡しack後だけserved、正常closeは成功', async t => {
  const f = await fixture(t); const ready = await f.ready();
  assert.equal(ready.creation, f.owner.creation); assert.equal(ready.pid, f.child.pid); assert.deepEqual(ready.actorCodes, codes); assert.match(ready.pipe, /^\\\\\.\\pipe\\m21-[a-f0-9-]+$/);
  let reading = true, samples = 0;
  const reader = (async () => {
    while (reading) {
      const raw = await readFile(join(f.dir, 'exploration-helper-state.json'));
      assert.ok(raw.length <= 16384); const snapshot = JSON.parse(raw);
      assert.ok(snapshot.runId === f.runId && snapshot.pid === f.child.pid && typeof snapshot.failed === 'boolean');
      assert.ok(Array.isArray(snapshot.servedCodes) && snapshot.servedCodes.every(code => codes.includes(code)) && new Set(snapshot.servedCodes).size === snapshot.servedCodes.length);
      samples++; await delay(1);
    }
  })();
  try {
  for (const code of codes) {
    const c = await f.connect(); const request = f.message(code); c.send(request);
    const reply = await c.read(); assert.equal(reply.op, 'actor'); assert.ok(JSON.stringify(reply.actor) === JSON.stringify(actors.find(a => a.code === code)), 'actorはメモリ内で完全一致');
    assert.ok(!(await f.state()).servedCodes.includes(code));
    c.send({ ...request, op: 'ack-delivered' }); const delivered = await c.read(); assert.equal(delivered.op, 'delivered');
    c.socket.end(); await poll(f.state, s => s.servedCodes.includes(code));
  }
  assert.deepEqual((await f.state()).servedCodes, codes); assert.equal((await f.state()).failed, false);
  } finally { reading = false; await reader; }
  assert.ok(samples > 0); t.diagnostic(`M21_COMPLETE_SNAPSHOTS_${samples}`);
});

for (const [name, mutate] of [
  ['null', () => null], ['異run', m => ({ ...m, runId: `m21-${randomUUID()}` })], ['未知code', m => ({ ...m, actorCode: 'outsider' })], ['必須欠落', m => { delete m.requestId; return m; }], ['余分キー', m => ({ ...m, code: '秘密入力' })], ['op不正', m => ({ ...m, op: 'eval' })], ['UUID不正', m => ({ ...m, residentId: '' })]
]) test(`M21-R5: ${name}を拒否しsticky failed`, async t => {
  const f = await fixture(t); const c = await f.connect(); c.send(mutate(f.message())); await f.failed();
  assert.deepEqual((await f.state()).servedCodes, []);
});

test('M21-R4: ack前切断と再接続再配布を拒否', async t => {
  const f = await fixture(t); const c = await f.connect(); c.send(f.message()); await c.read(); c.socket.destroy();
  await f.failed(); assert.deepEqual((await f.state()).servedCodes, []);
});
test('M21-R4: 配布完了済codeの重複claimは全体失敗', async t => {
  const f = await fixture(t); const c = await f.connect(); const m = f.message(); c.send(m); await c.read(); c.send({ ...m, op: 'ack-delivered' }); await c.read(); c.socket.end();
  const d = await f.connect(); d.send(f.message()); await f.failed();
});
test('M21-R4: 同一residentによる別codeのclaimも拒否', async t => {
  const f = await fixture(t); const c = await f.connect(); const m = f.message(); c.send(m); await c.read(); c.send({ ...m, op: 'ack-delivered' }); await c.read(); c.socket.end();
  const d = await f.connect(); d.send(f.message('member', { residentId: m.residentId })); await f.failed();
});
for (const bytes of [16384, 16385]) test(`M21-R5: UTF8 ${bytes}byte frame境界`, async t => {
  const f = await fixture(t); const c = await f.connect(); const m = JSON.stringify(f.message());
  const padding = ' '.repeat(bytes - Buffer.byteLength(m)); c.socket.write(m + padding + '\n');
  if (bytes === 16384) { assert.equal((await c.read()).op, 'actor'); c.socket.destroy(); await f.failed(); }
  else { await f.failed(); assert.deepEqual((await f.state()).servedCodes, []); }
});
test('M21-R5: trailing JSONと同chunk複数claimを拒否', async t => {
  const f = await fixture(t); const c = await f.connect(); c.socket.write(JSON.stringify(f.message()) + '{}\n'); await f.failed();
});
test('M21-R5: 改行後trailing garbageをackとして許可しない', async t => {
  const f = await fixture(t); const c = await f.connect(); c.send(f.message()); await c.read(); c.socket.write('garbage\n'); await f.failed();
});
test('M21-R5: 不完全UTF8/JSONの接続deadlineは固定10秒でfailed', { timeout: 45000 }, async t => {
  const f = await fixture(t); const c = await f.connect(); const start = Date.now(); c.socket.write(Buffer.from([0x7b, 0x22, 0xe3, 0x81]));
  await poll(async () => ({ state: await f.state(), exited: f.child.exitCode !== null || f.child.signalCode !== null }), s => s.state?.failed === true || s.exited, 20000);
  assert.ok(Date.now() - start >= 9000); assert.deepEqual((await f.state()).servedCodes, []);
  if (!(await f.state()).failed) { await deadline(f.ended); assert.notEqual(f.child.exitCode, 0); t.diagnostic('M21_TIMEOUT_NON_GREEN_EXIT'); }
});
test('M21-R4: ack直後のhalf-closeでも完全受信済み配布を保存する', async t => {
  const f = await fixture(t); const c = await f.connect(); const m = f.message(); c.send(m); await c.read();
  c.socket.end(JSON.stringify({ ...m, op: 'ack-delivered' }) + '\n');
  await deadline(once(c.socket, 'close')); await poll(f.state, s => s.servedCodes.includes('system'));
  assert.equal((await f.state()).failed, false);
  t.diagnostic(`M21_HALF_CLOSE_DELIVERED_OBSERVED_${c.received()}`);
});
test('M21-R5: 失敗snapshotが永続保存不能ならchild実exit1', async t => {
  const f = await fixture(t, { permanentStateFailure: true }); const c = await f.connect(); c.send(null);
  await deadline(f.ended); assert.equal(f.child.exitCode, 1); assert.equal((await f.state()).failed, false);
});
test('M21-R5: sticky failedは次connectionとcloseでも戻らない', async t => {
  const f = await fixture(t); const c = await f.connect(); c.send(null); await f.failed();
  const d = await f.connect(); d.send(f.message()); await deadline(once(d.socket, 'close'));
  assert.equal((await f.state()).failed, true); assert.deepEqual((await f.state()).servedCodes, []);
});
test('M21-R5: ack後の未改行garbageもsticky failed', async t => {
  const f = await fixture(t); const c = await f.connect(); const m = f.message(); c.send(m); await c.read();
  c.send({ ...m, op: 'ack-delivered' }); await c.read(); c.socket.write('x'); await f.failed();
});
test('M21-R5: 同chunk複数claimはackへ誤認しない', async t => {
  const f = await fixture(t); const c = await f.connect(); const m = JSON.stringify(f.message()); c.socket.write(m + '\n' + m + '\n'); await f.failed();
});
for (const ownerBytes of [16384, 16385]) test(`M21-R5: owner metadata ${ownerBytes}byte境界`, async t => {
  const f = await fixture(t, { ownerBytes });
  if (ownerBytes === 16384) assert.equal((await f.ready()).creation, f.owner.creation);
  else { await deadline(f.ended); assert.notEqual(f.child.exitCode, 0); }
});
for (const owner of [{ pid: 1 }, { runId: `m21-${randomUUID()}` }, { creation: '' }, { executable: '' }]) test('M21-R5: host owner不一致を成功へ偽造しない', async t => {
  const f = await fixture(t, { owner }); await deadline(f.ended); await assert.rejects(readFile(join(f.dir, 'exploration-ready.json')), { code: 'ENOENT' }); assert.notEqual(f.child.exitCode, 0);
});
for (const rawActors of ['null', '[]', JSON.stringify([...actors, actors[0]]), JSON.stringify(actors.map(a => ({ ...a, password: '' })))]) test('M21-R5: actor集合/必須値不正は起動失敗', async t => {
  const f = await fixture(t, { rawActors }); await deadline(f.ended); assert.notEqual(f.child.exitCode, 0); await assert.rejects(readFile(join(f.dir, 'exploration-ready.json')), { code: 'ENOENT' });
});
