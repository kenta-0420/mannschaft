// Windows Job Object の固定fixture probe。Chromiumは明示した固定fixtureだけ。
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const net = require('node:net');
// 所有者選択だけを末尾で受ける。既存fixture引数・期待値はそのまま検査する。
let OWNER = 'f6efb6d3-51e1-4408-a760-92f7d3642122';
if (process.argv.at(-2) === '--owner-id') {
  OWNER = process.argv.at(-1);
  assert.match(OWNER, /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/);
  process.argv.splice(-2);
}
const scenarios = ['normal', 'pre-ack', 'wrong-ack', 'assign-fail', 'host-fail', 'stdio-eof', 'cleanup-fail', 'save-fail', 'owner-mismatch', 'kill-close'];
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const LIMIT = Object.freeze({ total: 420000, reserve: 18000, channel: 2000, recoveryExit: 5000, recoveryChannel: 2000, poll: 25 });
const remaining = deadline => Math.max(0, Math.floor(deadline - performance.now()));
function requireWork(deadline) { assert.ok(remaining(deadline) > 0, 'work-budget-exhausted'); }
const REASONS = Object.freeze({ 'pre-ack': 'ack-timeout', 'wrong-ack': 'ack-mismatch', 'assign-fail': 'assign-fault',
  'host-fail': 'dummy-nonzero', 'stdio-eof': 'stdout-eof-timeout', 'cleanup-fail': 'dummy-nonzero',
  'save-fail': 'save-fault', 'owner-mismatch': 'hello-identity-mismatch' });
