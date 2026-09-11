import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * Billing Center PR6a — E群 i18n（AC-61）の受け入れテスト（試練C・red）。
 *
 * <p>正本 04_ui_i18n.md:175。解約予約（cancel_at_period_end）専用の文言キーが6言語すべてに存在し、
 * かつ「即時失効」を意味する文言になっていないことを固定する。既存の {@code manage.cancelConfirmBody*}
 * 系は<b>即時解約（無償契約・旧経路）用</b>の文言であり、PR6a の期末解約予約とは意味が異なるため、
 * 新規キー {@code manage.cancelReservationBody} を要求する（既存キーの使い回しは「すぐに使えなくなる」
 * という誤った説明を利用者に見せる恐れがある）。</p>
 *
 * <p>プレースホルダ {@code {endDate}} と {@code {nextBillingDate}} の両方を含むことを要求する
 * （AC-57「FEの確認文言にendDateとnextBillingDateの両方が入る」）。</p>
 */

const LOCALES = ['ja', 'en', 'es', 'de', 'ko', 'zh'] as const

function loadBilling(locale: string): any {
  const path = fileURLToPath(new URL(`../../../app/locales/${locale}/billing.json`, import.meta.url))
  return JSON.parse(readFileSync(path, 'utf-8'))
}

describe('billing.json — 解約予約(cancel_at_period_end)専用キー（AC-61）', () => {
  it.each(LOCALES)('%s: manage.cancelReservationTitle / cancelReservationBody / resumeCancelCta が存在する', (locale) => {
    const data = loadBilling(locale)
    const manage = data.billing?.manage ?? {}

    expect(manage.cancelReservationTitle, `${locale}: cancelReservationTitle が無い`).toBeTruthy()
    expect(manage.cancelReservationBody, `${locale}: cancelReservationBody が無い`).toBeTruthy()
    expect(manage.resumeCancelCta, `${locale}: resumeCancelCta が無い`).toBeTruthy()
  })

  it.each(LOCALES)('%s: cancelReservationBody に {endDate} と {nextBillingDate} の両方が入る', (locale) => {
    const data = loadBilling(locale)
    const body: string | undefined = data.billing?.manage?.cancelReservationBody
    expect(body, `${locale}: cancelReservationBody が無い`).toBeTruthy()
    expect(body).toContain('{endDate}')
    expect(body).toContain('{nextBillingDate}')
  })

  it('ja: cancelReservationBody は即時失効を意味する文言になっていない', () => {
    const data = loadBilling('ja')
    const body: string | undefined = data.billing?.manage?.cancelReservationBody
    expect(body, 'ja: cancelReservationBody が無い').toBeTruthy()
    expect(body).not.toContain('すぐに使えなくなり')
    expect(body).not.toContain('直ちに')
  })

  it('陽性対照: 既存の即時解約(無償)向けcancelSuccessキーは引き続き存在する（新設で消していないこと）', () => {
    const data = loadBilling('ja')
    expect(data.billing?.manage?.cancelSuccessFree ?? data.billing?.manage?.cancelSuccess).toBeTruthy()
  })
})
