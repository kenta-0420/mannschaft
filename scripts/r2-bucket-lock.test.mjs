import test from 'node:test'
import assert from 'node:assert/strict'
import { loadDeclaration, validateTarget, buildRequestRules, inspectResponse, run } from './r2-bucket-lock.mjs'
const allowlist = { accounts: ['sandbox-account'], buckets: ['sandbox-bucket', 'dev-bucket', 'test-bucket'] }
const declared = { rules: buildRequestRules() }
const response = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
test('宣言はreceipts/の7年Ageルールだけを持つ', () => assert.deepEqual(loadDeclaration().rules, [{ id: 'receipts-7y', enabled: true, prefix: 'receipts/', condition: { type: 'Age', maxAgeSeconds: 220924800 } }]))
test('本番識別子と本番実バケットを拒否しsandbox系だけを許可する', () => {
  for (const bucket of ['sandbox-bucket', 'dev-bucket', 'test-bucket']) assert.doesNotThrow(() => validateTarget('sandbox-account', bucket, allowlist))
  assert.throws(() => validateTarget('prod-account', 'sandbox-bucket', allowlist))
  assert.throws(() => validateTarget('sandbox-account', 'mannschaft-storage', { accounts: ['sandbox-account'], buckets: ['mannschaft-storage'] }))
  assert.throws(() => validateTarget('sandbox-account', 'bucket', { accounts: ['sandbox-account'], buckets: ['bucket'] }))
})
test('応答のプロパティ順序が違っても意味が同じなら一致する', () => assert.doesNotThrow(() => inspectResponse({ rules: [{ condition: { maxAgeSeconds: 220924800, type: 'Age' }, prefix: 'receipts/', enabled: true, id: 'receipts-7y' }] })))
test('余分・不足・ルール不一致は拒否する', () => {
  assert.throws(() => inspectResponse({ rules: [{ ...declared.rules[0], extra: true }] }))
  assert.throws(() => inspectResponse({ rules: [] }))
  assert.throws(() => inspectResponse({ rules: [{ ...declared.rules[0], prefix: 'other/' }] }))
})
test('dry-runはfetchせず宣言を返す', async () => assert.equal((await run({ mode: 'dry-run', fetchImpl: () => { throw new Error('fetchしてはならない') } })).mode, 'dry-run'))
test('applyは明示ゲートなしではfetchしない', async () => {
  const previous = process.env.R2_LOCK_ALLOW_APPLY
  delete process.env.R2_LOCK_ALLOW_APPLY
  await assert.rejects(() => run({ mode: 'apply', accountId: 'sandbox-account', bucketName: 'sandbox-bucket', token: 'token', allowlist, fetchImpl: () => { throw new Error('fetchしてはならない') } }), /R2_LOCK_ALLOW_APPLY/)
  if (previous === undefined) delete process.env.R2_LOCK_ALLOW_APPLY; else process.env.R2_LOCK_ALLOW_APPLY = previous
})
test('inspectはGET、applyはPUT後GETでURL・認証・本文を検証する', async () => {
  const calls = []
  const fetchImpl = async (url, init) => { calls.push({ url, init }); return response({ success: true, result: declared }) }
  await run({ mode: 'inspect', accountId: 'sandbox-account', bucketName: 'sandbox-bucket', token: 'secret', allowlist, fetchImpl })
  process.env.R2_LOCK_ALLOW_APPLY = 'true'
  await run({ mode: 'apply', accountId: 'sandbox-account', bucketName: 'sandbox-bucket', token: 'secret', allowlist, fetchImpl })
  delete process.env.R2_LOCK_ALLOW_APPLY
  assert.equal(calls[0].url, 'https://api.cloudflare.com/client/v4/accounts/sandbox-account/r2/buckets/sandbox-bucket/lock')
  assert.equal(calls[0].init.method, 'GET')
  assert.equal(calls[0].init.headers.Authorization, 'Bearer secret')
  assert.equal(calls[0].init.signal instanceof AbortSignal, true)
  assert.equal(calls[1].init.method, 'PUT')
  assert.equal(calls[1].init.body, JSON.stringify(declared))
})
