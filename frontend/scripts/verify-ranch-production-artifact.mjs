// production出力に開発用PNGや開発配信pathが混入していないことを検証する。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { verifyProductionPack, verifiedProductionArtifactHashes } from './verify-ranch-production-assets.mjs'
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
export function inspectArtifact(directory, blockedHashes, markers, verifiedPack = null) {
  assert.ok(fs.existsSync(directory), 'production buildが必要')
  let files = 0
  const root = fs.realpathSync(directory)
  const visited = new Set()
  const active = new Set()
  const approved = verifiedPack ? verifiedProductionArtifactHashes(verifiedPack) : new Map()
  const approvedSeen = new Set()
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
      const relativeFile = path.relative(root, resolved).split(path.sep).join('/')
      const expected = approved.get(relativeFile)
      if (relativeFile.startsWith('public/ranch/approved/')) assert.equal(hash, expected, `未登録/改竄正式素材: ${target}`)
      if (expected === hash) approvedSeen.add(relativeFile)
      assert.ok(!blockedHashes.has(hash) || expected === hash, `開発PNG混入: ${target}`)
      assert.ok(
        !markers.some((marker) => bytes.includes(Buffer.from(marker))),
        `開発path混入: ${target}`,
      )
    } else assert.fail(`通常ファイル以外: ${target}`)
  }
  check(directory)
  assert.equal(approvedSeen.size, approved.size, '正式packの配布coverage不足')
  assert.ok(files > 0)
  return files
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const registration = path.join(frontend, 'app/utils/ranch-production-pack-registration.json')
  let verifiedPack = null
  if (fs.existsSync(registration)) {
    const metadata = JSON.parse(fs.readFileSync(registration, 'utf8'))
    assert.match(metadata.packVersion, /^[A-Za-z0-9_-]+$/)
    verifiedPack = verifyProductionPack({ packDirectory: path.join(frontend, 'public/ranch/approved', metadata.packVersion),
      manifestSha256: metadata.manifestSha256, masterFile: path.join(frontend, 'scripts/fixtures/ranch-production-master.json'), masterSha256: metadata.masterSha256 })
  }
  console.log(`production artifact: ${inspectArtifact(output, hashes, forbidden, verifiedPack)} files checked`)
}
