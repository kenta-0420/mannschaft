import { test } from 'node:test'
import fs, { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import { syncBuiltinESMExports } from 'node:module'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { deflateSync } from 'node:zlib'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { verifyProductionPack, installVerifiedPack, accountProductionImageBytes } from './verify-ranch-production-assets.mjs'
import { inspectArtifact } from './verify-ranch-production-artifact.mjs'

// 合成の透明PNGと512tupleは検証器のfixture。実承認素材・本番公開packではない。
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
test('画像累積byte境界はv1の256MiBを維持しv2だけ320MiBとする', () => {
  for (const [version, limit] of [[1, 256 * 1024 * 1024], [2, 320 * 1024 * 1024]]) {
    const before = accountProductionImageBytes(0, limit - 1, version)
    assert.equal(before, limit - 1)
    assert.equal(accountProductionImageBytes(before, 1, version), limit)
    assert.throws(() => accountProductionImageBytes(before, 2, version), /memory bound/)
  }
  assert.throws(() => accountProductionImageBytes(0, 280355204, 1), /memory bound/)
  assert.equal(accountProductionImageBytes(0, 280355204, 2), 280355204)
})
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

function clipFixture() {
  const data = fixture()
  fs.unlinkSync(path.join(data.pack, 'sheet.png'))
  fs.unlinkSync(path.join(data.pack, 'fallback.png'))
  data.manifest.schemaVersion = 2
  const images = {}
  for (const [style, cell] of [['PIXEL', 96], ['PAINT_2D', 384]]) {
    for (const [kind, width] of [['atlas', cell * 4], ['static', cell]]) {
      const name = `${style}-${kind}.png`, bytes = png(width, cell)
      writeFileSync(path.join(data.pack, name), bytes)
      images[name] = hash(bytes)
    }
  }
  for (const row of data.manifest.entries) {
    row.variantKey = `V${Number(row.variantKey.slice(1)) + 1}`
    const cell = row.renderStyle === 'PIXEL' ? 96 : 384
    const egg = row.stage === 'EGG'
    row.filePath = `${row.renderStyle}-${egg ? 'static' : 'atlas'}.png`
    row.sourceSha256 = images[row.filePath]
    row.fallbackPath = `${row.renderStyle}-static.png`
    row.fallbackSha256 = images[row.fallbackPath]
    row.frameWidth = row.frameHeight = cell
    row.frameCount = egg ? 1 : 4
    row.sourceSpeciesKey = row.speciesKey
    row.habitat = 'LAND'
    row.logicalCanvas = { width: egg ? 96 : 112, height: egg ? 96 : 112 }
    row.careSpriteRect = { x: egg ? 0 : 8, y: egg ? 0 : 8, width: 96, height: 96 }
    row.rendererProfile = egg ? 'COMMON_EGG_SVG_V1' : 'APPROVED_APPEARANCE_V4'
    row.clips = egg ? null : Object.fromEntries(['idle', 'walk', 'feed', 'happy'].map(action => [action, {
      filePath: row.filePath, sourceSha256: row.sourceSha256, frameWidth: cell, frameHeight: cell,
      frameCount: 4, durationMs: 4500, phaseStarts: [0, 0.78, 0.84, 0.88],
      phaseMs: [3510, 269.9999999999997, 180.00000000000017, 540],
      loop: action === 'idle' || action === 'walk', returnTo: action === 'idle' || action === 'walk' ? null : 'idle',
      floorByFrame: null, sourceFloor384: null, sourceFloorByFrame384: null,
      tailMarks: null, shadowByFrame: null,
    }]))
  }
  data.master.assets = data.manifest.entries.map(row => ({ speciesKey: row.speciesKey,
    variantKey: row.variantKey, stage: row.stage, style: row.renderStyle, assetKey: row.assetKey,
    sha256: row.sourceSha256, staticFallbackSha256: row.fallbackSha256 }))
  return data
}

test('v2の512tuple内384個体が原本小数timingの4動作と128卵を保持する', () => {
  const data = clipFixture(), verified = verifyProductionPack(data.persist())
  assert.equal(verified.entries.length, 512)
  const clips = verified.entries.filter(row => row.clipEntry)
  assert.equal(clips.length, 384)
  assert.equal(clips[0].clipEntry.clips.idle.phaseMs[1], 269.9999999999997)
  assert.equal(clips[0].clipEntry.rendererProfile, 'APPROVED_APPEARANCE_V4')
})

test('承認元と同bytesでも検証済み正式namespaceだけproduction配布を許可する', () => {
  const data = fixture(), verified = verifyProductionPack(data.persist())
  const artifact = path.join(data.root, 'artifact')
  const output = path.join(artifact, 'public/ranch/approved/synthetic-test-only')
  mkdirSync(output, { recursive: true })
  writeFileSync(path.join(output, 'sheet.png'), data.source)
  writeFileSync(path.join(output, 'fallback.png'), readFileSync(path.join(data.pack, 'fallback.png')))
  writeFileSync(path.join(output, 'manifest.json'), readFileSync(path.join(data.pack, 'manifest.json')))
  assert.equal(inspectArtifact(artifact, new Set([hash(data.source)]), [], verified), 3)
  writeFileSync(path.join(output, 'sheet.png'), 'tampered')
  assert.throws(() => inspectArtifact(artifact, new Set([hash(data.source)]), [], verified))
  writeFileSync(path.join(output, 'sheet.png'), data.source)
  writeFileSync(path.join(output, 'unregistered.png'), data.source)
  assert.throws(() => inspectArtifact(artifact, new Set([hash(data.source)]), [], verified))
})

for (const kind of ['file', 'directory']) for (const order of ['alias-first', 'canonical-first']) {
  test(`正式素材への${kind} aliasを${order}の走査順でも拒否する`, () => {
    const data = fixture(), verified = verifyProductionPack(data.persist())
    const artifact = path.join(data.root, 'alias-artifact')
    const output = path.join(artifact, 'public/ranch/approved/synthetic-test-only')
    mkdirSync(output, { recursive: true })
    for (const name of ['sheet.png', 'fallback.png', 'manifest.json']) writeFileSync(path.join(output, name), readFileSync(path.join(data.pack, name)))
    const alias = path.join(artifact, 'public', `${order === 'alias-first' ? 'a' : 'z'}-alias${kind === 'file' ? '.png' : ''}`)
    fs.symlinkSync(kind === 'file' ? path.join(output, 'sheet.png') : output, alias, kind === 'file' ? 'file' : 'junction')
    try {
      assert.throws(() => inspectArtifact(artifact, new Set([hash(data.source)]), [], verified), /alias|開発PNG|未登録/)
    } finally {
      if (kind === 'file' || process.platform !== 'win32') fs.unlinkSync(alias)
      else fs.rmdirSync(alias)
    }
  })
}

test('v2の不足動作・余剰field・位相逆転・idle anchor不一致を拒否する', () => {
  for (const change of [
    row => { delete row.clips.happy },
    row => { row.clips.walk.extra = 1 },
    row => { row.clips.idle.phaseStarts[2] = 0.5 },
    row => { row.clips.idle.filePath = 'PAINT_2D-atlas.png' },
  ]) {
    const data = clipFixture()
    change(data.manifest.entries.find(row => row.stage === 'BABY'))
    assert.throws(() => verifyProductionPack(data.persist()))
  }
})

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
