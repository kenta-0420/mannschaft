import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 試練隊（第3陣）K群 — 価格改定管理画面の i18n（AC-150・AC-151）の受け入れテスト（red）。
 *
 * <p>正本: `.claude/campaigns/price-rev-plan-v3.md` K群 AC-146〜AC-165。管理画面
 * （`system-admin/billing.vue` 価格タブ刷新）は6言語すべてで同一キー集合を持つ `billing.json`
 * の `priceRevisions` 名前空間に文言を持つこと（AC-150: UI文言直書き禁止）、かつ
 * エラーコード別の表示文言（409 overlap / 409 CAS競合 / 400 税コード不正 / 409 lockVersion）を
 * 個別キーとして持つこと（AC-151）を固定する。本コミット時点で `priceRevisions` 名前空間は
 * どの言語にも存在しないため、全ケースが red になる。</p>
 */

const LOCALES = ['ja', 'en', 'es', 'de', 'ko', 'zh'] as const

const localesDir = resolve(dirname(fileURLToPath(import.meta.url)), '../../../app/locales')

interface PriceRevisionsMessages {
  createTitle?: string
  provisionAction?: string
  retryProvisionAction?: string
  activateAction?: string
  errorOverlap?: string
  errorLockVersionConflict?: string
  errorInvalidTaxCode?: string
  errorReconcileMismatch?: string
  [key: string]: unknown
}

interface BillingLocaleJson {
  billing?: { priceRevisions?: PriceRevisionsMessages }
}

function loadBilling(locale: string): BillingLocaleJson {
  return JSON.parse(readFileSync(resolve(localesDir, locale, 'billing.json'), 'utf-8')) as BillingLocaleJson
}

describe('billing.json — 価格改定管理画面の文言(priceRevisions名前空間・AC-150/151)', () => {
  it.each(LOCALES)('%s: 基本操作キー(createTitle/provisionAction/retryProvisionAction/activateAction)が存在する', (locale) => {
    const messages = loadBilling(locale).billing?.priceRevisions ?? {}
    expect(messages.createTitle, `${locale}: createTitle が無い`).toBeTruthy()
    expect(messages.provisionAction, `${locale}: provisionAction が無い`).toBeTruthy()
    expect(messages.retryProvisionAction, `${locale}: retryProvisionAction が無い`).toBeTruthy()
    expect(messages.activateAction, `${locale}: activateAction が無い`).toBeTruthy()
  })

  it.each(LOCALES)('%s: エラーコード別表示キー(overlap/lockVersion/税コード不正)が存在する(AC-151)', (locale) => {
    const messages = loadBilling(locale).billing?.priceRevisions ?? {}
    expect(messages.errorOverlap, `${locale}: errorOverlap が無い`).toBeTruthy()
    expect(messages.errorLockVersionConflict, `${locale}: errorLockVersionConflict が無い`).toBeTruthy()
    expect(messages.errorInvalidTaxCode, `${locale}: errorInvalidTaxCode が無い`).toBeTruthy()
  })

  it('502文言は作らない(決定・02_api_design.md §4「本戦役は同期実装・200+終局状態のみ」と矛盾するキーを禁止)', () => {
    const messages = loadBilling('ja').billing?.priceRevisions ?? {}
    const keys = Object.keys(messages)
    expect(keys.some(k => /502|badGateway|gatewayTimeout/i.test(k))).toBe(false)
  })
})
