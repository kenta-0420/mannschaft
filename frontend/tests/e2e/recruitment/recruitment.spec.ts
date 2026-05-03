import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F03.11 募集型予約 — Playwright E2E テスト (Phase 1+5a)
 *
 * テストID: RECRUIT-001 〜 RECRUIT-009
 *
 * 方針:
 * - API モック方式 (page.route で `**\/api/v1/...` をモック)
 * - waitForHydration でハイドレーション完了を待ってから UI 検証
 * - getByRole / getByText 優先、日本語値で検証
 * - timeout: 10_000 を統一
 *
 * 仕様書: docs/features/F03.11_recruitment_listing.md
 */

const TEAM_ID = 1
const LISTING_ID_OPEN = 101
const LISTING_ID_FULL = 102
const LISTING_ID_DRAFT = 103
const LISTING_ID_PAID = 104
const LISTING_ID_FREE = 105

// ---------------------------------------------------------------------------
// 共通モックデータ (Backend DTO に厳密準拠)
// ---------------------------------------------------------------------------

const MOCK_CATEGORIES = {
  data: [
    {
      id: 1,
      code: 'futsal_open',
      nameI18nKey: 'recruitment.category.futsal_open',
      icon: 'pi-circle-fill',
      defaultParticipationType: 'INDIVIDUAL',
      displayOrder: 10,
      isActive: true,
    },
    {
      id: 2,
      code: 'soccer_open',
      nameI18nKey: 'recruitment.category.soccer_open',
      icon: 'pi-circle-fill',
      defaultParticipationType: 'INDIVIDUAL',
      displayOrder: 20,
      isActive: true,
    },
    {
      id: 3,
      code: 'practice_match',
      nameI18nKey: 'recruitment.category.practice_match',
      icon: 'pi-users',
      defaultParticipationType: 'TEAM',
      displayOrder: 90,
      isActive: true,
    },
  ],
}

function buildListing(overrides: Record<string, unknown>) {
  return {
    id: LISTING_ID_OPEN,
    scopeType: 'TEAM',
    scopeId: TEAM_ID,
    categoryId: 1,
    categoryNameI18nKey: 'recruitment.category.futsal_open',
    subcategoryId: null,
    subcategoryName: null,
    title: 'フットサル個人参加 4/15(水) 19:00',
    description: '初心者歓迎です。',
    participationType: 'INDIVIDUAL',
    startAt: '2026-04-15T10:00:00Z',
    endAt: '2026-04-15T12:00:00Z',
    applicationDeadline: '2026-04-14T10:00:00Z',
    autoCancelAt: '2026-04-14T10:00:00Z',
    capacity: 12,
    minCapacity: 6,
    confirmedCount: 5,
    waitlistCount: 0,
    waitlistMax: 100,
    paymentEnabled: false,
    price: null,
    visibility: 'PUBLIC',
    status: 'OPEN',
    location: '渋谷スポーツセンター',
    reservationLineId: null,
    imageUrl: null,
    cancellationPolicyId: null,
    createdBy: 1,
    cancelledAt: null,
    cancelledBy: null,
    cancelledReason: null,
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-04-01T00:00:00Z',
    ...overrides,
  }
}

const MOCK_LISTING_OPEN = { data: buildListing({ id: LISTING_ID_OPEN, status: 'OPEN' }) }

const MOCK_LISTING_FULL = {
  data: buildListing({
    id: LISTING_ID_FULL,
    title: 'サッカー個人参加 4/20(月) 20:00',
    categoryId: 2,
    categoryNameI18nKey: 'recruitment.category.soccer_open',
    capacity: 10,
    confirmedCount: 10,
    waitlistCount: 2,
    status: 'FULL',
    location: '新宿フットサルコート',
    startAt: '2026-04-20T11:00:00Z',
    endAt: '2026-04-20T13:00:00Z',
  }),
}

const MOCK_LISTING_DRAFT = {
  data: buildListing({
    id: LISTING_ID_DRAFT,
    title: '【下書き】練習試合相手募集',
    categoryId: 3,
    categoryNameI18nKey: 'recruitment.category.practice_match',
    participationType: 'TEAM',
    capacity: 1,
    minCapacity: 1,
    confirmedCount: 0,
    visibility: 'SCOPE_ONLY',
    status: 'DRAFT',
    location: '駒沢公園グラウンド',
  }),
}

