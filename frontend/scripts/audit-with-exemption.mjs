// 修正版のない間接依存の個別例外（方針 docs/security/04_dependency_and_supply_chain.md §4.3）。
// GHSA-86w9-cpqp-85rv / node-forge 1.4.0 のみ。2026-10-16 UTC 当日まで。
// listhen の証明書生成経路では当該署名検証を呼ばない。本番 .output の除外は未実測。
// 修正版導入または listhen からの依存撤去時に例外と本スクリプトを削除する。
import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

const EXEMPT_URL = 'https://github.com/advisories/GHSA-86w9-cpqp-85rv'
const EXPIRES_AT = Date.parse('2026-10-17T00:00:00Z')
const SEVERITY_RANK = { info: 0, low: 1, moderate: 2, high: 3, critical: 4 }
// 実監査で確認した listhen/Nuxt 経路だけ。別の検証系消費者への拡大を認めない。
const EXEMPT_PACKAGES = new Set([
  'node-forge',
  'listhen',
  'nitropack',
  '@nuxt/cli',
  '@nuxt/nitro-server',
  '@nuxt/vite-builder',
  'nuxt',
])
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
    const high = advisories.filter(
      (via) => SEVERITY_RANK[via.severity] >= SEVERITY_RANK.high,
    )
    if (
      entry.severity === 'critical' ||
      (entry.severity === 'high' &&
        (high.length === 0 || !EXEMPT_PACKAGES.has(name)))
    ) {
      throw new Error(`許可されない脆弱性: ${entry.severity} ${name}`)
    }
    for (const advisory of high) {
      const forge = vulnerabilities['node-forge']
      if (
        advisory.url !== EXEMPT_URL ||
        advisory.name !== 'node-forge' ||
        advisory.dependency !== 'node-forge' ||
        advisory.range !== '<=1.4.0' ||
        advisory.severity !== 'high' ||
        !forge ||
        forge.isDirect !== false ||
        !forge.via.includes(advisory) ||
        !isObject(lock?.packages) ||
        !forge.nodes.every(
          (node) =>
            typeof node === 'string' &&
            /(^|\/)node_modules\/node-forge$/.test(node) &&
            lock.packages[node]?.version === '1.4.0',
        )
      ) {
        throw new Error(
          `許可されない advisory: ${advisory.severity} ${advisory.name} ${advisory.url}`,
        )
      }
      if (!Number.isFinite(now) || now >= EXPIRES_AT) {
        throw new Error(
          'GHSA-86w9-cpqp-85rv の除外期限切れ（2026-10-16 UTC 当日まで）',
        )
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
        ? 'npm audit: GHSA-86w9-cpqp-85rv のみ除外中（2026-10-16 UTC 当日まで）、他の high/critical は0件'
        : 'npm audit: high/critical は0件',
    )
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
