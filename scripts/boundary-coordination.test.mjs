import test from 'node:test';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const exec = promisify(execFile);
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, writeFile, copyFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { Coordinator, REF, MANIFEST, git, initialState, transition, validateManifest, validateState } from './boundary-coordination.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const script = 'scripts/boundary-coordination.mjs';
const manifest = JSON.parse(await readFile(join(root, MANIFEST), 'utf8'));
const owner = randomUUID();
const other = randomUUID();
const evidence = 'https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-test / 全AC green';
const claim = (s, territory, who = owner) => transition(s, manifest, 'claim', { territory, owner: who });
const update = (s, lane, options = {}) => transition(s, manifest, 'update', { owner, lane, ...options });
test('AC6: 未知laneと空証拠gateを拒否する', () => {
  const s = claim(initialState(manifest), 'common-core');
  assert.throws(() => update(s, 'L2-unknown', { state: 'planning' }), /領地/);
  assert.throws(() => transition(s, manifest, 'update', { owner, gate: 'return-targets', evidence: '' }), /証拠/);
});
test('AC9: manifestまたはCLIがorigin/main不一致ならinitせず未初期化を保つ', async t => {
  const { c, repo } = await fixture(t, undefined, false);
  await assert.rejects(c.status(), /未初期化/);
  const changed = structuredClone(manifest); changed.lanes['L1-R'].kind = 'preparation';
  await assert.rejects(new Coordinator(repo, changed).init(), /manifestがorigin\/main未着地または不一致/);
  await writeFile(join(repo, script), '// main未着地のCLI差分\n');
  await assert.rejects(c.init(), /CLIがorigin\/main未着地または不一致/);
  assert.equal(await c.remoteHead(), null);
});
function readyState(unfinished) {
  const s = initialState(manifest);
  for (const [id, lane] of Object.entries(s.lanes)) {
    lane.hold = null; lane.held = []; lane.state = unfinished.includes(id) ? 'todo' : 'done'; lane.evidence = evidence;
  }
  s.lanes['L5-I'].state = 'todo';
  for (const id of Object.keys(s.gates)) s.gates[id] = id === 'gradle-withdrawn' ? '' : evidence;
  return s;
}
async function fixture(t, runner, initialize = true) {
  const dir = await mkdtemp(join(tmpdir(), 'boundary-cas-'));
  t.after(() => { assert.equal(dirname(dir), tmpdir()); assert.ok(dir.includes('boundary-cas-')); return rm(dir, { recursive: true, force: true }); });
  const bare = join(dir, 'remote.git'); const repo = join(dir, 'client');
  await git(dir, ['init', '--bare', bare]); await git(dir, ['init', '-b', 'main', repo]);
  await git(repo, ['config', 'user.name', '領地CAS試験']); await git(repo, ['config', 'user.email', 'cas@example.invalid']);
  await git(repo, ['config', 'commit.gpgsign', 'false']);
  for (const file of [MANIFEST, script]) { await mkdir(dirname(join(repo, file)), { recursive: true }); await copyFile(join(root, file), join(repo, file)); }
  await git(repo, ['add', '.']); await git(repo, ['-c', 'core.hooksPath=/dev/null', 'commit', '-m', '試験用main']);
  await git(repo, ['remote', 'add', 'origin', bare]); await git(repo, ['push', 'origin', 'main']);
  const c = new Coordinator(repo, manifest, runner); if (initialize) await c.init();
  return { c, repo, bare };
}

