import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { VERSION, ORIGINAL_SHA, PATCHED_SHA, patchBytes } from './patch-nuxt-devtools-simple-git.mjs'

// 正規npm ci/postinstallが配置した本物の配布物を使い、合成一致bytesを作らない。
const patched = readFileSync(new URL('../node_modules/@nuxt/devtools/dist/chunks/module-main.mjs', import.meta.url))
const original = Buffer.from(patched.toString('utf8').replace("import { simpleGit as Git } from 'simple-git';", "import Git from 'simple-git';"))
const digest = bytes => createHash('sha256').update(bytes).digest('hex')

test('本物配布物の元bytesから唯一importだけを変換する', () => {
  assert.equal(digest(original), ORIGINAL_SHA)
  assert.equal(digest(patched), PATCHED_SHA)
  assert.deepEqual(patchBytes(original, VERSION), patched)
})
test('同一の適用済みbytesは再実行で変わらない', () => {
  assert.strictEqual(patchBytes(patched, VERSION), patched)
})
test('対象版以外・改変bytes・重複importを拒否する', () => {
  assert.throws(() => patchBytes(original, '3.4.2'), /DEVTOOLS_COMPATIBILITY_PATCH_REFUSED/)
  assert.throws(() => patchBytes(Buffer.concat([original, Buffer.from('\n')]), VERSION), /DEVTOOLS_COMPATIBILITY_PATCH_REFUSED/)
  assert.throws(() => patchBytes(Buffer.concat([original, Buffer.from("import Git from 'simple-git';")]), VERSION), /DEVTOOLS_COMPATIBILITY_PATCH_REFUSED/)
})
