#!/usr/bin/env node
/** CMP-261021-0017 専用。調整枝以外のref・index・checkoutを変更しない。 */
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const REF = 'refs/heads/campaign/cmp-261021-0017-coordination';
export const MANIFEST = 'docs/development/boundary-coordination.json';
const SCRIPT = 'scripts/boundary-coordination.mjs';
const TERRITORIES = ['foundation', 'common-core', 'repayment-a', 'repayment-b', 'repayment-c', 'gradle', 'closure'];
const STATES = ['todo', 'planning', 'working', 'paused', 'blocked', 'done'];
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
const check = (ok, message) => { if (!ok) throw new Error(message); };
const text = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 8000;
const digest = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const keysEqual = (a, b) => JSON.stringify(Object.keys(a ?? {}).sort()) === JSON.stringify([...b].sort());

export function validateManifest(m) {
  check(keysEqual(m, ['schema', 'campaign', 'territories', 'resources', 'lanes', 'gates', 'repeatable']), 'manifestの構造が不正');
  check(m?.schema === 1 && m.campaign === 'CMP-261021-0017', 'manifestの版・戦役が不正');
  check(JSON.stringify(m.territories) === JSON.stringify(TERRITORIES), '7領地の一覧が不正');
  check(JSON.stringify(m.repeatable) === JSON.stringify(['L-SHIME', 'L3-I']), '常設レーンの定義が不正');
  check(m.lanes && Object.keys(m.lanes).length > 113 && m.gates && m.resources, 'manifestのレーン・gate・resourceが不正');
  check(Object.keys(m.lanes).filter(id => id.startsWith('L2-')).length === 113, 'L2は113ロットが必要');
  for (const [id, lane] of Object.entries(m.lanes)) {
    check(/^L[0-5]-[A-Za-z0-9-]+$/.test(id) || id === 'L-SHIME', `レーンIDが不正: ${id}`);
    check(['implementation', 'preparation', 'aggregate'].includes(lane.kind), `レーン種別が不正: ${id}`);
    check(TERRITORIES.includes(lane.territory), `領地が不正: ${id}`);
    for (const field of ['depends', 'gates', 'resources', 'optionalResources']) check(Array.isArray(lane[field]), `${id}.${field}が不正`);
    check(lane.depends.every(dep => m.lanes[dep] && dep !== id), `${id}の依存先が不正`);
    check(lane.gates.every(g => m.gates[g]), `${id}のgateが不正`);
    check([...lane.resources, ...lane.optionalResources].every(r => m.resources.includes(r)), `${id}のresourceが不正`);
    if (lane.done) check(text(lane.done), `${id}の完了証拠が不正`);
    if (lane.hold) check(text(lane.hold.owner) && text(lane.hold.evidence) && !lane.done, `${id}の既存担当が不正`);
  }
  for (const gate of Object.values(m.gates)) check(TERRITORIES.includes(gate.territory) && text(gate.condition), 'gate担当・条件が不正');
  return m;
}

export function initialState(m) {
  validateManifest(m);
  return {
    schema: 1, campaign: m.campaign, manifest: digest(m), operation: randomUUID(),
    territories: Object.fromEntries(TERRITORIES.map(id => [id, { owner: null, note: '' }])),
    lanes: Object.fromEntries(Object.entries(m.lanes).map(([id, lane]) => [id, {
      state: lane.done ? 'done' : 'todo', evidence: lane.done ?? '', note: '',
      history: lane.done ? [lane.done] : [], hold: lane.hold ? structuredClone(lane.hold) : null, held: lane.hold ? [...lane.resources] : [],
    }])),
    gates: Object.fromEntries(Object.keys(m.gates).map(id => [id, ''])),
  };
}

