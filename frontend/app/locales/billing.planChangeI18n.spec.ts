import { describe, expect, it } from 'vitest'

/**
 * 試練D（第5隊）AC-131（＋AC-106 と重なる文言方針）: プラン変更まわりの i18n キーが
 * 6言語すべてに存在すること。i18n ルール（CLAUDE.md）により UI 文字列の直書きは禁止のため、
 * ロケールファイルにキーが無い状態が「実装が終わっていない」ことの直接の証拠になる。
 *
 * 未実装の現時点ではキーが存在しないため red になる（各言語ファイルにとりあえず
 * 日本語と同じ値でも良いので追加するのが出陣側の作業）。
 */

const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

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

describe.each(LOCALES)('AC-131: billing.json(%s) にプラン変更の i18n キーが揃っている', (locale) => {
  it.each(REQUIRED_KEYS)('%s が存在し、空文字ではない', async (key) => {
    const mod = await import(`./${locale}/billing.json`)
    const value = getByPath(mod.default ?? mod, key)
    expect(typeof value).toBe('string')
    expect((value as string).length).toBeGreaterThan(0)
  })
})

describe('AC-106（回帰の裏取り）: 支払い待ち文言は即時失効・請求済みを誤認させない', () => {
  it('pendingPaymentNotice / expiresAtNotice は「失敗」「エラー」と断定しない（誤認防止）', async () => {
    const mod = await import('./ja/billing.json')
    const b = (mod.default ?? mod) as { billing: { manage: { planChange: Record<string, string> } } }
    const notice = b.billing.manage.planChange.pendingPaymentNotice
    const expiresNotice = b.billing.manage.planChange.expiresAtNotice
    expect(notice).not.toMatch(/失敗|エラー|請求済み/)
    expect(expiresNotice).not.toMatch(/即時|直ちに/)
  })
})
