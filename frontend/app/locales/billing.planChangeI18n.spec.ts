import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 試練D（第5隊）AC-131（＋AC-106 と重なる文言方針）: プラン変更まわりの i18n キーが
 * 6言語すべてに存在すること。i18n ルール（CLAUDE.md）により UI 文字列の直書きは禁止のため、
 * ロケールファイルにキーが無い状態が「実装が終わっていない」ことの直接の証拠になる。
 *
 * <p><b>出陣時の是正（読み込み方式）</b>: 当初の実装は `await import('./${locale}/billing.json')`
 * で動的 import していたが、`environment: 'nuxt'`（`@nuxt/test-utils`）配下では
 * `@nuxtjs/i18n` の Vite プラグインがロケール JSON を `@intlify/message-compiler` の
 * メッセージ AST（`{type, start, end, loc, body}` 形）へ**すべてのキーについて**変換してから
 * 返す（新規キーに限らず既存キー `billing.manage.cancelCta` 等でも同一の AST になることを
 * 実測で確認済み＝実装のバグではなく読み込み方式の技術的欠陥）。これは
 * `frontend/tests/unit/locales/billing-cancel-reservation-i18n.spec.ts` が同種の理由で
 * `readFileSync` + `JSON.parse` を採用している前例と同型のため、同じ読み込み方式へ揃える。
 * **アサーション内容（キー一覧・存在確認・文言方針の正規表現）は一字も変えていない。**</p>
 */

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

// パス解決は billing-cancel-reservation-i18n.spec.ts と同じ流儀（dirname(fileURLToPath(...)) +
// resolve）。テンプレートリテラルを import() の引数へ埋めるとの混同を避けるため、
// ここでは常にファイルシステム読み取りで完結させる。
const localesDir = resolve(dirname(fileURLToPath(import.meta.url)), '.')

// AC-126〜130・AC-105（失効時刻の告知）・AC-129（失敗明示）に対応する最小キー集合。
const REQUIRED_KEYS = [
  'billing.manage.planChange.title',
  'billing.manage.planChange.amountDueNow',
  'billing.manage.planChange.effectiveAt',
  'billing.manage.planChange.amountInclTax',
  'billing.manage.planChange.amountExclTax',
  'billing.manage.planChange.taxAmount',
  'billing.manage.planChange.taxRate',
  'billing.manage.planChange.staysOnOldPlan',
  'billing.manage.planChange.pendingPaymentNotice',
  'billing.manage.planChange.expiresAtNotice',
  // 修繕（2巡目 P2-1）: 期限が取れないときに「嘘の期限」を出さないための代替文言。
  'billing.manage.planChange.expiresAtUnknownNotice',
  'billing.manage.planChange.confirmCta',
  'billing.manage.planChange.resumePaymentActionCta',
] as const

function getByPath(obj: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, key) => {
    if (acc && typeof acc === 'object' && key in (acc as Record<string, unknown>)) {
      return (acc as Record<string, unknown>)[key]
    }
    return undefined
  }, obj)
}

function loadLocale(locale: (typeof LOCALES)[number]): unknown {
  const raw = readFileSync(resolve(localesDir, locale, 'billing.json'), 'utf-8')
  return JSON.parse(raw)
}

describe.each(LOCALES)('AC-131: billing.json(%s) にプラン変更の i18n キーが揃っている', (locale) => {
  it.each(REQUIRED_KEYS)('%s が存在し、空文字ではない', (key) => {
    const mod = loadLocale(locale)
    const value = getByPath(mod, key)
    expect(typeof value).toBe('string')
    expect((value as string).length).toBeGreaterThan(0)
  })
})

describe('AC-106（回帰の裏取り）: 支払い待ち文言は即時失効・請求済みを誤認させない', () => {
  it('pendingPaymentNotice / expiresAtNotice は「失敗」「エラー」と断定しない（誤認防止）', () => {
    const mod = loadLocale('ja') as { billing: { manage: { planChange: Record<string, string> } } }
    const notice = mod.billing.manage.planChange.pendingPaymentNotice
    const expiresNotice = mod.billing.manage.planChange.expiresAtNotice
    expect(notice).not.toMatch(/失敗|エラー|請求済み/)
    expect(expiresNotice).not.toMatch(/即時|直ちに/)
  })
})