export function validateState(s, m) {
  check(keysEqual(s, ['schema', 'campaign', 'manifest', 'operation', 'territories', 'lanes', 'gates']), 'stateの構造が不正');
  check(s?.schema === 1 && s.campaign === m.campaign && s.manifest === digest(m), 'stateの版・manifestが一致しない。初期化で上書きしないこと');
  check(uuid(s.operation) && keysEqual(s.territories, TERRITORIES) && keysEqual(s.lanes, Object.keys(m.lanes)) && keysEqual(s.gates, Object.keys(m.gates)), 'stateの構造が不正');
  const owners = new Set();
  for (const territory of Object.values(s.territories)) {
    check(territory && (territory.owner === null || uuid(territory.owner)) && typeof territory.note === 'string', 'ownerまたはnoteが不正');
    if (territory.owner) { check(!owners.has(territory.owner), '同じownerが複数領地を取得している'); owners.add(territory.owner); }
  }
  const held = new Set();
  for (const [id, lane] of Object.entries(s.lanes)) {
    const declaration = m.lanes[id];
    check(lane?.state !== 'working' || s.territories[declaration.territory].owner, `無担当のworking: ${id}`);
    check(lane && STATES.includes(lane.state) && typeof lane.note === 'string' && typeof lane.evidence === 'string' && Array.isArray(lane.held) && Array.isArray(lane.history) && lane.history.every(text), `stateレーンが不正: ${id}`);
    check(lane.state !== 'done' || (text(lane.evidence) && !lane.hold && lane.held.length === 0), `完了証拠・解放が不正: ${id}`);
    if (lane.state === 'working' || lane.hold) check(declaration.resources.every(r => lane.held.includes(r)), `必須resource欠損: ${id}`);
    if (lane.hold) check(declaration.hold && JSON.stringify(lane.hold) === JSON.stringify(declaration.hold), `既存担当情報が不正: ${id}`);
    check(lane.hold || lane.held.length === 0 || s.territories[declaration.territory].owner, `無担当のresource保持: ${id}`);
    for (const resource of lane.held) {
      check([...declaration.resources, ...declaration.optionalResources].includes(resource) && !held.has(resource), `resourceが不正・二重取得: ${resource}`);
      held.add(resource);
    }
  }
  for (const evidence of Object.values(s.gates)) check(typeof evidence === 'string', 'gate証拠が不正');
  return s;
}

function blockers(s, m, id) {
  const declaration = m.lanes[id];
  const dependencies = id === 'L5-I' && text(s.gates['gradle-withdrawn']) ? declaration.depends.filter(dep => !['L3-I', 'L3-Bnn'].includes(dep)) : declaration.depends;
  return [
    ...(id !== 'L5-I' && ['working', 'done'].includes(s.lanes['L5-I'].state) ? ['L5-I終結中・終結済み'] : []),
    ...(id === 'L5-I' && Object.entries(s.lanes).some(([other, lane]) => other !== id && (lane.state === 'working' || lane.held.length > 0)) ? ['他レーン停止・解放待ち'] : []),
    ...declaration.resources.filter(resource => Object.entries(s.lanes).some(([other, lane]) => other !== id && lane.held.includes(resource))).map(resource => '共有resource:' + resource),
    ...(s.lanes[id].hold ? ['既存担当保持'] : []),
    ...dependencies.filter(dep => s.lanes[dep].state !== 'done').map(dep => `依存:${dep}`),
    ...declaration.gates.filter(gate => !text(s.gates[gate])).map(gate => `gate:${gate}`),
  ];
}
function reserve(s, m, id, resources) {
  for (const resource of resources) {
    const holder = Object.entries(s.lanes).find(([other, lane]) => other !== id && lane.held.includes(resource));
    check(!holder, `共有resource ${resource} は ${holder?.[0]} が保持中`);
    if (!s.lanes[id].held.includes(resource)) s.lanes[id].held.push(resource);
  }
}
function evidence(o) { check(text(o.evidence), '証拠が必要'); }
function stopped(o) { evidence(o); check(o.confirmedStopped === true, '足軽停止と既存担当の明示解放確認 --confirmed-stopped が必要'); }

