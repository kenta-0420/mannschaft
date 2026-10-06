// 修正版のない間接依存の個別例外（方針 docs/security/04_dependency_and_supply_chain.md §4.3）。
// GHSA-86w9-cpqp-85rv / node-forge 1.4.0 と GHSA-vfj7-8cjw-p6xm / braces 3.0.3、
// および simple-git 3.36.0・@simple-git/argv-parser 1.1.1 の4件（下の EXEMPTIONS 参照）のみ。
// node-forge・braces は 2026-10-16 UTC 当日まで、simple-git 系は 2026-11-06 UTC 当日まで。
// 期限は例外ごとの expiresAt（未指定なら EXPIRES_AT）で持つ。
// node-forge: listhen の証明書生成経路では当該署名検証を呼ばない。本番 .output の除外は未実測。
// braces: ビルド時のファイル探索にのみ使われ、本番で利用者入力を受けない。
// 修正版導入または依存撤去時に該当例外を削除する。
import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

const EXPIRES_AT = Date.parse('2026-10-17T00:00:00Z')
const SIMPLE_GIT_EXPIRES_AT = Date.parse('2026-11-07T00:00:00Z')
const SEVERITY_RANK = { info: 0, low: 1, moderate: 2, high: 3, critical: 4 }
// GHSA 単位の除外表。パッケージ名での包括除外はしない。
// packages は実監査で確認した到達経路だけ。別の消費者への拡大を認めない。
// simple-git の advisory に到達する消費者（実監査で確認した経路のみ）。
const SIMPLE_GIT_CONSUMERS = [
  'simple-git',
  '@nuxt/devtools',
  '@nuxt/nitro-server',
  '@nuxt/vite-builder',
  'nuxt',
]
const EXEMPTIONS = {
  'https://github.com/advisories/GHSA-86w9-cpqp-85rv': {
    id: 'GHSA-86w9-cpqp-85rv',
    name: 'node-forge',
    range: '<=1.4.0',
    version: '1.4.0',
    packages: new Set([
      'node-forge',
      'listhen',
      'nitropack',
      '@nuxt/cli',
      '@nuxt/nitro-server',
      '@nuxt/vite-builder',
      'nuxt',
    ]),
  },
  // braces: chokidar / micromatch / fast-glob 経由のビルド時ツールのみ。
  'https://github.com/advisories/GHSA-vfj7-8cjw-p6xm': {
    id: 'GHSA-vfj7-8cjw-p6xm',
    name: 'braces',
    range: '<=3.0.3',
    version: '3.0.3',
    packages: new Set([
      'braces',
      'micromatch',
      'chokidar',
      'fast-glob',
      'globby',
      'tailwindcss',
      'unplugin-vue-components',
      'unplugin-vue-router',
      '@intlify/unplugin-vue-i18n',
      '@nuxtjs/i18n',
      '@nuxtjs/tailwindcss',
      '@primevue/nuxt-module',
      'nitropack',
      '@nuxt/nitro-server',
      '@nuxt/vite-builder',
      'nuxt',
    ]),
  },
  // simple-git 系: @nuxt/devtools 3.4.1 の推移的依存。DevTools は本番で無効（nuxt.config.ts:68）で
  // 開発時のみ読み込まれる。simple-git 4 は default export を廃止しており devtools 3.4.1 と非互換
  // （devtools 3.x に修正版なし、4.0.0-beta.3 は vite 8・nitro 3 前提）。
  // 外す条件: Nuxt 4 への移行（DevTools 4 正式版へ更新）、または devtools 3.x で simple-git 4 対応版が出たとき。
  // 台帳: CMP-261006-1342（docs/task-list.md）。
  'https://github.com/advisories/GHSA-x6jw-m9v5-85vh': {
    id: 'GHSA-x6jw-m9v5-85vh',
    name: 'simple-git',
    severity: 'critical',
    range: '>=3.15.0 <4.0.1',
    version: '3.36.0',
    expiresAt: SIMPLE_GIT_EXPIRES_AT,
    packages: new Set(SIMPLE_GIT_CONSUMERS),
  },
  'https://github.com/advisories/GHSA-g4wm-2vf7-vfgr': {
    id: 'GHSA-g4wm-2vf7-vfgr',
    name: 'simple-git',
    range: '<=3.36.0',
    version: '3.36.0',
    expiresAt: SIMPLE_GIT_EXPIRES_AT,
    packages: new Set(SIMPLE_GIT_CONSUMERS),
  },
  'https://github.com/advisories/GHSA-858h-whjf-mvg5': {
    id: 'GHSA-858h-whjf-mvg5',
    name: 'simple-git',
    range: '<=3.36.0',
    version: '3.36.0',
    expiresAt: SIMPLE_GIT_EXPIRES_AT,
    packages: new Set(SIMPLE_GIT_CONSUMERS),
  },
  'https://github.com/advisories/GHSA-v5rq-49vh-5v5c': {
    id: 'GHSA-v5rq-49vh-5v5c',
    name: '@simple-git/argv-parser',
    severity: 'critical',
    range: '<2.0.1',
    version: '1.1.1',
    expiresAt: SIMPLE_GIT_EXPIRES_AT,
    packages: new Set(['@simple-git/argv-parser', ...SIMPLE_GIT_CONSUMERS]),
  },
}
const isObject = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
const validSeverity = (value) => Object.hasOwn(SEVERITY_RANK, value)

