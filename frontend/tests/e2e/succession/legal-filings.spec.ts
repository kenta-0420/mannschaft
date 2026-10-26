import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import {
  ADMIN_USER,
  DEFAULT_ORG_ID,
  setupAuth,
  setupLayoutMocks,
  setupLegalFilingsMocks,
  buildLegalFiling,
} from './helpers'

/**
 * F09.15 S6 法的手続き準備 E2E テスト
 *
 * テストID: LF-001〜LF-008
 *
 * 方針:
 * - API モックを使用してバックエンドへの依存を排除（page.route() を使用）
 * - residence-status の E2E テストパターンに倣った構成
 * - 法的手続き準備ページ (/organizations/{orgId}/succession/legal-filings) を網羅する
 *
 * 仕様書: docs/features/F09.15_succession_support.md
 */

const ORG_ID = DEFAULT_ORG_ID
const PAGE_URL = `/organizations/${ORG_ID}/succession/legal-filings`

test.describe('LF: F09.15 法的手続き準備', () => {
  // --------------------------------------------------------------------------
  // LF-001: ページタイトル・警告バナー
  // --------------------------------------------------------------------------
  test('LF-001: ページタイトル・警告バナーが表示される', async ({ page }) => {
    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, { filings: [] })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // ページタイトル（h1: 法的手続き準備）
    await expect(page.getByRole('heading', { name: '法的手続き準備' })).toBeVisible({
      timeout: 10_000,
    })

    // 警告バナー（Message warn / 弁護士監修なし注意書き）
    await expect(page.getByText('弁護士')).toBeVisible({ timeout: 10_000 })
  })

  // --------------------------------------------------------------------------
  // LF-002: 空一覧時の「データなし」メッセージ
  // --------------------------------------------------------------------------
  test('LF-002: 空一覧時に「データなし」メッセージが表示される', async ({ page }) => {
    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, { filings: [] })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // empty メッセージ
    await expect(page.getByText('法的手続きはまだありません')).toBeVisible({ timeout: 10_000 })
  })

  // --------------------------------------------------------------------------
  // LF-003: 一覧テーブルに既存 filing が表示される
  // --------------------------------------------------------------------------
  test('LF-003: 一覧テーブルに既存 filing が表示される', async ({ page }) => {
    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, {
      filings: [
        buildLegalFiling({
          id: 'filing-uuid-001',
          filingType: 'ABSENTEE_PROPERTY_MANAGER',
          residentRegistryId: 1001,
          dwellingUnitId: 201,
        }),
        buildLegalFiling({
          id: 'filing-uuid-002',
          filingType: 'INHERITANCE_LIQUIDATOR',
          residentRegistryId: 1002,
          dwellingUnitId: 202,
        }),
      ],
    })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // 2 件の行が DataTable に並ぶ
    await expect(page.locator('table tbody tr')).toHaveCount(2, { timeout: 10_000 })

    // タイプ Tag が表示されている（不在者財産管理人選任申立 / 相続財産清算人選任申立）
    await expect(
      page.getByText('不在者財産管理人選任申立').first(),
    ).toBeVisible({ timeout: 10_000 })
    await expect(
      page.getByText('相続財産清算人選任申立').first(),
    ).toBeVisible({ timeout: 10_000 })

    // residentRegistryId 列に数値（1001 / 1002）が表示される
    await expect(page.getByText('1001').first()).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('1002').first()).toBeVisible({ timeout: 10_000 })
  })

  // --------------------------------------------------------------------------
  // LF-004: 「新規起票」ボタンでダイアログが開く
  // --------------------------------------------------------------------------
  test('LF-004: 「新規起票」ボタンでダイアログが開く', async ({ page }) => {
    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, { filings: [] })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // ヘッダーの「新規起票」ボタンをクリック
    await page.getByRole('button', { name: '新規起票' }).click()

    // Dialog が表示される
    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // ダイアログ内に申立種別・居住者台帳ID・居室ID ラベルがある
    await expect(dialog.getByText('申立種別')).toBeVisible({ timeout: 5_000 })
    await expect(dialog.getByText('居住者台帳 ID')).toBeVisible({ timeout: 5_000 })
    await expect(dialog.getByText('居室 ID')).toBeVisible({ timeout: 5_000 })
  })

  // --------------------------------------------------------------------------
  // LF-005: 起票フォーム送信成功 → 一覧に追加される
  // --------------------------------------------------------------------------
  test('LF-005: 起票フォーム送信成功で一覧に追加される', async ({ page }) => {
    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, { filings: [] })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // 起票ダイアログを開く
    await page.getByRole('button', { name: '新規起票' }).click()
    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // InputNumber（居住者台帳ID / 居室ID）に値を入れる
    // PrimeVue InputNumber は内部 <input> を持つ。ダイアログ内の input.p-inputnumber-input 順に対応する
    const residentInput = dialog.locator('.p-inputnumber-input').nth(0)
    await residentInput.click()
    await residentInput.fill('1001')
    await residentInput.press('Tab')

    const dwellingInput = dialog.locator('.p-inputnumber-input').nth(1)
    await dwellingInput.click()
    await dwellingInput.fill('201')
    await dwellingInput.press('Tab')

    // POST レスポンスを待ち受けてから「起票する」をクリック
    const postResponsePromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/api/v1/organizations/${ORG_ID}/succession/legal-filings`)
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )

    await dialog.getByRole('button', { name: '起票する' }).click()
    const response = await postResponsePromise
    expect(response.status()).toBe(201)

    // ダイアログが閉じる
    await expect(dialog).toBeHidden({ timeout: 5_000 })

    // 一覧再取得後、行が 1 件に増える
    await expect(page.locator('table tbody tr')).toHaveCount(1, { timeout: 10_000 })
    await expect(page.getByText('1001').first()).toBeVisible({ timeout: 10_000 })
  })

  // --------------------------------------------------------------------------
  // LF-006: residentRegistryId=0 だと「起票する」が disabled
  // --------------------------------------------------------------------------
  test('LF-006: residentRegistryId が 0 だと送信ボタン disabled', async ({ page }) => {
    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, { filings: [] })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // ダイアログを開く
    await page.getByRole('button', { name: '新規起票' }).click()
    const dialog = page.locator('[role="dialog"]').last()
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // 何も入力しない（residentRegistryId と dwellingUnitId の初期値 = 0）
    // 「起票する」ボタンが disabled になっている
    const submitBtn = dialog.getByRole('button', { name: '起票する' })
    await expect(submitBtn).toBeDisabled({ timeout: 5_000 })
  })

  // --------------------------------------------------------------------------
  // LF-007: 証拠 ZIP 生成 → ダウンロードボタンへ切り替わる
  // --------------------------------------------------------------------------
  test('LF-007: 証拠 ZIP 生成ボタンで POST → 一覧再取得後にダウンロードボタンへ', async ({
    page,
  }) => {
    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, {
      filings: [
        buildLegalFiling({
          id: 'filing-uuid-build-001',
          evidencePackageS3Key: undefined,
          evidenceBuiltAt: undefined,
        }),
      ],
    })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // 「証拠 ZIP 生成」ボタンが表示される
    const buildBtn = page.getByRole('button', { name: /証拠 ZIP 生成/ })
    await expect(buildBtn).toBeVisible({ timeout: 10_000 })

    // POST evidence-package のレスポンスを待ち受け
    const buildPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes('/evidence-package')
        && !resp.url().includes('/download-url')
        && resp.request().method() === 'POST',
      { timeout: 10_000 },
    )

    await buildBtn.click()
    const response = await buildPromise
    expect(response.status()).toBe(200)

    // 一覧再取得後、ボタンが「証拠 ZIP ダウンロード」に切り替わる
    await expect(
      page.getByRole('button', { name: /証拠 ZIP ダウンロード/ }),
    ).toBeVisible({ timeout: 10_000 })
  })

  // --------------------------------------------------------------------------
  // LF-008: ダウンロードボタンで window.open が呼ばれる
  // --------------------------------------------------------------------------
  test('LF-008: 証拠 ZIP ダウンロード URL 取得で window.open が呼ばれる', async ({ page }) => {
    const expectedUrl = 'https://example.com/signed-url/evidence-download-001.zip'

    // window.open を spy する（ページ navigate 前に注入）
    await page.addInitScript(() => {
      const w = window as unknown as { __openedUrls: string[]; open: typeof window.open }
      w.__openedUrls = []
      w.open = ((url?: string | URL) => {
        w.__openedUrls.push(typeof url === 'string' ? url : String(url ?? ''))
        return null
      }) as typeof window.open
    })

    await setupAuth(page, ADMIN_USER)
    await setupLayoutMocks(page, ADMIN_USER)
    await setupLegalFilingsMocks(page, ORG_ID, {
      filings: [
        buildLegalFiling({
          id: 'filing-uuid-download-001',
          evidencePackageS3Key: 's3://mannschaft/evidence/filing-uuid-download-001.zip',
          evidenceBuiltAt: '2026-05-15T10:00:00Z',
        }),
      ],
      evidenceUrl: expectedUrl,
    })

    await page.goto(PAGE_URL)
    await waitForHydration(page)

    // 「証拠 ZIP ダウンロード」ボタンが表示される
    const downloadBtn = page.getByRole('button', { name: /証拠 ZIP ダウンロード/ })
    await expect(downloadBtn).toBeVisible({ timeout: 10_000 })

    // GET download-url のレスポンスを待ち受けてからクリック
    const downloadUrlPromise = page.waitForResponse(
      (resp) =>
        resp.url().includes('/evidence-package/download-url')
        && resp.request().method() === 'GET',
      { timeout: 10_000 },
    )

    await downloadBtn.click()
    const response = await downloadUrlPromise
    expect(response.status()).toBe(200)

    // window.open がモック URL で呼ばれた
    const openedUrls = await page.evaluate(
      () => (window as unknown as { __openedUrls: string[] }).__openedUrls,
    )
    expect(openedUrls).toContain(expectedUrl)
  })
})