const MOCK_LISTING_PAID = {
  data: buildListing({
    id: LISTING_ID_PAID,
    title: 'ヨガクラス 4/12(日) 朝',
    paymentEnabled: true,
    price: 5000,
    cancellationPolicyId: 202,
  }),
}

const MOCK_LISTING_FREE = {
  data: buildListing({
    id: LISTING_ID_FREE,
    title: '無料イベント 4/18(土)',
    paymentEnabled: false,
    price: null,
    cancellationPolicyId: null,
  }),
}

const MOCK_LISTINGS_PAGED = {
  data: [MOCK_LISTING_OPEN.data, MOCK_LISTING_FULL.data],
  meta: {
    totalElements: 2,
    pageNumber: 0,
    pageSize: 20,
    totalPages: 1,
  },
}

const MOCK_MY_PARTICIPATIONS_FOR_OPEN = {
  data: [
    {
      id: 9001,
      listingId: LISTING_ID_OPEN,
      participantType: 'USER',
      userId: 1,
      teamId: null,
      appliedBy: 1,
      status: 'CONFIRMED',
      waitlistPosition: null,
      note: null,
      appliedAt: '2026-04-01T00:00:00Z',
      statusChangedAt: '2026-04-01T00:00:00Z',
    },
  ],
}

const MOCK_MY_PARTICIPATIONS_FOR_PAID = {
  data: [
    {
      id: 9002,
      listingId: LISTING_ID_PAID,
      participantType: 'USER',
      userId: 1,
      teamId: null,
      appliedBy: 1,
      status: 'CONFIRMED',
      waitlistPosition: null,
      note: null,
      appliedAt: '2026-04-01T00:00:00Z',
      statusChangedAt: '2026-04-01T00:00:00Z',
    },
  ],
}

const MOCK_MY_PARTICIPATIONS_FOR_FREE = {
  data: [
    {
      id: 9003,
      listingId: LISTING_ID_FREE,
      participantType: 'USER',
      userId: 1,
      teamId: null,
      appliedBy: 1,
      status: 'CONFIRMED',
      waitlistPosition: null,
      note: null,
      appliedAt: '2026-04-01T00:00:00Z',
      statusChangedAt: '2026-04-01T00:00:00Z',
    },
  ],
}

const MOCK_MY_PARTICIPATIONS_EMPTY = { data: [] }

const MOCK_FEE_ESTIMATE_PAID = {
  data: {
    listingId: LISTING_ID_PAID,
    policyId: 202,
    feeAmount: 2500,
    appliedTierId: 1,
    tierOrder: 2,
    feeType: 'PERCENTAGE',
    freeUntilApplied: false,
    hoursBeforeStart: 50,
    calculatedAt: '2026-04-08T09:00:00Z',
  },
}

const MOCK_FEE_ESTIMATE_FREE = {
  data: {
    listingId: LISTING_ID_FREE,
    policyId: null,
    feeAmount: 0,
    appliedTierId: null,
    tierOrder: null,
    feeType: null,
    freeUntilApplied: true,
    hoursBeforeStart: 200,
    calculatedAt: '2026-04-08T09:00:00Z',
  },
}

// ---------------------------------------------------------------------------
// 共通モック関数
// ---------------------------------------------------------------------------

/**
 * F03.11 募集型予約 E2E 用の API モック設定。
 * - キャッチオールで全 v1 API を空データで返し、500/401 を防止
 * - 後着優先で個別エンドポイントを上書き
 */
