/**
 * F17 Phase 3 村機能 — E2E ゴールデンパステスト
 *
 * 設計書: docs/features/F17.1_village_community.md
 *
 * 5 件のゴールデンパスを mockApi 方式で網羅する。
 *   VILLAGE-P3-001: 寄合タブが表示できる（モック寄合 3 件）
 *   VILLAGE-P3-002: 寄合の候補日に AVAILABLE 投票できる
 *   VILLAGE-P3-003: 村史タブが月別カードで表示できる
 *   VILLAGE-P3-004: 巡礼推薦ウィジェットが今日のおすすめ村を表示する
 *   VILLAGE-P3-005: ニュースレター設定が表示できる
 *
 * 実 BE には依存せず、すべて page.route() でモックレスポンスを返す。
 * 認証は既存 storageState (playwright.config.ts) を流用する。
 */
import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import type {
  VillageChronicleResponse,
  VillageMeetupCandidateDateResponse,
  VillageMeetupResponse,
  VillageNewsletterSettingsResponse,
  VillagePilgrimageRecommendationResponse,
  VillageResponse,
} from '~/types/village'

// =============================================================================
// 共通モックデータ
// =============================================================================

const MOCK_VILLAGE_ID = '01900000-0000-7000-8000-000000000001'

/** 村本体（詳細ページ前提のためタブ系テストで必要） */
const MOCK_VILLAGE: VillageResponse = {
  id: MOCK_VILLAGE_ID,
  slug: 'test-village',
  name: 'テスト村',
  description: 'E2E テスト用の村',
  type: 'COMMUNITY',
  joinPolicy: 'FREE',
  visibility: 'PUBLIC',
  category: '一般',
  iconR2Key: null,
  coverR2Key: null,
  guidelineMd: null,
  bulletinVisibility: 'MEMBERS_ONLY',
  memberCount: 5,
  isOfficial: false,
  isMember: true,
  isPinned: false,
  myRole: 'HEADMAN',
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  version: 1,
}

/** 候補日サンプル */
const MOCK_CANDIDATE_DATE: VillageMeetupCandidateDateResponse = {
  id: '01900000-0000-7000-8100-000000000001',
  meetupId: '01900000-0000-7000-8200-000000000001',
  candidateDate: '2026-06-01',
  candidateTimeStart: '18:00',
  candidateTimeEnd: '20:00',
  voteCountYes: 2,
  voteCountNo: 0,
  voteCountMaybe: 1,
  isConfirmed: false,
}

/** 寄合 3 件 */
const MOCK_MEETUPS: VillageMeetupResponse[] = [
  {
    id: '01900000-0000-7000-8200-000000000001',
    villageId: MOCK_VILLAGE_ID,
    organizerUserId: 1,
    title: 'たまねぎ収穫祭の打ち合わせ',
    description: '今年の収穫祭について語り合いましょう',
    venue: '集会所',
    status: 'OPEN',
    confirmedDateId: null,
    candidateDates: [MOCK_CANDIDATE_DATE],
    participantCount: 3,
    createdAt: '2026-05-14T00:00:00Z',
    updatedAt: '2026-05-14T00:00:00Z',
  },
  {
    id: '01900000-0000-7000-8200-000000000002',
    villageId: MOCK_VILLAGE_ID,
    organizerUserId: 1,
    title: '春の例祭準備会',
    description: null,
    venue: 'オンライン',
    status: 'CONFIRMED',
    confirmedDateId: '01900000-0000-7000-8100-000000000002',
    candidateDates: [],
    participantCount: 5,
    createdAt: '2026-05-10T00:00:00Z',
    updatedAt: '2026-05-12T00:00:00Z',
  },
  {
    id: '01900000-0000-7000-8200-000000000003',
    villageId: MOCK_VILLAGE_ID,
    organizerUserId: 2,
    title: '新年会反省会',
    description: null,
    venue: null,
    status: 'CLOSED',
    confirmedDateId: null,
    candidateDates: [],
    participantCount: 8,
    createdAt: '2026-02-01T00:00:00Z',
    updatedAt: '2026-02-15T00:00:00Z',
  },
]

/**
 * 村史 月別 3 件。
 *
 * BE の実契約に一致させること（`yearMonth` は LocalDate の `YYYY-MM-DD`、
 * トピックは `{name, count}` の配列）。以前は FE 側の誤った想定
 * （`{items,total}` エンベロープ・`topicTags: string[]`・`YYYY-MM`）を
 * そのままモックしていたため、実 API では白画面になる不具合を検出できなかった。
 */
