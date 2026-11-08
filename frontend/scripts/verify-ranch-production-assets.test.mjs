import { test } from 'node:test'
import fs, { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { deflateSync } from 'node:zlib'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { verifyProductionPack, installVerifiedPack } from './verify-ranch-production-assets.mjs'

// 合成の透明PNGと512tupleは検証器のfixture。実承認素材・本番公開packではない。
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit++) crc = crc >>> 1 ^ (crc & 1 ? 0xedb88320 : 0)
  }
  return (crc ^ 0xffffffff) >>> 0
}
function png(width, height) {
  const chunk = (kind, bytes) => {
    const label = Buffer.from(kind), result = Buffer.alloc(bytes.length + 12)
    result.writeUInt32BE(bytes.length); label.copy(result, 4); bytes.copy(result, 8)
    result.writeUInt32BE(crc32(Buffer.concat([label, bytes])), bytes.length + 8)
    return result
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 6
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(Buffer.alloc((width * 4 + 1) * height))), chunk('IEND', Buffer.alloc(0))])
}
function fixture() {
  const root = mkdtempSync(path.join(tmpdir(), 'ranch-pack-synthetic-'))
  const pack = path.join(root, 'input'); mkdirSync(pack)
  const source = png(768, 96), fallback = png(96, 96)
  writeFileSync(path.join(pack, 'sheet.png'), source); writeFileSync(path.join(pack, 'fallback.png'), fallback)
  const entries = []
  for (let species = 0; species < 16; species++) for (let variant = 0; variant < 4; variant++) {
    for (const stage of ['EGG', 'BABY', 'JUVENILE', 'ADULT']) for (const renderStyle of ['PIXEL', 'PAINT_2D']) {
      entries.push({ speciesKey: `SYNTHETIC_${species}`, variantKey: `V${variant}`, stage, renderStyle,
        assetKey: `synthetic-${species}-${variant}-${stage}-${renderStyle}`, filePath: 'sheet.png', sourceSha256: hash(source),
        frameWidth: 96, frameHeight: 96, frameCount: 8, fallbackPath: 'fallback.png', fallbackSha256: hash(fallback) })
    }
  }
  const manifest = { schemaVersion: 1, catalogVersion: 1, packVersion: 'synthetic-test-only',
    approvalStatus: 'APPROVED', environment: 'PRODUCTION', entries }
  const master = { approved: true, catalogVersion: 1, assets: entries.map(row => ({ speciesKey: row.speciesKey,
    variantKey: row.variantKey, stage: row.stage, style: row.renderStyle, assetKey: row.assetKey,
    sha256: row.sourceSha256, staticFallbackSha256: row.fallbackSha256 })) }
  const masterFile = path.join(root, 'master.json')
  const persist = () => {
    const manifestBytes = Buffer.from(JSON.stringify(manifest)), masterBytes = Buffer.from(JSON.stringify(master))
    writeFileSync(path.join(pack, 'manifest.json'), manifestBytes); writeFileSync(masterFile, masterBytes)
    return { packDirectory: pack, manifestSha256: hash(manifestBytes), masterFile, masterSha256: hash(masterBytes) }
  }
  return { root, pack, manifest, master, persist, source }
}

test('同じ検証済みartifact bytesをBE classpathとFE有限moduleへ配置する', () => {
  const data = fixture(), verified = verifyProductionPack(data.persist())
  assert.equal(verified.entries.length, 512)
  const repository = path.join(data.root, 'synthetic-output')
  mkdirSync(path.join(repository, 'frontend/app/utils'), { recursive: true })
  installVerifiedPack(verified, repository)
  assert.deepEqual(readFileSync(path.join(repository, 'backend/src/main/resources/ranch/approved/asset-packs/synthetic-test-only/sheet.png')), data.source)
  assert.deepEqual(readFileSync(path.join(repository, 'frontend/public/ranch/approved/synthetic-test-only/manifest.json')), readFileSync(path.join(data.pack, 'manifest.json')))
  assert.match(readFileSync(path.join(repository, 'frontend/app/utils/ranch-production-assets.ts'), 'utf8'), /satisfies readonly RanchAssetEntry\[\]/)
})

test('manifestの実bytes hash変更は入力の検証で拒否する', () => {
  const data = fixture(), input = data.persist()
  writeFileSync(path.join(data.pack, 'manifest.json'), '{}')
  assert.throws(() => verifyProductionPack(input), /SHA256 mismatch/)
})

test('素材bytes改竄は承認metadataが残っていても拒否する', () => {
  const data = fixture(), input = data.persist()
  writeFileSync(path.join(data.pack, 'sheet.png'), png(96, 96))
  assert.throws(() => verifyProductionPack(input), /SHA256 mismatch/)
})

