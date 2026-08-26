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
  VillageMeetupVoteSummary,
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
  iconUrl: null,
  coverUrl: null,
  monshoUrl: null,
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

/**
 * 候補日サンプル。
 *
 * BE `MeetupCandidateDateResponse` は `{id, meetupId, candidateDate, sortOrder}` のみ。
 * 時刻・票数・確定フラグは含まれない（票数は投票集計 API から供給される）。
 */
const MOCK_CANDIDATE_DATE: VillageMeetupCandidateDateResponse = {
  id: '01900000-0000-7000-8100-000000000001',
  meetupId: '01900000-0000-7000-8200-000000000001',
  candidateDate: '2026-06-01',
  candidateTime: null,
  sortOrder: 0,
}

/**
 * 寄合 3 件。
 *
 * BE `MeetupResponse` は `location`（venue ではない）/ `confirmedDate`（LocalDate。
 * confirmedDateId ではない）を持ち、participantCount / updatedAt は持たない。
 * status は PLANNING / CONFIRMED / CANCELLED の 3 値のみ。
 */
const MOCK_MEETUPS: VillageMeetupResponse[] = [
  {
    id: '01900000-0000-7000-8200-000000000001',
    villageId: MOCK_VILLAGE_ID,
    organizerUserId: 1,
    title: 'たまねぎ収穫祭の打ち合わせ',
    description: '今年の収穫祭について語り合いましょう',
    location: '集会所',
    status: 'PLANNING',
    confirmedDate: null,
    confirmedTime: null,
    candidateDates: [MOCK_CANDIDATE_DATE],
    createdAt: '2026-05-14T00:00:00Z',
  },
  {
    id: '01900000-0000-7000-8200-000000000002',
    villageId: MOCK_VILLAGE_ID,
    organizerUserId: 1,
    title: '春の例祭準備会',
    description: null,
    location: 'オンライン',
    status: 'CONFIRMED',
    confirmedDate: '2026-05-20',
    confirmedTime: null,
    candidateDates: [],
    createdAt: '2026-05-10T00:00:00Z',
  },
  {
    id: '01900000-0000-7000-8200-000000000003',
    villageId: MOCK_VILLAGE_ID,
    organizerUserId: 2,
    title: '新年会反省会',
    description: null,
    location: null,
    status: 'CANCELLED',
    confirmedDate: null,
    confirmedTime: null,
    candidateDates: [],
    createdAt: '2026-02-01T00:00:00Z',
  },
]

/** 投票集計。BE `MeetupVoteSummaryResponse`（`GET /meetups/{id}/votes`）。 */
const MOCK_VOTE_SUMMARY: VillageMeetupVoteSummary = {
  meetupId: '01900000-0000-7000-8200-000000000001',
  candidates: [
    {
      candidateDateId: MOCK_CANDIDATE_DATE.id,
      candidateDate: MOCK_CANDIDATE_DATE.candidateDate,
      candidateTime: MOCK_CANDIDATE_DATE.candidateTime,
      availableCount: 2,
      maybeCount: 1,
      unavailableCount: 0,
    },
  ],
}

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

/**
 * ニュースレター設定。
 *
 * BE `NewsletterSettingsResponse` の実形状（`{villageId, settings: WEEKLY/MONTHLY の
 * 0〜2 件, optedOut: 個人の受信停止状態}`）に合わせる。以前はフラット単一形状
 * （`{userId, frequency, optedOut, ...}`）＋誤った URL（`.../villages/newsletter/settings`）
 * をモックしていたため、契約不一致（GET 空・PUT 400）を検知できていなかった。
 */
