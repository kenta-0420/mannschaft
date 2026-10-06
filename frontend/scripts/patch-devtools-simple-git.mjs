// @nuxt/devtools 3.4.1 の公開 artifact のみを simple-git 4 の named export に対応させる。
// upstream 安定版で対応されたら、この script と postinstall 呼び出しを削除する。
import { createHash } from 'node:crypto'
import { lstatSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ORIGINAL_SHA256 = '13f0dbd2e845848ad98fe644eab152c43a0a8cb88d6ff04a4bd77d12a68ac784'
const PATCHED_SHA256 = 'e42d96ee5d9a85f68785341fcac20b161da70336c5a10a11da5ea703d6c7406e'
const OLD_IMPORT = "import Git from 'simple-git';"
const NEW_IMPORT = "import { simpleGit as Git } from 'simple-git';"
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex')

try {
  const nodeModules = resolve(fileURLToPath(new URL('../node_modules/', import.meta.url)))
  const packageDir = resolve(nodeModules, '@nuxt/devtools')
  const packagePath = resolve(packageDir, 'package.json')
  const modulePath = resolve(packageDir, 'dist/chunks/module-main.mjs')
  if (lstatSync(nodeModules).isSymbolicLink() || realpathSync(nodeModules) !== nodeModules) {
    throw new Error('devtools patch: 共有先への node_modules リンクを拒否')
  }
  if (realpathSync(packageDir) !== packageDir || realpathSync(packagePath) !== packagePath || realpathSync(modulePath) !== modulePath) {
    throw new Error('devtools patch: 固定対象外へのリンクを拒否')
  }
  const pkg = JSON.parse(readFileSync(packagePath, 'utf8'))
  if (pkg.name !== '@nuxt/devtools' || pkg.version !== '3.4.1') {
    throw new Error('devtools patch: 未確認のパッケージ版を拒否')
  }
  const original = readFileSync(modulePath)
  const originalHash = hash(original)
  if (originalHash === PATCHED_SHA256) {
    console.log('devtools patch: 適用済み artifact を確認')
  } else {
    if (originalHash !== ORIGINAL_SHA256) throw new Error('devtools patch: 未確認の artifact を拒否')
    const patched = Buffer.from(original.toString('utf8').replace(OLD_IMPORT, NEW_IMPORT))
    if (hash(patched) !== PATCHED_SHA256) throw new Error('devtools patch: 変換後の artifact が不一致')
    writeFileSync(modulePath, patched)
    console.log('devtools patch: simple-git named export 対応を適用')
  }
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
