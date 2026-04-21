import { test, expect } from '@playwright/test'
import { mockTeam, mockTeamFeatureApis, TEAM_ID } from './helpers'
import { waitForHydration } from '../helpers/wait'

const ORG_ID = 1

/** 招待トークン1件のモックデータ（InviteService.toResponse() に合わせたフィールド） */
const MOCK_INVITE_TOKEN = {
  id: 1,
  token: '550e8400-e29b-41d4-a716-446655440001',
  roleName: 'MEMBER',
  expiresAt: null,
  maxUses: null,
  usedCount: 0,
  revokedAt: null,
  createdAt: '2026-04-01T00:00:00Z',
}

const MOCK_INVITE_TOKENS_RESPONSE = {
  data: [MOCK_INVITE_TOKEN],
}

/** 組織詳細モックデータ */
const MOCK_ORG = {
  id: ORG_ID,
  name: 'テスト組織',
  nameKana: null,
  nickname1: null,
  nickname2: null,
  template: 'SPORTS',
  prefecture: '東京都',
  city: '渋谷区',
  description: 'E2Eテスト用組織',
  visibility: 'PUBLIC',
  supporterEnabled: false,
  version: 1,
  memberCount: 3,
  supporterCount: 0,
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  iconUrl: null,
  bannerUrl: null,
}

/** 組織管理者権限モックデータ */
const MOCK_ORG_PERMISSIONS = {
  roleName: 'ADMIN',
  permissions: [
    'member.manage',
  ],
}

test.describe('INVITE-QR-001〜005: 招待QRコードPDFダウンロード', () => {
  // Nuxt SSR の初回ルートコンパイルが並列実行時に遅延するため、グローバル 60 秒より長く設定
  test.setTimeout(120_000)

  test.beforeEach(async ({ page }) => {
    // 1. チーム基本情報・権限をモック
    await mockTeam(page)
    // 2. チーム配下フィーチャーAPIを空レスポンスでまとめてモック（LIFO順序に注意: 先に登録）
    await mockTeamFeatureApis(page)
    // 3. 招待トークン一覧を後から登録（LIFO順序により mockTeamFeatureApis より優先される）
    await page.route(`**/api/v1/teams/${TEAM_ID}/invite-tokens`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_INVITE_TOKENS_RESPONSE),
      })
    })
  })

  test('INVITE-QR-001: チームADMINの招待タブにPDFダウンロードボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // 招待タブをクリック（チームページではvalue=3）
    await page.getByRole('tab', { name: '招待' }).click()

    // PDFアイコンボタンが表示されていることを確認
    const pdfButton = page.locator('button:has(.pi-file-pdf)')
    await expect(pdfButton.first()).toBeVisible({ timeout: 10_000 })
  })

  test('INVITE-QR-002: PDFボタンクリックで正しいAPIエンドポイントが呼ばれる', async ({ page }) => {
    let pdfCalled = false

    // PDF取得エンドポイントをモック（固有ルートを後から登録してLIFO優先させる）
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/invite-tokens/${MOCK_INVITE_TOKEN.id}/pdf`,
      async (route) => {
        pdfCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/pdf',
          body: '%PDF-1.4 test',
        })
      },
    )

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // 招待タブをクリック
    await page.getByRole('tab', { name: '招待' }).click()

    // PDFボタンが表示されるまで待機してからクリック
    const pdfButton = page.locator('button:has(.pi-file-pdf)')
    await expect(pdfButton.first()).toBeVisible({ timeout: 10_000 })
    await pdfButton.first().click()

    // PDF APIが呼ばれたことを確認
    await expect(async () => {
      expect(pdfCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })

  test('INVITE-QR-003: PDFダウンロード中はボタンがローディング状態になる', async ({ page }) => {
    // 3秒遅延でモック（ローディング状態を確認するための十分な時間を確保）
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/invite-tokens/${MOCK_INVITE_TOKEN.id}/pdf`,
      async (route) => {
        await new Promise<void>((resolve) => setTimeout(resolve, 3_000))
        await route.fulfill({
          status: 200,
          contentType: 'application/pdf',
          body: '%PDF-1.4 test',
        })
      },
    )

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // 招待タブをクリック
    await page.getByRole('tab', { name: '招待' }).click()

    // PDFボタンが表示されるまで待機
    const pdfButton = page.locator('button:has(.pi-file-pdf)')
    await expect(pdfButton.first()).toBeVisible({ timeout: 10_000 })

    // リクエストが発火するのを先に待ち受けてからボタンをクリック
    const requestStarted = page.waitForRequest(
      (req) => req.url().includes(`/invite-tokens/${MOCK_INVITE_TOKEN.id}/pdf`),
    )
    await pdfButton.first().click()
    await requestStarted

    // PrimeVue 4 は loading 中に SVG スピナーを data-pc-section="loadingicon" で描画する
    await expect(page.locator('[data-pc-section="loadingicon"]').first()).toBeVisible({ timeout: 2_000 })
  })

  test('INVITE-QR-004: APIエラー時にエラートーストが表示される', async ({ page }) => {
    // PDF APIを500エラーでモック
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/invite-tokens/${MOCK_INVITE_TOKEN.id}/pdf`,
      async (route) => {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Internal Server Error' }),
        })
      },
    )

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // 招待タブをクリック
    await page.getByRole('tab', { name: '招待' }).click()

    // PDFボタンが表示されるまで待機してからクリック
    const pdfButton = page.locator('button:has(.pi-file-pdf)')
    await expect(pdfButton.first()).toBeVisible({ timeout: 10_000 })
    await pdfButton.first().click()

    // ja/common.json の inviteToken.printQrPdfError の値がトーストに表示される
    await expect(page.getByText('PDF印刷に失敗しました')).toBeVisible({ timeout: 5_000 })
  })

  test('INVITE-QR-005: 組織スコープの招待トークン一覧ではPDFボタンが表示されない', async ({ page }) => {
    // 組織ページに必要なAPIをモック
    await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ORG }),
      })
    })

    await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ORG_PERMISSIONS }),
      })
    })

    // 組織内チーム一覧（OrgTeamGrid 向け）
    await page.route(`**/api/v1/organizations/${ORG_ID}/teams`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    // フォローステータス（useOrgDetail.fetchFollowStatus 向け）
    await page.route(`**/api/v1/organizations/${ORG_ID}/follow/status`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { status: 'NONE' } }),
      })
    })

    // 組織の招待トークン一覧
    await page.route(`**/api/v1/organizations/${ORG_ID}/invite-tokens`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_INVITE_TOKENS_RESPONSE),
      })
    })

    // 組織ページ配下のその他APIを空レスポンスでまとめてモック
    await page.route(`**/api/v1/organizations/${ORG_ID}/**`, async (route) => {
      const url = route.request().url()
      if (url.includes('/me/permissions')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_ORG_PERMISSIONS }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
        })
      }
    })

    await page.goto(`/organizations/${ORG_ID}`)
    await waitForHydration(page)

    // 招待タブをクリック（組織ページではvalue=4）
    await page.getByRole('tab', { name: '招待' }).click()

    // InviteTokenList が表示されるまで待機（招待リンクセクションの存在を確認）
    await expect(page.getByRole('heading', { name: '招待リンク' })).toBeVisible({ timeout: 10_000 })

    // PDFアイコンボタンが表示されていないことを確認
    // v-if="props.scopeType === 'team'" によって組織スコープでは非表示になる
    const pdfButton = page.locator('button:has(.pi-file-pdf)')
    await expect(pdfButton).not.toBeVisible()
  })
})
