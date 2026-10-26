import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F04.10 組織委員会機能 — Playwright E2E テスト
 *
 * テストID: COMMITTEE-001 〜 COMMITTEE-004
 *
 * 方針:
 * - API モック方式 (page.route で `**\/api/v1/...` をモック)
 * - waitForHydration でハイドレーション完了を待ってから UI 検証
 * - getByRole / getByText 優先、日本語値で検証
 * - timeout: 10_000 を統一
 *
 * 実行前提:
 * - feature/F04.10-committee ブランチのフロントエンド dev server が起動していること
 * - dev server が委員会機能のページ (organizations/[id]/committees 等) を持つ必要がある
 * - 委員会ページが存在しない dev server で実行した場合は 404 のため自動スキップする
 *
 * 仕様書: docs/features/F04.10_committee.md
 */

/**
 * 委員会一覧ページが存在するか（404 でないか）を確認する。
 * feature/F04.10-committee 以外のブランチで動いている dev server では
 * このページが存在しないため、テストをスキップするために使用する。
 */
async function committeePageExists(page: Page): Promise<boolean> {
  const response = await page.request.get(`/organizations/1/committees`).catch(() => null)
  return response !== null && response.status() !== 404
}

const ORG_ID = 1
const COMMITTEE_ID = 101

// ---------------------------------------------------------------------------
// 共通モックデータ
// ---------------------------------------------------------------------------

const MOCK_ORG_DETAIL = {
  id: ORG_ID,
  name: 'テスト組織A',
  nameKana: null,
  nickname1: null,
  nickname2: null,
  prefecture: '東京都',
  city: '新宿区',
  description: 'E2Eテスト用組織',
  visibility: 'PUBLIC',
  supporterEnabled: false,
  version: 1,
  memberCount: 10,
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
}

const MOCK_COMMITTEES = [
  {
    id: COMMITTEE_ID,
    organizationId: ORG_ID,
    name: '広報委員会',
    description: 'SNS・告知担当',
    purposeTag: 'PR',
    status: 'ACTIVE',
    visibilityToOrg: 'NAME_ONLY',
    memberCount: 3,
    startDate: '2026-01-01',
    endDate: null,
    createdAt: '2026-01-01T00:00:00Z',
  },
]

const MOCK_COMMITTEE_DETAIL = {
  id: COMMITTEE_ID,
  organizationId: ORG_ID,
  name: '広報委員会',
  description: 'SNS・告知担当',
  purposeTag: 'PR',
  status: 'ACTIVE',
  visibilityToOrg: 'NAME_ONLY',
  memberCount: 3,
  startDate: '2026-01-01',
  endDate: null,
  myRole: 'MEMBER',
  createdAt: '2026-01-01T00:00:00Z',
}

const MOCK_COMMITTEE_MEMBERS = [
  {
    committeeId: COMMITTEE_ID,
    userId: 1,
    displayName: '山田太郎',
    avatarUrl: null,
    role: 'CHAIR',
    joinedAt: '2026-01-01T00:00:00Z',
  },
  {
    committeeId: COMMITTEE_ID,
    userId: 2,
    displayName: '鈴木花子',
    avatarUrl: null,
    role: 'MEMBER',
    joinedAt: '2026-01-02T00:00:00Z',
  },
]

// ---------------------------------------------------------------------------
// 共通ヘルパー
// ---------------------------------------------------------------------------

/**
 * 認証済みユーザーをシミュレート。
 * localStorage に accessToken / currentUser を設定する。
 */
async function setupAuth(page: Page): Promise<void> {
  await page.addInitScript(() => {
    localStorage.setItem(
      'accessToken',
      'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
    )
    localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
    localStorage.setItem(
      'currentUser',
      JSON.stringify({
        id: 2,
        email: 'e2e-user@example.com',
        displayName: 'e2e_user',
        profileImageUrl: null,
      }),
    )
  })
}

/**
 * 組織詳細 API をモック（委員会一覧ページが内部で組織情報を参照するため）
 */
async function mockOrgApis(page: Page): Promise<void> {
  // キャッチオール: 未モック API を空データで返してページ描画を妨げないようにする
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  await page.route(`**/api/v1/organizations/${ORG_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_ORG_DETAIL }),
    })
  })

  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { roleName: 'MEMBER', permissions: [] },
      }),
    })
  })
}

/**
 * 委員会関連 API をモック
 */
async function mockCommitteeApis(page: Page): Promise<void> {
  await mockOrgApis(page)

  // 委員会一覧
  await page.route(`**/api/v1/organizations/${ORG_ID}/committees`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_COMMITTEES }),
    })
  })

  // 委員会詳細
  await page.route(`**/api/v1/committees/${COMMITTEE_ID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_COMMITTEE_DETAIL }),
    })
  })

  // 委員会メンバー一覧
  await page.route(`**/api/v1/committees/${COMMITTEE_ID}/members`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_COMMITTEE_MEMBERS }),
    })
  })
}

/**
 * ADMIN 権限のモック（委員会作成ボタン表示に必要）
 */
async function mockOrgApisAsAdmin(page: Page): Promise<void> {
  await mockOrgApis(page)

  await page.route(`**/api/v1/organizations/${ORG_ID}/me/permissions`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { roleName: 'ADMIN', permissions: ['MANAGE_COMMITTEES'] },
      }),
    })
  })

  await page.route(`**/api/v1/organizations/${ORG_ID}/committees`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: MOCK_COMMITTEES }),
    })
  })
}

