// Windows Job Object の固定dummy専用probe。通常のサーバやブラウザを起動しない。
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const OWNER = 'd7249e88-1678-4e0c-ae21-a550989098a6';
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
    const grandchild = spawn(process.execPath, [__filename, '--dummy', 'grandchild', run, nonce, scenario, String(remaining(workDeadline)), String(remaining(totalDeadline))], { windowsHide: true, stdio: 'inherit' });
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
    if (scenario === 'stdio-eof') process.exit(0); // 孫が継承pipeを開いたままにする。
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
        '-RunDirectory', run, '-Nonce', nonce, '-NodePath', process.execPath,
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
        receipt = read(path.join(run, 'result.json'));
        assert.equal(receipt.nonce, nonce);
        assert.equal(receipt.owner, OWNER);
        assert.equal(receipt.process.pid, p.pid);
        assert.match(receipt.process.creation, /^[0-9]+$/);
        assert.equal(receipt.recovered, true, `${scenario}: 自身資源の回収不明`);
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
            assert.ok(receipt.streams.some(s => !s.stdoutEof || !s.stderrEof), '一次EOF失敗を後続Job回収で消さない');
          }
        }
        const reason = value => value ? { stage: value.stage, reason: value.reason ?? null, kind: value.kind } : null;
        const streamEvidence = receipt.streams.map(s => ({ role: s.role, pid: s.pid, creation: s.creation, exited: s.exited, exitCode: s.exitCode, stdoutEof: s.stdoutEof, stderrEof: s.stderrEof, timingOrigin: s.timingOrigin, timings: s.timings, timingOverflow: s.timingOverflow }));
        let p4 = null;
        if (scenario === 'kill-close') {
          const certificate = read(path.join(run, 'certified-tree.json'));
          assert.equal(receipt.knownExitEvidence.length, 3);
          assert.ok(receipt.knownExitEvidence.every(s => s.exited === true));
          p4 = { certifier: certificate.certifier, members: certificate.members, actualExits: receipt.knownExitEvidence };
        }
        const evidence = { process: receipt.process, primary: reason(receipt.primary), secondary: receipt.secondary.map(reason),
          streams: streamEvidence, lastHandles: receipt.lastHandles, resourceRecovery: receipt.resourceRecovery,
          grandchildEvidence: receipt.grandchildEvidence, p6Observations: receipt.p6Observations, p4 };
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
(async () => {
  if (process.argv[2] === '--dummy') {
    assert.equal(process.argv.length, 9);
    await dummy(...process.argv.slice(3));
  } else {
    await suite();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