async function mockRecruitmentApis(page: Page): Promise<void> {
  // キャッチオール: 未モック API を空データで返してページ描画を妨げないようにする
  await page.route('**/api/v1/**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })

  // カテゴリマスタ
  await page.route('**/api/v1/recruitment-categories', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_CATEGORIES),
    })
  })

  // チーム別募集一覧 (PagedResponse 形式)
  await page.route(`**/api/v1/teams/${TEAM_ID}/recruitment-listings**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LISTINGS_PAGED),
    })
  })

  // 募集詳細 (ID 別)
  await page.route(`**/api/v1/recruitment-listings/${LISTING_ID_OPEN}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LISTING_OPEN),
    })
  })
  await page.route(`**/api/v1/recruitment-listings/${LISTING_ID_FULL}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LISTING_FULL),
    })
  })
  await page.route(`**/api/v1/recruitment-listings/${LISTING_ID_DRAFT}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LISTING_DRAFT),
    })
  })
  await page.route(`**/api/v1/recruitment-listings/${LISTING_ID_PAID}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LISTING_PAID),
    })
  })
  await page.route(`**/api/v1/recruitment-listings/${LISTING_ID_FREE}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_LISTING_FREE),
    })
  })

  // キャンセル料試算
  await page.route(
    `**/api/v1/recruitment-listings/${LISTING_ID_PAID}/cancellation-fee-estimate**`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_FEE_ESTIMATE_PAID),
      })
    },
  )
  await page.route(
    `**/api/v1/recruitment-listings/${LISTING_ID_FREE}/cancellation-fee-estimate**`,
    async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_FEE_ESTIMATE_FREE),
      })
    },
  )
}

/**
 * 自分の参加予定リストを切り替えるモック。
 */
async function mockMyParticipations(
  page: Page,
  payload: { data: unknown[] },
): Promise<void> {
  await page.route('**/api/v1/me/recruitment-listings', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payload),
    })
  })
}

// ---------------------------------------------------------------------------
// テストケース
// ---------------------------------------------------------------------------