test('同数でも重複tupleでcoverageを偽装できない', () => {
  const data = fixture()
  data.manifest.entries[511] = { ...data.manifest.entries[0] }
  assert.throws(() => verifyProductionPack(data.persist()), /duplicate tuple/)
})

test('masterとの同一tupleでassetHashが違えば拒否する', () => {
  const data = fixture()
  data.master.assets[0].sha256 = '0'.repeat(64)
  assert.throws(() => verifyProductionPack(data.persist()), /master tuple\/hash mismatch/)
})

test('URLと親directory参照はhash照会前に拒否する', () => {
  for (const filePath of ['../sheet.png', 'https://example.invalid/sheet.png']) {
    const data = fixture(); data.manifest.entries[0].filePath = filePath
    assert.throws(() => verifyProductionPack(data.persist()), /relative fixed file path/)
  }
})

test('draftや開発speciesは全512tupleがあっても公開登録しない', () => {
  const draft = fixture(); draft.manifest.approvalStatus = 'DRAFT'
  assert.throws(() => verifyProductionPack(draft.persist()), /unapproved/)
  const development = fixture(); development.manifest.entries[0].speciesKey = 'DEV_TRICERATOPS'
  assert.throws(() => verifyProductionPack(development.persist()), /identity/)
})

test('geometry不一致を拒否し、既存packを異なるbytesで上書きしない', () => {
  const invalid = fixture(); invalid.manifest.entries[0].frameCount = 7
  assert.throws(() => verifyProductionPack(invalid.persist()), /geometry mismatch/)
  const data = fixture(), verified = verifyProductionPack(data.persist())
  const repository = path.join(data.root, 'synthetic-conflict')
  const output = path.join(repository, 'frontend/public/ranch/approved/synthetic-test-only')
  mkdirSync(output, { recursive: true }); writeFileSync(path.join(output, 'sheet.png'), 'conflict')
  assert.throws(() => installVerifiedPack(verified, repository), /existing pack/)
  assert.equal(existsSync(path.join(repository, 'frontend/app/utils/ranch-production-assets.ts')), false)
})

test('IHDRだけのPNGとCRC不正はhashが一致しても実使用素材として拒否する', () => {
  for (const kind of ['header-only', 'invalid-crc']) {
    const data = fixture()
    const corrupted = kind === 'header-only' ? data.source.subarray(0, 33) : Buffer.from(data.source)
    if (kind === 'invalid-crc') corrupted[29] ^= 1
    writeFileSync(path.join(data.pack, 'sheet.png'), corrupted)
    data.manifest.entries.forEach(row => { row.sourceSha256 = hash(corrupted) })
    data.master.assets.forEach(row => { row.sha256 = hash(corrupted) })
    assert.throws(() => verifyProductionPack(data.persist()), /PNG IDAT\/IEND missing|PNG chunk CRC/)
  }
})

test('manifest非参照ファイルや未検証objectを公開copyへ通さない', () => {
  const data = fixture(), input = data.persist()
  writeFileSync(path.join(data.pack, 'private-unused.txt'), 'synthetic unreferenced')
  assert.throws(() => verifyProductionPack(input), /file set differs/)
  assert.throws(() => installVerifiedPack({ entries: [] }, data.root), /unverified installation/)
})

test('同一pack再配置は実bytesを保持しTS置換失敗でも旧moduleを破損しない', context => {
  const data = fixture(), verified = verifyProductionPack(data.persist())
  const repository = path.join(data.root, 'synthetic-atomic')
  mkdirSync(path.join(repository, 'frontend/app/utils'), { recursive: true })
  installVerifiedPack(verified, repository)
  const modulePath = path.join(repository, 'frontend/app/utils/ranch-production-assets.ts')
  const saved = readFileSync(modulePath)
  installVerifiedPack(verified, repository)
  assert.deepEqual(readFileSync(modulePath), saved)
  const rename = fs.renameSync
  context.mock.method(fs, 'renameSync', (source, destination) => {
    if (destination === modulePath) throw Object.assign(new Error('SYNTHETIC_ATOMIC_RENAME_FAILURE'), { code: 'EIO' })
    return rename(source, destination)
  })
  syncBuiltinESMExports()
  try {
    assert.throws(() => installVerifiedPack(verified, repository), /ATOMIC_RENAME_FAILURE/)
    assert.deepEqual(readFileSync(modulePath), saved)
  } finally {
    context.mock.restoreAll(); syncBuiltinESMExports()
  }
})
