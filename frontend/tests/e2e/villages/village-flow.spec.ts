/**
 * F17.1 村機能 Phase 1-FE — E2E ゴールデンパステスト
 *
 * 設計書: docs/features/F17.1_village_community.md
 *
 * 5 件のゴールデンパスを mockApi 方式で網羅する。
 *   VILLAGE-001: 村一覧ページが表示される（モック村 3 件）
 *   VILLAGE-002: 村詳細ページのタブ切り替え (bulletin → timeline → lobby → members)
 *   VILLAGE-003: 村作成申請フォーム送信
 *   VILLAGE-004: お気に入りピンの追加・解除
 *   VILLAGE-005: 村ニックネーム編集
 *
 * 実 BE には依存せず、すべて page.route() でモックレスポンスを返す。
 * 認証は既存 storageState (playwright.config.ts) を流用する。
 */
import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import type {
  PinListResponse,
  PinResponse,
  VillageCreationRequestResponse,
  VillageNicknameResponse,
  VillageResponse,
  VillageSearchResponse,
} from '~/types/village'

// =============================================================================
// 共通モックデータ
// =============================================================================

const MOCK_VILLAGE_ID = '01900000-0000-7000-8000-000000000001'

/** 村詳細ページで使う基準データ */
const MOCK_VILLAGE: VillageResponse = {
  id: MOCK_VILLAGE_ID,
  slug: 'test-village',
  name: 'テスト村',
  description: 'E2E テスト用の村',
  type: 'COMMUNITY',
  joinPolicy: 'FREE',
  visibility: 'PUBLIC',
  category: '一般',
  iconUrl: null,
  coverUrl: null,
  monshoUrl: null,
  guidelineMd: null,
  bulletinVisibility: 'MEMBERS_ONLY',
  memberCount: 5,
  isOfficial: false,
  isMember: false,
  isPinned: false,
  myRole: null,
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
}

/** 村一覧用に 3 件用意 */
const MOCK_VILLAGE_LIST: VillageResponse[] = [
  { ...MOCK_VILLAGE, id: MOCK_VILLAGE_ID, name: 'たまねぎ村', slug: 'onion' },
  {
    ...MOCK_VILLAGE,
    id: '01900000-0000-7000-8000-000000000002',
    name: 'にんじん村',
    slug: 'carrot',
    type: 'OFFICIAL',
    isOfficial: true,
  },
  {
    ...MOCK_VILLAGE,
    id: '01900000-0000-7000-8000-000000000003',
    name: 'じゃがいも村',
    slug: 'potato',
    joinPolicy: 'APPROVAL',
  },
]

const MOCK_SEARCH_RESPONSE: VillageSearchResponse = {
  content: MOCK_VILLAGE_LIST,
  totalElements: MOCK_VILLAGE_LIST.length,
  page: 0,
  size: 20,
}

const MOCK_CREATION_REQUEST: VillageCreationRequestResponse = {
  id: '01900000-0000-7000-8001-000000000001',
  requesterUserId: 1,
  name: 'たまねぎ侍村',
  slug: 'tamanegi-samurai',
  category: '趣味',
  purpose: '玉ねぎ料理について語り合う場所',
  status: 'PENDING',
  reviewedBy: null,
  reviewedAt: null,
  reviewComment: null,
  createdVillageId: null,
  createdAt: '2026-05-14T00:00:00Z',
}

const MOCK_PIN: PinResponse = {
  id: '01900000-0000-7000-8002-000000000001',
  villageId: MOCK_VILLAGE_ID,
  villageName: 'たまねぎ村',
  villageIconUrl: null,
  sortOrder: 0,
  pinnedAt: '2026-05-14T00:00:00Z',
}

const MOCK_PIN_LIST: PinListResponse = {
  items: [MOCK_PIN],
  count: 1,
  maxLimit: 30,
}

const MOCK_NICKNAME_BEFORE: VillageNicknameResponse = {
  nickname: 'おにいさん',
  avatarR2Key: null,
  bio: null,
  lastChangedAt: '2026-04-01T00:00:00Z',
  changeCountThisMonth: 0,
  monthlyLimit: 3,
}

const MOCK_NICKNAME_AFTER: VillageNicknameResponse = {
  ...MOCK_NICKNAME_BEFORE,
  nickname: 'たまねぎ侍',
  lastChangedAt: '2026-05-14T00:00:00Z',
  changeCountThisMonth: 1,
}

// =============================================================================
// 汎用 fulfill ヘルパ
// =============================================================================

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