// npm の終了状態も検証する。通信失敗の stdout が JSON でも成功扱いしない。
export function checkAudit(raw, status, lock, now = Date.now()) {
  if (![0, 1].includes(status))
    throw new Error(`npm audit の取得失敗: exit ${status}`)
  const report = JSON.parse(raw)
  if (
    !isObject(report) ||
    Object.hasOwn(report, 'error') ||
    report.auditReportVersion !== 2 ||
    !isObject(report.vulnerabilities) ||
    !isObject(report.metadata?.vulnerabilities)
  ) {
    throw new Error('npm audit のレポートが不正、または取得失敗')
  }
  const vulnerabilities = report.vulnerabilities
  const counts = {
    info: 0,
    low: 0,
    moderate: 0,
    high: 0,
    critical: 0,
    total: 0,
  }
  for (const [name, entry] of Object.entries(vulnerabilities)) {
    if (
      !isObject(entry) ||
      entry.name !== name ||
      !validSeverity(entry.severity) ||
      !Array.isArray(entry.via) ||
      entry.via.length === 0 ||
      !Array.isArray(entry.nodes) ||
      entry.nodes.length === 0 ||
      !entry.nodes.every((node) => typeof node === 'string' && node.length > 0)
    ) {
      throw new Error(`npm audit の依存情報が不正: ${name}`)
    }
    counts[entry.severity]++
    counts.total++
    for (const via of entry.via) {
      if (typeof via === 'string') {
        if (!Object.hasOwn(vulnerabilities, via))
          throw new Error(`参照先がない: ${name} → ${via}`)
      } else if (
        !isObject(via) ||
        !validSeverity(via.severity) ||
        typeof via.name !== 'string' ||
        typeof via.url !== 'string' ||
        typeof via.range !== 'string'
      ) {
        throw new Error(`npm audit の advisory が不正: ${name}`)
      }
    }
  }
  for (const [severity, count] of Object.entries(counts)) {
    if (report.metadata.vulnerabilities[severity] !== count) {
      throw new Error(`npm audit の集計と依存情報が不一致: ${severity}`)
    }
  }
  if (status !== (counts.high + counts.critical > 0 ? 1 : 0)) {
    throw new Error('npm audit の終了状態と high/critical 集計が不一致')
  }

  const collectAdvisories = (name, visited = new Set()) => {
    // npm の実レポートには Nuxt と builder 等の循環がある。全参照の検証は上で済ませる。
    if (visited.has(name)) return []
    visited.add(name)
    return vulnerabilities[name].via.flatMap((via) =>
      typeof via === 'string' ? collectAdvisories(via, visited) : [via],
    )
  }
  let exempt = false
  for (const [name, entry] of Object.entries(vulnerabilities)) {
    const advisories = collectAdvisories(name)
    if (
      advisories.some(
        (via) => SEVERITY_RANK[entry.severity] < SEVERITY_RANK[via.severity],
      )
    ) {
      throw new Error(
        `npm audit の依存と到達 advisory の深刻度が不一致: ${name}`,
      )
    }
    const high = advisories.filter(
      (via) => SEVERITY_RANK[via.severity] >= SEVERITY_RANK.high,
    )
    const maxRank = Math.max(...high.map((via) => SEVERITY_RANK[via.severity]))
    if (
      SEVERITY_RANK[entry.severity] >= SEVERITY_RANK.high &&
      (high.length === 0 ||
        // 依存側の深刻度が、到達する例外 advisory の最大深刻度を超えることも認めない。
        SEVERITY_RANK[entry.severity] > maxRank ||
        !high.every((via) => EXEMPTIONS[via.url]?.packages.has(name)))
    ) {
      throw new Error(`許可されない脆弱性: ${entry.severity} ${name}`)
    }
    for (const advisory of high) {
      const spec = EXEMPTIONS[advisory.url]
      const target = spec && vulnerabilities[spec.name]
      if (
        !spec ||
        advisory.name !== spec.name ||
        advisory.dependency !== spec.name ||
        advisory.range !== spec.range ||
        advisory.severity !== (spec.severity ?? 'high') ||
        !target ||
        target.isDirect !== false ||
        !target.via.includes(advisory) ||
        !isObject(lock?.packages) ||
        !target.nodes.every(
          (node) =>
            typeof node === 'string' &&
            (node === `node_modules/${spec.name}` ||
              node.endsWith(`/node_modules/${spec.name}`)) &&
            lock.packages[node]?.version === spec.version,
        )
      ) {
        throw new Error(
          `許可されない advisory: ${advisory.severity} ${advisory.name} ${advisory.url}`,
        )
      }
      const expiresAt = spec.expiresAt ?? EXPIRES_AT
      if (!Number.isFinite(now) || now >= expiresAt) {
        const lastDay = new Date(expiresAt - 1).toISOString().slice(0, 10)
        throw new Error(`${spec.id} の除外期限切れ（${lastDay} UTC 当日まで）`)
      }
      exempt = true
    }
  }
  return exempt
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  try {
    let raw
    let status = 0
    try {
      raw = execSync('npm audit --json --audit-level=high', {
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024,
      })
    } catch (error) {
      raw = error.stdout
      status = error.status
    }
    const lock = JSON.parse(
      readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'),
    )
    const exempt = checkAudit(raw, status, lock)
    console.log(
      exempt
        ? 'npm audit: GHSA-86w9-cpqp-85rv / GHSA-vfj7-8cjw-p6xm / simple-git 系4件（GHSA-x6jw-m9v5-85vh・GHSA-v5rq-49vh-5v5c・GHSA-g4wm-2vf7-vfgr・GHSA-858h-whjf-mvg5）のみ除外中（node-forge・braces は 2026-10-16、simple-git 系は 2026-11-06 UTC 当日まで）、他の high/critical は0件'
        : 'npm audit: high/critical は0件',
    )
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