const MOCK_CHRONICLES: VillageChronicleResponse[] = [
  {
    id: '01900000-0000-7000-8300-000000000001',
    villageId: MOCK_VILLAGE_ID,
    yearMonth: '2026-04-01',
    generatedAt: '2026-05-01T00:00:00Z',
    postCount: 42,
    newMemberCount: 3,
    topics: [
      { name: 'たまねぎ', count: 5 },
      { name: '収穫', count: 3 },
      { name: '春', count: 2 },
    ],
  },
  {
    id: '01900000-0000-7000-8300-000000000002',
    villageId: MOCK_VILLAGE_ID,
    yearMonth: '2026-03-01',
    generatedAt: '2026-04-01T00:00:00Z',
    postCount: 31,
    newMemberCount: 1,
    topics: [{ name: '花見', count: 4 }],
  },
  {
    id: '01900000-0000-7000-8300-000000000003',
    villageId: MOCK_VILLAGE_ID,
    yearMonth: '2026-02-01',
    generatedAt: '2026-03-01T00:00:00Z',
    postCount: 28,
    newMemberCount: 2,
    topics: [{ name: '新年会', count: 6 }],
  },
]

/** 巡礼推薦 */
const MOCK_PILGRIMAGE_RECOMMENDATION: VillagePilgrimageRecommendationResponse = {
  id: '01900000-0000-7000-8400-000000000001',
  userId: 1,
  recommendedVillageId: MOCK_VILLAGE_ID,
  recommendedAt: '2026-05-14T00:00:00Z',
  reason: '同じ趣味を持つ村人が多くいます',
  visited: false,
  visitedAt: null,
}

