// 正本export manifestと配布subsetをbyte/metadataの両面から照合する。
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const pin = 'bcc001841b2efaf75678376112303af216e0ae805781619bafc522a145efa28c'
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const bytes = fs.readFileSync(path.join(frontend, 'scripts/fixtures/ranch-development-clips-export-manifest.json'))
assert.equal(sha(bytes), pin, 'pinned export manifest')
const source = JSON.parse(bytes)
const manifest = JSON.parse(fs.readFileSync(path.join(frontend, 'app/utils/ranch-development-clips-manifest.json'), 'utf8'))
assert.equal(manifest.schemaVersion, 1)
assert.equal(manifest.catalogVersion, '3')
assert.equal(manifest.sourceManifestSha256, pin)
assert.equal(manifest.entries.length, 96)
assert.equal(source.clips.length, 384)
assert.equal(source.fallbacks.length, 96)
const mappings = [
  ['allosaurus', 'DEV_CLIP_ALLOSAURUS', 'LAND'], ['ankylosaurus', 'DEV_CLIP_ANKYLOSAURUS', 'LAND'],
  ['archelon', 'DEV_CLIP_ARCHELON', 'SEA'], ['brachiosaurus', 'DEV_CLIP_BRACHIOSAURUS', 'LAND'],
  ['giganotosaurus', 'DEV_CLIP_GIGANOTOSAURUS', 'LAND'], ['iguanodon', 'DEV_CLIP_IGUANODON', 'LAND'],
  ['longneck-spikes', 'DEV_CLIP_LONGNECK_SPIKES', 'LAND'], ['marine-longjaw', 'DEV_CLIP_MARINE_LONGJAW', 'SEA'],
  ['parasaurolophus', 'DEV_CLIP_PARASAUROLOPHUS', 'LAND'], ['pinacosaurus', 'DEV_CLIP_PINACOSAURUS', 'LAND'],
  ['plesiosaurus', 'DEV_CLIP_PLESIOSAURUS', 'SEA'], ['pteranodon', 'DEV_CLIP_PTERANODON', 'AIR'],
  ['spinosaurus', 'DEV_CLIP_SPINOSAURUS', 'LAND'], ['stegosaurus', 'DEV_CLIP_STEGOSAURUS', 'LAND'],
  ['triceratops', 'DEV_CLIP_TRICERATOPS', 'LAND'], ['tyrannosaurus', 'DEV_CLIP_TYRANNOSAURUS', 'LAND'],
]
const stages = { BABY: 'baby', JUVENILE: 'juvenile', ADULT: 'adult' }
const styles = { PIXEL: 'pixel', PAINT_2D: '2d' }
const actions = ['idle', 'walk', 'feed', 'happy']
const imageRoot = path.join(frontend, 'dev-fixtures/ranch/base16-v3')
assert.ok(!fs.existsSync(path.join(frontend, 'public/dev-fixtures/ranch/base16-v3')), 'v3画像は常時公開publicへ配置しない')
const expectedPaths = new Set()
let totalBytes = 0
const frameValues = value => value ? ['A', 'B', 'C', 'D'].map(key => value[key] ?? value[key.toLowerCase()] ?? null) : null
function verifyImage(image, original, frameCount) {
  assert.equal(original.variant, 'BASE_ONLY')
  assert.match(original.path, /^[a-zA-Z0-9_./-]+\.png$/)
  assert.ok(!original.path.includes('..'))
  assert.equal(image.sourcePath, original.path)
  assert.equal(image.src, '/dev-fixtures/ranch/base16-v3/' + original.path)
  assert.equal(image.sourceSha256, original.sha256)
  assert.ok(!expectedPaths.has(original.path), '画像重複')
  expectedPaths.add(original.path)
  const data = fs.readFileSync(path.join(imageRoot, original.path))
  assert.equal(sha(data), original.sha256, original.path)
  assert.equal(data.subarray(0, 8).toString('hex'), '89504e470d0a1a0a')
  const width = data.readUInt32BE(16), height = data.readUInt32BE(20)
  assert.equal(width, original.cell * frameCount)
  assert.equal(height, original.cell)
  assert.equal(image.sourceWidth, width)
  assert.equal(image.sourceHeight, height)
  totalBytes += data.length
}
const seenEntries = new Set()
for (const entry of manifest.entries) {
  const mapping = mappings.find(([species]) => species === entry.sourceSpecies)
  assert.ok(mapping, '明示species')
  assert.equal(entry.speciesKey, mapping[1])
  assert.equal(entry.habitat, mapping[2])
  assert.equal(entry.variantKey, 'DEV_BASE_16_CLIPS_V1')
  assert.equal(entry.catalogVersion, '3')
  assert.equal(entry.approval, 'DRAFT')
  assert.equal(entry.origin, 'ISOLATED_DEVELOPMENT')
  assert.deepEqual(entry.logicalCanvas, { width: 112, height: 112 })
  assert.deepEqual(entry.careSpriteRect, { x: 8, y: 8, width: 96, height: 96 })
  assert.ok(Object.hasOwn(stages, entry.stage))
  assert.ok(Object.hasOwn(styles, entry.renderStyle))
  const key = [entry.speciesKey, entry.stage, entry.renderStyle].join(':')
  assert.equal(entry.assetKey, key)
  assert.ok(!seenEntries.has(key), 'entry重複')
  seenEntries.add(key)
  const match = row => row.species === entry.sourceSpecies && row.stage === stages[entry.stage] && row.style === styles[entry.renderStyle]
  const fallback = source.fallbacks.filter(match)
  assert.equal(fallback.length, 1)
  assert.equal(fallback[0].frame, 'A')
  verifyImage(entry.fallback, fallback[0], 1)
  assert.deepEqual(Object.keys(entry.clips).sort(), [...actions].sort())
  for (const action of actions) {
    const originals = source.clips.filter(row => match(row) && row.action === action)
    assert.equal(originals.length, 1)
    const original = originals[0], clip = entry.clips[action]
    verifyImage(clip, original, 4)
    assert.equal(clip.action, action)
    assert.equal(clip.cell, original.cell)
    assert.equal(clip.frames, 4)
    assert.equal(original.layout, 'horizontal')
    assert.equal(clip.durationMs, original.seconds * 1000)
    assert.ok(clip.durationMs > 0 && Number.isFinite(clip.durationMs))
    assert.deepEqual(clip.phaseStarts, original.phaseStarts)
    assert.deepEqual(clip.phaseMs, original.phaseMs)
    assert.equal(clip.phaseStarts.length, 4)
    assert.equal(clip.phaseStarts[0], 0)
    for (let i = 0; i < 4; i++) {
      assert.ok(clip.phaseStarts[i] >= 0 && clip.phaseStarts[i] < 1)
      assert.ok(i === 0 || clip.phaseStarts[i] > clip.phaseStarts[i - 1])
      assert.ok(Math.abs(clip.phaseMs[i] - ((clip.phaseStarts[i + 1] ?? 1) - clip.phaseStarts[i]) * clip.durationMs) < 1e-8)
      assert.ok(i * clip.cell + clip.cell <= clip.sourceWidth, 'crop bounds')
    }
    assert.equal(clip.loop, original.loop)
    assert.equal(clip.returnTo, original.returnTo)
    assert.deepEqual(clip.floorByFrame, frameValues(original.floorByFrame))
    assert.equal(clip.sourceFloor384, original.sourceFloor384 ?? null)
    assert.deepEqual(clip.sourceFloorByFrame384, frameValues(original.sourceFloorByFrame384))
    assert.deepEqual(clip.tailMarks, original.nativeEffectMetadata?.tailMarks ?? null)
  }
}
const actualPaths = []
function visit(directory) {
  for (const name of fs.readdirSync(directory)) {
    const full = path.join(directory, name)
    if (fs.statSync(full).isDirectory()) visit(full)
    else actualPaths.push(path.relative(imageRoot, full).replaceAll('\\', '/'))
  }
}
visit(imageRoot)
assert.deepEqual(actualPaths.sort(), [...expectedPaths].sort(), '余分な配布物/欠落なし')
assert.equal(expectedPaths.size, 480)
assert.equal(totalBytes, 72678652)
console.log(JSON.stringify({ status: 'PASS', entries: 96, atlases: 384, fallbacks: 96, bytes: totalBytes, sourceManifestSha256: pin }))