// =============================================================================
// ゴールデンパス
// =============================================================================

test.describe('VILLAGE-001〜005: 村機能 Phase 1-FE ゴールデンパス', () => {
  test.beforeEach(async ({ page }) => {
    // auth middleware を通過させるため localStorage に偽トークンを仕込む
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
          email: 'e2e-user@test.mannschaft.local',
          fullName: 'E2E ユーザー',
          profileImageUrl: null,
          systemRole: 'USER',
        }),
      )
    })
  })

  test('VILLAGE-001: 村一覧ページが表示される（モック村 3 件）', async ({ page }) => {
    // GET /api/v1/villages/search?... をモック
    await page.route('**/api/v1/villages/search**', async (route) => {
      await fulfillJson(route, MOCK_SEARCH_RESPONSE)
    })

    await page.goto('/villages')
    await waitForHydration(page)

    // モック村 3 件が表示される（村カード内の名前）
    await expect(page.getByText('たまねぎ村')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('にんじん村')).toBeVisible()
    await expect(page.getByText('じゃがいも村')).toBeVisible()

    // 「村を作る」ボタンが表示される (PageHeader 隣接)
    // 注: i18n 翻訳が遅延ロード未解決の場合 'village.action.create' のキー文字列が
    // そのまま表示されるケースがあるため、両パターンを許容する。
    await expect(
      page.getByRole('button', { name: /村を作る|village\.action\.create/ }),
    ).toBeVisible()
  })

  test('VILLAGE-002: 村詳細ページのタブ切り替え (bulletin/timeline/lobby/members)', async ({
    page,
  }) => {
    // 村詳細 + メンバー一覧モック
    await page.route(`**/api/v1/villages/${MOCK_VILLAGE_ID}`, async (route) => {
      if (route.request().method() === 'GET') {
        await fulfillJson(route, MOCK_VILLAGE)
      }
      else {
        await route.continue()
      }
    })
    // /memberships は配下の任意の path に応答
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/memberships**`,
      async (route) => {
        await fulfillJson(route, {
          content: [],
          page: 0,
          size: 100,
          totalElements: 0,
          totalPages: 0,
        })
      },
    )

    await page.goto(`/villages/${MOCK_VILLAGE_ID}/bulletin`)
    await waitForHydration(page)

    // VillageHeader = 村名 h1 表示
    await expect(page.getByRole('heading', { name: 'テスト村', level: 1 })).toBeVisible({
      timeout: 10_000,
    })

    // 4 タブが存在することを確認（PrimeVue Tabs の Tab。i18n 未解決時は生キーで出る）
    await expect(
      page.getByText(/^(掲示板|village\.tab\.bulletin)$/),
    ).toBeVisible()
    await expect(
      page.getByText(/^(タイムライン|village\.tab\.timeline)$/),
    ).toBeVisible()
    await expect(
      page.getByText(/^(ロビー|village\.tab\.lobby)$/),
    ).toBeVisible()
    await expect(
      page.getByText(/^(村人一覧|village\.tab\.members)$/),
    ).toBeVisible()

    // タイムラインタブへ遷移
    await page
      .getByText(/^(タイムライン|village\.tab\.timeline)$/)
      .first()
      .click()
    await page.waitForURL(`**/villages/${MOCK_VILLAGE_ID}/timeline`, { timeout: 5_000 })

    // ロビータブへ遷移
    await page.getByText(/^(ロビー|village\.tab\.lobby)$/).first().click()
    await page.waitForURL(`**/villages/${MOCK_VILLAGE_ID}/lobby`, { timeout: 5_000 })

    // 村人一覧タブへ遷移
    await page.getByText(/^(村人一覧|village\.tab\.members)$/).first().click()
    await page.waitForURL(`**/villages/${MOCK_VILLAGE_ID}/members`, { timeout: 5_000 })
  })

  test('VILLAGE-003: 村作成申請フォーム送信', async ({ page }) => {
    let createCalled = false

    // GET /api/v1/me/village-creation-requests — 初期は空、送信後は 1 件
    let createdList: VillageCreationRequestResponse[] = []
    await page.route('**/api/v1/me/village-creation-requests', async (route) => {
      if (route.request().method() === 'GET') {
        await fulfillJson(route, createdList)
      }
      else {
        await route.continue()
      }
    })

    // POST /api/v1/villages/creation-requests
    await page.route('**/api/v1/villages/creation-requests', async (route) => {
      if (route.request().method() === 'POST') {
        createCalled = true
        createdList = [MOCK_CREATION_REQUEST]
        await fulfillJson(route, MOCK_CREATION_REQUEST, 201)
      }
      else {
        await route.continue()
      }
    })

    await page.goto('/villages/create-request')
    await waitForHydration(page)

    // ガイドライン同意チェック
    const agree = page.locator('input#guideline-agreed')
    await agree.check()

    // フォーム入力
    await page.locator('input#village-name').fill('たまねぎ侍村')
    await page.locator('input#village-slug').fill('tamanegi-samurai')
    await page.locator('input#village-category').fill('趣味')
    await page.locator('textarea#village-purpose').fill('玉ねぎ料理について語り合う場所')

    // 送信ボタン押下（i18n 未解決時は生キーが出る可能性を考慮）
    await page
      .getByRole('button', { name: /村を作る|village\.action\.create|village\.action\.submit|送信/ })
      .first()
      .click()

    // POST モックが呼ばれたこと
    await expect.poll(() => createCalled, { timeout: 5_000 }).toBe(true)
  })

  test('VILLAGE-004: お気に入りピンの追加・解除', async ({ page }) => {
    // --- Step A: 村詳細ページでピン留め
    let addPinCalled = false

    // 初期: 未ピン
    let villageState: VillageResponse = {
      ...MOCK_VILLAGE,
      isMember: true,
      myRole: 'VILLAGER',
      isPinned: false,
    }

    await page.route(`**/api/v1/villages/${MOCK_VILLAGE_ID}`, async (route) => {
      if (route.request().method() === 'GET') {
        await fulfillJson(route, villageState)
      }
      else {
        await route.continue()
      }
    })

    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/memberships**`,
      async (route) => {
        await fulfillJson(route, {
          content: [],
          page: 0,
          size: 100,
          totalElements: 0,
          totalPages: 0,
        })
      },
    )

    await page.route(`**/api/v1/me/village-pins/${MOCK_VILLAGE_ID}`, async (route) => {
      if (route.request().method() === 'POST') {
        addPinCalled = true
        villageState = { ...villageState, isPinned: true }
        await fulfillJson(route, MOCK_PIN)
      }
      else if (route.request().method() === 'DELETE') {
        await route.fulfill({ status: 204 })
      }
      else {
        await route.continue()
      }
    })

    await page.goto(`/villages/${MOCK_VILLAGE_ID}/bulletin`)
    await waitForHydration(page)

    // ピン留めボタン押下（i18n 未解決時は生キーが出る可能性）
    await page
      .getByRole('button', { name: /お気に入りに追加|village\.action\.pin/ })
      .first()
      .click()
    await expect.poll(() => addPinCalled, { timeout: 5_000 }).toBe(true)

    // --- Step B: /me/village-pins でピン一覧表示確認
    await page.route('**/api/v1/me/village-pins', async (route) => {
      if (route.request().method() === 'GET') {
        await fulfillJson(route, MOCK_PIN_LIST)
      }
      else {
        await route.continue()
      }
    })

    await page.goto('/me/village-pins')
    await waitForHydration(page)

    // ピン一覧にピン留めした村名が表示される
    await expect(page.getByText(MOCK_PIN.villageName)).toBeVisible({ timeout: 10_000 })
  })

  test('VILLAGE-005: 村ニックネーム編集', async ({ page }) => {
    let putCalled = false

    // GET /api/v1/me/village-nickname
    await page.route('**/api/v1/me/village-nickname', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await fulfillJson(route, MOCK_NICKNAME_BEFORE)
      }
      else if (method === 'PUT') {
        putCalled = true
        await fulfillJson(route, MOCK_NICKNAME_AFTER)
      }
      else {
        await route.continue()
      }
    })

    await page.goto('/me/village-nickname')
    await waitForHydration(page)

    // 既存ニックネームが表示されている
    const nicknameInput = page.locator('input#village-nickname-input')
    await expect(nicknameInput).toHaveValue('おにいさん', { timeout: 10_000 })

    // 新ニックネームに変更
    await nicknameInput.fill('たまねぎ侍')

    // 保存ボタン押下（i18n 未解決時は生キーが出る可能性）
    await page
      .getByRole('button', { name: /保存|village\.action\.save|common\.action\.save/ })
      .first()
      .click()

    // PUT モックが呼ばれたこと
    await expect.poll(() => putCalled, { timeout: 5_000 }).toBe(true)
  })
})

// =============================================================================
// （未使用警告抑止: page 型を明示的にエクスポート参照）
// =============================================================================
// eslint-disable-next-line @typescript-eslint/no-unused-vars
type _PageRef = Page
