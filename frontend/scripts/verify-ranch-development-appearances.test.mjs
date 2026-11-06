import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { validateMetadata } from './verify-ranch-development-appearances.mjs'
const manifest = JSON.parse(
  fs.readFileSync(
    new URL('../app/utils/ranch-development-appearances-manifest.json', import.meta.url),
  ),
)
test('v4 validator rejects count, frame, phase, hash and traversal changes', () => {
  validateMetadata(manifest)
  for (const mutate of [
    (d) => d.entries.pop(),
    (d) => d.entries.push(structuredClone(d.entries[0])),
    (d) => (d.entries[0].clips.idle.frames = 3),
    (d) => (d.entries[0].clips.idle.frames = 5),
    (d) => (d.entries[0].clips.idle.phaseStarts[1] = 0),
    (d) => (d.entries[0].clips.idle.durationMs = 0),
    (d) => (d.entries[0].clips.idle.provenance[0].sha256 = '0'.repeat(64)),
    (d) => (d.entries[0].clips.idle.sourcePath = '../escape.png'),
  ]) {
    const candidate = structuredClone(manifest)
    mutate(candidate)
    assert.throws(() => validateMetadata(candidate))
  }
})
