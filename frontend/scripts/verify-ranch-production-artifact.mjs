// production出力に開発用PNGや開発配信pathが混入していないことを検証する。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const output = path.join(frontend, '.output')
const manifest = JSON.parse(
  fs.readFileSync(path.join(frontend, 'app/utils/ranch-development-appearances-manifest.json')),
)
const legacy = JSON.parse(
  fs.readFileSync(path.join(frontend, 'app/utils/ranch-development-clips-manifest.json')),
)
const hashes = new Set(
  [...manifest.entries, ...legacy.entries].flatMap((e) => [
    e.fallback.sourceSha256,
    ...Object.values(e.clips).map((c) => c.sourceSha256),
  ]),
)
const forbidden = [
  '/dev-fixtures/ranch/appearance64-v4/',
  '/dev-fixtures/ranch/base16-v3/',
  'outputs/dinosaur-care-complete16-20261106',
  ...(frontend === '/app' ? [] : [frontend, frontend.replaceAll('\\', '/')]),
]
export function inspectArtifact(directory, blockedHashes, markers) {
  assert.ok(fs.existsSync(directory), 'production buildが必要')
  let files = 0
  const root = fs.realpathSync(directory)
  const visited = new Set()
  const active = new Set()
  function check(target) {
    fs.lstatSync(target) // 壊れたリンクも失敗させる。
    const resolved = fs.realpathSync(target)
    const relative = path.relative(root, resolved)
    assert.ok(
      relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative),
      `成果物外へのリンク: ${target}`,
    )
    assert.ok(!active.has(resolved), `成果物内の循環リンク: ${target}`)
    if (visited.has(resolved)) return
    visited.add(resolved)
    const stat = fs.statSync(resolved)
    if (stat.isDirectory()) {
      active.add(resolved)
      for (const name of fs.readdirSync(resolved)) check(path.join(resolved, name))
      active.delete(resolved)
    } else if (stat.isFile()) {
      files++
      const bytes = fs.readFileSync(resolved)
      const hash = crypto.createHash('sha256').update(bytes).digest('hex')
      assert.ok(!blockedHashes.has(hash), `開発PNG混入: ${target}`)
      assert.ok(
        !markers.some((marker) => bytes.includes(Buffer.from(marker))),
        `開発path混入: ${target}`,
      )
    } else assert.fail(`通常ファイル以外: ${target}`)
  }
  check(directory)
  assert.ok(files > 0)
  return files
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(`production artifact: ${inspectArtifact(output, hashes, forbidden)} files checked`)
}