// ---------------------------------------------------------------------------
// テストケース
// ---------------------------------------------------------------------------

test.describe('COMMITTEE-001〜004: F04.10 組織委員会機能', () => {
  test.beforeEach(async ({ page }) => {
    await setupAuth(page)
  })

  test('COMMITTEE-001: 委員会一覧ページが表示される', async ({ page }) => {
    // feature/F04.10-committee ブランチ以外の dev server では委員会ページが存在しないためスキップ
    const exists = await committeePageExists(page)
    test.skip(!exists, 'dev server に委員会ページが存在しない（feature/F04.10-committee ブランチ以外）')

    await mockCommitteeApis(page)

    await page.goto(`/organizations/${ORG_ID}/committees`)
    await waitForHydration(page)

    // ページ見出し: i18n committee.list.title = "委員会一覧"
    await expect(page.getByRole('heading', { name: '委員会一覧' })).toBeVisible({
      timeout: 10_000,
    })

    // モックした委員会名が表示される
    await expect(page.getByText('広報委員会')).toBeVisible({ timeout: 10_000 })
  })

  test('COMMITTEE-002: ORG_ADMIN として「委員会を設立する」ボタンが表示され、ダイアログが開く', async ({
    page,
  }) => {
    // feature/F04.10-committee ブランチ以外の dev server では委員会ページが存在しないためスキップ
    const exists = await committeePageExists(page)
    test.skip(!exists, 'dev server に委員会ページが存在しない（feature/F04.10-committee ブランチ以外）')

    // ADMIN ロールとして設定
    await page.addInitScript(() => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: 1,
          email: 'e2e-admin@example.com',
          displayName: 'e2e_admin',
          profileImageUrl: null,
        }),
      )
    })

    await mockOrgApisAsAdmin(page)

    await page.goto(`/organizations/${ORG_ID}/committees`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '委員会一覧' })).toBeVisible({
      timeout: 10_000,
    })

    // i18n committee.list.create = "委員会を設立する"
    const createButton = page.getByRole('button', { name: '委員会を設立する' })
    await expect(createButton).toBeVisible({ timeout: 10_000 })

    // ボタンをクリックしてダイアログが開く
    await createButton.click()

    // i18n committee.create_dialog.title = "委員会を設立する"（ダイアログヘッダー）
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 5_000 })

    // 委員会名入力欄が表示される
    // i18n committee.field.name = "委員会名"
    await expect(page.getByText('委員会名')).toBeVisible({ timeout: 5_000 })
  })

  test('COMMITTEE-003: 委員会詳細ページに名前・ステータス・メンバー一覧が表示される', async ({
    page,
  }) => {
    // feature/F04.10-committee ブランチ以外の dev server では委員会ページが存在しないためスキップ
    const exists = await committeePageExists(page)
    test.skip(!exists, 'dev server に委員会ページが存在しない（feature/F04.10-committee ブランチ以外）')

    await mockCommitteeApis(page)

    await page.goto(`/committees/${COMMITTEE_ID}`)
    await waitForHydration(page)

    // 委員会名がページヘッダーに表示される
    await expect(page.getByRole('heading', { name: '広報委員会' })).toBeVisible({
      timeout: 10_000,
    })

    // ステータスタグ: i18n committee.status.ACTIVE = "活動中"
    await expect(page.getByText('活動中')).toBeVisible({ timeout: 10_000 })

    // メンバー一覧セクション: i18n committee.detail.members = "メンバー"
    await expect(page.getByText('メンバー')).toBeVisible({ timeout: 10_000 })

    // メンバーの名前が表示される
    await expect(page.getByText('山田太郎')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('鈴木花子')).toBeVisible({ timeout: 10_000 })
  })

  test('COMMITTEE-004: 無効な招集トークンにアクセスするとエラーメッセージが表示される', async ({
    page,
  }) => {
    // feature/F04.10-committee ブランチ以外の dev server では招集ページが存在しないためスキップ
    const exists = await committeePageExists(page)
    test.skip(!exists, 'dev server に委員会ページが存在しない（feature/F04.10-committee ブランチ以外）')

    // 無効なトークンに対して 404/400 を返すようにモック
    await page.route('**/api/v1/committee-invitations/accept-by-token', async (route) => {
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: 'INVITATION_NOT_FOUND',
            message: 'Invitation not found',
          },
        }),
      })
    })

    // 招集受諾ページは auth レイアウト (認証不要)
    await page.goto('/committee-invitations/invalid-token')
    await waitForHydration(page)

    // 受諾/辞退ボタンが表示される（エラーはボタンクリック後に発生）
    const acceptButton = page.getByRole('button', {
      name: '参加する', // i18n committee.invitation.accept
    })
    await expect(acceptButton).toBeVisible({ timeout: 10_000 })

    // 受諾ボタンをクリック → APIエラーが発生し、エラーメッセージ表示
    await acceptButton.click()

    // i18n committee.invitation.invalid = "無効な招集状です"
    await expect(page.getByRole('heading', { name: '無効な招集状です' })).toBeVisible({ timeout: 10_000 })
  })
})
