import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import { inspectArtifact } from './verify-ranch-production-artifact.mjs'
test('production artifact rejects matching PNG bytes and development paths', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ranch-artifact-'))
  try {
    const file = path.join(dir, 'asset')
    const image = Buffer.from('fixture png bytes')
    const hashes = new Set([crypto.createHash('sha256').update(image).digest('hex')])
    const markers = ['/dev-fixtures/ranch/appearance64-v4/']
    fs.writeFileSync(file, 'ordinary artifact')
    assert.equal(inspectArtifact(dir, hashes, markers), 1)
    fs.writeFileSync(file, image)
    assert.throws(() => inspectArtifact(dir, hashes, markers))
    fs.writeFileSync(file, markers[0])
    assert.throws(() => inspectArtifact(dir, hashes, markers))
  } finally {
    const target = path.join(dir, 'asset')
    if (fs.existsSync(target)) fs.unlinkSync(target)
    fs.rmdirSync(dir)
  }
})

function removeLink(link) {
  if (process.platform === 'win32') fs.rmdirSync(link)
  else fs.unlinkSync(link)
}

test('inventory permits internal links and rejects external links and cycles', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ranch-links-'))
  const inner = path.join(dir, 'inner')
  const inside = path.join(dir, 'inside')
  const outside = path.join(dir, 'outside')
  const loop = path.join(inner, 'loop')
  fs.mkdirSync(inner)
  fs.writeFileSync(path.join(inner, 'file'), 'ordinary')
  try {
    fs.symlinkSync(inner, inside, 'junction')
    assert.equal(inspectArtifact(dir, new Set(), []), 1)
    fs.symlinkSync(os.tmpdir(), outside, 'junction')
    assert.throws(() => inspectArtifact(dir, new Set(), []))
    removeLink(outside)
    fs.symlinkSync(dir, loop, 'junction')
    assert.throws(() => inspectArtifact(dir, new Set(), []))
  } finally {
    for (const link of [inside, outside, loop]) if (fs.existsSync(link)) removeLink(link)
    fs.unlinkSync(path.join(inner, 'file'))
    fs.rmdirSync(inner)
    fs.rmdirSync(dir)
  }
})
