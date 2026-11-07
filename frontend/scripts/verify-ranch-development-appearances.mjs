// v4配布素材と原本RGBA照合のsnapshotを検証する。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import sharp from 'sharp'
import { fileURLToPath } from 'node:url'
const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex')
const root = path.join(frontend, 'dev-fixtures/ranch/appearance64-v4')
const manifestBytes = fs.readFileSync(
  path.join(frontend, 'app/utils/ranch-development-appearances-manifest.json'),
)
const manifest = JSON.parse(manifestBytes)
// Git管理JSONだけCRLFをLFに正規化する。PNG・原本manifest・RGBAのSHAはraw bytesのまま。
const snapshotBytes = fs.readFileSync(
  path.join(frontend, 'scripts/fixtures/ranch-development-appearances-rgba.json'),
)
assert.equal(
  sha(Buffer.from(snapshotBytes.toString('utf8').replace(/\r\n/g, '\n'))),
  'b6f68996b333d3e2e4aa567575526661e8b13279d21e14774a04c3a3977e7cf9',
)
const snapshot = JSON.parse(snapshotBytes)
export function validateMetadata(data) {
  assert.equal(data.schemaVersion, 1)
  assert.equal(data.catalogVersion, '4')
  assert.equal(data.entries.length, 384)
  const legacy = JSON.parse(
    fs.readFileSync(path.join(frontend, 'app/utils/ranch-development-clips-manifest.json')),
  )
  const expected = new Set(
    legacy.entries.flatMap((e) =>
      [1, 2, 3, 4].map((v) =>
        [e.speciesKey, `DEV_APPEARANCE_${v}`, e.stage, e.renderStyle].join(':'),
      ),
    ),
  )
  const original = new Map(snapshot.records.map((r) => [r.assetKey + ':' + r.action, r]))
  const seen = new Set()
  for (const e of data.entries) {
    assert.match(e.speciesKey, /^DEV_CLIP_[A-Z_]+$/)
    assert.match(e.variantKey, /^DEV_APPEARANCE_[1-4]$/)
    assert.ok(['BABY', 'JUVENILE', 'ADULT'].includes(e.stage))
    assert.ok(['PIXEL', 'PAINT_2D'].includes(e.renderStyle))
    assert.equal(e.catalogVersion, '4')
    const source = legacy.entries.find(
      (v) =>
        v.speciesKey === e.speciesKey && v.stage === e.stage && v.renderStyle === e.renderStyle,
    )
    assert.ok(source)
    assert.equal(e.sourceSpecies, source.sourceSpecies)
    assert.equal(e.habitat, source.habitat)
    const key = [e.speciesKey, e.variantKey, e.stage, e.renderStyle].join(':')
    assert.equal(e.assetKey, key)
    assert.ok(expected.has(key))
    assert.ok(!seen.has(key))
    seen.add(key)
    assert.deepEqual(Object.keys(e.clips).sort(), ['feed', 'happy', 'idle', 'walk'])
    for (const [action, c] of Object.entries(e.clips)) {
      assert.equal(c.action, action)
      assert.equal(c.frames, 4)
      assert.equal(c.phaseStarts.length, 4)
      assert.equal(c.phaseMs.length, 4)
      assert.ok(Number.isFinite(c.durationMs) && c.durationMs > 0)
      assert.equal(c.phaseStarts[0], 0)
      for (let i = 0; i < 4; i++) {
        assert.ok(
          c.phaseStarts[i] >= 0 &&
            c.phaseStarts[i] < 1 &&
            (i === 0 || c.phaseStarts[i] > c.phaseStarts[i - 1]),
        )
        assert.ok(
          Math.abs(c.phaseMs[i] - ((c.phaseStarts[i + 1] ?? 1) - c.phaseStarts[i]) * c.durationMs) <
            1e-7,
        )
      }
      assert.equal(c.provenance.length, 4)
      assert.deepEqual(c.provenance, original.get(key + ':' + action)?.provenance)
      for (const source of c.provenance) {
        assert.match(source.path, /^outputs\/[a-zA-Z0-9_./-]+\.png$/)
        assert.ok(!source.path.includes('..'))
        assert.match(source.sha256, /^[a-f0-9]{64}$/)
      }
      for (const image of [c, e.fallback]) {
        assert.match(image.sourcePath, /^[a-zA-Z0-9_./-]+\.png$/)
        assert.ok(!image.sourcePath.includes('..'))
        assert.equal(image.src, '/dev-fixtures/ranch/appearance64-v4/' + image.sourcePath)
        assert.match(image.sourceSha256, /^[a-f0-9]{64}$/)
      }
    }
  }
}
export function validateInventory(expected, actual) {
  assert.equal(expected.length, 1920)
  assert.equal(new Set(expected).size, 1920)
  assert.deepEqual([...actual].sort(), [...expected].sort())
}
function listFiles(dir, prefix = '') {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((item) => {
    const rel = prefix ? `${prefix}/${item.name}` : item.name
    if (item.isDirectory()) return listFiles(path.join(dir, item.name), rel)
    assert.ok(item.isFile(), `通常ファイル以外: ${rel}`)
    return [rel]
  })
}
export async function verify() {
  validateMetadata(manifest)
  const expectedPaths = manifest.entries.flatMap((e) => [
    e.fallback.sourcePath,
    ...Object.values(e.clips).map((c) => c.sourcePath),
  ])
  validateInventory(expectedPaths, listFiles(root))
  assert.equal(
    sha(Buffer.from(manifestBytes.toString('utf8').replace(/\r\n/g, '\n'))),
    '01acc16644bda2fb07539ba0fdb7ed7d7eeee58216d487d74d41fc85c48b20d9',
  )
  assert.equal(snapshot.records.length, 1536)
  assert.ok(!fs.existsSync(path.join(frontend, 'public/dev-fixtures/ranch/appearance64-v4')))
  const records = new Map(snapshot.records.map((r) => [r.assetKey + ':' + r.action, r]))
  assert.equal(records.size, 1536)
  for (const e of manifest.entries)
    for (const [action, c] of Object.entries(e.clips)) {
      const data = fs.readFileSync(path.join(root, c.sourcePath))
      assert.equal(sha(data), c.sourceSha256)
      const img = await sharp(data).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
      assert.equal(img.info.width, c.cell * 4)
      assert.equal(img.info.height, c.cell)
      assert.equal(c.sourceWidth, c.cell * 4)
      assert.equal(c.sourceHeight, c.cell)
      const snap = records.get(e.assetKey + ':' + action)
      assert.ok(snap)
      assert.equal(snap.frameRGBA.length, 4)
      assert.deepEqual(c.provenance, snap.provenance)
      for (let i = 0; i < 4; i++) {
        const tile = Buffer.alloc(c.cell * c.cell * 4)
        for (let y = 0; y < c.cell; y++)
          img.data.copy(
            tile,
            y * c.cell * 4,
            (y * c.cell * 4 + i * c.cell) * 4,
            (y * c.cell * 4 + (i + 1) * c.cell) * 4,
          )
        assert.equal(sha(tile), snap.frameRGBA[i])
      }
      if (action === 'idle') {
        const f = fs.readFileSync(path.join(root, e.fallback.sourcePath))
        assert.equal(sha(f), e.fallback.sourceSha256)
        assert.equal(sha(f), c.provenance[0].sha256)
      }
    }
  console.log('v4: 384 entries, 1536 atlases, 384 fallbacks, 6144 RGBA frames verified')
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  await verify()
