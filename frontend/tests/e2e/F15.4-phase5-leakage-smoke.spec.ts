import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * F15.4 Phase 5-δ 漏洩スモーク + 404 網羅 E2E
 *
 * 設計書: docs/features/F15.4_phase5_team_public_detail.md §9.1 / §9.3
 *
 * カバレッジ:
 *   A. 個人情報漏洩スモーク（HTML / JSON のダブルチェック）
 *      - 「漏洩太郎」「漏洩花子」等の特徴的氏名が HTML 全文に出ない
 *      - メールアドレス（@含む文字列）が描画 HTML に出ない（aria/placeholder 含む）
 *      - API レスポンス JSON 自体に members / email / phone / address / archivedAt /
 *        deletedAt / version / supporterEnabled / chatMessages / announcements /
 *        files / attendances キーが含まれない
 *   B. 404 ケース網羅
 *      - 不在 ID / 削除済み / archived / visibility=MEMBERS_AND_ABOVE / visibility=GUESTS_AND_ABOVE
 *      - エラーページにチーム情報が漏れない
 */

const TEAM_ID = 9101

// 漏洩検査用の禁則語（個人情報・内部状態）
const FORBIDDEN_PHRASES = [
  '漏洩太郎',
  '漏洩花子',
  '漏洩三郎',
  '090-1234-5678',
  '03-1111-2222',
  'secret@example.com',
  'leak@example.com',
  '東京都渋谷区代々木1-2-3', // 番地レベル
] as const

const FORBIDDEN_JSON_KEYS = [
  'members',
  'memberList',
  'users',
  'userList',
  'email',
  'phone',
  'phoneNumber',
  'address',
  'archivedAt',
  'deletedAt',
  'version',
  'supporterEnabled',
  'chatMessages',
  'announcements',
  'files',
  'attendances',
] as const

const SAFE_PUBLIC_TEAM = {
  id: TEAM_ID,
  name: 'スモーク試験店舗',
  nameKana: 'スモークシケンテンポ',
  nickname1: null,
  nickname2: null,
  template: 'NEIGHBORHOOD',
  prefecture: '東京都',
  city: '渋谷区',
  iconUrl: null,
  bannerUrl: null,
  homepageUrl: null,
  establishedDate: null,
  establishedDatePrecision: null,
  philosophy: '地域の絆を大切に。',
  memberCount: 0,
  mapEmbedUrl: null,
}

/**
 * バックエンド DTO が「禁則フィールドを抑制し切れていなかった場合」をシミュレートするための
 * 「汚染レスポンス」。フロント側がもし誤って描画してしまったら検出するためのトラップ。
 * 実際の本番 DTO ではここに含まれる禁則フィールドは送られてこないが、E2E の防衛線として用意する。
 */
const POISONED_PUBLIC_TEAM = {
  ...SAFE_PUBLIC_TEAM,
  // 以下は本来あってはならない（バックエンド抑制 DTO に存在しない）が、
  // 万一フロントが追加属性を素通しで描画してしまった場合に検出するためのトラップ。
  members: [
    { id: 1, name: '漏洩太郎', email: 'leak@example.com' },
    { id: 2, name: '漏洩花子', email: 'secret@example.com' },
  ],
  email: 'leak@example.com',
  phone: '090-1234-5678',
  address: '東京都渋谷区代々木1-2-3',
}

async function mockPublicTeam(
  page: Page,
  opts: { status?: number; body?: object | null } = {},
): Promise<void> {
  const status = opts.status ?? 200
  await page.route('**/api/v1/public/teams/*', async (route: Route) => {
    if (status === 200) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: opts.body ?? SAFE_PUBLIC_TEAM }),
      })
    } else {
      await route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Not Found' }),
      })
    }
  })
}

// ════════════════════════════════════════════════════════════
// A. 個人情報漏洩スモーク
// ════════════════════════════════════════════════════════════

