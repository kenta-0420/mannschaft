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
export function verifyNoApprovedPackPrecache(source) {
  // 文字列・コメントをmaskし、runtimeCachingや説明文中のURLは検査対象にしない。
  const masked = source.replace(
    /"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g,
    (value) => ' '.repeat(value.length),
  )
  const calls = [...masked.matchAll(/\bprecacheAndRoute\s*\(/g)]
  assert.ok(calls.length > 0, '生成SWのprecache manifestが見つからない')
  let checked = 0
  for (const call of calls) {
    let start = call.index + call[0].length
    while (/\s/.test(masked[start] ?? '') && start < masked.length) start++
    assert.equal(masked[start], '[', '生成SWのprecache配列を検査できない')
    let depth = 0
    let end = start
    for (; end < masked.length; end++) {
      if (masked[end] === '[') depth++
      if (masked[end] === ']' && --depth === 0) break
    }
    assert.ok(end < masked.length, '生成SWのprecache配列が閉じていない')
    const literal = source.slice(start, end + 1)
    // Workbox生成物のbare propertyだけをJSON化。文字列内部の語は書き換えない。
    const keys = [...masked.slice(start, end + 1).matchAll(/\b(url|revision|integrity)(?=\s*:)/g)]
    const pieces = []
    let cursor = 0
    for (const key of keys) {
      pieces.push(literal.slice(cursor, key.index), JSON.stringify(key[0]))
      cursor = key.index + key[0].length
    }
    pieces.push(literal.slice(cursor))
    const entries = JSON.parse(pieces.join(''))
    for (const entry of entries) {
      const url = typeof entry === 'string' ? entry : entry.url
      assert.equal(typeof url, 'string', '生成SWのprecache URLが不正')
      const pathname = decodeURIComponent(new URL(url, 'https://artifact.invalid/').pathname)
      assert.ok(pathname !== '/ranch/approved' && !pathname.startsWith('/ranch/approved/'),
        `正式packの一括precacheは禁止: ${url}`)
      checked++
    }
  }
  return checked
}
export function inspectArtifact(directory, blockedHashes, markers, verifiedPack = null) {
  assert.ok(fs.existsSync(directory), 'production buildが必要')
  let files = 0
  const lexicalRoot = path.resolve(directory)
  const root = fs.realpathSync(directory)
  const visited = new Set()
  const active = new Set()
  const approved = verifiedPack ? verifiedProductionArtifactHashes(verifiedPack) : new Map()
  const approvedDirectories = new Set([...approved.keys()].flatMap(file => {
    const parts = file.split('/')
    return parts.slice(0, -1).map((_part, index) => parts.slice(0, index + 1).join('/'))
  }))
  const approvedSeen = new Set()
  function check(target) {
    fs.lstatSync(target) // 壊れたリンクも失敗させる。
    const resolved = fs.realpathSync(target)
    const relative = path.relative(root, resolved)
    const relativeFile = path.relative(lexicalRoot, path.resolve(target)).split(path.sep).join('/')
    const resolvedFile = relative.split(path.sep).join('/')
    assert.ok(
      relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative),
      `成果物外へのリンク: ${target}`,
    )
    // 正式namespaceの例外を別名へ移せない。canonical先でもdedup前に判定する。
    assert.ok(
      relativeFile === resolvedFile || !(approved.has(resolvedFile) || approvedDirectories.has(resolvedFile)
        || relativeFile.startsWith('public/ranch/approved/')),
      `正式素材への未登録alias: ${target}`,
    )
    assert.ok(!active.has(resolved), `成果物内の循環リンク: ${target}`)
    const stat = fs.statSync(resolved)
    if (stat.isFile() && (relativeFile === 'public/sw.js' || resolvedFile === 'public/sw.js')) {
      verifyNoApprovedPackPrecache(fs.readFileSync(resolved, 'utf8'))
    }
    if (visited.has(resolved)) return
    visited.add(resolved)
    if (stat.isDirectory()) {
      active.add(resolved)
      for (const name of fs.readdirSync(target)) check(path.join(target, name))
      active.delete(resolved)
    } else if (stat.isFile()) {
      files++
      const bytes = fs.readFileSync(resolved)
      const hash = crypto.createHash('sha256').update(bytes).digest('hex')
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
