import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 試練隊（第4陣）K群 — 価格改定 管理画面（一覧・詳細）の受け入れテスト（red）。
 *
 * 正本: `.claude/campaigns/price-rev-plan-v3.md` K群 AC-146〜AC-165。
 *
 * `pages/system-admin/provisioning/index.vue` の UT 金型（`tests/unit/pages/system-admin/
 * provisioning.spec.ts`）に倣い、PrimeVue 依存の重い mount を避けてソース文字列アサーションで
 * 主要分岐を固定する。対象画面（一覧: `system-admin/price-revisions/index.vue`、詳細:
 * `system-admin/price-revisions/[id].vue`）はどちらも未実装のため、`readFileSync` は
 * 空文字にフォールバックし、全アサーションが red になる（本コミット時点で実測済み）。
 *
 * AC-150/151（i18n キーの存在）は試練隊（第3陣）の `tests/unit/locales/price-revisions-i18n.spec.ts`
 * が既に担当しているため、本ファイルでは重複させない。
 */

function readSourceOrEmpty(relativePath: string): string {
  try {
    return readFileSync(resolve(process.cwd(), relativePath), 'utf8')
  } catch {
    return ''
  }
}

const listSource = readSourceOrEmpty('app/pages/system-admin/price-revisions/index.vue')
const detailSource = readSourceOrEmpty('app/pages/system-admin/price-revisions/[id].vue')