function phases(run) {
  const file = path.join(run, 'supervisor.out');
  assert.ok(fs.statSync(file).size <= 4096);
  const lines = fs.readFileSync(file, 'ascii').split(/\r?\n/).filter(Boolean);
  assert.ok(lines.length <= 12);
  return lines.map(line => {
    const match = /^M21_PHASE\|([a-z-]+)\|pid=([0-9]+)\|creation=([0-9]+)\|utcTicks=([0-9]+)\|elapsedMs=([0-9]+)$/.exec(line);
    assert.ok(match, '固定ASCII phase形式');
    return { phase: match[1], pid: Number(match[2]), creation: match[3], utcTicks: match[4], elapsedMs: Number(match[5]) };
  });
}
function atomic(file, value) {
  fs.writeFileSync(`${file}.next`, JSON.stringify(value), { flag: 'wx' });
  fs.renameSync(`${file}.next`, file);
}
function read(file) {
  const stat = fs.lstatSync(file);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size <= 16384);
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
}
async function until(predicate, ms, deadline) {
  const end = Math.min(performance.now() + ms, deadline);
  while (performance.now() < end) {
    if (predicate()) return;
    await wait(Math.min(LIMIT.poll, remaining(end)));
  }
  throw new Error('固定probe期限超過');
}
function observeChannel(name, source, target) {
  const state = { name, file: target.path, eof: false, readClosed: false, readError: null, written: false, writeClosed: false, writeError: null };
  source.on('end', () => { state.eof = true; });
  source.on('close', () => { state.readClosed = true; });
  source.on('error', error => { state.readError = error.message; });
  target.on('finish', () => { state.written = true; });
  target.on('close', () => { state.writeClosed = true; });
  target.on('error', error => { state.writeError = error.message; });
  source.pipe(target);
  return { source, target, state };
}
async function collectChannel(channel, totalDeadline) {
  const failures = [];
  const state = channel.state;
  try {
    await until(() => state.eof || state.readError !== null, LIMIT.channel, totalDeadline);
    if (state.readError !== null) throw new Error(state.readError);
  } catch (error) { failures.push({ stage: state.name + '-eof', reason: error.message }); }
  // EOF失敗でも、もう一方のstreamとファイル書込完了は独立に待つ。
  try {
    await until(() => (state.written && state.writeClosed) || state.writeError !== null, LIMIT.channel, totalDeadline);
    if (state.writeError !== null) throw new Error(state.writeError);
  } catch (error) { failures.push({ stage: state.name + '-write', reason: error.message }); }
  return failures;
}
async function recoverChannel(channel, totalDeadline) {
  const state = channel.state;
  channel.source.destroy();
  if (!channel.target.writableEnded) channel.target.end();
  let failure = null;
  try {
    await until(() => state.readClosed && state.writeClosed, LIMIT.recoveryChannel, totalDeadline);
  } catch (error) { failure = error.message; }
  if (!state.writeClosed) channel.target.destroy();
  return { ...state, recoveryWaitFailure: failure };
}
async function dummy(role, run, nonce, scenario, workMs, totalMs) {
  workMs = Number(workMs); totalMs = Number(totalMs);
  assert.ok(Number.isInteger(workMs) && Number.isInteger(totalMs) && workMs >= 0 && workMs <= totalMs && totalMs <= LIMIT.total);
  const origin = performance.now();
  const workDeadline = origin + workMs;
  const totalDeadline = origin + totalMs;
  requireWork(workDeadline);
  assert.ok(['child', 'grandchild', 'hold-stream'].includes(role));
  assert.ok(scenarios.includes(scenario) && /^[a-f0-9]{32}$/.test(nonce));
  const owner = read(path.join(run, 'owner.json'));
  assert.equal(owner.owner, OWNER);
  assert.equal(owner.nonce, nonce);
  process.stdout.write(`${role}:out\n`);
  process.stderr.write(`${role}:err\n`);
  const afterGrandchildExit = scenario === 'host-fail' || scenario === 'cleanup-fail';
  // この2caseの孫metadataは、直接creatorである子が実exit0を観測した後だけ公開する。
  if (role !== 'grandchild' || !afterGrandchildExit) atomic(path.join(run, `${role}.json`), { pid: process.pid, nonce });
  if (role === 'child') {
    requireWork(workDeadline);
    const grandchild = spawn(process.execPath, [__filename, '--dummy', 'grandchild', run, nonce, scenario, String(remaining(workDeadline)), String(remaining(totalDeadline)), '--owner-id', OWNER], { windowsHide: true, stdio: 'inherit', detached: scenario === 'stdio-eof' });
    let grandchildObserved = null;
    const grandchildExit = new Promise(resolve => {
      grandchild.once('exit', (code, signal) => { grandchildObserved = { code, signal }; resolve(grandchildObserved); });
      grandchild.once('error', () => { grandchildObserved = { code: null, signal: 'spawn-error' }; resolve(grandchildObserved); });
    });
    grandchild.on('error', () => { process.exitCode = 2; });
    if (afterGrandchildExit) {
      await until(() => grandchildObserved !== null, Infinity, workDeadline);
      const observed = await grandchildExit;
      assert.equal(observed.code, 0);
      assert.equal(observed.signal, null);
      requireWork(workDeadline);
      atomic(path.join(run, 'grandchild.json'), { pid: grandchild.pid, nonce, creatorPid: process.pid, exited: true, exitCode: observed.code });
      process.exit(17);
    }
    await until(() => fs.existsSync(path.join(run, 'grandchild.json')), Infinity, workDeadline);
    if (scenario === 'stdio-eof') {
      await until(() => fs.existsSync(path.join(run, 'p6-release.json')), Infinity, workDeadline);
      const ack = read(path.join(run, 'p6-release.json'));
      assert.deepEqual(Object.keys(ack).sort(), ['child', 'grandchild', 'nonce', 'ownedJobVerified', 'owner'].sort());
      assert.equal(ack.owner, OWNER); assert.equal(ack.nonce, nonce); assert.equal(ack.ownedJobVerified, true);
      for (const [name, pid] of [['child', process.pid], ['grandchild', grandchild.pid]]) {
        const identity = ack[name];
        assert.deepEqual(Object.keys(identity).sort(), ['owner', 'nonce', 'pid', 'creation'].sort());
        assert.equal(identity.owner, OWNER); assert.equal(identity.nonce, nonce);
        assert.ok(Number.isInteger(identity.pid) && identity.pid > 0); assert.equal(identity.pid, pid);
        assert.equal(typeof identity.creation, 'string'); assert.match(identity.creation, /^[1-9][0-9]*$/);
        assert.deepEqual(identity, read(path.join(run, `${name}-candidate.json`)));
      }
      requireWork(workDeadline);
      process.exit(0); // exact outer Job認定後、孫が継承pipeを開いたままにする。
    }
    if (scenario === 'kill-close') {
      await wait(Math.min(15000, remaining(workDeadline))); // 固定dummy holdを総work残予算でclampする。
      process.exit(19);
    }
    await until(() => grandchildObserved !== null, Infinity, workDeadline);
    const observed = await grandchildExit;
    assert.equal(observed.code, 0);
    assert.equal(observed.signal, null);
    return;
  }
  await wait(Math.min(scenario === 'stdio-eof' || scenario === 'kill-close' ? 15000 : 800, remaining(workDeadline)));
  requireWork(workDeadline);
}
async function readinessFixtureSuite(kind = 'readiness') {
  const formal = kind === 'formal-contract';
  const relay = kind === 'wsl-relay-loss';
  assert.equal(process.platform, 'win32');
  assert.equal(process.argv.length, 3, '固定fixture選択以外の入力は拒否');
  const origin = performance.now();
  const totalMs = relay ? 60000 : LIMIT.total;
  const reserveMs = relay ? 20000 : LIMIT.reserve;
  const totalDeadline = origin + totalMs;
  const workDeadline = totalDeadline - reserveMs;
  const inputCommit = process.env.M21_INPUT_COMMIT ?? null;
  if (inputCommit !== null) assert.match(inputCommit, /^[0-9a-f]{40}$/);
  if (relay) {
    assert.match(inputCommit ?? '', /^[0-9a-f]{40}$/);
    assert.equal(OWNER, 'f6efb6d3-51e1-4408-a760-92f7d3642122');
    assert.equal(crypto.createHash('sha256').update(fs.readFileSync(process.execPath)).digest('hex'), '0d0f5e39f9f3d9587bc19f73eab3c2c9c4903fd02d6dbf9c853dd81b3d95fad4');
  }
  const script = path.join(__dirname, 'm21-windows-supervisor.ps1');
  const inputFiles = [
    ['supervisor', script], ['probe', __filename], ['fixture', path.join(__dirname, 'm21-windows-supervisor.readiness-fixture.ps1')],
    ['operations', path.join(__dirname, '..', 'docs', 'development', 'm21-windows-supervisor.md')],
  ];
  if (relay) inputFiles.push(['adapter', path.join(__dirname, 'm21-windows-formal-adapter.ps1')], ['bridge', path.join(__dirname, 'm21-wsl-docker-bridge.py')], ['bridgeFixture', path.join(__dirname, 'test_m21_wsl_docker_bridge.py')]);
  const inputSHA256 = Object.fromEntries(inputFiles.map(([name, file]) => [name, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase()]));
  const base = fs.mkdtempSync(path.join(os.tmpdir(), relay ? 'm21-windows-relay-loss-' : formal ? 'm21-formal-contract-fixture-' : 'm21-readiness-fixture-'));
  const baseIdentity = fs.lstatSync(base);
  const baseCanonical = fs.realpathSync(base);
  if (!relay) atomic(path.join(base, 'owner.json'), { owner: OWNER, purpose: 'M21固定readiness試練' });
  const results = [];
  const fixtures = relay ? ['wsl-relay-loss'] : formal ? ['formal-ms', 'formal-fe', 'formal-admission'] : ['parent-before-certificate', 'lock-release', 'lock-held', 'invalid-json'];
  for (const fixture of fixtures) {
    if (remaining(workDeadline) === 0) break;
    const run = relay ? base : path.join(base, fixture); if (!relay) fs.mkdirSync(run);
    const nonce = crypto.randomBytes(16).toString('hex'); atomic(path.join(run, 'owner.json'), { owner: OWNER, nonce });
    if (formal && fixture !== 'formal-ms') {
      const cli = path.join(run, 'frontend', 'node_modules', 'nuxt', 'bin', 'nuxt.mjs');
      fs.mkdirSync(path.dirname(cli), { recursive: true });
      // 実Nuxtではなく、固定argv/CWD/role環境と実到達だけを返すowned dummy。
      fs.writeFileSync(cli, `import fs from 'node:fs';import path from 'node:path';import {fileURLToPath} from 'node:url';
const cli=fileURLToPath(import.meta.url),run=path.resolve(path.dirname(cli),'../../../..');
const owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json'),'utf8'));
if(owner.owner!==${JSON.stringify(OWNER)}||JSON.stringify(process.argv.slice(2))!==JSON.stringify(['dev','--host','127.0.0.1','--port','3003']))process.exit(19);
fs.writeFileSync(path.join(run,'formal-fe-entered.json'),JSON.stringify({owner:owner.owner,nonce:owner.nonce,pid:process.pid}),{flag:'wx'});
console.log(JSON.stringify({owner:owner.owner,nonce:owner.nonce,pid:process.pid,cwd:process.cwd(),cli,forbiddenPresent:['M21_HOST_ONLY_SENTINEL','SPRING_DATASOURCE_PASSWORD','MANNSCHAFT_JWT_SECRET'].some(k=>Object.hasOwn(process.env,k)),nodeOptions:process.env.NODE_OPTIONS??null}));`, { flag: 'wx' });
    }
    const parentCase = fixture === 'parent-before-certificate';
    requireWork(workDeadline);
    const p = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', script,
      '-Mode', formal || relay ? 'Parent' : parentCase ? 'Observe' : 'ReadinessFixture', '-Scenario', parentCase ? 'kill-close' : 'normal',
      '-TestFixture', fixture, '-RunDirectory', run, '-Nonce', nonce, '-OwnerId', OWNER, '-NodePath', process.execPath,
      '-WorkRemainingMs', String(remaining(workDeadline)), '-TotalRemainingMs', String(remaining(totalDeadline))],
    { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let exited = false, closed = false, code = null, signal = null, launchError = null;
    p.once('exit', (value, kind) => { exited = true; code = value; signal = kind; });
    p.once('close', () => { closed = true; });
    p.once('error', error => { launchError = error; });
    const channels = [observeChannel('stdout', p.stdout, fs.createWriteStream(path.join(run, 'launcher.out'), { flags: 'wx' })),
      observeChannel('stderr', p.stderr, fs.createWriteStream(path.join(run, 'launcher.err'), { flags: 'wx' }))];
    let parentSafe = false, parentActual = null, parentReceipt = null, receipt = null, stopped = false;
    let parentExitObservedAt = null, stopAt = null, failure = null, evidence = null;
    const recoveryFailures = [];
    const stopOwnLauncher = () => {
      if (!stopped && !exited && (!parentCase || parentSafe)) { stopped = true; stopAt = Math.floor(performance.now() - origin); p.kill(); }
    };
    try {
      if (parentCase) {
        await until(() => fs.existsSync(path.join(run, 'fixture-parent-exit.json')) || exited || launchError !== null, Infinity, workDeadline);
        assert.ok(fs.existsSync(path.join(run, 'fixture-parent-exit.json')), 'fixture Parentの実exit未到達');
        parentActual = read(path.join(run, 'fixture-parent-exit.json'));
        assert.deepEqual(Object.keys(parentActual).sort(), ['owner', 'nonce', 'pid', 'creation', 'exited', 'exitCode'].sort());
        assert.equal(parentActual.owner, OWNER); assert.equal(parentActual.nonce, nonce);
        assert.ok(Number.isInteger(parentActual.pid) && parentActual.pid > 0); assert.match(parentActual.creation, /^[1-9][0-9]*$/);
        assert.equal(parentActual.exited, true); assert.equal(parentActual.exitCode, 1);
        parentReceipt = read(path.join(run, 'result.json'));
        assert.equal(parentReceipt.mode, 'Parent'); assert.equal(parentReceipt.owner, OWNER); assert.equal(parentReceipt.nonce, nonce);
        assert.equal(parentReceipt.process.pid, parentActual.pid); assert.equal(parentReceipt.process.creation, parentActual.creation);
        assert.equal(parentReceipt.primary.reason, 'fixture-parent-exit');
        assert.equal(parentReceipt.resourceRecovery.confirmed, true); assert.equal(parentReceipt.streams.length, 0);
        for (const name of ['certified-tree.json', 'child-candidate.json', 'child.json', 'supervisor-owned.json']) assert.equal(fs.existsSync(path.join(run, name)), false);
        parentSafe = true;
        parentExitObservedAt = Math.floor(performance.now() - origin);
        // 任意の性能条件を増やさず、既存Rと残G/Wの小さい方までだけ旧Observeを観測する。
        const earlyDeadline = Math.min(workDeadline, totalDeadline, performance.now() + LIMIT.reserve);
        try { await until(() => exited || launchError !== null, Infinity, earlyDeadline); }
        catch { stopOwnLauncher(); throw new Error('fixture-parent-exit-observe-still-waiting'); }
      } else {
        try { await until(() => exited || launchError !== null, Infinity, workDeadline); }
        catch { stopOwnLauncher(); throw new Error('fixture-work-budget-exhausted'); }
      }
      if (launchError) throw launchError;
      const channelFailures = [];
      for (const channel of channels) channelFailures.push(...await collectChannel(channel, totalDeadline));
      assert.deepEqual(channelFailures, []);
      await until(() => closed, LIMIT.channel, totalDeadline);
      receipt = read(path.join(run, parentCase ? 'observe-result.json' : 'result.json'));
      assert.equal(receipt.owner, OWNER); assert.equal(receipt.nonce, nonce); assert.equal(receipt.process.pid, p.pid);
      assert.equal(receipt.resourceRecovery.confirmed, true);
      if (formal) {
        assert.equal(code, 0); assert.equal(receipt.primary, null);
        assert.equal(receipt.resourceRecovery.jobEmpty, true);
        evidence = receipt.formalContractEvidence;
        assert.equal(evidence.fixture, fixture); assert.equal(evidence.passed, true);
        assert.match(evidence.identity.creation, /^[1-9][0-9]*$/);
        if (fixture === 'formal-ms') {
          const actual = evidence.actualSubMs;
          assert.match(actual.creationTicks, /^[1-9][0-9]*$/); assert.match(actual.epochMs, /^[0-9]+$/);
          assert.equal(actual.actualHandleHeld, true);
          assert.equal(BigInt(actual.creationTicks) - 621355968000000000n, BigInt(actual.epochMs) * 10000n + BigInt(actual.subMs));
          assert.equal(actual.hasSubMs, actual.subMs !== '0'); // 実OS subms=0も有効。
          assert.deepEqual(evidence.vectors.map(v => [v.ticks, v.epochMs, v.subMs]), [['638000000000009999','1664403200000','9999'],['638000000000010000','1664403200001','0']]);
        } else {
          assert.ok(evidence.child.pid > 0); assert.match(evidence.child.creation, /^[1-9][0-9]*$/);
          assert.equal(evidence.child.exited, true); assert.equal(evidence.child.exitCode, 0);
          assert.equal(evidence.child.stdoutEof, true); assert.equal(evidence.child.stderrEof, true);
          if (fixture === 'formal-admission') {
            assert.equal(evidence.observations.workMarkerAbsent, true); assert.equal(evidence.observations.cleanupMarkerPresent, true);
            assert.equal(evidence.observations.afterGMarkerAbsent, true);
            assert.equal(evidence.observations.workRemainingMs, 0); assert.equal(evidence.observations.totalRemainingMs, 0);
            assert.equal(fs.existsSync(path.join(run, 'formal-fe-entered.json')), false);
            assert.equal(read(path.join(run, 'hold-stream.json')).pid, evidence.child.pid);
            assert.equal(fs.existsSync(path.join(run, 'grandchild.json')), false);
          }
        }
      } else if (relay) {
        assert.equal(code, 0); assert.equal(receipt.primary, null);
        evidence = receipt.fixtureEvidence;
        assert.equal(evidence.fixture, 'wsl-relay-loss'); assert.equal(evidence.passed, true);
        assert.equal(evidence.primaryRelayQualified, false); assert.equal(evidence.formalRunQualified, false);
        assert.equal(evidence.realDockerInvoked, false); assert.equal(evidence.linuxBridgeActualExitObserved, false);
        assert.equal(evidence.windows.actualExited, true); assert.equal(evidence.windows.stdoutEof, true); assert.equal(evidence.windows.stderrEof, true);
        assert.equal(evidence.linux.primaryGreen, false); assert.equal(evidence.linux.childrenReaped, true);
        assert.equal(evidence.linux.stdoutEof, true); assert.equal(evidence.linux.stderrEof, true); assert.equal(evidence.linux.writeClosed, true);
      } else if (parentCase) {
        assert.equal(code, 1); assert.equal(receipt.mode, 'Observe');
        assert.equal(receipt.primary.reason, 'parent-exited-before-certificate');
        assert.equal(receipt.parentKilled, false); assert.equal(receipt.knownExited, 0);
        assert.equal(fs.existsSync(path.join(run, 'certified-tree.json')), false);
        assert.equal(receipt.streams.length, 1);
        const captured = receipt.streams[0];
        assert.equal(captured.role, 'direct'); assert.equal(captured.pid, parentActual.pid); assert.equal(captured.creation, parentActual.creation);
        assert.equal(captured.exited, true); assert.equal(captured.exitCode, 1);
        assert.equal(captured.stdoutEof, true); assert.equal(captured.stderrEof, true);
        const parentExit = receipt.parentExitEvidence;
        assert.equal(parentExit.exited, true); assert.equal(parentExit.exitCode, 1); assert.equal(parentExit.certificateAdopted, false);
        assert.equal(parentExit.identity.pid, parentActual.pid); assert.equal(parentExit.identity.creation, parentActual.creation);
        assert.equal(parentExit.receiptPresent, true); assert.equal(parentExit.receipt.mode, 'Parent');
      } else {
        evidence = receipt.fixtureEvidence;
        assert.equal(evidence.fixture, fixture);
        const stats = receipt.metadataReadiness.filter(s => s.file === 'child-candidate.json');
        assert.equal(stats.length, 1);
        if (fixture === 'invalid-json') {
          assert.equal(code, 1); assert.equal(receipt.primary.ioOperation, 'decode');
          assert.equal(evidence.realRead32, 0); assert.equal(evidence.accepted, 0); assert.equal(evidence.budgetExhausted, false);
          assert.equal(stats[0].count32, 0); assert.equal(stats[0].count33, 0); assert.equal(stats[0].exhausted, false);
        } else {
          assert.ok(evidence.realRead32 > 0); assert.equal(evidence.lastHResult, -2147024864); assert.equal(evidence.lockAcquired, true);
          assert.equal(stats[0].count32, evidence.realRead32); assert.equal(stats[0].count33, 0);
          assert.equal(stats[0].lastHResult, -2147024864); assert.ok(stats[0].cumulativeWaitMs > 0);
          if (fixture === 'lock-release') {
            assert.equal(evidence.lockReleased, true); assert.equal(code, 0); assert.equal(receipt.primary, null);
            assert.equal(evidence.accepted, 1); assert.deepEqual(evidence.acceptedIdentity, evidence.expectedIdentity);
            assert.deepEqual(evidence.events.map(e => e.event), ['lock-acquired', 'actual-read32', 'lock-released', 'accepted']);
            assert.equal(stats[0].recovered, true); assert.equal(stats[0].exhausted, false);
          } else {
            assert.equal(code, 1); assert.equal(evidence.accepted, 0); assert.equal(evidence.budgetExhausted, true);
            assert.equal(receipt.primary.reason, 'metadata-readiness-exhausted');
            assert.equal(stats[0].recovered, false); assert.equal(stats[0].exhausted, true);
            assert.ok(receipt.primary.exceptions.some(e => e.type === 'System.IO.IOException' && e.hResult === -2147024864));
          }
        }
      }
    } catch (error) {
      failure = error.message;
      try { stopOwnLauncher(); } catch (stopError) { failure += '; own-stop:' + stopError.name; }
      try { await until(() => exited || launchError !== null, LIMIT.recoveryExit, totalDeadline); } catch { /* exact状態を結果へ残す */ }
      for (const channel of channels) {
        const failures = await collectChannel(channel, totalDeadline);
        recoveryFailures.push(...failures);
        if (failures.length) await recoverChannel(channel, totalDeadline);
      }
      if (!exited) p.unref();
    }
    // 故意のREDでも次の独立対照へ進む。通常10case suiteは呼ばない。
    results.push({ fixture, passed: failure === null, failure, parentSafe, parentActual, parentReceipt,
      parentExitObservedAt, stopAt, stopped, launcherPid: p.pid, launcherExited: exited, launcherExitCode: code,
      launcherSignal: signal, launcherClosed: closed, channels: channels.map(c => c.state), recoveryFailures, receipt,
      unverifiedOwnedTree: !exited || (parentCase && !parentSafe), elapsedMs: Math.floor(performance.now() - origin) });
    if (!exited || (parentCase && !parentSafe) || (formal && failure !== null)) break;
  }
  let passed = results.length === fixtures.length && results.every(r => r.passed);
  let cleanupFailure = null;
  const sourceSHA256After = relay ? Object.fromEntries(inputFiles.map(([name, file]) => [name, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase()])) : null;
  if (relay && JSON.stringify(sourceSHA256After) !== JSON.stringify(inputSHA256)) passed = false;
  if (passed && !relay) {
    try {
      const current = fs.lstatSync(base);
      assert.ok(current.isDirectory() && !current.isSymbolicLink()); assert.equal(current.dev, baseIdentity.dev); assert.equal(current.ino, baseIdentity.ino);
      assert.equal(fs.realpathSync(base), baseCanonical); assert.equal(read(path.join(base, 'owner.json')).owner, OWNER);
      fs.rmSync(base, { recursive: true });
    } catch (error) { passed = false; cleanupFailure = error.message; }
  }
  console.log(JSON.stringify({ kind: relay ? 'fixed-wsl-relay-loss-fixture' : formal ? 'fixed-formal-contract-fixture' : 'fixed-readiness-fixture', passed, inputCommit, inputSHA256, sourceSHA256After, totalMs, reserveMs,
    results, notRun: fixtures.filter(f => !results.some(r => r.fixture === f)), cleanupFailure, retained: relay || !passed ? base : null }));
  if (!passed) process.exitCode = 1;
}
async function chromiumWitnessContract() {
  // 純粋schema/ASTと既存native literalのコンパイルだけ。Job/Chrome/実Appは起動しない。
  assert.equal(process.argv.length, 3);
  const nonce = crypto.randomBytes(16).toString('hex');
  const directory = path.join(__dirname, '..', '.claude', 'campaigns', `chromium-witness-contract-${nonce}`);
  fs.mkdirSync(directory, { recursive: true });
  atomic(path.join(directory, 'owner.json'), { owner: OWNER, nonce, purpose: 'Chromium witness純粋試練' });
  const script = path.join(__dirname, 'm21-windows-supervisor.ps1');
  const sourceFiles = [script, __filename, path.join(__dirname, 'm21-windows-formal-adapter.ps1'), path.join(__dirname, 'm21-windows-supervisor.readiness-fixture.ps1')];
  const snapshot = () => sourceFiles.map(file => ({ file: path.basename(file), sha256: crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'), mtimeMs: fs.statSync(file).mtimeMs }));
  const before = snapshot();
  const fixture = path.join(directory, 'pure.ps1');
  fs.writeFileSync(fixture, '\uFEFF' + String.raw`param([string]$Source,[string]$Adapter,[string]$Probe)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'parser'}
foreach($name in @('Assert-ChromiumWitnessAck','Test-ChromiumWitnessQualification')){
 $functions=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq $name},$true))
 if($functions.Count -ne 1){throw ('missing-pure-'+$name)}
 . ([ScriptBlock]::Create($functions[0].Extent.Text))
}
$owner='f6efb6d3-51e1-4408-a760-92f7d3642122';$Nonce='0123456789abcdef0123456789abcdef'
$identity=@{owner=$owner;nonce=$Nonce;pid=71;creation='123456789'}
$ack=@{owner=$owner;nonce=$Nonce;pid=71;creation='123456789';ownedJobVerified=$true}
Assert-ChromiumWitnessAck ([pscustomobject]$ack) ([pscustomobject]$identity)
foreach($case in @(@{key='owner';value='foreign'},@{key='nonce';value=('f'*32)},@{key='pid';value=72},@{key='creation';value='123456788'},@{key='ownedJobVerified';value=$false},@{key='ownedJobVerified';value='true'},@{key='extra';value=1})){
 $bad=$ack.Clone();$bad[$case.key]=$case.value;$rejected=$false
 try{Assert-ChromiumWitnessAck ([pscustomobject]$bad) ([pscustomobject]$identity)}catch{$rejected=$true}
 if(!$rejected){throw ('ack-admitted-'+$case.key)}
}
$proof=@{innerFlags=8192;parentOutsideInner=$true;rootsInsideInner=$true;observerOutsideWitness=$true;parentInsideWitness=$true;rootsInsideWitness=$true;activeBefore=5;parentExited=$true;rootsExited=$true;parentStdoutEof=$true;parentStderrEof=$true;activeAfter=0;remainingRecoveryMs=1;outerStopUsed=$false;outerClosedBeforeProof=$false;priorFailure=$false;innerActiveZeroObserved=$false;browserEofObserved=$false;fullRendererActualObserved=$false}
if(!(Test-ChromiumWitnessQualification $proof)){throw 'complete-rejected'}
foreach($case in @(@{key='innerFlags';value=0},@{key='innerFlags';value=10240},@{key='parentOutsideInner';value=$false},@{key='rootsInsideInner';value=$false},@{key='observerOutsideWitness';value=$false},@{key='parentInsideWitness';value=$false},@{key='rootsInsideWitness';value=$false},@{key='activeBefore';value=0},@{key='parentExited';value=$false},@{key='rootsExited';value=$false},@{key='parentStdoutEof';value=$false},@{key='parentStderrEof';value=$null},@{key='activeAfter';value=1},@{key='outerStopUsed';value=$true},@{key='outerClosedBeforeProof';value=$true},@{key='priorFailure';value=$true},@{key='innerActiveZeroObserved';value=$true},@{key='browserEofObserved';value=$true},@{key='fullRendererActualObserved';value=$true})){
 $bad=$proof.Clone();$bad[$case.key]=$case.value;if(Test-ChromiumWitnessQualification $bad){throw ('proof-admitted-'+$case.key)}
}
foreach($remaining in @(0,-1,$null,'1')){
 $bad=$proof.Clone();$bad.remainingRecoveryMs=$remaining
 if(Test-ChromiumWitnessQualification $bad){throw 'proof-admitted-expired-or-unknown-recovery-budget'}
}
$bad=$proof.Clone();$bad.Remove('remainingRecoveryMs')
if(Test-ChromiumWitnessQualification $bad){throw 'proof-admitted-missing-recovery-budget'}
# 既存純粋関数と唯一の14case markerだけを抽出。adapter全体を実行しない。
$adapterAst=[Management.Automation.Language.Parser]::ParseFile($Adapter,[ref]$null,[ref]$null)
$pendingFunction=@($adapterAst.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Test-M21PendingExitRecovery'},$true))
if($pendingFunction.Count -ne 1){throw 'existing-pending-function-count'}
. ([ScriptBlock]::Create($pendingFunction[0].Extent.Text))
$SupervisorPath=$Source
$probeText=[IO.File]::ReadAllText($Probe)
$markers=[regex]::Matches($probeText,'(?s)# M21_PENDING_EXIT_PURE_BEGIN\r?\n(.*?)# M21_PENDING_EXIT_PURE_END')
if($markers.Count -ne 1){throw 'existing-pure-marker-count'}
. ([ScriptBlock]::Create($markers[0].Groups[1].Value))
$native=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.StringConstantExpressionAst] -and $n.Value.StartsWith('using System;') -and $n.Value.Contains('public sealed class M21Child')},$true))
if($native.Count -ne 1){throw 'native-literal'}
Add-Type -TypeDefinition $native[0].Value
if($null -eq [M21Job].GetMethod('LimitFlags')){throw 'native-flags-query-missing'}
$parentGate=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'Wait-ChromiumWitnessAck'},$true))
if($parentGate.Count -ne 1 -or $parentGate[0].Extent.Text -match 'M21Job|M21Child|Process\]::Start|Add-Type|New-Supervisor'){throw 'pre-ack-spawn-native'}
$calls=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -ceq 'Wait-ChromiumWitnessAck'},$true))
$compile=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.Extent.Text -ceq 'Add-Type -TypeDefinition $nativeCode'},$true))
$innerCreate=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -ceq '$job' -and $n.Right.Extent.Text -ceq '[M21Job]::Create()'},$true))
$innerChild=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.Extent.Text -ceq "New-Supervisor 'Supervisor' 'supervisor'"},$true))
if($calls.Count -ne 1 -or $compile.Count -ne 1 -or $innerCreate.Count -ne 1 -or $innerChild.Count -ne 1){throw 'parent-gate-target-count'}
foreach($stage in @($compile[0],$innerCreate[0],$innerChild[0])){if($calls[0].Extent.StartOffset -ge $stage.Extent.StartOffset){throw 'parent-gate-order'}}
[Console]::Out.WriteLine('M21_CHROMIUM_WITNESS_PURE_OK')
`);
  const totalDeadline = performance.now() + LIMIT.total, workDeadline = totalDeadline - LIMIT.reserve;
  const p = spawn('C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe', ['-NoProfile', '-NonInteractive', '-File', fixture, script, sourceFiles[2], __filename], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let exited = false, code = null, launchError = null;
  p.on('exit', value => { exited = true; code = value; }); p.on('error', error => { launchError = error.name; });
  const channels = [observeChannel('stdout', p.stdout, fs.createWriteStream(path.join(directory, 'stdout.txt'), { flags: 'wx' })), observeChannel('stderr', p.stderr, fs.createWriteStream(path.join(directory, 'stderr.txt'), { flags: 'wx' }))];
  let failure = null;
  try {
    await until(() => exited || launchError, Infinity, workDeadline);
    assert.equal(launchError, null);
    for (const channel of channels) assert.deepEqual(await collectChannel(channel, totalDeadline), []);
    assert.equal(code, 0);
    assert.ok(fs.readFileSync(path.join(directory, 'stdout.txt'), 'utf8').includes('M21_CHROMIUM_WITNESS_PURE_OK'));
    assert.deepEqual(snapshot(), before);
  } catch (error) {
    failure = error.message;
    if (!exited) p.kill();
    try { await until(() => exited || launchError, LIMIT.recoveryExit, totalDeadline); } catch { /* exact未確認を保存 */ }
    for (const channel of channels) await recoverChannel(channel, totalDeadline);
    if (!exited) p.unref();
  }
  const packet = { owner: OWNER, nonce, scope: 'chromium-witness-pure-contract', passed: failure === null, failure, before, after: snapshot(), launcherPid: p.pid, launcherExited: exited, launcherExitCode: code, channels: channels.map(c => c.state), browserStarts: 0, nativeJobCreates: 0, runtimeQualified: false, retained: directory };
  atomic(path.join(directory, 'packet.json'), packet); console.log(JSON.stringify(packet));
  if (failure !== null) process.exitCode = 1;
}
async function chromiumFixtureSuite() {
  assert.equal(process.platform, 'win32');
  assert.equal(process.argv.length, 3);
  assert.equal(OWNER, 'f6efb6d3-51e1-4408-a760-92f7d3642122');
  const inputCommit = process.env.M21_INPUT_COMMIT;
  assert.match(inputCommit ?? '', /^[a-f0-9]{40}$/);
  const totalDeadline = performance.now() + LIMIT.total;
  const workDeadline = totalDeadline - LIMIT.reserve;
  const script = path.join(__dirname, 'm21-windows-supervisor.ps1');
  const sourceFiles = [script, __filename, path.join(__dirname, 'm21-windows-formal-adapter.ps1'), path.join(__dirname, 'm21-windows-supervisor.readiness-fixture.ps1')];
  const sourceSHA256 = () => Object.fromEntries(sourceFiles.map(file => [path.basename(file), crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
  const inputSHA256 = sourceSHA256();
  const chrome = 'C:/Users/kenta/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe';
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(chrome)).digest('hex'), '290fa7018fda22c52ada5eddb0113baf3ebc41fd0fde6085eddb19793606c635');
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(process.execPath)).digest('hex'), '0d0f5e39f9f3d9587bc19f73eab3c2c9c4903fd02d6dbf9c853dd81b3d95fad4');
  // 固定2caseだけ。自己runはrootの証拠確定・所有確認まで保持する。
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'm21-chromium-fixture-'));
  atomic(path.join(base, 'owner.json'), { owner: OWNER });
  const results = [];
  for (const fixture of ['chromium-normal', 'chromium-parent-close']) {
    requireWork(workDeadline);
    assert.deepEqual(sourceSHA256(), inputSHA256, 'source変更中の試験禁止');
    const run = path.join(base, fixture); fs.mkdirSync(run);
    const nonce = crypto.randomBytes(16).toString('hex');
    atomic(path.join(run, 'owner.json'), { owner: OWNER, nonce });
    const abnormal = fixture === 'chromium-parent-close';
    const p = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', script, '-Mode', abnormal ? 'Observe' : 'Parent', '-TestFixture', fixture,
      '-Scenario', abnormal ? 'kill-close' : 'normal', '-RunDirectory', run, '-Nonce', nonce, '-OwnerId', OWNER,
      '-NodePath', process.execPath, '-WorkRemainingMs', String(remaining(workDeadline)), '-TotalRemainingMs', String(remaining(totalDeadline))], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let exited = false, code = null, launchError = null;
    p.on('exit', value => { exited = true; code = value; }); p.on('error', error => { launchError = error.name; });
    const channels = [observeChannel('stdout', p.stdout, fs.createWriteStream(path.join(run, 'launcher.out'), { flags: 'wx' })), observeChannel('stderr', p.stderr, fs.createWriteStream(path.join(run, 'launcher.err'), { flags: 'wx' }))];
    let failure = null, receipt = null, ready = null;
    try {
      await until(() => exited || launchError || fs.existsSync(path.join(run, 'chromium-ready.json')), Infinity, workDeadline);
      assert.equal(exited, false); assert.equal(launchError, null);
      ready = read(path.join(run, 'chromium-ready.json'));
      assert.equal(ready.owner, OWNER); assert.equal(ready.nonce, nonce); assert.equal(ready.fixture, fixture);
      assert.deepEqual(ready.members.map(m => m.role), ['supervisor', 'system', 'admin-de', 'member']);
      assert.ok(ready.members.every(m => m.ownedJobVerified === true));
      console.log(JSON.stringify({ kind: 'chromium-sky-ready', fixture, run, readyFile: path.join(run, 'chromium-ready.json'), observedFile: abnormal ? path.join(run, 'chromium-observed.json') : null, owner: OWNER, nonce, inputCommit, inputSHA256, remainingWorkMs: remaining(workDeadline) }));
      await until(() => exited || launchError, Infinity, workDeadline);
      assert.equal(launchError, null);
      for (const channel of channels) assert.deepEqual(await collectChannel(channel, totalDeadline), []);
      receipt = read(path.join(run, abnormal ? 'observe-result.json' : 'result.json'));
      assert.equal(code, 0); assert.equal(receipt.passed, true); assert.equal(receipt.recovered, true);
      assert.ok(receipt.streams.every(s => s.exited && s.stdoutEof && s.stderrEof));
      if (abnormal) {
        assert.equal(receipt.parentKilled, true); assert.equal(receipt.knownExited, 4);
        assert.equal(receipt.knownExitEvidence.length, 4); assert.ok(receipt.knownExitEvidence.every(e => e.exited));
        assert.equal(receipt.resourceRecovery.jobEmpty, null, 'inner Job残数は未観測');
        const witness = receipt.chromiumWitness;
        assert.equal(witness.qualified, true); assert.equal(witness.recovery.fallbackStopUsed, false);
        assert.ok(Number.isInteger(witness.recovery.remainingAfterPublicationMs) && witness.recovery.remainingAfterPublicationMs > 0);
        const proof = read(path.join(run, 'chromium-witness.json'));
        assert.deepEqual(proof, witness.proof); assert.equal(proof.owner, OWNER); assert.equal(proof.nonce, nonce);
        assert.equal(proof.passed, true); assert.equal(proof.witnessContainmentRemainingZero, true);
        assert.equal(proof.innerFlags, 8192); assert.equal(proof.parentOutsideInner, true); assert.equal(proof.rootsInsideInner, true);
        assert.equal(proof.observerOutsideWitness, true); assert.equal(proof.parentInsideWitness, true); assert.equal(proof.rootsInsideWitness, true);
        assert.deepEqual(proof.containment.map(e => e.role), ['parent', 'supervisor', 'system', 'admin-de', 'member']);
        assert.ok(proof.containment.every(e => e.insideWitness === true));
        assert.ok(proof.activeBefore > 0); assert.equal(proof.activeAfter, 0);
        for (const key of ['zeroQueryRemainingBeforeMs', 'zeroQueryRemainingAfterMs', 'remainingRecoveryMs']) assert.ok(Number.isInteger(proof[key]) && proof[key] > 0);
        assert.equal(proof.parentExited, true); assert.equal(proof.parentStdoutEof, true); assert.equal(proof.parentStderrEof, true);
        assert.equal(proof.rootsExited, true); assert.deepEqual(proof.rootActualExits, receipt.knownExitEvidence);
        for (const key of ['outerStopUsed', 'outerClosedBeforeProof', 'priorFailure', 'innerActiveZeroObserved', 'browserEofObserved', 'fullRendererActualObserved']) assert.equal(proof[key], false);
      } else {
        assert.equal(receipt.resourceRecovery.jobEmpty, true);
        const browsers = receipt.streams.filter(s => s.role === 'chromium-normal-close');
        assert.equal(browsers.length, 3); assert.ok(browsers.every(s => s.exitCode === 0));
      }
    } catch (error) {
      failure = error.message;
      if (!exited) p.kill();
      try { await until(() => exited || launchError, LIMIT.recoveryExit, totalDeadline); } catch { /* 未確認を保持する */ }
      for (const channel of channels) await recoverChannel(channel, totalDeadline);
      if (!exited) p.unref();
    }
    results.push({ fixture, passed: failure === null, failure, run, nonce, ready, launcherPid: p.pid, launcherExited: exited, launcherExitCode: code, channels: channels.map(c => c.state), receipt, unverifiedOwnedTree: failure !== null });
    if (failure !== null) break;
  }
  const passed = results.length === 2 && results.every(r => r.passed);
  const sourceSHA256After = sourceSHA256();
  assert.deepEqual(sourceSHA256After, inputSHA256, '試験後source変更');
  const abnormalWitnessContainmentRemainingZero = passed && results[1].receipt.chromiumWitness.qualified === true && results[1].receipt.chromiumWitness.proof.witnessContainmentRemainingZero === true;
  console.log(JSON.stringify({ kind: 'fixed-chromium-fixture', passed, abnormalWitnessContainmentRemainingZero, fullAbnormalTreeRecoveryProven: false, abnormalBrowserEofObserved: false, abnormalJobActiveZeroObserved: false, owner: OWNER, inputCommit, inputSHA256, sourceSHA256After, totalMs: LIMIT.total, reserveMs: LIMIT.reserve, results, retained: base,
    retentionReason: 'rootによる証拠確定とexact所有確認後のprofile撤去待ち' }));
  if (!passed) process.exitCode = 1;
}
async function suite() {
  assert.equal(process.platform, 'win32', 'Windows専用。非Windowsを合格扱いにしない');
  assert.equal(process.argv.length, 2, '任意commandや出力pathの入力は受け付けない');
  // 自己Temp準備/最初のspawnより前に一度だけ設定し、caseではresetしない。
  const suiteStart = performance.now();
  const totalDeadline = suiteStart + LIMIT.total;
  const workDeadline = totalDeadline - LIMIT.reserve;
  const inputCommit = process.env.M21_INPUT_COMMIT ?? null;
  if (inputCommit !== null) assert.match(inputCommit, /^[0-9a-f]{40}$/);
  const inputSHA256 = Object.fromEntries([
    ['supervisor', path.join(__dirname, 'm21-windows-supervisor.ps1')], ['probe', __filename],
    ['operations', path.join(__dirname, '..', 'docs', 'development', 'm21-windows-supervisor.md')],
  ].map(([name, file]) => [name, crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase()]));
  const events = [];
  const stamp = (scenario, event) => events.push({ scenario, event, elapsedMs: Math.floor(performance.now() - suiteStart), remainingWorkMs: remaining(workDeadline), remainingTotalMs: remaining(totalDeadline) });
  const script = path.join(__dirname, 'm21-windows-supervisor.ps1');
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'm21-job-probe-'));
  const baseIdentity = fs.lstatSync(base);
  const baseCanonical = fs.realpathSync(base);
  const results = [];
  // このharness自身が新規作成した領域だけを最後に撤去する。
  fs.writeFileSync(path.join(base, 'owner.json'), JSON.stringify({ owner: OWNER, purpose: 'M21固定dummy probe' }), { flag: 'wx' });
  let allRecovered = true;
  try {
    for (const scenario of scenarios) {
      if (remaining(workDeadline) === 0) { allRecovered = false; results.push({ scenario, passed: false, reason: 'work-budget-exhausted', notStarted: true }); break; }
      stamp(scenario, 'case-start');
      const run = path.join(base, scenario);
      fs.mkdirSync(run);
      const nonce = crypto.randomBytes(16).toString('hex');
      atomic(path.join(run, 'owner.json'), { owner: OWNER, nonce });
      if (remaining(workDeadline) === 0) { allRecovered = false; results.push({ scenario, passed: false, reason: 'work-budget-exhausted', notStarted: true }); break; }
      stamp(scenario, 'spawn-before');
      const p = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-File', script,
        '-Mode', scenario === 'kill-close' ? 'Observe' : 'Parent', '-Scenario', scenario,
        '-RunDirectory', run, '-Nonce', nonce, '-OwnerId', OWNER, '-NodePath', process.execPath,
        '-WorkRemainingMs', String(remaining(workDeadline)), '-TotalRemainingMs', String(remaining(totalDeadline))], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      stamp(scenario, 'spawn-after');
      // 親stdioも捨てず、有限終端まで採取する。内容は資格情報を含まない固定dummyだけ。
      const out = fs.createWriteStream(path.join(run, 'launcher.out'), { flags: 'wx' });
      const err = fs.createWriteStream(path.join(run, 'launcher.err'), { flags: 'wx' });
      const channels = [observeChannel('stdout', p.stdout, out), observeChannel('stderr', p.stderr, err)];
      let code = null;
      let exited = false;
      let closed = false;
      let launchError;
      let receipt = null;
      let stopStarted = false;
      let killFailure = null;
      const stopLauncher = () => {
        if (!stopStarted && !exited && p.pid) { stopStarted = true; stamp(scenario, 'stop-start'); try { p.kill(); } catch (error) { killFailure = error.message; } }
      };
      const observed = new Set();
      const observeProgress = () => {
        for (const name of ['hello.json', 'ack.json']) if (!observed.has(name) && fs.existsSync(path.join(run, name))) { observed.add(name); stamp(scenario, name + '-observed'); }
        const phaseFile = path.join(run, 'supervisor.out');
        if (!observed.has('entry') && fs.existsSync(phaseFile) && fs.statSync(phaseFile).size > 0) {
          if (fs.readFileSync(phaseFile, 'ascii').startsWith('M21_PHASE|entry|')) { observed.add('entry'); stamp(scenario, 'entry-observed'); }
        }
      };
      p.on('error', error => { launchError = error; });
      p.on('exit', value => { code = value; exited = true; stamp(scenario, 'actual-exit'); });
      p.on('close', () => { closed = true; });
      try {
        let launchFailure = null;
        try { await until(() => { observeProgress(); return exited || launchError !== undefined; }, Infinity, workDeadline); } catch (error) { launchFailure = remaining(workDeadline) === 0 ? new Error('work-budget-exhausted') : error; }
        // W到達ではEOF収集より先に、直接保持launcherの停止を開始する。
        if (launchFailure) { stopLauncher(); throw launchFailure; }
        stamp(scenario, 'channel-recovery-start');
        const channelFailures = [];
        for (const channel of channels) channelFailures.push(...await collectChannel(channel, totalDeadline));
        stamp(scenario, 'channel-recovery-end');
        if (launchError) throw launchError;
        if (channelFailures.length) throw new Error(JSON.stringify(channelFailures));
        assert.ok(remaining(totalDeadline) > 0, 'total-budget-exhausted');
        receipt = read(path.join(run, scenario === 'kill-close' ? 'observe-result.json' : 'result.json'));
        assert.equal(receipt.nonce, nonce);
        assert.equal(receipt.owner, OWNER);
        assert.equal(receipt.process.pid, p.pid);
        assert.match(receipt.process.creation, /^[0-9]+$/);
        assert.equal(receipt.recovered, true, `${scenario}: 自身資源の回収不明`);
        for (const proof of receipt.resourceRecovery.lateExitProofs ?? []) {
          assert.equal(proof.sameHeldChild, true); assert.equal(proof.adopted, true);
          assert.equal(proof.recoveryAllowed, true); assert.equal(proof.jobSatisfied, true); assert.ok(proof.remainingMs > 0);
          assert.equal(proof.final.initialExitObserved, false); assert.equal(proof.final.initialPublicationAttempted, false);
          for (const field of ['exited', 'stdoutEof', 'stderrEof', 'stdoutWriterClosed', 'stderrWriterClosed', 'stdoutPublished', 'stderrPublished']) assert.equal(proof.final[field], true);
          const first = receipt.streams.find(s => s.pid === proof.pid && s.creation === proof.creation && s.role === proof.role && s.exited === false);
          assert.ok(first); assert.equal(first.stdoutPublished, false); assert.equal(first.stderrPublished, false);
          assert.ok(receipt.secondary.some(s => s.stage === `${proof.role}-exit-timeout`) || receipt.primary?.stage === `${proof.role}-exit-timeout`);
        }
        assert.equal(code, scenario === 'normal' || scenario === 'kill-close' ? 0 : 1);
        assert.equal(receipt.passed, code === 0);
        if (scenario === 'normal') {
          assert.equal(receipt.primary, null);
          assert.equal(receipt.streams.length, 2);
          assert.deepEqual(receipt.streams.map(s => s.role).sort(), ['direct', 'dummy']);
          assert.equal(new Set(receipt.streams.map(s => s.pid)).size, 2);
          for (const stream of receipt.streams) { assert.ok(stream.pid > 0); assert.match(stream.creation, /^[0-9]+$/); assert.equal(stream.exitCode, 0); }
          assert.ok(receipt.streams.every(s => s.exited && s.stdoutEof && s.stderrEof));
          assert.equal(receipt.lastHandles.length, 2);
          assert.deepEqual(receipt.lastHandles.map(s => s.pid).sort(), receipt.streams.map(s => s.pid).sort());
          assert.ok(receipt.lastHandles.every(s => s.exited && s.stdoutEof && s.stderrEof));
          const observedPhases = phases(run);
          assert.deepEqual(observedPhases.map(s => s.phase), ['entry', 'guard-before', 'guard-after', 'hello-before', 'hello-published', 'ack-validated', 'native-before', 'native-after']);
          const supervisor = receipt.streams.find(s => s.role === 'direct');
          assert.ok(observedPhases.every(s => s.pid === supervisor.pid && s.creation === supervisor.creation));
        } else if (scenario === 'kill-close') {
          assert.equal(receipt.parentKilled, true);
          assert.equal(receipt.knownExited, 3);
        } else {
          assert.ok(receipt.primary);
          assert.equal(receipt.primary.reason, REASONS[scenario], '指定fault点以外のbootstrap失敗を合格にしない');
          assert.ok(receipt.attempts.includes('external-cleanup'));
          assert.ok(receipt.attempts.includes('result-save'));
          if (['pre-ack', 'wrong-ack', 'assign-fail', 'owner-mismatch'].includes(scenario)) {
            assert.equal(fs.existsSync(path.join(run, 'child.json')), false);
          }
          if (scenario === 'pre-ack' || scenario === 'wrong-ack') {
            const owned = read(path.join(run, 'supervisor-owned.json'));
            assert.equal(owned.owner, OWNER); assert.equal(owned.nonce, nonce);
            const inner = read(path.join(run, 'supervisor-result.json'));
            assert.equal(inner.primary.reason, REASONS[scenario]);
            if (scenario === 'pre-ack') { assert.equal(fs.existsSync(path.join(run, 'ack.json')), false); assert.equal(inner.primary.reasonFile, 'ack.json'); }
            else { const ack = read(path.join(run, 'ack.json')); assert.notEqual(ack.nonce, nonce); assert.equal(ack.ownedJobVerified, true); }
            assert.equal(phases(run).some(s => s.phase === 'native-before'), false);
          }
          if (scenario === 'assign-fail' || scenario === 'owner-mismatch') {
            const hello = read(path.join(run, 'hello.json'));
            assert.equal(hello.nonce, nonce);
            assert.equal(hello.owner, scenario === 'assign-fail' ? OWNER : 'wrong-owner');
            assert.equal(fs.existsSync(path.join(run, 'supervisor-owned.json')), false);
            assert.equal(fs.existsSync(path.join(run, 'ack.json')), false);
          }
          const dummyExit = receipt.streams.find(s => s.role === 'dummy');
          if (scenario === 'host-fail' || scenario === 'cleanup-fail') { assert.ok(dummyExit); assert.equal(dummyExit.exitCode, 17); assert.equal(dummyExit.exited, true); }
          if (scenario === 'cleanup-fail') { assert.equal(receipt.primary.stage, 'dummy-nonzero'); assert.ok(receipt.secondary.some(s => s.reason === 'cleanup-fault')); }
          if (scenario === 'save-fail') { assert.equal(receipt.primary.stage, 'result-save'); assert.ok(dummyExit); assert.equal(dummyExit.exitCode, 0); }
          if (scenario === 'stdio-eof') {
            assert.equal(receipt.resourceRecovery.jobEmpty, true);
            assert.ok(dummyExit); assert.equal(dummyExit.exitCode, 0);
            assert.equal(dummyExit.stdoutEof, false); assert.equal(dummyExit.stderrEof, false);
            const certified = receipt.p6Certification;
            assert.equal(certified.ownedJobVerified, true); assert.equal(certified.liveBeforeRelease, true);
            assert.equal(certified.releasePublished, true); assert.equal(certified.jobStopped, true);
            assert.equal(certified.child.pid, dummyExit.pid); assert.equal(certified.child.creation, dummyExit.creation);
            assert.equal(certified.childExited, true); assert.equal(certified.childExitCode, 0);
            assert.equal(certified.grandchildExited, true); assert.equal(typeof certified.grandchildExitCode, 'number');
            assert.deepEqual(receipt.p6Observations.map(s => s.stage), ['after-body-child-exit', 'after-child-exit', 'stdout-before', 'stdout-after', 'stderr-before', 'stderr-after']);
            for (const observation of receipt.p6Observations) {
              assert.equal(observation.pid, certified.grandchild.pid); assert.equal(observation.creation, certified.grandchild.creation);
              assert.equal(observation.waitExit0, false); assert.equal(observation.hasExited, false);
            }
          }
        }
        const reason = value => value ? { stage: value.stage, reason: value.reason ?? null, kind: value.kind } : null;
        const streamEvidence = receipt.streams.map(s => ({ role: s.role, pid: s.pid, creation: s.creation, exited: s.exited, exitCode: s.exitCode, stdoutEof: s.stdoutEof, stderrEof: s.stderrEof, timingOrigin: s.timingOrigin, timings: s.timings, timingOverflow: s.timingOverflow }));
        let p4 = null;
        if (scenario === 'kill-close') {
          const certificate = read(path.join(run, 'certified-tree.json'));
          assert.equal(receipt.knownExitEvidence.length, 3);
          assert.ok(receipt.knownExitEvidence.every(s => s.exited === true));
          p4 = { certifier: certificate.certifier, members: certificate.members, actualExits: receipt.knownExitEvidence,
            metadataReadiness: certificate.metadataReadiness };
        }
        const evidence = { process: receipt.process, primary: reason(receipt.primary), secondary: receipt.secondary.map(reason),
          streams: streamEvidence, lastHandles: receipt.lastHandles, resourceRecovery: receipt.resourceRecovery,
          grandchildEvidence: receipt.grandchildEvidence, p6Observations: receipt.p6Observations, p6Certification: receipt.p6Certification,
          metadataReadiness: receipt.metadataReadiness, parentExitEvidence: receipt.parentExitEvidence, p4 };
        stamp(scenario, 'case-recovered');
        results.push({ scenario, passed: true, launcherPid: p.pid, launcherCreation: receipt.process?.creation ?? null,
          launcherExitCode: code, launcherExited: exited, channels: channels.map(c => c.state), evidence });
      } catch (error) {
        allRecovered = false;
        // own childだけ。Job親のkillでJob treeを閉じる。成功・回収済みとは主張しない。
        stamp(scenario, 'cleanup-start');
        stopLauncher();
        let cleanupFailure = null;
        try { await until(() => exited || launchError !== undefined, LIMIT.recoveryExit, totalDeadline); } catch (cleanupError) { cleanupFailure = cleanupError.message; }
        const channelRecovery = [];
        for (const channel of channels) {
          try { channelRecovery.push(await recoverChannel(channel, totalDeadline)); }
          catch (channelError) { channelRecovery.push({ ...channel.state, recoveryWaitFailure: channelError.message }); }
        }
        results.push({ scenario, passed: false, reason: error.message, killFailure, cleanupFailure,
          ownLauncherPid: p.pid ?? null, ownLauncherCreation: receipt?.process?.creation ?? null,
          ownLauncherExitCode: code, ownLauncherExited: exited, ownLauncherClosed: closed, channels: channelRecovery,
          unverifiedOwnedTree: stopStarted, workBudgetStopStarted: stopStarted });
        stamp(scenario, 'cleanup-end');
        // 回収不明をretainedへ記録して非0にする。未知PID/子へ再取得して接続しない。
        p.unref();
        break;
      }
    }
  } finally {
    if (allRecovered && results.length === scenarios.length) {
      try {
        const current = fs.lstatSync(base);
        assert.ok(current.isDirectory() && !current.isSymbolicLink());
        assert.equal(current.dev, baseIdentity.dev);
        assert.equal(current.ino, baseIdentity.ino);
        assert.equal(fs.realpathSync(base), baseCanonical);
        assert.equal(read(path.join(base, 'owner.json')).owner, OWNER);
        fs.rmSync(base, { recursive: true });
      } catch (error) {
        allRecovered = false;
        results.push({ scenario: 'own-temp-cleanup', passed: false, reason: error.message });
      }
    }
  }
  console.log(JSON.stringify({ passed: allRecovered && results.length === scenarios.length && results.every(r => r.passed), inputCommit, inputSHA256, totalMs: LIMIT.total, reserveMs: LIMIT.reserve, events, results,
    notRun: scenarios.filter(s => !results.some(r => r.scenario === s)),
    retained: allRecovered ? null : base }));
  if (!allRecovered) process.exitCode = 1;
}
async function formalEntryContract() {
  // 外部asset生成境界だけを置換し、固定入口の順序・期限・fail-closedを検査する。
  const origin = performance.now();
  const contractNonce = crypto.randomBytes(16).toString('hex');
  const sourceFiles = [__filename, path.join(__dirname, 'm21-windows-formal-adapter.ps1'), path.join(__dirname, 'm21-windows-supervisor.ps1')];
  const sourceSHA256 = () => Object.fromEntries(sourceFiles.map(file => [path.basename(file), crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
  const sourceBefore = sourceSHA256();
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'm21-entry-contract-'));
  const baseIdentity = fs.lstatSync(base), baseCanonical = fs.realpathSync(base);
  atomic(path.join(base, 'owner.json'), { owner: OWNER, nonce: contractNonce, purpose: 'M21関数有限診断' });
  const bulk = text => Buffer.from(`$${Buffer.byteLength(text)}\r\n${text}\r\n`, 'ascii');
  const expectedRunId = 'a'.repeat(40);
  const goodInfo = `# Server\r\nrun_id:${expectedRunId}\r\n`;
  const endpointResponses = [bulk(goodInfo), bulk(`# Server\r\nrun_id:${'b'.repeat(40)}\r\n`), bulk('# Server\r\nredis_version:8\r\n'),
    bulk(`${goodInfo}run_id:${expectedRunId}\r\n`), Buffer.from('$20000\r\n'), Buffer.from('+OK\r\n'), Buffer.from('$100\r\nshort'), Buffer.concat([bulk(goodInfo), Buffer.from('+EXTRA\r\n')]), null];
  const endpointSockets = new Set();
  let endpointConnections = 0;
  const endpointServer = net.createServer(socket => {
    endpointSockets.add(socket); socket.on('close', () => endpointSockets.delete(socket)); socket.on('error', () => {});
    const response = endpointResponses[endpointConnections++];
    let request = '', answered = false;
    socket.on('data', bytes => {
      request += bytes.toString('ascii');
      const fixed = '*2\r\n$4\r\nINFO\r\n$6\r\nserver\r\n';
      if (request.length > fixed.length || !fixed.startsWith(request)) { socket.destroy(); return; }
      if (!answered && request === fixed) { answered = true; if (response !== null && response !== undefined) socket.end(response); }
    });
  });
  await new Promise((resolve, reject) => { endpointServer.once('error', reject); endpointServer.listen({ host: '127.0.0.1', port: 0, exclusive: true }, resolve); });
  const endpointPort = endpointServer.address().port;
  const fixture = path.join(base, 'contract.ps1');
  fs.writeFileSync(fixture, `param([string]$Adapter,[string]$SupervisorPath,[int]$EndpointPort)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Set-Reason { param($value) $script:reason=$value }
function Guard-Run {}
function Require-Work { if($script:expired){throw 'fixture-work-expired'} }
function Set-Operation { param($value) }
$script:expired=$false
$script:reason=$null
$script:self=[Diagnostics.Process]::GetCurrentProcess()
$script:phaseClock=[Diagnostics.Stopwatch]::StartNew()
$script:localClock=[Diagnostics.Stopwatch]::StartNew();$script:WorkRemainingMs=60000
function Remaining { param([int]$Cap=[int]::MaxValue,[switch]$Recovery) return [int][Math]::Max(0,[Math]::Min($Cap,[Math]::Floor($script:WorkRemainingMs-$script:localClock.Elapsed.TotalMilliseconds))) }
function Entry-Phase([string]$Name) { [Console]::Out.WriteLine('ENTRY_PHASE|'+$Name+'|pid='+$self.Id+'|creation='+$self.StartTime.ToUniversalTime().Ticks+'|utcTicks='+[DateTime]::UtcNow.Ticks+'|elapsedMs='+$script:phaseClock.ElapsedMilliseconds) }
Entry-Phase 'entered'
. $Adapter
Entry-Phase 'loaded'
$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($SupervisorPath,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'native-source-parser'}
$native=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.StringConstantExpressionAst] -and $node.Value.StartsWith('using System;') -and $node.Value.Contains('public sealed class M21Child')},$true))
if($native.Count -ne 1){throw 'native-fixed-literal'}
Add-Type -TypeDefinition $native[0].Value
Entry-Phase 'native-defined'
$bridgeMethod=[M21Child].GetMethod('BridgeArguments',[Reflection.BindingFlags]'NonPublic,Static')
$bridgeVector=[string[]]@('-d','Ubuntu-24.04','--exec','/usr/bin/python3','-I','/mnt/c/fixed source/m21-wsl-docker-bridge.py','--run-directory','/mnt/c/fixed run/bridge','--owner','00000000-0000-0000-0000-000000000001','--run','00000000-0000-0000-0000-000000000002','--nonce',('a'*32),'--input-commit',('b'*40))
$bridgeParameters=New-Object object[] 1;$bridgeParameters[0]=$bridgeVector
$rendered=$bridgeMethod.Invoke($null,$bridgeParameters)
if(!$rendered.StartsWith('-d Ubuntu-24.04 --exec /usr/bin/python3 -I "') -or !$rendered.Contains('"/mnt/c/fixed source/m21-wsl-docker-bridge.py" "--run-directory" "/mnt/c/fixed run/bridge"')){throw 'bridge-prefix-rendering'}
$bridgeVector[0]='--unknown';$rejected=$false;try{$bridgeMethod.Invoke($null,$bridgeParameters)}catch{$actual=$_.Exception;while($null -ne $actual.InnerException){$actual=$actual.InnerException};if($actual -isnot [IO.InvalidDataException]){throw};$rejected=$true};if(!$rejected){throw 'bridge-prefix-unknown'}
$bridgeVector[0]='-d';$shortVector=[string[]]$bridgeVector[0..14];$bridgeParameters[0]=$shortVector;$rejected=$false;try{$bridgeMethod.Invoke($null,$bridgeParameters)}catch{$actual=$_.Exception;while($null -ne $actual.InnerException){$actual=$actual.InnerException};if($actual -isnot [IO.InvalidDataException]){throw};$rejected=$true};if(!$rejected){throw 'bridge-argument-length'}
$budget=Get-M21FormalBudget
if($budget.totalMs -ne 3300000 -or $budget.workMs -ne 3000000 -or $budget.reserveMs -ne 300000){throw 'budget'}
if(($budget.recoveryCapsMs|Measure-Object -Sum).Sum -ne 267000 -or $budget.marginMs -ne 33000){throw 'caps'}
Assert-M21ScenarioBudget 'formal-m21' 'Parent' 'none' 3000000 3300000
Assert-M21ScenarioBudget 'normal' 'Parent' 'none' 402000 420000
foreach($case in @(@('normal','Parent','none',420001,420001),@('formal-m21','Observe','none',3000000,3300000),@('formal-m21','Parent','formal-fe',3000000,3300000),@('formal-m21','Parent','none',3000001,3300000),@('formal-m21','Parent','none',3000000,3299999),@('formal-m21','Parent','none',2,1))){
 $rejected=$false;try{Assert-M21ScenarioBudget @case}catch{$rejected=$true};if(!$rejected){throw 'invalid-budget-admitted'}
}
Assert-M21FormalRequiredAlive @($self)
Entry-Phase 'budget'
$rejected=$false;try{Assert-M21FormalRequiredAlive @()}catch{$rejected=$true};if(!$rejected){throw 'empty-required-alive'}
$script:events=New-Object 'Collections.Generic.List[string]'
function Invoke-M21ProductionAssets { $script:events.Add('assets');return @{formalRunQualified=$false;runtimeCP='fixture-only'} }
function Invoke-M21FormalWork { $script:events.Add('work');throw 'fixture-runtime-unconnected' }
$failed=$false;try{Invoke-M21FormalSupervisor}catch{$failed=$true}
if(!$failed -or ($events -join ',') -cne 'assets,work'){throw 'asset-work-order'}
$events.Clear()
function Invoke-M21ProductionAssets { $script:events.Add('assets');throw 'fixture-cp-failed' }
try{Invoke-M21FormalSupervisor}catch{}
if(($events -join ',') -cne 'assets'){throw 'continued-after-cp-failure'}
$events.Clear();$script:expired=$true
try{Invoke-M21FormalSupervisor}catch{}
if($events.Count -ne 0){throw 'work-after-deadline'}
Entry-Phase 'order'
$script:expired=$false
$script:run=$PSScriptRoot;$script:owner='${OWNER}';$script:Nonce='0123456789abcdef0123456789abcdef'
$env:M21_INPUT_COMMIT='7bd15a1c3de31aedce8a235b632a16be75c376b9'
$env:M21_HOST_ONLY_SENTINEL='public-test-sentinel'
$environment=New-M21FormalSupervisorEnvironment
if($environment.ContainsKey('M21_HOST_ONLY_SENTINEL') -or $environment.ContainsKey('PATH') -or $environment['M21_INPUT_COMMIT'] -cne $env:M21_INPUT_COMMIT -or $environment['TEMP'] -cne $run){throw 'supervisor-environment'}
$script:m21FormalConnection=@{database='m21_0123456789abcdef0123456789abcdef';mysqlPort=33061;valkeyPort=63791;password='public-test-app-password'}
$script:m21ProductionAssets=@{backend=$PSScriptRoot;productionCP='fixture-cp';fixtureJar=(Join-Path $PSScriptRoot 'fixture.jar')}
$script:m21FormalHelper=Join-Path $PSScriptRoot 'helper.cjs';$script:m21FormalHelperSha='a'*64
$script:NodePath='C:/fixture/node.exe'
$hostEnvironment=New-M21FormalHostEnvironment
if(!$hostEnvironment.ContainsKey('JOB_QR_SIGNING_SECRET') -or [string]::IsNullOrWhiteSpace($hostEnvironment['JOB_QR_SIGNING_SECRET']) -or [Text.Encoding]::UTF8.GetByteCount($hostEnvironment['JOB_QR_SIGNING_SECRET']) -lt 32){throw 'qr-signing-secret-missing'}
# M21_PENDING_EXIT_PURE_BEGIN
$pureMainAst=[Management.Automation.Language.Parser]::ParseFile($SupervisorPath,[ref]$null,[ref]$null)
$pureJobAssignments=@($pureMainAst.FindAll({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and $n.Left.Extent.Text -ceq '$jobSatisfied'},$true))
if($pureJobAssignments.Count -ne 1){throw 'pending-exit-job-expression-count'}
$pureJobExpression=[ScriptBlock]::Create($pureJobAssignments[0].Right.Extent.Text)
$pureChild=[pscustomobject]@{pid=71;creation='same-birth'};$pureForeign=[pscustomobject]@{pid=71;creation='same-birth'}
$pureBase=@{initialExitObserved=$false;initialPublicationAttempted=$false;exited=$true;stdoutEof=$true;stderrEof=$true;stdoutWriterClosed=$true;stderrWriterClosed=$true;stdoutPublished=$true;stderrPublished=$true}
$pureCases=@(
 @{name='complete';expected=$true},
 @{name='exit-unconfirmed';key='exited';value=$false;expected=$false},
 @{name='stdout-not-eof';key='stdoutEof';value=$false;expected=$false},
 @{name='stderr-not-eof';key='stderrEof';value=$false;expected=$false},
 @{name='writer-not-closed';key='stdoutWriterClosed';value=$false;expected=$false},
 @{name='not-published';key='stderrPublished';value=$false;expected=$false},
 @{name='publication-already-attempted';key='initialPublicationAttempted';value=$true;expected=$false},
 @{name='not-initial-timeout';key='initialExitObserved';value=$true;expected=$false},
 @{name='foreign-reference';foreign=$true;expected=$false},
 @{name='prior-recovery-failure';blocked=$true;expected=$false},
 @{name='job-created-unobserved';jobCreated=$true;jobEmpty=$false;expected=$false},
 @{name='job-not-created';jobCreated=$false;jobEmpty=$true;expected=$false},
 @{name='budget-zero';zeroBudget=$true;expected=$false},
 @{name='unknown-eof';key='stderrEof';value=$null;expected=$false})
foreach($pureCase in $pureCases){
 $pureProof=$pureBase.Clone();if($pureCase.ContainsKey('key')){$pureProof[$pureCase.key]=$pureCase.value}
 $pureObserved=$pureChild;if($pureCase.ContainsKey('foreign')){$pureObserved=$pureForeign}
 $pureAllowed=!$pureCase.ContainsKey('blocked');$jobCreated=$true;$jobEmpty=$true
 if($pureCase.ContainsKey('jobCreated')){$jobCreated=$pureCase.jobCreated;$jobEmpty=$pureCase.jobEmpty}
 $pureJob=& $pureJobExpression
 $pureRemaining=1;if($pureCase.ContainsKey('zeroBudget')){$pureRemaining=0}
 $pureActual=Test-M21PendingExitRecovery $pureChild $pureObserved $pureProof $pureAllowed $pureJob $pureRemaining
 if($pureActual -isnot [bool] -or $pureActual -ne $pureCase.expected){throw ('pending-exit-pure-'+$pureCase.name)}
}
[Console]::Out.WriteLine('M21_PENDING_EXIT_PURE_OK')
# M21_PENDING_EXIT_PURE_END
Entry-Phase 'host-environment'
foreach($key in @('M21_HOST_ONLY_SENTINEL','PATH','USERPROFILE','JAVA_TOOL_OPTIONS','NODE_OPTIONS','AWS_PROFILE','HTTP_PROXY')){if($hostEnvironment.ContainsKey($key)){throw 'host-env-inheritance'}}
if($hostEnvironment['SPRING_DATASOURCE_USERNAME'] -cne 'm21' -or $hostEnvironment['SPRING_DATA_REDIS_PORT'] -cne '63791' -or $hostEnvironment['MANNSCHAFT_EMAIL_SIMULATE'] -cne 'true' -or $hostEnvironment['AWS_EC2_METADATA_DISABLED'] -cne 'true' -or $hostEnvironment['MANNSCHAFT_R2_ENDPOINT'] -cne 'http://127.0.0.1:8081' -or $hostEnvironment['MANNSCHAFT_SQS_ENABLED'] -cne 'false'){throw 'host-env-contract'}
$close=[pscustomobject]@{owner=$owner;nonce=$Nonce;runId='m21-00000000-0000-0000-0000-000000000001';actorCodes=@('system','admin-de','member');requested=$true}
Assert-M21FormalBrowserCloseRequest $close $close.runId
$close.nonce='ffffffffffffffffffffffffffffffff'
$rejected=$false;try{Assert-M21FormalBrowserCloseRequest $close $close.runId}catch{$rejected=$true};if(!$rejected){throw 'other-run-close-admitted'}
$close.nonce=$Nonce;$close.actorCodes=@('system','member','member')
$rejected=$false;try{Assert-M21FormalBrowserCloseRequest $close $close.runId}catch{$rejected=$true};if(!$rejected){throw 'wrong-actors-close-admitted'}
$script:m21FormalConnection.valkeyPort=6379
$rejected=$false;try{New-M21FormalHostEnvironment}catch{$rejected=$true};if(!$rejected){throw 'shared-valkey-admitted'}
$endpoint=Confirm-M21FormalValkeyEndpoint $EndpointPort ('a'*40)
if($endpoint.run_id -cne ('a'*40) -or $endpoint.socket_proof.green -ne $true -or $endpoint.socket_proof.socket_closed -ne $true){throw 'endpoint-positive'}
foreach($case in @('mismatch','missing','duplicate','oversize','malformed','partial','extra','timeout')){
 $rejected=$false;try{Confirm-M21FormalValkeyEndpoint $EndpointPort ('a'*40)}catch{$rejected=$true}
 if(!$rejected -or $script:m21LastValkeyProbe.socket_proof.green -ne $false -or $script:m21LastValkeyProbe.socket_proof.socket_closed -ne $true){throw ('endpoint-negative-'+$case)}
}
Entry-Phase 'endpoint'
$script:localClock=[pscustomobject]@{ElapsedTicks=([long][Diagnostics.Stopwatch]::Frequency*2)}
$script:m21BridgeOriginTicks=[long][Diagnostics.Stopwatch]::Frequency;$script:m21BridgeGlobalOffsetMs=2000;$script:m21BridgeWorkOffsetMs=1600
function Remaining { param([int]$Cap=[int]::MaxValue,[switch]$Recovery) return [Math]::Min($Cap,200) }
if((Get-M21FormalBridgeDeadlineOffset 100) -ne 1100){throw 'deadline-offset-reset'}
$script:m21BridgeWorkOffsetMs=1050
if((Get-M21FormalBridgeDeadlineOffset 100) -ne 1050){throw 'deadline-offset-not-clamped'}
$script:m21BridgeWorkOffsetMs=999
$rejected=$false;try{Get-M21FormalBridgeDeadlineOffset 100}catch{$rejected=$true};if(!$rejected){throw 'expired-offset-admitted'}
$script:m21BridgeRun='00000000-0000-0000-0000-000000000002'
$script:m21BridgeReady=[pscustomobject]@{boot_id='00000000-0000-0000-0000-000000000003';pid_namespace='pid:[123]'}
$identity=[pscustomobject]@{pid=123;pgid=123;startticks='123456';boot_id=$script:m21BridgeReady.boot_id;pid_namespace=$script:m21BridgeReady.pid_namespace}
$started=[pscustomobject]@{owner=$owner;nonce=$Nonce;run=$script:m21BridgeRun;request_id=1;operation='daemon-info';resource='daemon';deadline_ns=10000;session_acknowledged=$true;leader_identity=$identity;watchdog_identity=$identity}
Assert-M21WslRelayStarted $started 10000
$started.nonce='ffffffffffffffffffffffffffffffff'
$rejected=$false;try{Assert-M21WslRelayStarted $started 10000}catch{$rejected=$true};if(!$rejected){throw 'old-relay-started-admitted'}
Entry-Phase 'complete'
[Console]::Out.WriteLine('M21_ENTRY_CONTRACT_OK')
`);
  const launchRequestedMs = Math.floor(performance.now() - origin);
  const child = spawn('C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe', ['-NoProfile', '-NonInteractive', '-File', fixture, path.join(__dirname, 'm21-windows-formal-adapter.ps1'), path.join(__dirname, 'm21-windows-supervisor.ps1'), String(endpointPort)], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '', stderr = '', stdoutEof = false, stderrEof = false, actualExit = null, closeMs = null, firstOutputMs = null, spawnObservedMs = null, deadlineStopIssued = false;
  child.on('spawn', () => { spawnObservedMs = Math.floor(performance.now() - origin); });
  child.stdout.on('data', data => { firstOutputMs ??= Math.floor(performance.now() - origin); stdout += data; });
  child.stderr.on('data', data => { stderr += data; });
  child.stdout.on('end', () => { stdoutEof = true; });
  child.stderr.on('end', () => { stderrEof = true; });
  child.on('exit', (code, signal) => { actualExit = { code, signal, elapsedMs: Math.floor(performance.now() - origin) }; });
  const timer = setTimeout(() => { deadlineStopIssued = true; child.kill(); }, 60000);
  let code;
  try { code = await new Promise((resolve, reject) => { child.on('error', reject); child.on('close', value => { closeMs = Math.floor(performance.now() - origin); resolve(value); }); }); }
  finally {
    clearTimeout(timer);
    for (const socket of endpointSockets) socket.destroy();
    await new Promise(resolve => endpointServer.close(resolve));
  }
  fs.writeFileSync(path.join(base, 'stdout.txt'), stdout, { flag: 'wx' });
  fs.writeFileSync(path.join(base, 'stderr.txt'), stderr, { flag: 'wx' });
  const phases = stdout.split(/\r?\n/).filter(line => line.startsWith('ENTRY_PHASE|')).map(line => {
    const match = /^ENTRY_PHASE\|([a-z-]+)\|pid=([0-9]+)\|creation=([0-9]+)\|utcTicks=([0-9]+)\|elapsedMs=([0-9]+)$/.exec(line);
    assert.ok(match); return { phase: match[1], pid: Number(match[2]), creation: match[3], utcTicks: match[4], elapsedMs: Number(match[5]) };
  });
  const sourceAfter = sourceSHA256();
  const passed = code === 0 && stdoutEof && stderrEof && !deadlineStopIssued && endpointConnections === 9 && stdout.includes('M21_ENTRY_CONTRACT_OK') && JSON.stringify(sourceBefore) === JSON.stringify(sourceAfter);
  const evidenceDirectory = path.join(__dirname, '..', '.claude');
  fs.mkdirSync(evidenceDirectory, { recursive: true });
  assert.ok(!fs.lstatSync(evidenceDirectory).isSymbolicLink());
  const evidenceFile = path.join(evidenceDirectory, `m21-entry-contract-${contractNonce}.json`);
  const evidence = { scope: 'formal-entry-contract-finite-diagnostic', owner: OWNER, nonce: contractNonce, passed, runtimeQualified: false,
    diagnosticLimitMs: 60000, productionBudgetProof: false, inputCommit: process.env.M21_INPUT_COMMIT ?? null, sourceBefore, sourceAfter,
    ownLauncherPid: child.pid, launchRequestedMs, spawnObservedMs, actualExit, closeMs, firstOutputMs, stdoutEof, stderrEof, deadlineStopIssued, endpointConnections, phases, retained: passed ? null : base };
  fs.writeFileSync(evidenceFile, JSON.stringify(evidence), { flag: 'wx' });
  if (passed) {
    const current = fs.lstatSync(base);
    assert.ok(current.isDirectory() && !current.isSymbolicLink()); assert.equal(current.dev, baseIdentity.dev); assert.equal(current.ino, baseIdentity.ino);
    assert.equal(fs.realpathSync(base), baseCanonical); assert.equal(read(path.join(base, 'owner.json')).nonce, contractNonce);
    fs.rmSync(base, { recursive: true });
  }
  console.log(JSON.stringify({ passed, scope: evidence.scope, runtimeQualified: false, actualExit, stdoutEof, stderrEof, firstOutputMs, phases, evidenceFile, retained: evidence.retained }));
  assert.ok(passed, `formal entry finite diagnostic failed; evidence:${evidenceFile}`);
}
(async () => {
  if (process.argv[2] === '--dummy') {
    assert.equal(process.argv.length, 9);
    await dummy(...process.argv.slice(3));
  } else if (process.argv[2] === '--formal-entry-contract') {
    await formalEntryContract();
  } else if (process.argv[2] === '--wsl-relay-loss-fixture') {
    await readinessFixtureSuite('wsl-relay-loss');
  } else if (process.argv[2] === '--readiness-fixture') {
    await readinessFixtureSuite();
  } else if (process.argv[2] === '--formal-contract-fixture') {
    await readinessFixtureSuite('formal-contract');
  } else if (process.argv[2] === '--chromium-witness-contract') {
    await chromiumWitnessContract();
  } else if (process.argv[2] === '--chromium-fixture') {
    await chromiumFixtureSuite();
  } else {
    await suite();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