test.describe('RECRUIT-001〜009: F03.11 募集型予約', () => {
  /**
   * 認証済み状態をシミュレート。
   * tests/e2e/.auth/user.json は .gitignore 対象のため、
   * storageState の origin に依存せず addInitScript で localStorage を直接設定する。
   */
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: 59,
          email: 'e2e-user@example.com',
          displayName: 'e2e_user',
          profileImageUrl: null,
        }),
      )
    })
  })

  test('RECRUIT-001: チーム募集一覧ページが表示される', async ({ page }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_EMPTY)

    await page.goto(`/teams/${TEAM_ID}/recruitment-listings`)
    await waitForHydration(page)

    // ページ見出し: i18n recruitment.page.teamRecruitmentListings = "募集枠管理"
    await expect(page.getByRole('heading', { name: '募集枠管理' })).toBeVisible({
      timeout: 10_000,
    })

    // モックした 1 件目のタイトル
    await expect(page.getByText('フットサル個人参加 4/15(水) 19:00')).toBeVisible({
      timeout: 10_000,
    })
  })

  test('RECRUIT-002: 募集詳細(OPEN・未参加)で「申込」ボタンが表示される', async ({ page }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_EMPTY)

    await page.goto(`/recruitment-listings/${LISTING_ID_OPEN}`)
    await waitForHydration(page)

    // 詳細タイトル
    await expect(
      page.getByRole('heading', { name: 'フットサル個人参加 4/15(水) 19:00' }),
    ).toBeVisible({ timeout: 10_000 })

    // OPEN かつ未参加 → i18n recruitment.action.apply = "申込" ボタン
    await expect(page.getByRole('button', { name: '申込' })).toBeVisible({ timeout: 10_000 })
  })

  test('RECRUIT-003: 募集詳細(FULL)で「キャンセル待ちに登録」ボタンが表示される', async ({
    page,
  }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_EMPTY)

    await page.goto(`/recruitment-listings/${LISTING_ID_FULL}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('heading', { name: 'サッカー個人参加 4/20(月) 20:00' }),
    ).toBeVisible({ timeout: 10_000 })

    // FULL → i18n recruitment.action.joinWaitlist = "キャンセル待ちに登録"
    await expect(
      page.getByRole('button', { name: 'キャンセル待ちに登録' }),
    ).toBeVisible({ timeout: 10_000 })
  })

  test('RECRUIT-004: 個人マイページに自分の参加予定が表示される', async ({ page }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_FOR_OPEN)

    await page.goto('/me/recruitment-listings')
    await waitForHydration(page)

    // ページ見出し: i18n recruitment.page.myRecruitmentListings = "自分の参加予定"
    await expect(page.getByRole('heading', { name: '自分の参加予定' })).toBeVisible({
      timeout: 10_000,
    })

    // status=CONFIRMED の参加レコードに対応する Tag ("確定") が表示される
    await expect(page.getByText('確定').first()).toBeVisible({ timeout: 10_000 })
  })

  test('RECRUIT-005: 募集作成ページにフォームが表示される', async ({ page }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_EMPTY)

    await page.goto(`/teams/${TEAM_ID}/recruitment-listings/new`)
    await waitForHydration(page)

    // ページ見出し: i18n recruitment.page.newListing = "募集枠を作成"
    await expect(page.getByRole('heading', { name: '募集枠を作成' })).toBeVisible({
      timeout: 10_000,
    })

    // タイトル入力欄 (label "タイトル" にひも付き)
    await expect(page.getByLabel('タイトル')).toBeVisible({ timeout: 10_000 })

    // 作成ボタン: i18n recruitment.action.create = "作成"
    await expect(page.getByRole('button', { name: '作成' })).toBeVisible({ timeout: 10_000 })
  })

  test('RECRUIT-006: キャンセル料試算 → 確認モーダルに ¥2,500 が表示される', async ({ page }) => {
    await mockRecruitmentApis(page)
    // 自分が PAID listing に CONFIRMED 状態 → cancelMyApplication フローが起動
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_FOR_PAID)

    await page.goto(`/recruitment-listings/${LISTING_ID_PAID}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('heading', { name: 'ヨガクラス 4/12(日) 朝' }),
    ).toBeVisible({ timeout: 10_000 })

    // i18n recruitment.action.cancelMyApplication = "申込をキャンセル"
    await page.getByRole('button', { name: '申込をキャンセル' }).click()

    // 確認モーダル見出し: PrimeVue Dialog の header は span でレンダリングされるため getByText を使用
    // i18n recruitment.confirmModal.cancellationFee.title = "キャンセル料の確認"
    await expect(page.getByText('キャンセル料の確認')).toBeVisible({
      timeout: 10_000,
    })

    // 試算結果 ¥2,500 (toLocaleString は ¥2,500 を返す)
    await expect(page.getByText('¥2,500')).toBeVisible({ timeout: 10_000 })
  })

  test('RECRUIT-007: DRAFT 状態の募集詳細に「公開」ボタンが表示される', async ({ page }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_EMPTY)

    await page.goto(`/recruitment-listings/${LISTING_ID_DRAFT}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('heading', { name: '【下書き】練習試合相手募集' }),
    ).toBeVisible({ timeout: 10_000 })

    // DRAFT → i18n recruitment.action.publish = "公開" ボタン
    await expect(page.getByRole('button', { name: '公開' })).toBeVisible({ timeout: 10_000 })
  })

  test('RECRUIT-008: 無料キャンセルのとき「無料でキャンセルできます」が表示される', async ({
    page,
  }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_FOR_FREE)

    await page.goto(`/recruitment-listings/${LISTING_ID_FREE}`)
    await waitForHydration(page)

    await expect(
      page.getByRole('heading', { name: '無料イベント 4/18(土)' }),
    ).toBeVisible({ timeout: 10_000 })

    await page.getByRole('button', { name: '申込をキャンセル' }).click()

    // i18n recruitment.confirmModal.cancellationFee.freeMessage = "現在は無料でキャンセルできます。"
    await expect(page.getByText('現在は無料でキャンセルできます。')).toBeVisible({
      timeout: 10_000,
    })
  })

  test('RECRUIT-009: 個人マイページが空のとき「募集はありません」が表示される', async ({
    page,
  }) => {
    await mockRecruitmentApis(page)
    await mockMyParticipations(page, MOCK_MY_PARTICIPATIONS_EMPTY)

    await page.goto('/me/recruitment-listings')
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '自分の参加予定' })).toBeVisible({
      timeout: 10_000,
    })

    // i18n recruitment.label.noListings = "募集はありません"
    await expect(page.getByText('募集はありません')).toBeVisible({ timeout: 10_000 })
  })
})
