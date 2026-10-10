import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkAudit } from './audit-with-exemption.mjs'

const NOW = Date.parse('2026-10-02T03:00:00Z')
const URL = 'https://github.com/advisories/GHSA-86w9-cpqp-85rv'
const LOCK = { packages: { 'node_modules/node-forge': { version: '1.4.0' } } }
const advisory = (severity = 'high', url = URL) => ({
  name: 'node-forge',
  dependency: 'node-forge',
  severity,
  url,
  range: '<=1.4.0',
})
const entry = (name, via, severity = 'high') => ({
  name,
  severity,
  isDirect: false,
  via,
  nodes: [`node_modules/${name}`],
})
function report(vulnerabilities = {}) {
  const counts = {
    info: 0,
    low: 0,
    moderate: 0,
    high: 0,
    critical: 0,
    total: 0,
  }
  for (const item of Object.values(vulnerabilities)) {
    counts[item.severity]++
    counts.total++
  }
  return {
    auditReportVersion: 2,
    vulnerabilities,
    metadata: { vulnerabilities: counts },
  }
}
const allowed = () =>
  report({ 'node-forge': entry('node-forge', [advisory()]) })
const check = (
  value,
  now = NOW,
  lock = LOCK,
  status = value?.metadata?.vulnerabilities.high +
    value?.metadata?.vulnerabilities.critical >
  0
    ? 1
    : 0,
) => checkAudit(JSON.stringify(value), status, lock, now)

test('指摘0件と閾値未満の指摘は例外を使わず通る', () => {
  assert.equal(check(report(), NOW, LOCK, 0), false)
  assert.equal(
    check(
      report({ small: entry('small', [advisory('moderate')], 'moderate') }),
    ),
    false,
  )
})
test('許可GHSAだけを通し、実npmレポートのNuxt循環参照を解決する', () => {
  const value = allowed()
  value.vulnerabilities['node-forge'].fixAvailable = {
    name: 'nuxt',
    version: '3.15.1',
  }
  const graph = {
    ...value.vulnerabilities,
    listhen: entry('listhen', ['node-forge']),
    nitropack: entry('nitropack', ['listhen']),
    '@nuxt/cli': entry('@nuxt/cli', ['listhen']),
    '@nuxt/nitro-server': entry('@nuxt/nitro-server', ['nitropack', 'nuxt']),
    '@nuxt/vite-builder': entry('@nuxt/vite-builder', ['nuxt']),
    nuxt: entry('nuxt', [
      '@nuxt/cli',
      '@nuxt/nitro-server',
      '@nuxt/vite-builder',
    ]),
  }
  assert.equal(check(report(graph)), true)
})
for (const severity of ['high', 'critical']) {
  test(`別の${severity} advisory混在は拒否する`, () => {
    const value = allowed()
    value.vulnerabilities['node-forge'].via.push(
      advisory(severity, 'https://github.com/advisories/GHSA-other'),
    )
    assert.throws(
      () => check(value),
      /許可されない(advisory|脆弱性)|深刻度が不一致/,
    )
  })
}

const BRACES_URL = 'https://github.com/advisories/GHSA-vfj7-8cjw-p6xm'
const BRACES_LOCK = {
  packages: { 'node_modules/braces': { version: '3.0.3' } },
}
const bracesAdvisory = () => ({
  name: 'braces',
  dependency: 'braces',
  severity: 'high',
  url: BRACES_URL,
  range: '<=3.0.3',
})
const bracesGraph = () => ({
  braces: entry('braces', [bracesAdvisory()]),
  micromatch: entry('micromatch', ['braces']),
  chokidar: {
    ...entry('chokidar', ['braces']),
    nodes: [
      'node_modules/@primevue/nuxt-module/node_modules/chokidar',
      'node_modules/tailwindcss/node_modules/chokidar',
    ],
  },
})
test('braces GHSA は名指しで通り、期限・lock版・範囲・消費者を検証する', () => {
  assert.equal(check(report(bracesGraph()), NOW, BRACES_LOCK), true)
  assert.throws(
    () =>
      check(
        report(bracesGraph()),
        Date.parse('2026-10-17T00:00:00Z'),
        BRACES_LOCK,
      ),
    /GHSA-vfj7-8cjw-p6xm の除外期限切れ/,
  )
  assert.throws(
    () =>
      check(report(bracesGraph()), NOW, {
        packages: { 'node_modules/braces': { version: '3.0.4' } },
      }),
    /許可されない advisory/,
  )
  const ranged = bracesGraph()
  ranged.braces.via[0].range = '<=9.0.0'
  assert.throws(
    () => check(report(ranged), NOW, BRACES_LOCK),
    /許可されない advisory/,
  )
  const unknown = bracesGraph()
  unknown.other = entry('other', ['braces'])
  assert.throws(
    () => check(report(unknown), NOW, BRACES_LOCK),
    /許可されない脆弱性/,
  )
})
test('braces 除外は node-forge の消費者名を流用できず、逆も拒否する', () => {
  const g = report({
    ...bracesGraph(),
    listhen: entry('listhen', ['braces']),
  })
  assert.throws(() => check(g, NOW, BRACES_LOCK), /許可されない脆弱性/)
  const forge = {
    'node-forge': entry('node-forge', [advisory()]),
    micromatch: entry('micromatch', ['node-forge']),
  }
  assert.throws(() => check(report(forge)), /許可されない脆弱性/)
})

