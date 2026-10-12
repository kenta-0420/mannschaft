import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'

// 公式 simple-git 4 の named export へ、DevTools 3.4.1 の消費者を適合させる。
// 対象は npm 公式配布物のこの1行のみ。別の版・内容は変更せず停止する。
const BEFORE_HASH = '13f0dbd2e845848ad98fe644eab152c43a0a8cb88d6ff04a4bd77d12a68ac784'
const AFTER_HASH = 'e42d96ee5d9a85f68785341fcac20b161da70336c5a10a11da5ea703d6c7406e'
const BEFORE_LINE = "import Git from 'simple-git';"
const AFTER_LINE = "import { simpleGit as Git } from 'simple-git';"
const packageRoot = new URL('../node_modules/@nuxt/devtools/', import.meta.url)
const version = JSON.parse(readFileSync(new URL('package.json', packageRoot), 'utf8')).version
if (version !== '3.4.1') throw new Error('DevTools の互換対応対象版が不一致です')

const modulePath = new URL('dist/chunks/module-main.mjs', packageRoot)
const source = readFileSync(modulePath, 'utf8')
const sha256 = (text) => createHash('sha256').update(text).digest('hex')
const currentHash = sha256(source)
if (currentHash !== AFTER_HASH) {
  if (currentHash !== BEFORE_HASH) throw new Error('DevTools の公式配布物の内容が不一致です')
  const updated = source.replace(BEFORE_LINE, AFTER_LINE)
  if (sha256(updated) !== AFTER_HASH) throw new Error('DevTools の互換対応後の内容が不一致です')
  writeFileSync(modulePath, updated, 'utf8')
}
if (sha256(readFileSync(modulePath, 'utf8')) !== AFTER_HASH) {
  throw new Error('DevTools の互換対応を確認できませんでした')
}