export function transition(current, m, action, o) {
  validateState(current, m);
  const s = structuredClone(current);
  check(uuid(o.owner), '--owner にセッション固有UUIDv4が必要');
  const territory = o.territory ?? (o.lane && m.lanes[o.lane]?.territory) ?? (o.gate && m.gates[o.gate]?.territory);
  check(TERRITORIES.includes(territory), '領地・レーン・gateを指定すること');
  const domain = s.territories[territory];
  check(!(o.gate && (o.lane || o.resource || o.state)) && !(o.resource && o.state), 'gate/resource/stateを混在させないこと');
  if (action === 'claim') {
    check(s.lanes['L5-I'].state !== 'done', '親戦役は終結済み。新claim禁止');
    check(domain.owner === null || domain.owner === o.owner, `領地 ${territory} は取得済み`);
    check(!Object.entries(s.territories).some(([id, value]) => id !== territory && value.owner === o.owner), '同じownerで複数領地は取得できない');
    domain.owner = o.owner;
  } else {
    check(domain.owner === o.owner, `領地 ${territory} のownerではない`);
    if (action === 'release') {
      stopped(o);
      domain.note += (domain.note ? '\n' : '') + `解放: ${o.owner} → 未所有 / 停止・解放証拠: ${o.evidence}`;
      for (const [id, declaration] of Object.entries(m.lanes)) if (declaration.territory === territory && !s.lanes[id].hold) {
        s.lanes[id].held = [];
        if (s.lanes[id].state === 'working') s.lanes[id].state = 'paused';
      }
      domain.owner = null;
    } else if (action === 'handoff') {
      stopped(o); check(uuid(o.to), '引継ぎ先UUIDが必要');
      if (o.lane) {
        check(m.lanes[o.lane]?.territory === territory, '担当外レーン');
        const lane = s.lanes[o.lane];
        check(lane.hold && lane.hold.owner === o.from && o.to === o.owner, '既存担当名と取得ownerへの明示移管が必要');
        lane.hold = null; lane.evidence = o.evidence; lane.state = 'paused';
      } else {
        check(o.to !== o.owner && !Object.values(s.territories).some(value => value.owner === o.to), '引継ぎ先は別セッションの未取得UUIDにすること');
        domain.note += (domain.note ? '\n' : '') + `引継ぎ: ${o.owner} → ${o.to} / 停止・引継ぎ証拠: ${o.evidence}`;
        domain.owner = o.to;
        for (const [id, declaration] of Object.entries(m.lanes)) if (declaration.territory === territory && s.lanes[id].state === 'working') s.lanes[id].state = 'paused';
      }
    } else if (action === 'update') {
      if (o.gate) {
        check(m.gates[o.gate]?.territory === territory, 'gate担当ではない'); evidence(o); check((m.gates[o.gate].depends ?? []).every(id => s.lanes[id].state === 'done'), 'gateの依存が未完了'); s.gates[o.gate] = o.evidence;
      } else if (o.lane) {
        check(m.lanes[o.lane]?.territory === territory, '担当外レーン');
        const lane = s.lanes[o.lane];
        check(!lane.hold, '既存担当保持は明示handoffまで変更できない');
        check(lane.state !== 'done' || m.repeatable.includes(o.lane), '完了レーンは再着手できない');
        if (o.resource) {
          check(m.lanes[o.lane].optionalResources.includes(o.resource), 'このレーンで任意予約できないresource');
          check(o.acquire !== o.free && (o.acquire === true || o.free === true), '--acquire または --free が必要');
          if (o.acquire) { check(lane.state === 'working' && blockers(s, m, o.lane).length === 0, '共有編集は入口充足・working後に予約'); reserve(s, m, o.lane, [o.resource]); }
          else {
            stopped(o);
            lane.note += (lane.note ? '\n' : '') + `resource解放: ${o.resource} / owner: ${o.owner} / 停止・引渡し証拠: ${o.evidence}`;
            lane.held = lane.held.filter(r => r !== o.resource);
          }
        } else if (o.state) {
          check(m.lanes[o.lane].kind !== 'aggregate' || ['planning', 'done'].includes(o.state), 'aggregateはplanningで準備し全出口証拠後doneのみ');
          check(STATES.includes(o.state) && o.state !== 'todo', '更新stateが不正');
          if (['working', 'done'].includes(o.state)) {
            if (o.lane !== 'L5-I') check(s.lanes['L5-I'].state !== 'working' && s.lanes['L5-I'].held.length === 0, 'L5-I終結中は他レーンを着手できない');
            const reasons = blockers(s, m, o.lane); check(reasons.length === 0, `未解消の入口: ${reasons.join(', ')}`);
            if (o.lane === 'L5-I') check(!Object.entries(s.lanes).some(([id, other]) => id !== 'L5-I' && (other.state === 'working' || other.held.length > 0)), '終結前に他レーンを停止・解放すること');
            reserve(s, m, o.lane, m.lanes[o.lane].resources);
          }
          if (o.state === 'done') { evidence(o); lane.evidence = o.evidence; lane.history.push(o.evidence); lane.held = []; }
          lane.state = o.state;
        } else check(text(o.note), '更新内容がない');
        if (o.note !== undefined) { check(text(o.note), 'noteが不正'); lane.note += (lane.note ? '\n' : '') + o.note; }
      } else check(text(o.note), '領地noteが必要');
    } else throw new Error(`不明な操作: ${action}`);
  }
  if (o.note !== undefined) { check(text(o.note), 'noteが不正'); domain.note += (domain.note ? '\n' : '') + o.note; }
  s.operation = o.operation ?? randomUUID();
  return validateState(s, m);
}

