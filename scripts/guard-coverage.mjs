#!/usr/bin/env node
/** page-reachability.yaml の宣言と全Vue routeの台帳カバレッジを報告する。 */
import { readdir, readFile } from 'node:fs/promises';
import { join, resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const INVENTORY = join('docs', 'inventory', 'page-reachability.yaml');
const PAGES_DIR = join('frontend', 'app', 'pages');
const CLASSIFICATIONS = new Set(['reachable', 'duplicate-remnant', 'be-pending', 'intentional-direct-only']);
const PAGE_FIELDS = new Set(['path', 'classification', 'reason', 'replacement', 'cmp', 'link_detection_note']);

export function toRoutePath(relativeFile) {
  let path = relativeFile.split(sep).join('/').replaceAll('\\', '/');
  if (!path.endsWith('.vue')) throw new Error(`Vueファイルではありません: ${relativeFile}`);
  path = path.slice(0, -4);
  if (path.endsWith('/index')) path = path.slice(0, -6);
  else if (path === 'index') path = '';
  return `/${path}`;
}

function fail(line, message) { throw new Error(`page-reachability.yaml:${line}: ${message}`); }

function scalar(raw, line) {
  const value = raw.trim();
  if (!value) return '';
  if (value.startsWith('"')) {
    try { return JSON.parse(value); } catch { fail(line, '不正なdouble-quoted scalar'); }
  }
  if (value.startsWith("'")) {
    if (!value.endsWith("'")) fail(line, '不正なsingle-quoted scalar');
    return value.slice(1, -1).replaceAll("''", "'");
  }
  if (value === 'null' || value === '~') return null;
  if (value === '[]' || value === '{}') return value === '[]' ? [] : {};
  if (/^(true|false)$/i.test(value)) return value.toLowerCase() === 'true';
  if (/^[[{&*!>|]/.test(value)) fail(line, 'この台帳で未対応のYAML scalar形式です');
  return value.replace(/\s+#.*$/, '').trim();
}

/** YAML全般を曖昧に解釈せず、この台帳の2つのsequence形式だけを読む。 */
export function parseInventory(text) {
  let section = null;
  let currentPage = null;
  const trackedPaths = [];
  const pages = [];
  const seenSections = new Set();
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const lineNumber = index + 1;
    const line = lines[index];
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const top = /^(tracked_paths|pages):\s*(?:#.*)?$/.exec(line);
    if (top) {
      section = top[1];
      if (seenSections.has(section)) fail(lineNumber, `section ${section} が重複しています`);
      seenSections.add(section);
      currentPage = null;
      continue;
    }
    if (!section) fail(lineNumber, 'tracked_paths/pages より前に未知のYAML構造があります');
    if (section === 'tracked_paths') {
      const item = /^  -\s+(.+?)\s*$/.exec(line);
      if (!item) fail(lineNumber, 'tracked_paths は2空白の `- /route` 形式にしてください');
      trackedPaths.push(scalar(item[1], lineNumber));
      continue;
    }
    const pageItem = /^  -\s+path:\s*(.*)$/.exec(line);
    if (pageItem) {
      currentPage = {};
      pages.push(currentPage);
      currentPage.path = scalar(pageItem[1], lineNumber);
      continue;
    }
    const field = /^    ([a-z][a-z0-9_]*):\s*(.*)$/.exec(line);
    if (field && currentPage) {
      if (!PAGE_FIELDS.has(field[1])) fail(lineNumber, `未対応のpages field: ${field[1]}`);
      if (Object.hasOwn(currentPage, field[1])) fail(lineNumber, `pages field ${field[1]} が重複しています`);
      currentPage[field[1]] = scalar(field[2], lineNumber);
      continue;
    }
    fail(lineNumber, 'pages は2空白の `- path:` と4空白のscalar field形式にしてください');
  }
  if (!seenSections.has('tracked_paths')) throw new Error('page-reachability.yaml: tracked_paths がありません');
  if (!seenSections.has('pages')) throw new Error('page-reachability.yaml: pages がありません');
  if (!trackedPaths.length) throw new Error('page-reachability.yaml: tracked_paths が空です');
  if (!pages.length) throw new Error('page-reachability.yaml: pages が空です');
  return { trackedPaths, pages };
}

async function collectVueFiles(directory, root = directory) {
  const output = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isSymbolicLink()) continue;
    if (entry.isDirectory()) output.push(...await collectVueFiles(path, root));
    else if (entry.isFile() && entry.name.endsWith('.vue')) output.push(relative(root, path));
  }
  return output.sort();
}

function validateRoute(path, where) {
  if (typeof path !== 'string' || !path.startsWith('/') || path !== '/' && (path.endsWith('/') || path.includes('//'))) {
    throw new Error(`${where}: routeは先頭slashの正規絶対パスである必要があります`);
  }
}

export async function inspectCoverage(repo) {
  const inventoryPath = join(repo, INVENTORY);
  const pagesDir = join(repo, PAGES_DIR);
  const [yaml, files] = await Promise.all([readFile(inventoryPath, 'utf8'), collectVueFiles(pagesDir)]);
  if (!files.length) throw new Error('frontend/app/pages にVueページがありません');
  const { trackedPaths, pages } = parseInventory(yaml);
  const errors = [];
  const trackedSet = new Set();
  for (const path of trackedPaths) {
    validateRoute(path, 'tracked_paths');
    if (trackedSet.has(path)) errors.push(`tracked_pathsの重複: ${path}`);
    trackedSet.add(path);
  }
  const byPath = new Map();
  for (const page of pages) {
    if (typeof page.path !== 'string' || !page.path) { errors.push('pagesにpathの無い要素があります'); continue; }
    validateRoute(page.path, 'pages');
    if (byPath.has(page.path)) errors.push(`pagesのpath重複: ${page.path}`);
    byPath.set(page.path, page);
    if (!CLASSIFICATIONS.has(page.classification)) errors.push(`未知または欠落したclassification: ${page.path}`);
    if (typeof page.reason !== 'string' || !page.reason.trim()) errors.push(`reasonがありません: ${page.path}`);
    if (page.classification === 'duplicate-remnant' && (typeof page.replacement !== 'string' || !page.replacement.startsWith('/'))) errors.push(`replacementがありません: ${page.path}`);
    if (page.classification === 'be-pending' && (typeof page.cmp !== 'string' || !/^CMP-/.test(page.cmp))) errors.push(`cmpがありません: ${page.path}`);
  }

  const declared = new Set(byPath.keys());
  const undeclaredTracked = [...trackedSet].filter((path) => !declared.has(path)).sort();
  const declaredUntracked = [...declared].filter((path) => !trackedSet.has(path)).sort();
  const allRoutes = files.map(toRoutePath);
  const routeSet = new Set(allRoutes);
  const duplicateRoutes = [...new Set(allRoutes.filter((route, index) => allRoutes.indexOf(route) !== index))].sort();
  const routes = [...routeSet].sort();
  const nonExistingTracked = [...trackedSet].filter((path) => !routeSet.has(path)).sort();
  const nonExistingDeclared = [...declared].filter((path) => !routeSet.has(path)).sort();
  const untrackedRoutes = routes.filter((path) => !trackedSet.has(path)).sort();
  return {
    counts: { vueFiles: files.length, uniqueRoutes: routes.length, routeCollisions: duplicateRoutes.length, trackedPaths: trackedSet.size, declaredPages: declared.size, untrackedRoutes: untrackedRoutes.length,
      undeclaredTracked: undeclaredTracked.length, declaredUntracked: declaredUntracked.length,
      nonExistingTracked: nonExistingTracked.length, nonExistingDeclared: nonExistingDeclared.length, schemaErrors: errors.length },
    routes, duplicateRoutes, untrackedRoutes, undeclaredTracked, declaredUntracked, nonExistingTracked, nonExistingDeclared, errors,
    classifications: Object.fromEntries([...CLASSIFICATIONS].map((classification) => [classification, pages.filter((page) => page.classification === classification).length])),
  };
}

export function render(result) {
  const lines = [
    `Vueファイル: ${result.counts.vueFiles}件`, `unique route: ${result.counts.uniqueRoutes}件`,
    `同一routeへの複数Vue: ${result.counts.routeCollisions}件`, `追跡対象: ${result.counts.trackedPaths}件`,
    `宣言ページ: ${result.counts.declaredPages}件`, `未検査route: ${result.counts.untrackedRoutes}件`,
    `tracked未宣言: ${result.counts.undeclaredTracked}件`, `追跡外宣言: ${result.counts.declaredUntracked}件`,
    `不存在route: ${result.counts.nonExistingTracked + result.counts.nonExistingDeclared}件`,
    `schema違反: ${result.counts.schemaErrors}件`,
  ];
  for (const key of ['undeclaredTracked', 'declaredUntracked', 'nonExistingTracked', 'nonExistingDeclared', 'errors']) {
    for (const value of result[key]) lines.push(`${key}: ${value}`);
  }
  for (const value of result.duplicateRoutes) lines.push(`duplicateRoute: ${value}`);
  return lines.join('\n');
}

async function main() {
  let repo = process.cwd();
  let json = false;
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--repo' && argv[i + 1]) repo = resolve(argv[++i]);
    else if (argv[i] === '--json') json = true;
    else throw new Error(`未対応の引数: ${argv[i]}`);
  }
  const result = await inspectCoverage(repo);
  console.log(json ? JSON.stringify(result, null, 2) : render(result));
  if (result.counts.undeclaredTracked || result.counts.declaredUntracked || result.counts.nonExistingTracked
      || result.counts.nonExistingDeclared || result.counts.schemaErrors) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