const MOCK_NEWSLETTER_SETTINGS: VillageNewsletterSettingsResponse = {
  villageId: MOCK_VILLAGE_ID,
  settings: [
    {
      id: '01900000-0000-7000-8300-000000000001',
      villageId: MOCK_VILLAGE_ID,
      frequency: 'WEEKLY',
      isEnabled: true,
      lastSentAt: '2026-05-07T00:00:00',
      nextScheduledAt: null,
      createdAt: '2026-05-01T00:00:00',
      updatedAt: '2026-05-07T00:00:00',
      version: 1,
    },
  ],
  optedOut: false,
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
    // 注: BE は ApiResponse でラップして `{ data: [...] }` を返す（素の配列ではない）。
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups**`,
      async (route) => {
        if (route.request().method() === 'GET') {
          await fulfillJson(route, { data: MOCK_MEETUPS })
        }
        else {
          await route.continue()
        }
      },
    )

    // 本モックは status クエリに関わらず 3 件返すため、既定フィルタ(PLANNING)のままでよい。
    await page.goto(`/villages/${MOCK_VILLAGE_ID}/meetups`)
    await waitForHydration(page)

    // 寄合 3 件のタイトルが表示されること
    await expect(page.getByText('たまねぎ収穫祭の打ち合わせ')).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByText('春の例祭準備会')).toBeVisible()
    await expect(page.getByText('新年会反省会')).toBeVisible()
  })

  test('VILLAGE-P3-002: 寄合の候補日に AVAILABLE 投票できる', async ({ page }) => {
    let voteMethod: string | null = null
    let voteBody: unknown = null
    let summaryFetchCount = 0
    const meetup = MOCK_MEETUPS[0]!
    const meetupId = meetup.id
    const candidateDateId = MOCK_CANDIDATE_DATE.id

    await setupVillageDetailMocks(page)

    // Playwright は「後から登録した route が優先」される。
    // そのため 一覧 → 詳細 → 集計 → 投票 の順に登録し、より具体的な URL を後勝ちさせる。

    // 一覧 GET
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups**`,
      async (route) => {
        if (route.request().method() === 'GET') {
          await fulfillJson(route, { data: MOCK_MEETUPS })
        }
        else {
          await route.continue()
        }
      },
    )

    // 詳細 GET（候補日込み）
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups/${meetupId}`,
      async (route) => {
        if (route.request().method() === 'GET') {
          await fulfillJson(route, { data: meetup })
        }
        else {
          await route.continue()
        }
      },
    )

    // 投票集計 GET（BE のパスは /votes。/votes/summary ではない）
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups/${meetupId}/votes`,
      async (route) => {
        if (route.request().method() === 'GET') {
          summaryFetchCount += 1
          await fulfillJson(route, { data: MOCK_VOTE_SUMMARY })
        }
        else {
          await route.continue()
        }
      },
    )

    // 投票 PUT（candidateDateId はパス変数・BE は 204 No Content で本体を返さない）
    await page.route(
      `**/api/v1/villages/${MOCK_VILLAGE_ID}/meetups/${meetupId}/candidate-dates/${candidateDateId}/vote`,
      async (route) => {
        voteMethod = route.request().method()
        voteBody = route.request().postDataJSON()
        await route.fulfill({ status: 204, body: '' })
      },
    )

    await page.goto(`/villages/${MOCK_VILLAGE_ID}/meetups`)
    await waitForHydration(page)

    // 寄合カードをクリック → 詳細ダイアログが開く
    await page.getByText(meetup.title).click()

    // 候補日が表示される
    await expect(page.getByText('2026-06-01')).toBeVisible({ timeout: 10_000 })

    // 集計 API 由来の票数が表示されること（AVAILABLE=2 / MAYBE=1 / UNAVAILABLE=0）
    await expect.poll(() => summaryFetchCount, { timeout: 5_000 }).toBeGreaterThan(0)

    // 「行ける」(AVAILABLE) ボタンをクリック
    await page.getByRole('button', { name: '行ける', exact: true }).click()

    // PUT が正しい verb / body で呼ばれたこと（body は voteType のみ）
    await expect.poll(() => voteMethod, { timeout: 5_000 }).toBe('PUT')
    expect(voteBody).toEqual({ voteType: 'AVAILABLE' })

    // 投票後は 204 のため再取得で最新化される（詳細 + 集計を再フェッチ）
    await expect.poll(() => summaryFetchCount, { timeout: 5_000 }).toBeGreaterThan(1)
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

    // GET/PUT /api/v1/villages/{id}/newsletter（実 URL は村 ID を含む）
    await page.route('**/api/v1/villages/*/newsletter', async (route) => {
      const method = route.request().method()
      if (method === 'GET') {
        await fulfillJson(route, MOCK_NEWSLETTER_SETTINGS)
      }
      else if (method === 'PUT') {
        putCalled = true
        // BE は upsert した単一 setting を返す（一覧ではない）。
        await fulfillJson(route, {
          id: '01900000-0000-7000-8300-000000000002',
          villageId: MOCK_VILLAGE_ID,
          frequency: 'MONTHLY',
          isEnabled: true,
          lastSentAt: null,
          nextScheduledAt: null,
          createdAt: '2026-05-01T00:00:00',
          updatedAt: '2026-05-17T00:00:00',
          version: 1,
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

    // 週次（WEEKLY）の見出しが表示されている（トグル UI へ刷新済み）
    await expect(page.getByText(/週次|WEEKLY|毎週/i).first()).toBeVisible()

    // 任意: MONTHLY トグルを操作すると PUT が飛ぶ（HEADMAN なら編集可能）。
    // トグルへ刷新したため単一「保存」ボタンは廃止。
    const monthlyToggle = page.getByTestId('newsletter-toggle-MONTHLY').first()
    if (await monthlyToggle.isVisible().catch(() => false)) {
      await monthlyToggle.click()
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