export async function git(repo, args, input) {
  return new Promise((success, failure) => {
    const child = spawn('git', args, { cwd: repo, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let out = ''; let err = ''; child.stdout.setEncoding('utf8'); child.stdout.on('data', chunk => { out += chunk; });
    child.stderr.setEncoding('utf8'); child.stderr.on('data', chunk => { err = (err + chunk).slice(-4000); });
    child.on('error', () => failure(new Error('Gitを起動できない')));
    child.on('close', code => {
      if (code === 0) return success(out.trim());
      const reason = err.replace(/(?:https?:\/\/|ssh:\/\/|git@)[^\s]+/gi, '[remote]').replace(/(?:token|password|authorization|credential)[^\r\n]*/gi, '[資格情報関連の診断を省略]').trim().slice(-600);
      const error = new Error(`Git ${args[0]} が失敗 (exit ${code}): ${reason}`); error.code = code; failure(error);
    });
    child.stdin.on('error', () => {}); child.stdin.end(input);
  });
}

export class Coordinator {
  constructor(repo, manifest, runner = git) { this.repo = resolve(repo); this.manifest = validateManifest(manifest); this.git = (args, input) => runner(this.repo, args, input); }
  async remoteHead(ref = REF) {
    const output = await this.git(['ls-remote', '--refs', 'origin', ref]);
    if (!output) return null;
    const lines = output.split(/\r?\n/); check(lines.length === 1 && /^[0-9a-f]{40,64}\s/.test(lines[0]) && lines[0].split(/\s+/)[1] === ref, 'remote ref応答が不正');
    return lines[0].split(/\s+/)[0];
  }
  async fetch(sha) { await this.git(['fetch', '--no-write-fetch-head', '--no-tags', '--refmap=', 'origin', sha]); }
  async read() {
    const head = await this.remoteHead(); check(head, '調整台帳が未初期化。main着地後のinitが必要'); await this.fetch(head);
    const files = await this.git(['ls-tree', '--name-only', head]); check(files === 'state.json', '調整枝はstate.jsonだけであること');
    const state = validateState(JSON.parse(await this.git(['show', `${head}:state.json`])), this.manifest);
    return { head, state };
  }
  async status() {
    const result = await this.read();
    return { ...result, meaning: '領地取得はレーン入口の充足ではない。planningは読取準備のみ。', readiness: Object.fromEntries(Object.keys(this.manifest.lanes).map(id => [id, blockers(result.state, this.manifest, id)])) };
  }
  async commit(state, parent) {
    const blob = await this.git(['hash-object', '-w', '--stdin'], JSON.stringify(state, null, 2) + '\n');
    const tree = await this.git(['mktree'], `100644 blob ${blob}\tstate.json\n`);
    return this.git(['-c', 'commit.gpgsign=false', 'commit-tree', tree, ...(parent ? ['-p', parent] : []), '-m', `領地調整: ${state.operation}`]);
  }
  async init() {
    check(!(await this.remoteHead()), '調整台帳は既に存在する。再初期化禁止');
    const main = await this.remoteHead('refs/heads/main'); check(main, 'origin/mainがない'); await this.fetch(main);
    const upstream = JSON.parse(await this.git(['show', `${main}:${MANIFEST}`])); check(digest(upstream) === digest(this.manifest), 'manifestがorigin/main未着地または不一致');
    const script = await this.git(['show', `${main}:${SCRIPT}`]);
    const local = (await readFile(resolve(this.repo, SCRIPT), 'utf8')).replace(/\r\n/g, '\n').trim(); check(script.replace(/\r\n/g, '\n') === local, 'CLIがorigin/main未着地または不一致');
    const state = initialState(this.manifest); const candidate = await this.commit(state, null);
    try { await this.git(['push', 'origin', `${candidate}:${REF}`]); }
    catch (error) { const actual = await this.read(); check(actual.state.manifest === state.manifest, error.message); return actual; }
    return this.read();
  }
  async mutate(action, options) {
    const operation = randomUUID();
    for (let attempt = 0; attempt < 4; attempt++) {
      const base = await this.read();
      const state = transition(base.state, this.manifest, action, { ...options, operation });
      const candidate = await this.commit(state, base.head);
      try { await this.git(['push', 'origin', `${candidate}:${REF}`]); }
      catch (error) {
        const actual = await this.read();
        try { await this.git(['merge-base', '--is-ancestor', candidate, actual.head]); return { ...actual, recovered: true }; }
        catch (ancestryError) { if (ancestryError.code !== 1) throw ancestryError; if (actual.head === base.head) throw error; }
        continue;
      }
      return this.read();
    }
    throw new Error('CAS競合が継続。statusを再確認して再実行すること');
  }
}

function summary(result, m, territory) {
  const { state, head } = result;
  const territories = Object.fromEntries(TERRITORIES.map(id => {
    const lanes = Object.keys(m.lanes).filter(lane => m.lanes[lane].territory === id);
    return [id, { owner: state.territories[id].owner, ready: lanes.filter(lane => state.lanes[lane].state !== 'done' && blockers(state, m, lane).length === 0).length,
      done: lanes.filter(lane => state.lanes[lane].state === 'done').length, total: lanes.length,
      note: state.territories[id].note, requests: lanes.filter(lane => state.lanes[lane].note).map(lane => ({ lane, note: state.lanes[lane].note })) }];
  }));
  const output = { head, closed: state.lanes['L5-I'].state === 'done', meaning: '領地取得はレーン入口の充足ではない。planningは読取準備のみ。', territories,
    holds: Object.fromEntries(Object.entries(state.lanes).filter(([, lane]) => lane.hold).map(([id, lane]) => [id, lane.hold])),
    next: 'status --territory <自領> でレーン入口・依頼・共有予約を確認' };
  if (territory) {
    check(TERRITORIES.includes(territory), '領地が不正');
    output.lanes = Object.fromEntries(Object.entries(state.lanes).filter(([id]) => m.lanes[id].territory === territory).map(([id, lane]) => [id, { ...lane, kind: m.lanes[id].kind, blockers: blockers(state, m, id) }]));
    output.gates = Object.fromEntries(Object.entries(m.gates).filter(([, gate]) => gate.territory === territory).map(([id, gate]) => [id, { condition: gate.condition, evidence: state.gates[id] }]));
    output.resources = Object.fromEntries(Object.entries(state.lanes).filter(([, lane]) => lane.held.length).map(([id, lane]) => [id, lane.held]));
  }
  return output;
}

async function main() {
  const [action, ...args] = process.argv.slice(2);
  check(['status', 'claim', 'update', 'release', 'handoff', 'init'].includes(action), 'status/claim/update/release/handoff/init を指定');
  const options = {}; const booleans = ['confirmed-stopped', 'acquire', 'free'];
  const allowed = [...booleans, 'owner', 'territory', 'lane', 'gate', 'state', 'resource', 'note', 'evidence', 'from', 'to', 'repo'];
  for (let i = 0; i < args.length; i++) {
    const key = args[i].replace(/^--/, ''); check(args[i].startsWith('--') && allowed.includes(key), `不明な引数: ${args[i]}`);
    const field = key.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()); check(options[field] === undefined, `重複引数: ${key}`);
    if (booleans.includes(key)) options[field] = true;
    else { check(text(args[i + 1]) && !args[i + 1].startsWith('--'), `引数不足: ${key}`); options[field] = args[++i]; }
  }
  if (action === 'claim' && !options.owner) options.owner = randomUUID();
  if (options.owner) console.error('セッションowner: ' + options.owner);
  const repo = resolve(options.repo ?? process.cwd());
  const m = JSON.parse(await readFile(resolve(repo, MANIFEST), 'utf8'));
  const coordinator = new Coordinator(repo, m);
  const result = action === 'status' ? await coordinator.status() : action === 'init' ? await coordinator.init() : await coordinator.mutate(action, options);
  const output = action === 'status' || action === 'init' ? summary(result, m, options.territory) : { owner: options.owner, action, territory: options.territory ?? m.lanes[options.lane]?.territory ?? m.gates[options.gate]?.territory, lane: options.lane, gate: options.gate, state: options.lane ? result.state.lanes[options.lane].state : undefined, head: result.head, recovered: result.recovered ?? false, meaning: '領地取得はレーン入口の充足ではない。', next: 'status --territory <自領> で最新の入口と依頼を確認' };
  console.log(JSON.stringify(output, null, 2));
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(error.message); process.exitCode = 1; });