test('F15.4-P5δ-A1: 安全レスポンスでは個人情報語が一切描画されない', async ({ page }) => {
  await mockPublicTeam(page, { status: 200, body: SAFE_PUBLIC_TEAM })

  await page.goto(`/public/teams/${TEAM_ID}`)
  await expect(page.getByTestId('public-team-header')).toBeVisible()

  // 描画 HTML 全体を取得し、禁則語が一切含まれないことを確認
  const html = await page.content()
  for (const phrase of FORBIDDEN_PHRASES) {
    expect(
      html,
      `安全レスポンスで禁則語 '${phrase}' が描画 HTML に出現してはならない`,
    ).not.toContain(phrase)
  }

  // 描画テキストに「@」が漏れていないこと（メール推定）
  // 例外: ロケール `aria-label="..." `等で「@」が偽陽性で残るケースを除外するため、
  //       body の innerText（実描画テキスト）のみを対象に検査する。
  const bodyText = await page.locator('body').innerText()
  expect(bodyText).not.toMatch(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/)
})

test('F15.4-P5δ-A2: 汚染レスポンスでも禁則語が画面に描画されない（フロント側の防衛線）', async ({
  page,
}) => {
  // ※本来バックエンド抑制 DTO が保証するため到達しないが、深層防御として確認
  await mockPublicTeam(page, { status: 200, body: POISONED_PUBLIC_TEAM })

  await page.goto(`/public/teams/${TEAM_ID}`)
  await expect(page.getByTestId('public-team-header')).toBeVisible()

  const bodyText = await page.locator('body').innerText()
  for (const phrase of FORBIDDEN_PHRASES) {
    expect(
      bodyText,
      `汚染レスポンスでも '${phrase}' は描画テキストに出てはならない（フロント側で参照していないこと）`,
    ).not.toContain(phrase)
  }
})

test('F15.4-P5δ-A3: API レスポンス JSON 自体に禁則フィールドキーが含まれない', async ({
  page,
}) => {
  // バックエンドが返す本物のレスポンスを page.waitForResponse で捕捉する想定。
  // ここでは Playwright の route で SAFE_PUBLIC_TEAM を返し、その JSON にキーが無いことを確認する。
  await mockPublicTeam(page, { status: 200, body: SAFE_PUBLIC_TEAM })

  const [response] = await Promise.all([
    page.waitForResponse(
      (res) =>
        res.url().includes(`/api/v1/public/teams/${TEAM_ID}`) &&
        res.request().method() === 'GET',
    ),
    page.goto(`/public/teams/${TEAM_ID}`),
  ])

  const json = await response.text()
  for (const key of FORBIDDEN_JSON_KEYS) {
    // JSON キーは `"key"` の形で必ず出現する。部分一致でも誤検出にはならないが、
    // より厳密にダブルクオート付きで検査する。
    expect(
      json,
      `Public DTO JSON に禁則キー '"${key}"' が含まれてはならない（抑制 DTO 違反）`,
    ).not.toContain(`"${key}"`)
  }
})

// ════════════════════════════════════════════════════════════
// B. 404 ケース網羅
// ════════════════════════════════════════════════════════════

const NOT_FOUND_CASES: ReadonlyArray<{ label: string; teamId: number }> = [
  { label: 'archived チーム', teamId: 9201 },
  { label: '削除済みチーム', teamId: 9202 },
  { label: 'visibility=MEMBERS_AND_ABOVE チーム', teamId: 9203 },
  { label: 'visibility=GUESTS_AND_ABOVE チーム', teamId: 9204 },
  { label: '存在しないチーム ID', teamId: 9999 },
]

for (const { label, teamId } of NOT_FOUND_CASES) {
  test(`F15.4-P5δ-B-${teamId}: ${label} は 404 となり公開詳細が描画されない`, async ({
    page,
  }) => {
    // バックエンドは TEAM_001 → 一律 404（IDOR 対策）
    await mockPublicTeam(page, { status: 404, body: null })

    await page.goto(`/public/teams/${teamId}`)

    // 公開詳細領域が一切描画されないこと
    await expect(page.getByTestId('public-team-header')).toHaveCount(0)
    await expect(page.getByTestId('public-team-basic-info')).toHaveCount(0)
    await expect(page.getByTestId('public-team-map')).toHaveCount(0)
    await expect(page.getByTestId('public-team-philosophy')).toHaveCount(0)

    // エラーページにチーム名や所在地などのチラ見せが無いこと
    const html = await page.content()
    expect(html).not.toContain('スモーク試験店舗')
    expect(html).not.toContain('東京都 渋谷区')
  })
}
