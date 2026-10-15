#!/usr/bin/env node
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const DECLARATION = path.join(ROOT, 'config', 'r2', 'bucket-lock.json')
const PRODUCTION_PATTERN = /(^|[-_])(prod|production|live)([-_]|$)/i
const SANDBOX_PATTERN = /(^|[-_])(sandbox|dev|test)([-_]|$)/i
const PRODUCTION_BUCKET = 'mannschaft-storage'
function exactKeys(value, keys, label) { const actual = Object.keys(value ?? {}).sort(); const expected = [...keys].sort(); if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error(`${label} has unexpected fields`) }
export function validateDeclaration(value) {
  if (!Array.isArray(value?.rules) || value.rules.length !== 1) throw new Error('declaration must contain exactly one rule')
  const rule = value.rules[0]
  exactKeys(rule, ['id', 'enabled', 'prefix', 'condition'], 'rule'); exactKeys(rule.condition, ['type', 'maxAgeSeconds'], 'condition')
  if (rule.id !== 'receipts-7y' || rule.enabled !== true || rule.prefix !== 'receipts/' || rule.condition.type !== 'Age' || rule.condition.maxAgeSeconds !== 220924800) throw new Error('declaration does not match required retention rule')
  return value
}
export function loadDeclaration() { const value = JSON.parse(fs.readFileSync(DECLARATION, 'utf8')); return validateDeclaration(value) }
export function validateTarget(accountId, bucketName, allowlist = { accounts: [], buckets: [] }) {
  if (PRODUCTION_PATTERN.test(accountId) || PRODUCTION_PATTERN.test(bucketName) || bucketName.toLowerCase() === PRODUCTION_BUCKET) throw new Error('production target denied')
  if (!SANDBOX_PATTERN.test(bucketName)) throw new Error('bucket must identify sandbox, dev, or test')
  if (!allowlist.accounts.includes(accountId) || !allowlist.buckets.includes(bucketName)) throw new Error('target is outside the allowlist')
}
export function buildRequestRules() { return loadDeclaration().rules }
function normalizeRule(rule) { exactKeys(rule, ['id', 'enabled', 'prefix', 'condition'], 'rule'); exactKeys(rule.condition, ['type', 'maxAgeSeconds'], 'condition'); return { id: rule.id, enabled: rule.enabled, prefix: rule.prefix, condition: { type: rule.condition.type, maxAgeSeconds: rule.condition.maxAgeSeconds } } }
function normalizeRules(rules) { if (!Array.isArray(rules)) throw new Error('rules must be an array'); return rules.map(normalizeRule).sort((a, b) => String(a.id).localeCompare(String(b.id))) }
export function inspectResponse(response) { const expected = normalizeRules(buildRequestRules()); const actual = normalizeRules(response?.rules ?? []); if (JSON.stringify(actual) !== JSON.stringify(expected)) throw new Error('response does not match declaration'); return true }
function envList(name) { return (process.env[name] ?? '').split(',').map(s => s.trim()).filter(Boolean) }
async function request(method, accountId, bucketName, token, body, fetchImpl) { const response = await fetchImpl(`https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}/r2/buckets/${encodeURIComponent(bucketName)}/lock`, { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body, signal: AbortSignal.timeout(10000) }); const text = await response.text(); if (!response.ok) throw new Error(`Cloudflare API failed (${response.status}): ${text.slice(0, 500)}`); let parsed; try { parsed = JSON.parse(text) } catch { throw new Error('Cloudflare API response is not JSON') }; if (parsed.success === false) throw new Error(`Cloudflare API error: ${JSON.stringify(parsed.errors ?? parsed)}`); return parsed.result ?? parsed }
export async function run({ mode = 'dry-run', accountId = process.env.R2_LOCK_ACCOUNT_ID, bucketName = process.env.R2_LOCK_BUCKET_NAME, token = process.env.CLOUDFLARE_API_TOKEN, allowlist = { accounts: envList('R2_LOCK_ALLOWED_ACCOUNTS'), buckets: envList('R2_LOCK_ALLOWED_BUCKETS') }, fetchImpl = globalThis.fetch } = {}) {
  if (!['dry-run', 'inspect', 'apply'].includes(mode)) throw new Error(`unknown mode: ${mode}`)
  if (mode === 'dry-run') return { mode, rules: buildRequestRules() }
  if (!accountId || !bucketName) throw new Error('R2 lock account and bucket are required'); validateTarget(accountId, bucketName, allowlist)
  if (mode === 'apply' && process.env.R2_LOCK_ALLOW_APPLY !== 'true') throw new Error('apply requires R2_LOCK_ALLOW_APPLY=true'); if (!token) throw new Error('Cloudflare API token is required')
  if (mode === 'apply') await request('PUT', accountId, bucketName, token, JSON.stringify({ rules: buildRequestRules() }), fetchImpl)
  inspectResponse(await request('GET', accountId, bucketName, token, undefined, fetchImpl)); return { mode, matched: true }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) { const mode = process.argv.includes('--apply') ? 'apply' : process.argv.includes('--inspect') ? 'inspect' : 'dry-run'; run({ mode }).then(result => console.log(JSON.stringify(result))).catch(error => { console.error(error.message); process.exitCode = 1 }) }
