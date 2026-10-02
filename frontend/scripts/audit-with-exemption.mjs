// npm audit（high 以上）を、期限付きで 1 件の advisory だけ除外して実行する。
//
// 除外対象: GHSA-86w9-cpqp-85rv（node-forge）
//   理由: 修正版がまだ存在しない（脆弱範囲 <= 1.4.0、npm 最新も 1.4.0）。
//         node-forge は listhen 経由（nitropack / @nuxt/cli の開発サーバー・preview 用）で入る開発用依存で、
//         本番成果物（.output）には含まれない。
//   期限: 2026-10-16（過ぎたら本スクリプトが exit 1 で CI を落とす）
//   解除条件: node-forge の修正版が公開された時点で overrides に追加して本除外を削除する。
//             または listhen が node-forge を使わなくなった時点。解除用の行は docs/task-list.md にある。
// audit-level を下げる・npm audit 全体を無効化することは禁止。除外は下の 1 件のみ名指しで行う。
import { execSync } from 'node:child_process'

const EXEMPT_ID = 'GHSA-86w9-cpqp-85rv'
const EXPIRES = '2026-10-16'
const SEVERITY_RANK = { info: 0, low: 1, moderate: 2, high: 3, critical: 4 }

const today = new Date().toISOString().slice(0, 10)
if (today > EXPIRES) {
  console.error(
    `除外期限切れ: ${EXEMPT_ID} の除外は ${EXPIRES} までだった（本日 ${today}）。` +
      'node-forge の修正版を確認して override で引き上げるか、殿に期限延長を諮ること。',
  )
  process.exit(1)
}

let raw
try {
  raw = execSync('npm audit --json', { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
} catch (e) {
  // npm audit は脆弱性があると exit 1 を返すが、JSON は stdout に出る
  raw = e.stdout
}
if (!raw) {
  console.error('npm audit の出力が取得できなかった')
  process.exit(1)
}
const report = JSON.parse(raw)
if (report.error) {
  console.error('npm audit が失敗した:', JSON.stringify(report.error))
  process.exit(1)
}

// 根本原因の advisory（via のオブジェクト）を重複排除して集める
const advisories = new Map()
for (const vuln of Object.values(report.vulnerabilities ?? {})) {
  for (const via of vuln.via ?? []) {
    if (typeof via === 'object' && via.url) advisories.set(via.url, via)
  }
}

const blocking = []
for (const [url, adv] of advisories) {
  if (SEVERITY_RANK[adv.severity] < SEVERITY_RANK.high) continue
  if (url.endsWith(`/${EXEMPT_ID}`)) {
    console.log(`除外中（期限 ${EXPIRES}）: ${EXEMPT_ID} ${adv.name} - ${adv.title}`)
    continue
  }
  blocking.push(`${adv.severity} ${adv.name}: ${adv.title} ${url}`)
}

if (blocking.length > 0) {
  console.error('high 以上の脆弱性が残っている:')
  for (const b of blocking) console.error(`  - ${b}`)
  process.exit(1)
}
console.log('npm audit: 除外 1 件を除き high 以上は 0 件')
