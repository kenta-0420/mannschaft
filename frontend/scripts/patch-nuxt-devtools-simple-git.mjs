import { createHash } from 'node:crypto'
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// 公式3.4.1配布物の唯一のdefault importだけを、v4の公開named exportへ合わせる。
export const VERSION = '3.4.1'
export const ORIGINAL_SHA = '13f0dbd2e845848ad98fe644eab152c43a0a8cb88d6ff04a4bd77d12a68ac784'
export const PATCHED_SHA = 'e42d96ee5d9a85f68785341fcac20b161da70336c5a10a11da5ea703d6c7406e'
const BEFORE = "import Git from 'simple-git';"
const AFTER = "import { simpleGit as Git } from 'simple-git';"
const digest = bytes => createHash('sha256').update(bytes).digest('hex')
const refuse = () => { throw new Error('DEVTOOLS_COMPATIBILITY_PATCH_REFUSED') }

export function patchBytes(bytes, version) {
  if (version !== VERSION || !Buffer.isBuffer(bytes)) refuse()
  const sha = digest(bytes)
  if (sha === PATCHED_SHA) return bytes
  if (sha !== ORIGINAL_SHA) refuse()
  const text = bytes.toString('utf8')
  if (text.split(BEFORE).length !== 2) refuse()
  const result = Buffer.from(text.replace(BEFORE, AFTER), 'utf8')
  if (digest(result) !== PATCHED_SHA) refuse()
  return result
}

export function applyPinnedPatch() {
  if (process.argv.length !== 2) refuse()
  // 任意path・共有node_modules・symlinkを受け付けない。
  const frontend = dirname(dirname(fileURLToPath(import.meta.url)))
  if (realpathSync(frontend) !== resolve(frontend)) refuse()
  const components = ['node_modules', '@nuxt', 'devtools', 'dist', 'chunks']
  let current = frontend
  for (const component of components) {
    current = join(current, component)
    const stat = lstatSync(current)
    if (!stat.isDirectory() || stat.isSymbolicLink()) refuse()
  }
  const packagePath = join(frontend, 'node_modules', '@nuxt', 'devtools', 'package.json')
  const target = join(current, 'module-main.mjs')
  for (const path of [packagePath, target]) {
    const stat = lstatSync(path)
    if (!stat.isFile() || stat.isSymbolicLink()) refuse()
  }
  const version = JSON.parse(readFileSync(packagePath, 'utf8')).version
  const original = readFileSync(target)
  const patched = patchBytes(original, version)
  if (patched !== original) writeFileSync(target, patched)
  if (digest(readFileSync(target)) !== PATCHED_SHA) refuse()
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { applyPinnedPatch() } catch { console.error('DEVTOOLS_COMPATIBILITY_PATCH_REFUSED'); process.exitCode = 1 }
}
