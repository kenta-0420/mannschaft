// 承認済みRGBAを変換せず正式identityへ再配置する。実行先は専用worktreeだけ。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { parseArgs } from 'node:util'
import { fileURLToPath } from 'node:url'
const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const normalizedHash = bytes => hash(Buffer.from(bytes.toString('utf8').replace(/\r\n/g, '\n')))
const expectedCare = '109c6116c99cc73737f1f7fbd23fb166a2b17cac74e11b16f5b95be717a7a63d'
const expectedWalk = 'de0783e63ab6b759df313be2e46d00045257365c27fa80ec8e96b953c9aaad23'
const packVersion = 'ranch-approved-20261108-v1'
const { values } = parseArgs({ options: { originals: { type: 'string' }, 'sharp-module': { type: 'string' },
  'master-assets': { type: 'string' }, evidence: { type: 'string' }, adoption: { type: 'string' } } })
assert.ok(values.originals && values['master-assets'] && values.evidence && values.adoption, '原本root・採択証拠・master出力・証拠出力を明示')
const require = createRequire(import.meta.url)
const sharp = require(values['sharp-module'] ? path.resolve(values['sharp-module']) : 'sharp')
const adoption = JSON.parse(fs.readFileSync(values.adoption, 'utf8').replace(/^\uFEFF/, ''))
assert.equal(adoption.birth81?.adopted, true)
assert.equal(adoption.questionnaire?.adopted, true)
const originalRoot = fs.realpathSync(values.originals)
assert.equal(hash(fs.readFileSync(path.join(originalRoot, 'outputs/dinosaur-care-complete16-20261106/manifest.json'))), expectedCare)
assert.equal(hash(fs.readFileSync(path.join(originalRoot, 'outputs/dinosaur-latest16-four-color-review-20261026/manifest.json'))), expectedWalk)
const bytes = fs.readFileSync(path.join(frontend, 'app/utils/ranch-development-appearances-manifest.json'))
assert.equal(normalizedHash(bytes), '01acc16644bda2fb07539ba0fdb7ed7d7eeee58216d487d74d41fc85c48b20d9')
const source = JSON.parse(bytes)
const snapshotBytes = fs.readFileSync(path.join(frontend, 'scripts/fixtures/ranch-development-appearances-rgba.json'))
assert.equal(normalizedHash(snapshotBytes), 'b6f68996b333d3e2e4aa567575526661e8b13279d21e14774a04c3a3977e7cf9')
const snapshot = new Map(JSON.parse(snapshotBytes).records.map(row => [row.assetKey + ':' + row.action, row]))
const output = path.join(frontend, 'public/ranch/approved', packVersion)
const sourceRoot = path.join(frontend, 'dev-fixtures/ranch/appearance64-v4')
const records = [], entries = [], assetRows = []
const put = (name, data) => {
  assert.match(name, /^[A-Za-z0-9_/-]+\.png$|^manifest\.json$/)
  const file = path.join(output, name)
  if (fs.existsSync(file)) assert.deepEqual(fs.readFileSync(file), data, '既存packは上書きしない')
  else { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, data, { flag: 'wx' }) }
}
const checkedSources = new Map()
for (const entry of source.entries) {
  const variantKey = 'V' + entry.variantKey.slice(-1)
  const style = entry.renderStyle === 'PIXEL' ? 'pixel' : 'paint'
  const assetKey = `${entry.sourceSpecies}-${variantKey}-${entry.stage.toLowerCase()}-${style}`
  const clips = {}
  for (const [action, clip] of Object.entries(entry.clips)) {
    const data = fs.readFileSync(path.join(sourceRoot, clip.sourcePath))
    assert.equal(hash(data), clip.sourceSha256)
    const decoded = await sharp(data).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
    assert.equal(decoded.info.width, clip.cell * 4)
    assert.equal(decoded.info.height, clip.cell)
    const record = snapshot.get(entry.assetKey + ':' + action)
    assert.deepEqual(clip.provenance, record.provenance)
    const frameRGBA = []
    for (let frame = 0; frame < 4; frame++) {
      const tile = await sharp(data).extract({ left: frame * clip.cell, top: 0, width: clip.cell, height: clip.cell }).ensureAlpha().raw().toBuffer()
      const digest = hash(tile)
      assert.equal(digest, record.frameRGBA[frame])
      const provenance = clip.provenance[frame]
      let originalDigest = checkedSources.get(provenance.path)
      if (!originalDigest) {
        const originalPath = fs.realpathSync(path.join(originalRoot, provenance.path))
        assert.ok(!path.relative(originalRoot, originalPath).startsWith('..'))
        const original = fs.readFileSync(originalPath)
        assert.equal(hash(original), provenance.sha256)
        const raw = await sharp(original).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
        assert.equal(raw.info.width, clip.cell)
        assert.equal(raw.info.height, clip.cell)
        originalDigest = hash(raw.data)
        checkedSources.set(provenance.path, originalDigest)
      }
      assert.equal(digest, originalDigest, '正式frameと承認原本RGBAの一致')
      frameRGBA.push(digest)
    }
    const filePath = clip.sourcePath
    put(filePath, data)
    clips[action] = { filePath, sourceSha256: clip.sourceSha256, frameWidth: clip.cell, frameHeight: clip.cell,
      frameCount: 4, durationMs: clip.durationMs, phaseStarts: clip.phaseStarts, phaseMs: clip.phaseMs,
      loop: clip.loop, returnTo: clip.returnTo, floorByFrame: clip.floorByFrame,
      sourceFloor384: clip.sourceFloor384, sourceFloorByFrame384: clip.sourceFloorByFrame384,
      tailMarks: clip.tailMarks, shadowByFrame: clip.shadowByFrame ?? null }
    records.push({ assetKey, action, frameRGBA, provenance: clip.provenance })
  }
  const fallback = fs.readFileSync(path.join(sourceRoot, entry.fallback.sourcePath))
  assert.equal(hash(fallback), entry.fallback.sourceSha256)
  assert.equal(entry.fallback.sourceSha256, entry.clips.idle.provenance[0].sha256)
  put(entry.fallback.sourcePath, fallback)
  entries.push({ speciesKey: entry.sourceSpecies, variantKey, stage: entry.stage, renderStyle: entry.renderStyle,
    assetKey, ...Object.fromEntries(['filePath', 'sourceSha256', 'frameWidth', 'frameHeight', 'frameCount'].map(key => [key, clips.idle[key]])),
    fallbackPath: entry.fallback.sourcePath, fallbackSha256: entry.fallback.sourceSha256,
    sourceSpeciesKey: entry.sourceSpecies, habitat: entry.habitat, logicalCanvas: entry.logicalCanvas,
    careSpriteRect: entry.careSpriteRect, rendererProfile: 'APPROVED_APPEARANCE_V4', clips })
}
const eggs = {}
for (const [renderStyle, style, cell] of [['PIXEL', 'pixel', 96], ['PAINT_2D', 'paint', 384]]) {
  const outline = style === 'pixel'
    ? 'M40 16H56V24H64V36H72V60H76V72H68V80H28V72H20V60H24V36H32V24H40Z'
    : 'M48 16C32 16 24 49 24 62C24 88 72 88 72 62C72 49 64 16 48 16Z'
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96" width="${cell}" height="${cell}"><path d="${outline}" fill="#f7ebd1" stroke="#665b45" stroke-width="3"/><path d="M37 35h8v8h-8zM52 53h9v9h-9zM34 65h7v7h-7z" fill="#b9cb89"/></svg>`
  const data = await sharp(Buffer.from(svg)).png().toBuffer()
  const filePath = `eggs/common-${style}.png`
  put(filePath, data)
  eggs[renderStyle] = { filePath, sha256: hash(data), cell }
}
for (const representative of source.entries.filter(entry => entry.stage === 'BABY')) {
  const variantKey = 'V' + representative.variantKey.slice(-1), style = representative.renderStyle === 'PIXEL' ? 'pixel' : 'paint'
  const egg = eggs[representative.renderStyle]
  entries.push({ speciesKey: representative.sourceSpecies, variantKey, stage: 'EGG', renderStyle: representative.renderStyle,
    assetKey: `${representative.sourceSpecies}-${variantKey}-egg-${style}`, filePath: egg.filePath, sourceSha256: egg.sha256,
    frameWidth: egg.cell, frameHeight: egg.cell, frameCount: 1, fallbackPath: egg.filePath, fallbackSha256: egg.sha256,
    sourceSpeciesKey: representative.sourceSpecies, habitat: representative.habitat,
    logicalCanvas: { width: 96, height: 96 }, careSpriteRect: { x: 0, y: 0, width: 96, height: 96 },
    rendererProfile: 'COMMON_EGG_SVG_V1', clips: null })
}
assert.equal(entries.length, 512)
for (const row of entries) assetRows.push({ speciesKey: row.speciesKey, variantKey: row.variantKey, stage: row.stage,
  style: row.renderStyle, assetKey: row.assetKey, sha256: row.sourceSha256, staticFallbackSha256: row.fallbackSha256 })
const manifest = { schemaVersion: 2, catalogVersion: 20261108, packVersion,
  approvalStatus: 'APPROVED', environment: 'PRODUCTION', entries }
const manifestBytes = Buffer.from(JSON.stringify(manifest, null, 2) + '\n')
put('manifest.json', manifestBytes)
fs.writeFileSync(values['master-assets'], JSON.stringify({ catalogVersion: 20261108, packVersion, manifestSha256: hash(manifestBytes), assets: assetRows }, null, 2) + '\n')
fs.writeFileSync(values.evidence, JSON.stringify({ packVersion, manifestSha256: hash(manifestBytes), careManifestSha256: expectedCare,
  walkManifestSha256: expectedWalk, verifiedFrames: records.length * 4, originalFiles: checkedSources.size,
  tuples: entries.length, pngFiles: 1922, eggs, records }, null, 2) + '\n')
console.log(JSON.stringify({ packVersion, manifestSha256: hash(manifestBytes), tuples: entries.length, verifiedFrames: records.length * 4, pngFiles: 1922 }))