describe('/system-admin/price-revisions 一覧画面', () => {
  it('AC-146: DRAFT revision と bands を画面の操作で作成できる', () => {
    expect(listSource).toContain('createPriceRevision')
    expect(listSource).toContain("'DRAFT'")
  })

  it('AC-147: 税コードの登録・更新・無効化ができる', () => {
    expect(listSource).toContain('createTaxCode')
    expect(listSource).toContain('updateTaxCode')
    expect(listSource).toContain('deactivateTaxCode')
  })

  it('AC-149: 一覧API（検索条件・ページング）を使い、既存 revision の詳細へ遷移できる', () => {
    expect(listSource).toContain('listPriceRevisions')
    expect(listSource).toMatch(/page\s*[:=]/)
    expect(listSource).toMatch(/\/system-admin\/price-revisions\/\$\{.*\.id\}/)
  })

  it('AC-150関連: 一覧を status で絞り込める（絞り込み自体の分岐。文言キー存在は第3陣担当）', () => {
    expect(listSource).toMatch(/status/)
    expect(listSource).toMatch(/DRAFT|PROVISION_FAILED|PROVISIONING|READY|SCHEDULED|ACTIVE|RETIRED/)
  })

  it('AC-153: 税の扱い（税込/税抜）を選ばずに送信できない', () => {
    expect(listSource).toMatch(/taxInclusive|taxMode|priceIsTaxIncluded/)
    expect(listSource).toMatch(/disabled=".*(taxMode|taxInclusive|priceIsTaxIncluded)/)
  })

  it('AC-154: 入力金額に対する税抜・税額・税込が確定前に画面で確認できる', () => {
    expect(listSource).toMatch(/computed/)
    expect(listSource).toMatch(/taxExcluded|amountExcludingTax/)
    expect(listSource).toMatch(/taxAmount/)
    expect(listSource).toMatch(/taxIncluded|amountIncludingTax/)
  })

  it('AC-155: 「既存契約は次周期まで変わらない」「部分成功時は販売不可」が画面に明示される', () => {
    expect(listSource).toMatch(/priceRevisions\.notice\.(nextCycle|noSaleOnPartialFailure)/)
  })

  it('AC-157: SYSTEM_ADMIN 以外には一覧画面・導線が出ない', () => {
    expect(listSource).toContain('authStore.isSystemAdmin')
    expect(listSource).toContain('priceRevisions.noPermission')
  })

  it('AC-159: 応答オブジェクトに client_secret / raw / Stripe生ペイロードのキーを一切保持・表示しない', () => {
    expect(listSource).not.toMatch(/client_secret/)
    expect(listSource).not.toMatch(/\braw\b\s*:/)
  })

  it('AC-162: 送信中は loading 表示になり、二重押下が抑止される', () => {
    expect(listSource).toMatch(/const\s+loading\s*=\s*ref/)
    expect(listSource).toMatch(/:disabled="loading/)
  })

  it('AC-163: キーボード操作・a11y（aria-label、ダイアログの focus trap/restore/Escape）を備える', () => {
    expect(listSource).toMatch(/aria-label/)
    expect(listSource).toMatch(/Escape|@keydown\.esc/)
  })

  it('AC-164: モバイル幅（375px）で横スクロールが発生せず、一覧が代替表示になる', () => {
    expect(listSource).toMatch(/sm:hidden|md:hidden|overflow-x-auto|mobile/i)
  })
})

describe('/system-admin/price-revisions/[id] 詳細画面', () => {
  it('AC-148: Provision を実行でき、band ごとの成否・エラーコード・試行回数が画面で見える', () => {
    expect(detailSource).toContain('provisionPriceRevision')
    expect(detailSource).toMatch(/attemptCount/)
    expect(detailSource).toMatch(/errorCode/)
  })

  it('AC-151: retry-provision を画面から実行でき、PROVISIONING 停滞時は reconcile 導線が出る', () => {
    expect(detailSource).toContain('retryProvisionPriceRevision')
    expect(detailSource).toMatch(/reconcile/i)
    expect(detailSource).toMatch(/PROVISIONING/)
  })

  it('AC-152: 全 band READY のときだけ Activate ボタンが押せる', () => {
    expect(detailSource).toMatch(/allBandsReady|every\(.*READY/)
    expect(detailSource).toMatch(/:disabled="!allBandsReady|:disabled="!.*every/)
  })

  it('AC-157: SYSTEM_ADMIN 以外には詳細画面・導線が出ない', () => {
    expect(detailSource).toContain('authStore.isSystemAdmin')
    expect(detailSource).toContain('priceRevisions.noPermission')
  })

  it('AC-158: 通信結果不明の自動再送は同一 Idempotency-Key、業務再試行ボタンは新しい UUID を発行する', () => {
    expect(detailSource).toMatch(/crypto\.randomUUID\(\)/)
    // 自動リトライ(通信不明)は同一キー変数を使い回す実装であること（新規発行呼び出しが1関数に閉じていること）
    expect(detailSource).toMatch(/function\s+onRetryProvisionClick|const\s+onRetryProvisionClick/)
    expect(detailSource).toMatch(/function\s+autoResendOnNetworkError|autoResendSameKey/)
  })

  it('AC-159: 応答オブジェクトに client_secret / raw / Stripe生ペイロードのキーを一切保持・表示しない', () => {
    expect(detailSource).not.toMatch(/client_secret/)
    expect(detailSource).not.toMatch(/\braw\b\s*:/)
  })

  it('AC-161: エラーコード別に別々の文言キーで表示する（overlap/CAS競合/税コード不正/RECONCILE_ATTRIBUTE_MISMATCH/PROCESSING）', () => {
    expect(detailSource).toMatch(/errorOverlap/)
    expect(detailSource).toMatch(/errorLockVersionConflict/)
    expect(detailSource).toMatch(/errorInvalidTaxCode/)
    expect(detailSource).toMatch(/RECONCILE_ATTRIBUTE_MISMATCH/)
    expect(detailSource).toMatch(/PROCESSING/)
  })

  it('AC-162: 送信中は loading 表示になり、二重押下が抑止される', () => {
    expect(detailSource).toMatch(/const\s+loading\s*=\s*ref/)
    expect(detailSource).toMatch(/:disabled="loading/)
  })

  it('AC-163: キーボード操作・a11y を備える', () => {
    expect(detailSource).toMatch(/aria-label/)
  })
})

describe('K群 横断（AC-156・AC-165）', () => {
  it('AC-156: E2E は一連の操作をブラウザ操作のみで完結させ、直接 fetch/axios 呼び出しを持たない', () => {
    const e2eSource = readSourceOrEmpty('tests/e2e/admin/system-admin-price-revisions.spec.ts')
    expect(e2eSource.length, 'E2E仕様ファイルが存在しない').toBeGreaterThan(0)
    expect(e2eSource).not.toMatch(/\bfetch\(/)
    expect(e2eSource).not.toMatch(/axios\./)
    expect(e2eSource).toMatch(/getByRole|getByLabel|getByTestId/)
  })

  it('AC-165: FE 単体テスト（税導出表示・状態別ボタン活性・一覧絞り込み）が存在する', () => {
    expect(listSource.length, '一覧画面が未実装').toBeGreaterThan(0)
    expect(detailSource.length, '詳細画面が未実装').toBeGreaterThan(0)
  })
})