/** ニュースレター設定 */
const MOCK_NEWSLETTER_SETTINGS: VillageNewsletterSettingsResponse = {
  userId: 1,
  villageId: MOCK_VILLAGE_ID,
  frequency: 'WEEKLY',
  optedOut: false,
  lastSentAt: '2026-05-07T00:00:00Z',
  nextScheduledAt: '2026-05-14T00:00:00Z',
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

/** 村詳細ページの共通モック（村本体 + メンバー一覧空） */
async function setupVillageDetailMocks(page: Page) {
  await page.route(`**/api/v1/villages/${MOCK_VILLAGE_ID}`, async (route) => {
    if (route.request().method() === 'GET') {
      await fulfillJson(route, MOCK_VILLAGE)
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
}

// =============================================================================
// ゴールデンパス
// =============================================================================

test.describe('VILLAGE-P3-001〜005: 村機能 Phase 3 ゴールデンパス', () => {
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

  test('VILLAGE-P3-001: 寄合タブが表示できる（モック寄合 3 件）', async ({ page }) => {
    await setupVillageDetailMocks(page)

    // GET /api/v1/villages/{id}/meetups
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups**`,
      async (route) => {
        if (route.request().method() === 'GET') {
          await fulfillJson(route, MOCK_MEETUPS)
        }
        else {
          await route.continue()
        }
      },
    )

    await page.goto(`/villages/${MOCK_VILLAGE_ID}/meetups`)
    await waitForHydration(page)

    // 寄合 3 件のタイトルが表示されること
    await expect(page.getByText('たまねぎ収穫祭の打ち合わせ')).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('春の例祭準備会')).toBeVisible()
    await expect(page.getByText('新年会反省会')).toBeVisible()
  })

  test('VILLAGE-P3-002: 寄合の候補日に投票できる', async ({ page }) => {
    let voteCalled = false
    const meetup = MOCK_MEETUPS[0]!
    const meetupId = meetup.id

    await setupVillageDetailMocks(page)

    // 一覧モック
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups**`,
      async (route) => {
        const url = route.request().url()
        const method = route.request().method()
        // /meetups/{id} の単体 GET には触らない
        if (method === 'GET' && !url.includes(`/meetups/${meetupId}`)) {
          await fulfillJson(route, MOCK_MEETUPS)
        }
        else {
          await route.continue()
        }
      },
    )

    // 詳細 GET
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups/${meetupId}`,
      async (route) => {
        if (route.request().method() === 'GET') {
          await fulfillJson(route, meetup)
        }
        else {
          await route.continue()
        }
      },
    )

    // 投票 POST
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups/${meetupId}/votes`,
      async (route) => {
        if (route.request().method() === 'POST') {
          voteCalled = true
          await fulfillJson(route, {
            ...meetup,
            candidateDates: [
              {
                ...MOCK_CANDIDATE_DATE,
                voteCountYes: MOCK_CANDIDATE_DATE.voteCountYes + 1,
              },
            ],
          })
        }
        else {
          await route.continue()
        }
      },
    )

    await page.goto(`/villages/${MOCK_VILLAGE_ID}/meetups`)
    await waitForHydration(page)

    // 寄合カードをクリック → 候補日が見える
    await page.getByText(meetup.title).click()

    // 候補日リストが表示される（YYYY-MM-DD いずれかのフォーマット）
    await expect(page.getByText(/2026-06-01|6月1日/)).toBeVisible({ timeout: 10_000 })

    // 「参加できる」「YES」「○」相当のいずれかのボタンをクリック
    const yesButton = page
      .getByRole('button', { name: /参加|YES|参加できる|○/i })
      .first()
    await yesButton.click()

    // POST モックが呼ばれたこと
    await expect.poll(() => voteCalled, { timeout: 5_000 }).toBe(true)
  })

  test('VILLAGE-P3-003: 村史タブが月別カードで表示できる', async ({ page }) => {
    await setupVillageDetailMocks(page)

    // GET /api/v1/villages/{id}/chronicles
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/chronicles**`,
      async (route) => {
        if (route.request().method() === 'GET') {
          // BE: ApiResponse<List<ChronicleResponse>> → {"data":[...]}（素の配列）
          await fulfillJson(route, { data: MOCK_CHRONICLES })
        }
        else {
          await route.continue()
        }
      },
    )

    await page.goto(`/villages/${MOCK_VILLAGE_ID}/chronicles`)
    await waitForHydration(page)

    // 月別ラベルが表示されること（h3 見出しに月別ラベル）
    // 注: getByText(/2026-04|2026年4月/) は h3 と日付 span の両方にマッチしてしまうため、
    // 月別カードの見出し (h3) に限定する。
    await expect(
      page.getByRole('heading', { name: /2026年4月|2026-04/ }),
    ).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('heading', { name: /2026年3月|2026-03/ })).toBeVisible()
    await expect(page.getByRole('heading', { name: /2026年2月|2026-02/ })).toBeVisible()

    // 投稿数バッジ（42）が見える
    // 注: page 内に nuxt devtools の "425" もあるので exact match で限定
    await expect(page.getByText('42', { exact: true }).first()).toBeVisible()
  })

  // fixme: PilgrimageRecommendationWidget.vue は実装済みだがダッシュボードへの組込みが未実施。
  // ダッシュボードページに <PilgrimageRecommendationWidget /> を配置するか、別ページで表示する
  // 仕様が確定してから本テストを有効化する。
  test.fixme('VILLAGE-P3-004: 巡礼推薦カードが表示できる', async ({ page }) => {
    // GET /api/v1/pilgrimage/today
    await page.route('**/api/v1/pilgrimage/today', async (route) => {
      if (route.request().method() === 'GET') {
        await fulfillJson(route, MOCK_PILGRIMAGE_RECOMMENDATION)
      }
      else {
        await route.continue()
      }
    })

    // 推薦先の村情報も問い合わせる可能性があるためモック
    await page.route(`**/api/v1/villages/${MOCK_VILLAGE_ID}`, async (route) => {
      if (route.request().method() === 'GET') {
        await fulfillJson(route, MOCK_VILLAGE)
      }
      else {
        await route.continue()
      }
    })

    // 巡礼推薦ウィジェットはダッシュボードに表示される想定
    await page.goto('/')
    await waitForHydration(page)

    // 推薦根拠の文言が表示されること
    await expect(page.getByText('同じ趣味を持つ村人が多くいます')).toBeVisible({
      timeout: 10_000,
    })
  })

  test('VILLAGE-P3-005: ニュースレター設定が表示できる', async ({ page }) => {
    let putCalled = false

    // GET /api/v1/villages/newsletter/settings
    await page.route('**/api/v1/villages/newsletter/settings', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await fulfillJson(route, MOCK_NEWSLETTER_SETTINGS)
      }
      else if (method === 'PUT') {
        putCalled = true
        await fulfillJson(route, {
          ...MOCK_NEWSLETTER_SETTINGS,
          frequency: 'MONTHLY',
        })
      }
      else {
        await route.continue()
      }
    })

    await setupVillageDetailMocks(page)

    await page.goto(`/villages/${MOCK_VILLAGE_ID}/newsletter-settings`)
    await waitForHydration(page)

    // フォームが表示される（「ニュースレター」「配信頻度」など見出しいずれか）
    await expect(
      page.getByText(/ニュースレター|配信頻度|Newsletter|Frequency/i).first(),
    ).toBeVisible({ timeout: 10_000 })

    // 現在の頻度 WEEKLY が反映されている UI 要素のいずれか
    // （Select/Dropdown 表示は実装依存のため、頻度ラベル文言が表示されることだけ確認）
    await expect(page.getByText(/週次|WEEKLY|毎週/i).first()).toBeVisible()

    // 任意: 保存ボタンが存在する（HEADMAN なら編集可能）
    const saveButton = page.getByRole('button', { name: /保存|Save/i }).first()
    if (await saveButton.isVisible().catch(() => false)) {
      await saveButton.click()
      // PUT が呼ばれることを確認（失敗しても致命的ではないため緩く）
      await expect.poll(() => putCalled, { timeout: 3_000 }).toBe(true)
    }
  })
})

// =============================================================================
// （未使用警告抑止: page 型を明示的にエクスポート参照）
// =============================================================================
// eslint-disable-next-line @typescript-eslint/no-unused-vars
type _PageRef = Page
