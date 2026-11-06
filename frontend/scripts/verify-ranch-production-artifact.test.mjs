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