// 実監査の19 highエントリを、秘密・環境情報を含まず再現する。
const mixedGraph = () => {
  const graph = {
    ...bracesGraph(),
    'fast-glob': entry('fast-glob', ['micromatch']),
    globby: entry('globby', ['fast-glob']),
    tailwindcss: entry('tailwindcss', ['chokidar', 'fast-glob', 'micromatch']),
    'unplugin-vue-components': {
      ...entry('unplugin-vue-components', ['chokidar']),
      nodes: [
        'node_modules/@primevue/nuxt-module/node_modules/unplugin-vue-components',
      ],
    },
    'unplugin-vue-router': entry('unplugin-vue-router', [
      'fast-glob',
      'micromatch',
    ]),
    '@intlify/unplugin-vue-i18n': entry('@intlify/unplugin-vue-i18n', [
      'fast-glob',
    ]),
    '@nuxtjs/i18n': entry('@nuxtjs/i18n', [
      '@intlify/unplugin-vue-i18n',
      'unplugin-vue-router',
    ]),
    '@nuxtjs/tailwindcss': entry('@nuxtjs/tailwindcss', ['tailwindcss']),
    '@primevue/nuxt-module': entry('@primevue/nuxt-module', [
      'unplugin-vue-components',
    ]),
    'node-forge': entry('node-forge', [advisory()]),
    listhen: entry('listhen', ['node-forge']),
    '@nuxt/cli': entry('@nuxt/cli', ['listhen']),
    nitropack: entry('nitropack', ['globby', 'listhen']),
    '@nuxt/nitro-server': entry('@nuxt/nitro-server', ['nitropack', 'nuxt']),
    '@nuxt/vite-builder': entry('@nuxt/vite-builder', ['nuxt']),
    nuxt: entry('nuxt', [
      '@nuxt/cli',
      '@nuxt/nitro-server',
      '@nuxt/vite-builder',
    ]),
  }
  for (const name of [
    '@nuxtjs/i18n',
    '@nuxtjs/tailwindcss',
    '@primevue/nuxt-module',
    'nuxt',
  ]) {
    graph[name].isDirect = true
  }
  return graph
}
const MIXED_LOCK = { packages: { ...LOCK.packages, ...BRACES_LOCK.packages } }
test('実監査の2advisory混在19件は循環とforge専用辺を含めて通る', () => {
  const value = report(mixedGraph())
  assert.equal(value.metadata.vulnerabilities.high, 19)
  assert.equal(check(value, NOW, MIXED_LOCK), true)
  assert.equal(
    check(value, Date.parse('2026-10-16T23:59:59.999Z'), MIXED_LOCK),
    true,
  )
  assert.throws(
    () => check(value, Date.parse('2026-10-17T00:00:00Z'), MIXED_LOCK),
    /期限切れ/,
  )
})
test('braces既知消費者名でも未知node・末端の別nodeは拒否する', () => {
  for (const name of ['braces', 'micromatch', 'chokidar', 'nuxt']) {
    const graph = mixedGraph()
    const node = `node_modules/unknown/node_modules/${name}`
    graph[name].nodes.push(node)
    const lock = {
      packages: { ...MIXED_LOCK.packages, [node]: { version: '3.0.3' } },
    }
    assert.throws(() => check(report(graph), NOW, lock), /許可されない依存経路/)
  }
})
test('既知名間のbraces経路付替え・未知の循環辺は拒否する', () => {
  for (const [name, via] of [
    ['micromatch', 'chokidar'],
    ['@intlify/unplugin-vue-i18n', 'braces'],
    ['nuxt', 'micromatch'],
    ['micromatch', 'fast-glob'],
  ]) {
    const graph = mixedGraph()
    graph[name].via.push(via)
    assert.throws(
      () => check(report(graph), NOW, MIXED_LOCK),
      /許可されない依存経路/,
    )
  }
})
test('同じGHSAでもcritical・別package・別range・直接依存・別versionは拒否する', () => {
  const mutations = [
    (value) => {
      value.vulnerabilities['node-forge'].via[0].severity = 'critical'
    },
    (value) => {
      value.vulnerabilities['node-forge'].via[0].name = 'other'
    },
    (value) => {
      value.vulnerabilities['node-forge'].via[0].range = '<=2.0.0'
    },
    (value) => {
      value.vulnerabilities['node-forge'].isDirect = true
    },
  ]
  for (const mutate of mutations) {
    const value = allowed()
    mutate(value)
    assert.throws(() => check(value), /許可されない advisory|深刻度が不一致/)
  }
  assert.throws(
    () => check(allowed(), NOW, { packages: {} }),
    /許可されない advisory/,
  )
  assert.throws(
    () =>
      check(allowed(), NOW, {
        packages: { 'node_modules/node-forge': { version: '1.3.0' } },
      }),
    /許可されない advisory/,
  )
})
test('UTC10月16日末まで通り、17日0時ちょうどから例外を拒否する', () => {
  assert.equal(check(allowed(), Date.parse('2026-10-16T23:59:59.999Z')), true)
  assert.throws(
    () => check(allowed(), Date.parse('2026-10-17T00:00:00Z')),
    /期限切れ/,
  )
  assert.throws(
    () => check(allowed(), Date.parse('2026-11-01T00:00:00Z')),
    /期限切れ/,
  )
  assert.equal(
    check(report(), Date.parse('2026-11-01T00:00:00Z'), LOCK, 0),
    false,
  )
})
test('不正JSON・構造欠落・error・npm異常終了は拒否する', () => {
  assert.throws(() => checkAudit('{', 1, LOCK, NOW))
  for (const value of [
    {},
    null,
    [],
    { ...allowed(), error: { code: 'ENETUNREACH' } },
    { ...allowed(), vulnerabilities: [] },
    { ...allowed(), auditReportVersion: 1 },
  ]) {
    assert.throws(() => check(value))
  }
  for (const status of [null, 2, 127])
    assert.throws(() => check(allowed(), NOW, LOCK, status), /取得失敗/)
  assert.throws(() => check(allowed(), NOW, LOCK, 0), /不一致/)
  assert.throws(() => check(report(), NOW, LOCK, 1), /不一致/)
})
test('参照欠落・末端のない循環・空via・unknown severityを拒否する', () => {
  for (const value of [
    report({ unknown: entry('unknown', ['missing']) }),
    report({ a: entry('a', ['b']), b: entry('b', ['a']) }),
    report({ unknown: entry('unknown', []) }),
    report({ unknown: entry('unknown', [advisory()], 'new-severity') }),
    report({ unknown: entry('unknown', [null]) }),
  ]) {
    assert.throws(() => check(value))
  }
})
test('レポート集計と依存情報の不一致を拒否する', () => {
  const value = allowed()
  value.metadata.vulnerabilities.high = 0
  assert.throws(() => check(value), /不一致/)
})
test('同じ許可GHSAへの参照でも新しいhigh消費者は拒否する', () => {
  const value = allowed()
  value.vulnerabilities['node-jose'] = entry('node-jose', ['node-forge'])
  assert.throws(
    () => check(report(value.vulnerabilities)),
    /許可されない脆弱性/,
  )
})

test('high advisoryへの未知消費者をmoderateと報告しても拒否する', () => {
  const value = allowed()
  value.vulnerabilities['node-jose'] = entry(
    'node-jose',
    ['node-forge'],
    'moderate',
  )
  assert.throws(() => check(report(value.vulnerabilities)), /深刻度が不一致/)
})
