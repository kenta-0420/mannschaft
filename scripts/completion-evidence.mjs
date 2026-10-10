#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MARKDOWN_LINK_PATTERN = /\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)/g;

function splitRow(line) {
  const result = [];
  let current = '';
  let escaped = false;
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];
    if (escaped) {
      current += char;
      escaped = false;
    } else if (char === '\\') {
      if (line[index + 1] === '|') escaped = true;
      else current += char;
    } else if (char === '|') {
      result.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  if (escaped) current += '\\';
  result.push(current.trim());
  if (result[0] === '') result.shift();
  if (result.at(-1) === '') result.pop();
  return result;
}

export function parseTaskList(markdown) {
  if (typeof markdown !== 'string') throw new Error('docs/task-list.md が文字列ではありません');
  const rows = [];
  const byId = new Map();
  for (const [lineNumber, line] of markdown.split(/\r?\n/).entries()) {
    if (!/^\s*\|/.test(line)) continue;
    const cells = splitRow(line);
    const id = cells[0];
    if (!/^CMP-(?:\d{6}-\d{4}|\d{3})$/.test(id ?? '')) continue;
    if (cells.length !== 7) throw new Error(`${id} の台帳行は7列ではありません（${lineNumber + 1}行目: ${cells.length}列）`);
    if (byId.has(id)) throw new Error(`docs/task-list.md に ${id} の重複があります`);
    const row = { id, cells, state: cells[2].replaceAll('**', '').trim(), evidence: cells[5], lineNumber };
    byId.set(id, row);
    rows.push(row);
  }
  return { rows, byId };
}

