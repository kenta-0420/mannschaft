import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, isAbsolute, relative, resolve } from 'node:path';

// DevTools 3.4.1 と simple-git 4.0.2 の import 互換性だけを補正する。
// 対象版・元資産が変わった場合は停止し、無条件の依存パッチを行わない。
const frontend = resolve(fileURLToPath(new URL('..', import.meta.url)));
if (lstatSync(frontend).isSymbolicLink()) throw new Error('Frontend root must not be a link');
const physicalFrontend = realpathSync(frontend);
function checkedPath(relativePath) {
  const path = resolve(frontend, relativePath);
  const physicalRelative = relative(physicalFrontend, realpathSync(path));
  if (physicalRelative.startsWith('..') || isAbsolute(physicalRelative)) throw new Error('Dependency path leaves this frontend');
  let ancestor = path;
  while (ancestor !== frontend) {
    if (lstatSync(ancestor).isSymbolicLink()) throw new Error('Dependency ancestors must not be links');
    const next = dirname(ancestor);
    if (next === ancestor) throw new Error('Dependency path leaves this frontend');
    ancestor = next;
  }
  return path;
}
const packages = [
  ['@nuxt/devtools', '3.4.1'],
  ['simple-git', '4.0.2'],
  ['@simple-git/argv-parser', '2.0.1'],
];
for (const [name, expected] of packages) {
  const path = checkedPath(`node_modules/${name}/package.json`);
  if (lstatSync(path).isSymbolicLink()) throw new Error('Dependency package path must not be a symlink');
  const pkg = JSON.parse(readFileSync(path, 'utf8'));
  if (pkg.name !== name || pkg.version !== expected) throw new Error(`Unsupported dependency version: ${name}`);
}
const path = checkedPath('node_modules/@nuxt/devtools/dist/chunks/module-main.mjs');
if (lstatSync(path).isSymbolicLink()) throw new Error('DevTools module must not be a symlink');
const beforeHash = '13f0dbd2e845848ad98fe644eab152c43a0a8cb88d6ff04a4bd77d12a68ac784';
const afterHash = 'e42d96ee5d9a85f68785341fcac20b161da70336c5a10a11da5ea703d6c7406e';
const bytes = readFileSync(path);
const hash = createHash('sha256').update(bytes).digest('hex');
if (hash !== afterHash) {
  if (hash !== beforeHash) throw new Error('Unexpected DevTools module content');
  const oldImport = "import Git from 'simple-git';";
  const newImport = "import { simpleGit as Git } from 'simple-git';";
  const text = bytes.toString('utf8');
  if (text.split(oldImport).length !== 2) throw new Error('Expected exactly one DevTools Git import');
  const patched = Buffer.from(text.replace(oldImport, newImport), 'utf8');
  if (createHash('sha256').update(patched).digest('hex') !== afterHash) throw new Error('Unexpected patch output');
  writeFileSync(path, patched);
  if (createHash('sha256').update(readFileSync(path)).digest('hex') !== afterHash) throw new Error('DevTools patch verification failed');
}
