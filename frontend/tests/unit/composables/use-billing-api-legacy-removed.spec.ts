import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 試練隊（第4陣）J群 — 旧 price-bands API の FE 除去（AC-143・AC-144）の受け入れテスト（red）。
 *
 * 正本: `.claude/campaigns/price-rev-plan-v3.md` J群。BE 側は試練隊（第2陣/第3陣）が
 * `PUT /plans/{planKey}/price-bands` の 410 化を担当済み（`SystemAdminBillingLegacyPriceBandsGoneTest`）。
 * 本ファイルは FE 側（`useBillingApi.ts` と `system-admin/billing.vue` の `priceBandsTab`）が
 * その旧エンドポイントを叩かなくなることを固定する。
 *
 * 本コミット時点では `useBillingApi.ts` に `replacePriceBandsAdmin`（`PUT .../price-bands`）が
 * 残っており、`billing.vue` の保存処理もこれを呼んでいるため red（実測済み）。
 */

const useBillingApiSource = readFileSync(
  resolve(process.cwd(), 'app/composables/useBillingApi.ts'),
  'utf8',
)

const billingPageSource = readFileSync(
  resolve(process.cwd(), 'app/pages/system-admin/billing.vue'),
  'utf8',
)

describe('AC-143: useBillingApi.ts から旧 price-bands エンドポイント呼び出しが除去される', () => {
  it('PUT .../plans/{planKey}/price-bands を叩く関数が存在しない', () => {
    expect(useBillingApiSource).not.toMatch(/\/plans\/\$\{planKey\}\/price-bands/)
  })

  it('replacePriceBandsAdmin という名前の関数がエクスポートされない（新 API 名へ置き換え済み）', () => {
    expect(useBillingApiSource).not.toMatch(/replacePriceBandsAdmin/)
  })
})

describe('AC-144: system-admin/billing.vue の priceBandsTab が旧エンドポイントを叩かなくなる', () => {
  it('replacePriceBandsAdmin を呼び出していない', () => {
    expect(billingPageSource).not.toMatch(/replacePriceBandsAdmin/)
  })

  it('410 を踏んで壊れたままにしない（旧 price-bands 保存導線が新 price-revisions 導線へ置き換わっている）', () => {
    expect(billingPageSource).toMatch(/price-revisions/)
  })
})
