// 実gitleaksで公開IDの限定例外と検知保持を検査する。疑似値/生reportは専用TMPから出さない。
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const { values } = parseArgs({ options: { gitleaks: { type: 'string' }, evidence: { type: 'string' },
  'history-range': { type: 'string' }, 'baseline-commit': { type: 'string' }, 'git-directory': { type: 'string' },
  'alternate-objects': { type: 'string' }, 'expected-history-findings': { type: 'string', default: '1680' },
  'counterprobes-only': { type: 'boolean', default: false } } })
assert.ok(values.gitleaks && values.evidence && (values['counterprobes-only'] || values['baseline-commit'] && values['history-range']), '実binary/証拠path/履歴範囲を明示する')
if (values['baseline-commit']) assert.match(values['baseline-commit'], /^[a-f0-9]{40}$/)
if (values['history-range']) assert.match(values['history-range'], /^[a-f0-9]{40}\.\.[a-f0-9]{40}$/)
const deadline = Date.now() + 180000
const remaining = () => Math.max(1, deadline - Date.now())
const version = execFileSync(values.gitleaks, ['version'], { encoding: 'utf8', timeout: remaining(), windowsHide: true }).trim()
assert.equal(version, '8.28.0')
const sourceEnv = { ...process.env }
if (values['git-directory']) { sourceEnv.GIT_DIR = path.resolve(values['git-directory']); sourceEnv.GIT_WORK_TREE = repository }
if (values['alternate-objects']) sourceEnv.GIT_ALTERNATE_OBJECT_DIRECTORIES = values['alternate-objects']
const config = path.join(repository, '.gitleaks.toml')
const currentConfig = fs.readFileSync(config, 'utf8')
const manifest = JSON.parse(fs.readFileSync(path.join(repository, 'frontend/public/ranch/approved/ranch-approved-20261108-v1/manifest.json')))
const publicId = manifest.entries.find(row => currentConfig.includes(`'''^${row.assetKey}$'''`))?.assetKey
assert.ok(publicId)
const temporaryParent = fs.realpathSync(os.tmpdir())
const temporary = fs.mkdtempSync(path.join(temporaryParent, 'ranch-public-gitleaks-'))
const evidence = { scannerVersion: version, historyRange: values['history-range'], temporary, sourceCopyCreated: false,
  realCredentialsUsed: false, candidateValuesPrinted: 0, timeoutMs: 180000, cases: {} }