function isComplete(state) {
  return /^完了(?:$|[\s（(])/.test(state);
}

function evidenceJsonLink(cell, cmpId) {
  const matches = [...cell.matchAll(MARKDOWN_LINK_PATTERN)].flatMap(([, destination]) => {
    const match = /^(?:\.\/)?(?:evidence|docs\/evidence)\/(CMP-(?:\d{6}-\d{4}|\d{3}))([^/]*)\.json$/.exec(destination);
    if (!match) return [];
    return [{ path: `docs/evidence/${match[1]}.json`, cmp: match[1], suffix: match[2] }];
  });
  if (matches.length > 1) throw new Error(`${cmpId} の証拠欄に証拠JSONリンクが複数あります`);
  if (matches.length === 1 && (matches[0].path !== `docs/evidence/${cmpId}.json` || matches[0].cmp !== cmpId || matches[0].suffix)) {
    throw new Error(`${cmpId} の証拠JSONリンクがCMP IDと一致しません`);
  }
  return matches[0]?.path ?? null;
}

const TOOLING_PATH = /^(?:scripts\/|\.github\/|\.claude\/|docs\/|CLAUDE\.md$|AGENTS\.md$|README\.md$|TEST_CONVENTION\.md$)/;

export function inferImplementationScope(files) {
  if (!Array.isArray(files) || files.length === 0) throw new Error('実装PRの変更ファイル一覧を取得できません');
  return files.every(file => TOOLING_PATH.test(typeof file === 'string' ? file : file.filename ?? '')) ? 'tooling' : 'app';
}

/** 完了遷移または変更された完了証拠だけを返す。未変更の過去証拠はAPI取得対象にしない。 */
export function selectEvidencePaths(baseText, headText, changedEvidencePaths = []) {
  const base = parseTaskList(baseText);
  const head = parseTaskList(headText);
  const changed = changedEvidencePaths instanceof Set ? changedEvidencePaths : new Set(changedEvidencePaths);
  const candidates = new Set();
  for (const [id, oldRow] of base.byId) {
    if (!isComplete(oldRow.state)) continue;
    const newRow = head.byId.get(id);
    if (!newRow) throw new Error(`${id}: 過去完了行・証拠を削除できません`);
    const oldLink = evidenceJsonLink(oldRow.evidence, id);
    const newLink = evidenceJsonLink(newRow.evidence, id);
    if (oldLink && !newLink) throw new Error(`${id}: 過去完了の証拠リンクを削除できません`);
    if (oldLink && newLink !== oldLink) throw new Error(`${id}: 過去完了の証拠リンクを差替えできません`);
    if (!oldLink && newLink) candidates.add(newLink);
    if (oldLink && changed.has(oldLink)) candidates.add(oldLink);
  }
  for (const [id, newRow] of head.byId) {
    if (!isComplete(newRow.state)) continue;
    const oldRow = base.byId.get(id);
    if (!oldRow || !isComplete(oldRow.state)) {
      const link = evidenceJsonLink(newRow.evidence, id);
      if (link) candidates.add(link);
    }
  }
  return [...candidates];
}

function evidenceReferences(evidence) {
  const refs = [];
  for (const ac of evidence.acceptanceCriteria ?? []) {
    for (const item of ac.tests ?? []) refs.push(item.evidence);
  }
  for (const stage of Object.values(evidence.stages ?? {})) {
    if (stage?.status === 'passed') refs.push(stage.evidence);
  }
  for (const regression of evidence.regressions ?? []) refs.push(regression.redEvidence, regression.greenEvidence);
  return refs;
}

function validateReference(reference, cmpId, options) {
  if (typeof reference !== 'string' || !reference.trim()) throw new Error(`${cmpId}: 証拠参照が空です`);
  if (/^https:\/\//i.test(reference)) {
    let url;
    try { url = new URL(reference); } catch { throw new Error(`${cmpId}: 証拠URLが不正です`); }
    if (url.hostname !== 'github.com' || url.pathname.split('/').slice(1, 3).join('/') !== options.repository) {
      throw new Error(`${cmpId}: 証拠URLは同一GitHubリポジトリを指す必要があります`);
    }
    return;
  }
  if (/^[a-z][a-z\d+.-]*:/i.test(reference) || reference.startsWith('/') || reference.startsWith('\\') || reference.includes('\\') || /^[A-Za-z]:/.test(reference)) {
    throw new Error(`${cmpId}: 証拠パスに絶対パス・URL schemeは使えません`);
  }
  const normalized = path.posix.normalize(reference);
  if (normalized === '..' || normalized.startsWith('../') || normalized !== reference) {
    throw new Error(`${cmpId}: 証拠パスにパストラバーサルまたは正規化差があります`);
  }
  if (options.fileExists && !options.fileExists(normalized)) throw new Error(`${cmpId}: 証拠ファイルがheadに存在しません: ${normalized}`);
}

export function validateEvidence(evidence, cmpId, options = {}) {
  if (!evidence || typeof evidence !== 'object' || Array.isArray(evidence)) throw new Error(`${cmpId}: 証拠JSONが空または不正です`);
  if (evidence.schemaVersion !== 1) throw new Error(`${cmpId}: schemaVersion は1でなければなりません`);
  if (evidence.cmpId !== cmpId) throw new Error(`${cmpId}: cmpId が一致しません`);
  if (!['app', 'tooling'].includes(evidence.scope)) throw new Error(`${cmpId}: scope はapp/toolingのいずれかが必要です`);
  if (!evidence.implementation || !Number.isSafeInteger(evidence.implementation.pr) || evidence.implementation.pr < 1 || !/^[0-9a-f]{40}$/i.test(evidence.implementation.sha ?? '')) {
    throw new Error(`${cmpId}: implementation PR/head SHAが不正です`);
  }
  if (!Array.isArray(evidence.acceptanceCriteria) || evidence.acceptanceCriteria.length === 0) throw new Error(`${cmpId}: acceptanceCriteriaは空にできません`);
  const acIds = new Set();
  for (const ac of evidence.acceptanceCriteria) {
    if (!ac || typeof ac.id !== 'string' || !ac.id.trim() || acIds.has(ac.id)) throw new Error(`${cmpId}: AC IDが空または重複しています`);
    acIds.add(ac.id);
    if (typeof ac.description !== 'string' || !ac.description.trim() || !Array.isArray(ac.tests) || ac.tests.length === 0) throw new Error(`${cmpId}: 各ACに説明とテスト証拠が必要です`);
    for (const item of ac.tests) {
      if (typeof item?.name !== 'string' || !item.name.trim()) throw new Error(`${cmpId}: テスト名が空です`);
      validateReference(item.evidence, cmpId, options);
    }
  }
  if (!Array.isArray(evidence.ci) || evidence.ci.length === 0) throw new Error(`${cmpId}: CI runが必要です`);
  for (const ci of evidence.ci) {
    if (!Number.isSafeInteger(ci?.runId) || ci.runId <= 0) throw new Error(`${cmpId}: CI runIdが不正です`);
  }
  const stages = evidence.stages;
  if (!stages || typeof stages !== 'object' || !['review', 'real', 'exploratory'].every(key => stages[key])) throw new Error(`${cmpId}: review/real/exploratoryのstageが必要です`);
  if (stages.review.status !== 'passed') throw new Error(`${cmpId}: reviewはpassed必須です`);
  for (const [name, stage] of Object.entries(stages)) {
    if (!stage || typeof stage !== 'object' || !['passed', 'not-applicable'].includes(stage.status)) throw new Error(`${cmpId}: ${name} statusはpassedまたはnot-applicableが必要です`);
    if (stage.status === 'passed') validateReference(stage.evidence, cmpId, options);
    if (stage.status === 'not-applicable') {
      if (typeof stage.reason !== 'string' || !stage.reason.trim()) throw new Error(`${cmpId}: ${name} not-applicableにreasonが必要です`);
      if ((name === 'real' || name === 'exploratory') && (evidence.scope !== 'tooling' || stage.scope !== 'tooling')) throw new Error(`${cmpId}: appでは実機・探索をnot-applicableにできません`);
    }
  }
  if (evidence.scope === 'app' && (stages.real.status !== 'passed' || stages.exploratory.status !== 'passed')) throw new Error(`${cmpId}: appはrealとexploratoryがpassed必須です`);
  if (!Array.isArray(evidence.regressions)) throw new Error(`${cmpId}: regressionsは配列が必要です`);
  for (const regression of evidence.regressions) {
    if (![regression?.description, regression?.test].every(value => typeof value === 'string' && value.trim())) throw new Error(`${cmpId}: 回帰証跡にdescription/testが必要です`);
    validateReference(regression.redEvidence, cmpId, options);
    validateReference(regression.greenEvidence, cmpId, options);
  }
  const implementationMeta = options.implementations instanceof Map
    ? options.implementations.get(evidence.implementation.pr)
    : options.implementations?.[evidence.implementation.pr] ?? options.implementation;
  if (options.implementations && !implementationMeta) throw new Error(`${cmpId}: 実装PR ${evidence.implementation.pr} のGitHub照合情報がありません`);
  if (implementationMeta) {
    const implementation = implementationMeta;
    if (implementation.repository !== options.repository || implementation.merged !== true) throw new Error(`${cmpId}: 実装PRが同一repoでmergedではありません`);
    if (implementation.sha !== evidence.implementation.sha) throw new Error(`${cmpId}: 実装PR head SHAが証拠JSONと一致しません`);
    const acceptableChecks = new Set(['success', 'skipped', 'neutral']);
    if (!Array.isArray(implementation.checks) || implementation.checks.length === 0 || !implementation.checks.includes('success') || implementation.checks.some(conclusion => !acceptableChecks.has(conclusion))) {
      throw new Error(`${cmpId}: 実装PR head SHAに失敗・未完了checkがあるか成功checkがありません`);
    }
    if (inferImplementationScope(implementation.files) !== evidence.scope) throw new Error(`${cmpId}: scopeが実装PRの変更ファイル範囲と一致しません`);
  }
  if (options.runs) {
    for (const ci of evidence.ci) {
      const run = options.runs instanceof Map ? options.runs.get(ci.runId) : options.runs[ci.runId];
      if (!run || run.repository !== options.repository || run.sha !== evidence.implementation.sha || run.conclusion !== 'success') {
        throw new Error(`${cmpId}: CI run ${ci.runId} が同一repo・実装SHAのsuccessではありません`);
      }
    }
  }
  return true;
}

export function validateCompletionChanges(baseText, headText, evidenceByPath = {}, options = {}) {
  const base = parseTaskList(baseText);
  const head = parseTaskList(headText);
  const validateRowEvidence = (id, row) => {
    const evidencePath = evidenceJsonLink(row.evidence, id);
    if (!evidencePath) throw new Error(`${id}: 完了行に証拠JSONリンクが必要です`);
    const evidence = evidenceByPath[evidencePath];
    validateEvidence(evidence, id, options);
  };
  for (const [id, oldRow] of base.byId) {
    if (!isComplete(oldRow.state)) continue;
    const newRow = head.byId.get(id);
    if (!newRow) throw new Error(`${id}: 過去完了行・証拠を削除できません`);
    const oldLink = evidenceJsonLink(oldRow.evidence, id);
    const newLink = evidenceJsonLink(newRow.evidence, id);
    if (oldLink && !newLink) throw new Error(`${id}: 過去完了の証拠リンクを削除できません`);
    if (oldLink && newLink !== oldLink) throw new Error(`${id}: 過去完了の証拠リンクを差替えできません`);
    if (oldLink && options.evidenceDeleted?.has(oldLink)) throw new Error(`${id}: 過去完了の証拠JSONを削除できません`);
    if (oldLink && options.evidenceChanged?.has(oldLink)) validateRowEvidence(id, newRow);
    if (!oldLink && newLink) validateRowEvidence(id, newRow);
  }
  for (const [id, newRow] of head.byId) {
    if (!isComplete(newRow.state)) continue;
    const oldRow = base.byId.get(id);
    const becameComplete = !oldRow || !isComplete(oldRow.state);
    const link = evidenceJsonLink(newRow.evidence, id);
    const jsonChanged = link && options.evidenceChanged?.has(link);
    if (becameComplete || jsonChanged) validateRowEvidence(id, newRow);
  }
  return true;
}

function gitShow(ref, file) {
  return execFileSync('git', ['show', `${ref}:${file}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
}

function cli() {
  const args = process.argv.slice(2);
  if (args[0] === '--input' && args[1]) {
    const input = JSON.parse(readFileSync(args[1], 'utf8'));
    validateCompletionChanges(input.baseTaskList, input.headTaskList, input.evidenceByPath, {
      repository: input.repository,
      fileExists: path => input.existingFiles.includes(path),
      implementations: input.implementations,
      runs: new Map(input.runs.map(run => [run.runId, run])),
      evidenceChanged: new Set(input.evidenceChanged),
      evidenceDeleted: new Set(input.evidenceDeleted),
    });
    console.log('完了証拠の遷移・JSON・提供されたGitHub実装PR/CIメタデータを照合しました');
    return;
  }
  const baseIndex = args.indexOf('--base');
  const headIndex = args.indexOf('--head');
  if (baseIndex < 0 || headIndex < 0 || !args[baseIndex + 1] || !args[headIndex + 1]) throw new Error('使い方: node scripts/completion-evidence.mjs --base <ref> --head <ref>');
  const baseRef = args[baseIndex + 1];
  const headRef = args[headIndex + 1];
  const baseTaskList = gitShow(baseRef, 'docs/task-list.md');
  const headTaskList = gitShow(headRef, 'docs/task-list.md');
  const before = parseTaskList(baseTaskList);
  const after = parseTaskList(headTaskList);
  const evidenceByPath = {};
  const evidenceChanged = new Set();
  const evidenceDeleted = new Set();
  const evidencePaths = new Set();
  for (const [id, row] of before.byId) if (isComplete(row.state)) {
    const evidencePath = evidenceJsonLink(row.evidence, id);
    if (evidencePath) evidencePaths.add(evidencePath);
  }
  for (const [id, row] of after.byId) if (isComplete(row.state)) {
    const evidencePath = evidenceJsonLink(row.evidence, id);
    if (evidencePath) evidencePaths.add(evidencePath);
  }
  for (const evidencePath of evidencePaths) {
    let baseEvidence = null;
    let headEvidence = null;
    try { baseEvidence = gitShow(baseRef, evidencePath); } catch { /* no base evidence */ }
    try { headEvidence = gitShow(headRef, evidencePath); } catch { /* missing head evidence */ }
    if (baseEvidence !== headEvidence) evidenceChanged.add(evidencePath);
    if (baseEvidence !== null && headEvidence === null) evidenceDeleted.add(evidencePath);
    if (headEvidence === null) evidenceByPath[evidencePath] = null;
    else {
      try { evidenceByPath[evidencePath] = JSON.parse(headEvidence); } catch { evidenceByPath[evidencePath] = null; }
    }
  }
  const existingFiles = new Set();
  for (const value of Object.values(evidenceByPath)) {
    for (const ref of value ? evidenceReferences(value) : []) {
      if (typeof ref === 'string' && !ref.startsWith('https://')) {
        try { gitShow(headRef, ref); existingFiles.add(ref); } catch { /* validateEvidence reports absent refs */ }
      }
    }
  }
  validateCompletionChanges(baseTaskList, headTaskList, evidenceByPath, {
    repository: process.env.GITHUB_REPOSITORY ?? 'local/local',
    fileExists: ref => existingFiles.has(ref),
    evidenceChanged,
    evidenceDeleted,
  });
  console.log(`完了証拠の構造を確認しました (${baseRef}...${headRef})。注意: このCLI実行ではGitHub API上のPR/CI状態を検証していません。`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { cli(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