test('AC1: 正本113ロットを38/38/37へ一度ずつ配賦し全細分レーンを含む', async () => {
  validateManifest(manifest);
  assert.equal(Object.keys(manifest.lanes).length, 141);
  const plan = await readFile(join(root, 'docs/architecture/modular_monolith_boundary_debt_repayment.md'), 'utf8');
  const source = [...plan.matchAll(/^\| `[^`]+` \|.*\| (L2-[a-z]+) \|\r?$/gm)].map(match => match[1]).sort();
  const lots = Object.keys(manifest.lanes).filter(id => id.startsWith('L2-')).sort();
  assert.equal(source.length, 113); assert.deepEqual(lots, source);
  assert.deepEqual(['a', 'b', 'c'].map(suffix => lots.filter(id => manifest.lanes[id].territory === `repayment-${suffix}`).length), [38, 38, 37]);
  for (const id of ['L0-M', 'L1-A', 'L1-F', 'L-SHIME', 'L3-0A', 'L3-0K', 'L3-0D', 'L3-I', 'L3-P', 'L3-Bnn', 'L4-auth', 'L4-role', 'L4-team', 'L4-organization', 'L4-membership', 'L4-I', 'L5-PRE-D', 'L5-PRE-V', 'L5-PRE-O', 'L5-I']) assert.ok(manifest.lanes[id], id);
});
test('AC2: 同じ領地の同時claimは一方だけ成功し異なるUUIDを混ぜない', async t => {
  let active = false; let arrivals = 0; let release; const barrier = new Promise(resolve => { release = resolve; }); const parents = [];
  const runner = async (repo, args, input) => {
    if (active && args.includes('commit-tree')) parents.push(args[args.indexOf('-p') + 1]);
    if (active && args[0] === 'push') { if (++arrivals === 2) release(); await barrier; }
    return git(repo, args, input);
  };
  const { c, repo } = await fixture(t, runner); active = true; const second = new Coordinator(repo, manifest, runner);
  const results = await Promise.allSettled([c.mutate('claim', { territory: 'repayment-a', owner }), second.mutate('claim', { territory: 'repayment-a', owner: other })]);
  assert.equal(parents.length, 2); assert.equal(parents[0], parents[1]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.ok([owner, other].includes((await c.status()).state.territories['repayment-a'].owner));
});
test('AC2: 別領地の同時claimは最新stateへ再適用して両方残す', async t => {
  const { c, repo } = await fixture(t);
  await Promise.all([c.mutate('claim', { territory: 'repayment-a', owner }), new Coordinator(repo, manifest).mutate('claim', { territory: 'repayment-b', owner: other })]);
  const { state } = await c.status(); assert.equal(state.territories['repayment-a'].owner, owner); assert.equal(state.territories['repayment-b'].owner, other);
});
test('AC5/性能: 実Git競合が続いてもCAS再試行は4回で停止する', async t => {
  let active = false; let attempts = 0; let normal;
  const runner = async (repo, args, input) => {
    if (active && args[0] === 'push') {
      attempts++;
      const candidate = args[2].split(':')[0];
      const parent = await git(repo, ['rev-parse', `${candidate}^`]);
      const competing = JSON.parse(await git(repo, ['show', `${parent}:state.json`]));
      competing.operation = randomUUID();
      const competingHead = await normal.commit(competing, parent);
      await git(repo, ['push', 'origin', `${competingHead}:${REF}`]);
    }
    return git(repo, args, input);
  };
  const { c, repo } = await fixture(t, runner); normal = new Coordinator(repo, manifest); active = true;
  await assert.rejects(c.mutate('claim', { owner, territory: 'closure' }), /CAS競合が継続/);
  assert.equal(attempts, 4);
  assert.equal((await c.status()).state.territories.closure.owner, null);
});
test('AC3: 既存担当3件は領地claim・解放でも保持する', () => {
  let s = claim(initialState(manifest), 'foundation');
  assert.throws(() => update(s, 'L1-F', { state: 'working' }), /既存担当/);
  s = transition(s, manifest, 'release', { owner, territory: 'foundation', evidence, confirmedStopped: true });
  assert.ok(s.territories.foundation.note.includes(evidence));
  assert.ok(s.territories.foundation.note.includes(`${owner} → 未所有`));
  for (const id of ['L0-M', 'L1-A', 'L1-F']) assert.equal(s.lanes[id].hold.owner, `existing:${id}`);
  assert.deepEqual(s.lanes['L1-F'].held, manifest.lanes['L1-F'].resources);
});
test('AC4: 非owner・owner空・同UUID複数領地・TTL相当の無断引取を拒否', () => {
  const s = claim(initialState(manifest), 'common-core');
  assert.throws(() => transition(s, manifest, 'claim', { owner, territory: 'gradle' }), /複数領地/);
  assert.throws(() => transition(s, manifest, 'claim', { owner: other, territory: 'common-core' }), /取得済み/);
  assert.throws(() => transition(s, manifest, 'release', { owner: other, territory: 'common-core', evidence, confirmedStopped: true }), /ownerではない/);
  assert.throws(() => transition(s, manifest, 'claim', { owner: '', territory: 'gradle' }), /UUID/);
});
test('AC4: 明示handoff後は旧ownerを拒否し新ownerのみ再開できる', () => {
  const s = claim(initialState(manifest), 'common-core');
  assert.throws(() => transition(s, manifest, 'handoff', { owner, territory: 'common-core', to: other, evidence }), /停止/);
  const handed = transition(s, manifest, 'handoff', { owner, territory: 'common-core', to: other, evidence, confirmedStopped: true });
  assert.throws(() => transition(handed, manifest, 'update', { owner, territory: 'common-core', note: '旧owner' }), /ownerではない/);
  const resumed = transition(handed, manifest, 'update', { owner: other, territory: 'common-core', note: '引継書確認' });
  assert.ok(resumed.territories['common-core'].note.includes(`${owner} → ${other}`));
  assert.ok(resumed.territories['common-core'].note.includes(evidence));
  assert.ok(resumed.territories['common-core'].note.endsWith('\n引継書確認'));
});
test('AC4: 既存lane移管には保持者名・解放証拠・停止確認・取得UUIDの一致が必要', () => {
  const s = claim(initialState(manifest), 'common-core');
  const args = { owner, lane: 'L1-A', from: 'existing:L1-A', to: owner, evidence, confirmedStopped: true };
  assert.throws(() => transition(s, manifest, 'handoff', { ...args, from: 'other' }), /既存担当名/);
  assert.throws(() => transition(s, manifest, 'handoff', { ...args, evidence: '' }), /証拠/);
  assert.equal(transition(s, manifest, 'handoff', args).lanes['L1-A'].hold, null);
});
test('AC5: push成功後の応答喪失は実remote読戻しでcommit到達を確認する', async t => {
  let fail = false;
  const runner = async (repo, args, input) => { const result = await git(repo, args, input); if (fail && args[0] === 'push') { fail = false; throw new Error('応答喪失'); } return result; };
  const { c } = await fixture(t, runner); fail = true;
  const result = await c.mutate('claim', { owner, territory: 'repayment-c' });
  assert.equal(result.recovered, true); assert.equal(result.state.territories['repayment-c'].owner, owner);
});
test('AC5: push前障害は成功扱いせずremote状態を変えない', async t => {
  let fail = false;
  const runner = async (repo, args, input) => { if (fail && args[0] === 'push') throw new Error('送信不能'); return git(repo, args, input); };
  const { c } = await fixture(t, runner); const before = (await c.status()).head; fail = true;
  await assert.rejects(c.mutate('claim', { owner, territory: 'repayment-c' }), /送信不能/);
  assert.equal((await c.status()).head, before);
});
test('AC6: 壊れたstate・manifest不一致・欠落・未知レーンを空台帳へ丸めない', async t => {
  const { c, repo } = await fixture(t); const current = await c.read();
  assert.throws(() => validateState({ ...current.state, schema: 2 }, manifest), /一致/);
  assert.throws(() => validateState({ ...current.state, manifest: 'bad' }, manifest), /一致/);
  const missing = structuredClone(current.state); delete missing.lanes['L0-M']; assert.throws(() => validateState(missing, manifest), /構造/);
  const corrupt = { ...current.state, lanes: null }; const sha = await c.commit(corrupt, current.head); await git(repo, ['push', 'origin', `${sha}:${REF}`]);
  await assert.rejects(c.mutate('claim', { owner, territory: 'foundation' }), /構造/);
  assert.equal(await c.remoteHead(), sha);
});
test('AC6: 初期化を繰り返して保持・履歴を上書きしない', async t => {
  const { c } = await fixture(t); const head = (await c.read()).head;
  await assert.rejects(c.init(), /再初期化禁止/); assert.equal((await c.read()).head, head);
});
test('AC7: 未解消依存ではworking・done拒否、読取準備は区別する', () => {
  const s = claim(initialState(manifest), 'gradle');
  assert.throws(() => update(s, 'L1-B', { state: 'working' }), /依存:L0-M/);
  assert.throws(() => update(s, 'L1-B', { state: 'done', evidence }), /依存:L0-M/);
  assert.equal(update(s, 'L1-B', { state: 'planning', note: '読取のみ' }).lanes['L1-B'].state, 'planning');
});
test('AC7: 波gateは自ロットのみ、後続波未確定でも着手できる', () => {
  let s = readyState(['L2-actionmemo', 'L2-activity']); s.gates['wave-actionmemo'] = ''; s.gates['wave-activity'] = '';
  s = claim(s, 'repayment-a'); assert.throws(() => update(s, 'L2-actionmemo', { state: 'working' }), /gate:wave-actionmemo/);
  s = transition(s, manifest, 'update', { owner, gate: 'wave-actionmemo', evidence });
  assert.equal(update(s, 'L2-actionmemo', { state: 'working' }).lanes['L2-actionmemo'].state, 'working');
  assert.equal(s.gates['wave-activity'], '');
});
test('AC7: 他領地gateの無断解除と証拠なし完了を拒否する', () => {
  const s = claim(readyState(['L1-R']), 'common-core');
  assert.throws(() => transition(s, manifest, 'update', { owner, territory: 'common-core', gate: 'bundle-map', evidence }), /gate担当/);
  assert.throws(() => update(s, 'L1-R', { state: 'done' }), /証拠/);
  assert.equal(update(s, 'L1-R', { state: 'done', evidence }).lanes['L1-R'].state, 'done');
});
test('AC7: 共有build予約は保持中断でも維持し明示解放後だけ別レーンへ渡る', () => {
  let s = readyState(['L1-M', 'L3-I']); s = claim(s, 'foundation'); s = claim(s, 'gradle', other);
  s = update(s, 'L1-M', { state: 'working' }); s = update(s, 'L1-M', { state: 'paused' });
  assert.throws(() => transition(s, manifest, 'update', { owner: other, lane: 'L3-I', state: 'working' }), /resource:? ?build/);
  s = transition(s, manifest, 'release', { owner, territory: 'foundation', evidence, confirmedStopped: true });
  assert.equal(transition(s, manifest, 'update', { owner: other, lane: 'L3-I', state: 'working' }).lanes['L3-I'].state, 'working');
});
test('AC7: L2別領地は並行working可能、共有verify編集だけを明示予約する', () => {
  let s = readyState(['L2-actionmemo', 'L2-activity']); s = claim(s, 'repayment-a'); s = claim(s, 'repayment-b', other);
  s = update(s, 'L2-actionmemo', { state: 'working' }); s = transition(s, manifest, 'update', { owner: other, lane: 'L2-activity', state: 'working' });
  s = update(s, 'L2-actionmemo', { resource: 'verify-list', acquire: true });
  assert.throws(() => transition(s, manifest, 'update', { owner: other, lane: 'L2-activity', resource: 'verify-list', acquire: true }), /共有resource/);
  s = update(s, 'L2-actionmemo', { resource: 'verify-list', free: true, evidence, confirmedStopped: true });
  assert.ok(s.lanes['L2-actionmemo'].note.includes('resource解放: verify-list'));
  assert.ok(s.lanes['L2-actionmemo'].note.includes(evidence));
  assert.deepEqual(transition(s, manifest, 'update', { owner: other, lane: 'L2-activity', resource: 'verify-list', acquire: true }).lanes['L2-activity'].held, ['verify-list']);
});
test('AC7: 締めの重み表とL3統合の重み表を同時予約できない', () => {
  let s = readyState(['L-SHIME', 'L3-I']); s = claim(s, 'closure'); s = claim(s, 'gradle', other);
  s = update(s, 'L-SHIME', { state: 'working' });
  assert.throws(() => transition(s, manifest, 'update', { owner: other, lane: 'L3-I', state: 'working' }), /resource:? ?weights/);
});
test('AC7: L5-PRE greenだけでは終結できず他working中もL5-Iを拒否する', () => {
  let s = readyState(['L5-I', 'L2-actionmemo']); s = claim(s, 'closure');
  assert.throws(() => update(s, 'L5-I', { state: 'working' }), /依存:L2-actionmemo/);
  s = readyState(['L5-I', 'L5-PRE-D']); s = claim(s, 'closure'); s = update(s, 'L5-PRE-D', { state: 'working' });
  assert.throws(() => update(s, 'L5-I', { state: 'working' }), /他レーン/);
});
test('AC8: 未解決依頼のnoteは次の進捗で上書きせず追記する', () => {
  let s = claim(initialState(manifest), 'common-core');
  s = update(s, 'L1-R', { note: '依頼先repayment-a / NameResolver / PR未作成 / API引渡し待ち' });
  s = update(s, 'L1-R', { note: '読取調査を継続' });
  assert.match(s.lanes['L1-R'].note, /API引渡し待ち\n読取調査を継続/);
});
test('AC9: dirty作業木・index・HEAD・調整追跡以外の全refを変更しない', async t => {
  const { c, repo } = await fixture(t);
  await writeFile(join(repo, 'sentinel.txt'), '手元資産'); await git(repo, ['add', 'sentinel.txt']);
  const otherRefs = async () => (await git(repo, ['show-ref'])).split('\n').filter(line => !line.endsWith(' refs/remotes/origin/campaign/cmp-261021-0017-coordination')).join('\n');
  const before = { refs: await otherRefs(), head: await git(repo, ['rev-parse', 'HEAD']), index: await readFile(join(repo, '.git/index')) };
  await c.mutate('claim', { owner, territory: 'common-core' });
  assert.equal(await otherRefs(), before.refs); assert.equal(await git(repo, ['rev-parse', 'HEAD']), before.head);
  assert.deepEqual(await readFile(join(repo, '.git/index')), before.index); assert.equal(await readFile(join(repo, 'sentinel.txt'), 'utf8'), '手元資産');
});
test('AC7: 終結開始後も逆向きの別レーンworkingを拒否する', () => {
  let s = claim(readyState(['L5-I', 'L5-PRE-D']), 'closure');
  s = update(s, 'L5-I', { state: 'working' });
  assert.throws(() => update(s, 'L5-PRE-D', { state: 'working' }), /終結中/);
});
test('AC7: 常設L3-Iと締めは完了後に再開でき各回証拠を保持する', () => {
  let s = claim(readyState(['L3-I']), 'gradle');
  s = update(s, 'L3-I', { state: 'working' }); s = update(s, 'L3-I', { state: 'done', evidence: '第1回PR・green' });
  s = transition(s, manifest, 'update', { owner, gate: 'base-integrated', evidence });
  s = update(s, 'L3-I', { state: 'working' }); s = update(s, 'L3-I', { state: 'done', evidence: '第2回PR・green' });
  assert.deepEqual(s.lanes['L3-I'].history, ['第1回PR・green', '第2回PR・green']);
  assert.equal(s.gates['base-integrated'], evidence);
  let closing = claim(readyState(['L-SHIME']), 'closure');
  closing = update(closing, 'L-SHIME', { state: 'done', evidence });
  assert.equal(update(closing, 'L-SHIME', { state: 'working' }).lanes['L-SHIME'].state, 'working');
});
test('AC7: aggregate束はplanningと出口doneだけで未確定workingを許さない', () => {
  const s = claim(readyState(['L3-Bnn']), 'gradle');
  assert.throws(() => update(s, 'L3-Bnn', { state: 'working' }), /aggregate/);
  assert.equal(update(s, 'L3-Bnn', { state: 'done', evidence }).lanes['L3-Bnn'].state, 'done');
});
test('AC6: workingの必須resourceが欠けたstateを拒否する', () => {
  let s = claim(readyState(['L3-I']), 'gradle'); s = update(s, 'L3-I', { state: 'working' });
  s.lanes['L3-I'].held = s.lanes['L3-I'].held.filter(r => r !== 'build');
  assert.throws(() => validateState(s, manifest), /必須resource欠損/);
});
test('AC5: 読戻し祖先照合の致命Gitエラーは競合扱いで再試行しない', async t => {
  let fail = false; let pushes = 0;
  const runner = async (repo, args, input) => {
    if (fail && args[0] === 'merge-base') { const error = new Error('Git照合不能'); error.code = 128; throw error; }
    const result = await git(repo, args, input);
    if (fail && args[0] === 'push') { pushes++; throw new Error('応答喪失'); } return result;
  };
  const { c } = await fixture(t, runner); fail = true;
  await assert.rejects(c.mutate('claim', { owner, territory: 'closure' }), /照合不能/);
  assert.equal(pushes, 1);
});
test('AC9: 実CLIが自動UUIDを発行しclaim/status/updateと引数拒否を行う', async t => {
  const { repo } = await fixture(t);
  const run = (...args) => exec(process.execPath, [join(repo, script), ...args], { cwd: repo, maxBuffer: 2000000 });
  const claimed = await run('claim', '--territory', 'closure'); const parsed = JSON.parse(claimed.stdout);
  assert.match(parsed.owner, /^[0-9a-f-]{36}$/); assert.ok(claimed.stderr.includes(parsed.owner));
  await run('update', '--owner', parsed.owner, '--territory', 'closure', '--note', '依頼を確認');
  const status = JSON.parse((await run('status')).stdout); assert.equal(status.territories.closure.note, '依頼を確認');
  assert.match(status.meaning, /入口の充足ではない/);
  await assert.rejects(run('claim', '--force'), error => error.code === 1 && /不明な引数/.test(error.stderr));
  await run('release', '--owner', parsed.owner, '--territory', 'closure', '--confirmed-stopped', '--evidence', 'UNIQUE-RELEASE-EVIDENCE');
  const released = JSON.parse((await run('status')).stdout);
  assert.equal(released.territories.closure.owner, null);
  assert.ok(released.territories.closure.note.includes('UNIQUE-RELEASE-EVIDENCE'));
  assert.ok(released.territories.closure.note.includes('依頼を確認'));
});

test('AC6: resourceがないworkingでも領地ownerが必要', () => {
  const s = readyState(['L2-actionmemo']); s.lanes['L2-actionmemo'].state = 'working';
  assert.throws(() => validateState(s, manifest), /無担当のworking/);
});
test('AC7: 親終結後は常設レーン再開と新claimを拒否する', () => {
  let s = claim(readyState([]), 'closure'); s = update(s, 'L5-I', { state: 'done', evidence });
  assert.throws(() => update(s, 'L-SHIME', { state: 'working' }), /終結/);
  assert.throws(() => transition(s, manifest, 'claim', { owner: other, territory: 'foundation' }), /終結済み/);
});

test('AC7: 通常の束未完了は拒否し裁可済みGradle撤退だけ終結依存を読み替える', () => {
  let s = claim(readyState(['L3-I', 'L3-Bnn', 'L5-I']), 'closure'); s = claim(s, 'gradle', other);
  assert.throws(() => update(s, 'L5-I', { state: 'working' }), /依存:L3/);
  assert.throws(() => transition(s, manifest, 'update', { owner, territory: 'closure', gate: 'gradle-withdrawn', evidence }), /gate担当/);
  assert.throws(() => transition(s, manifest, 'update', { owner: other, gate: 'gradle-withdrawn', evidence: '' }), /証拠/);
  s = transition(s, manifest, 'update', { owner: other, gate: 'gradle-withdrawn', evidence: '§14裁可 / 凍結空・全verify green / PR...' });
  assert.equal(update(s, 'L5-I', { state: 'working' }).lanes['L5-I'].state, 'working');
  s.lanes['L2-actionmemo'].state = 'todo';
  assert.throws(() => update(s, 'L5-I', { state: 'working' }), /依存:L2-actionmemo/);
});