const metadata = rows => rows.map(row => ({ File: row.File, RuleID: row.RuleID, Line: row.StartLine, Commit: row.Commit, Fingerprint: row.Fingerprint }))
const sha = data => crypto.createHash('sha256').update(data).digest('hex')
function scan(name, source, configuration, env, historyRange = '--all') {
  const report = path.join(temporary, `${name}.json`)
  const start = new Date().toISOString()
  const run = spawnSync(values.gitleaks, ['detect', '--source', source, '--config', configuration, '--redact', '--no-banner',
    '--report-format', 'json', '--report-path', report, '--exit-code', '13', ...(historyRange === null ? ['--no-git'] : [`--log-opts=${historyRange}`])],
  { env, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout: remaining(), windowsHide: true })
  if (run.error) throw new Error(`scanner invocation failed: ${run.error.code}`)
  assert.ok(run.status === 0 || run.status === 13, `scanner process failed: ${run.status}`)
  const bytes = fs.existsSync(report) ? fs.readFileSync(report) : Buffer.from('[]')
  const rows = JSON.parse(bytes)
  evidence.cases[name] = { start, end: new Date().toISOString(), exitCode: run.status, findings: rows.length,
    metadata: metadata(rows), stdoutBytes: Buffer.byteLength(run.stdout), stderrBytes: Buffer.byteLength(run.stderr),
    stdoutSha256: sha(run.stdout), stderrSha256: sha(run.stderr) }
  return { rows, reportBytes: bytes, exitCode: run.status }
}
try {
  const self = scan('regression-source-no-secret', fileURLToPath(import.meta.url), config, process.env, null)
  assert.equal(self.exitCode, 0)
  assert.equal(self.rows.length, 0)
  if (!values['counterprobes-only']) {
    const baseline = path.join(temporary, 'baseline.toml')
    fs.writeFileSync(baseline, execFileSync('git', ['-C', repository, 'show', `${values['baseline-commit']}:.gitleaks.toml`], { env: sourceEnv, timeout: remaining(), windowsHide: true }))
    const old = scan('history-baseline', repository, baseline, sourceEnv, values['history-range'])
    assert.equal(old.exitCode, 13)
    assert.equal(old.rows.length, Number(values['expected-history-findings']))
    assert.ok(old.rows.every(row => row.RuleID === 'generic-api-key'))
    const updated = scan('history-limited-config', repository, config, sourceEnv, values['history-range'])
    assert.equal(updated.exitCode, 0)
    assert.equal(updated.rows.length, 0)
  }

  const fixture = path.join(temporary, 'synthetic-fixture')
  fs.mkdirSync(fixture)
  const git = args => execFileSync('git', ['-C', fixture, '-c', 'user.name=Ranch scanner synthetic fixture',
    '-c', 'user.email=ranch-scanner@example.invalid', '-c', `core.hooksPath=${path.join(temporary, 'no-hooks')}`, ...args], { stdio: 'pipe', timeout: remaining(), windowsHide: true })
  git(['init', '--quiet'])
  const target = 'frontend/app/utils/ranch-production-assets.ts', unlisted = 'unlisted/ranch-public-id.ts'
  for (const name of [target, unlisted]) {
    fs.mkdirSync(path.dirname(path.join(fixture, name)), { recursive: true })
    fs.writeFileSync(path.join(fixture, name), `// Synthetic public identity fixture.\nexport const assetKey = ${JSON.stringify(publicId)}\n`)
  }
  git(['add', '.']); git(['commit', '--quiet', '-m', '公開IDの合成fixture'])
  let fake, entropy
  do {
    fake = crypto.randomBytes(32).toString('base64url')
    const counts = new Map()
    for (const character of fake) counts.set(character, (counts.get(character) ?? 0) + 1)
    entropy = -[...counts.values()].reduce((sum, count) => sum + count / fake.length * Math.log2(count / fake.length), 0)
  } while (entropy < 4.5)
  fs.appendFileSync(path.join(fixture, target), `// Runtime-generated synthetic probe; never used for an account.\nexport const apiKey = ${JSON.stringify(fake)}\n`)
  git(['add', '.']); git(['commit', '--quiet', '-m', '新commitと別lineでの検知probe'])
  const newCommit = git(['rev-parse', 'HEAD']).toString('utf8').trim()
  const regression = scan('same-path-new-secret-and-unlisted-public-id', fixture, config, process.env)
  assert.equal(regression.exitCode, 13)
  assert.equal(regression.rows.length, 2)
  assert.ok(regression.rows.some(row => row.RuleID === 'generic-api-key' && row.File === target && row.Commit === newCommit && row.StartLine === 4))
  assert.ok(regression.rows.some(row => row.RuleID === 'generic-api-key' && row.File === unlisted))
  assert.ok(!regression.reportBytes.includes(Buffer.from(fake)), '疑似値をreportへ露出しない')

  const probeConfig = path.join(temporary, 'other-rule.toml')
  fs.writeFileSync(probeConfig, currentConfig + `\n[[rules]]\nid = "ranch-synthetic-public-id-probe"\ndescription = "限定例外が別ruleへ波及しない合成試験"\nregex = '''(${publicId})'''\nsecretGroup = 1\n`)
  const other = scan('other-rule-remains-active', fixture, probeConfig, process.env)
  assert.ok(other.rows.some(row => row.RuleID === 'ranch-synthetic-public-id-probe' && row.File === target))
  // 同じ公開値/lineの複数rule検知はscanner側で重複排除され得る。別の疑似keyは残る。
  assert.ok(other.rows.some(row => row.RuleID === 'generic-api-key' && row.File === target && row.Commit === newCommit && row.StartLine === 4))
  assert.ok(!other.reportBytes.includes(Buffer.from(fake)))
  evidence.status = values['counterprobes-only'] ? 'PASS_ACTUAL_GITLEAKS_BOUNDARIES_HISTORY_PENDING' : 'PASS_ACTUAL_GITLEAKS_HISTORY_AND_BOUNDARIES'
} finally {
  assert.equal(path.dirname(fs.realpathSync(temporary)), temporaryParent)
  assert.ok(path.basename(temporary).startsWith('ranch-public-gitleaks-'))
  fs.rmSync(temporary, { recursive: true, force: true })
  evidence.temporaryRemoved = !fs.existsSync(temporary)
  fs.writeFileSync(values.evidence, JSON.stringify(evidence, null, 2) + '\n')
}
console.log(JSON.stringify({ status: evidence.status, scannerVersion: version,
  cases: Object.fromEntries(Object.entries(evidence.cases).map(([name, value]) => [name, { exitCode: value.exitCode, findings: value.findings }])),
  temporaryRemoved: evidence.temporaryRemoved }))
