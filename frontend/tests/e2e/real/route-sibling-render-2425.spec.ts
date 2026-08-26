/**
 * PR #2450 実機描画 E2E: 親子ルート地雷5件の index.vue 兄弟化検証（課題 #2425）。
 *
 * ── 何を検証しているか（根治点）──────────────────────────────────
 * `Foo.vue`（親ページ）と `Foo/`（子ルート群）が同一パスセグメントで共存すると、
 * Nuxt のファイルベースルーティングが `Foo.vue` を単独ページとして解決してしまい、
 * `Foo/xxx.vue` 配下の子ルートが一切マウントされない（親の loading スピナーで固まる）。
 * この地雷はユニットテスト・型チェックでは検出できず、実際にブラウザで子ルートへ
 * 遷移して「実コンテンツが描画されるか」を見て初めて検出・実証できる（#2407 の教訓）。
 *
 * PR #2450 は `Foo.vue` を `Foo/index.vue` へリネームすることで親子を同一ディレクトリの
 * 兄弟ルートとして再構成し、地雷の発生条件（`Foo.vue` と `Foo/` の共存）自体を解消した。
 *
 * 対象5件:
 *   1. /organizations/{slug}/tournaments → tournaments/{tId}/standings
 *   2. /teams/{slug}/tournaments → tournaments/{tId}/roster
 *   3. /public/organizations/{slug} → posts/{postId}（認証不要）
 *   4. /system-admin/advertising/moderation-queue → moderation-queue/{id}
 *   5. /teams/{slug}/events/{eventId} → events/{eventId}/roll-call
 *
 * ── 実データについて（#1・#2 の 6/6 化・本 worktree で追加）───────────
 * #1・#2 は「子ルートのマウント自体は実証済みだが、実データ未投入で陽性描画までは
 * 未検証」という状態だった（親ルーティング地雷とは無関係な別問題）。
 * 本ファイルの実行前提として、beforeAll で以下を実データとして作成する:
 *   - #1: PUBLIC 組織 + LEAGUE 大会 + ディビジョン + 参加チーム2 を作成し、
 *     順位表再計算（POST .../standings/recalculate）で
 *     tournament_standings 行を2件生成する（試合結果なしでも参加チーム分の
 *     0スタッツ行が作られる仕様: StandingsCalculationService#recalculate 参照）。
 *   - #2: 使い捨てチームを作成する（roster ページは matchId 未指定時は
 *     tournamentId 自体を参照しない空状態表示のため、大会の実在は不要。
 *     teams/[slug]/tournaments/[tId]/roster.vue 参照）。
 *   - #5: 上記チームにイベントを1件作成する（roll-call ページの見出しは
 *     イベント取得結果を待たず常に描画される。teams/[slug]/events/[eventId]/
 *     roll-call.vue 参照）。
 *   - #3・#4 は既存 seed データ（org-000001 の公開投稿 / モデレーションキュー
 *     キャンペーン）を再利用する。
 *
 * ── 実行方法 ──────────────────────────────────────────────
 *   cd frontend
 *   BASE_URL=http://localhost:3000 API_BASE_URL=http://localhost:8080 \
 *     node node_modules/@playwright/test/cli.js test tests/e2e/real/route-sibling-render-2425.spec.ts \
 *     --project=chromium-real --workers=1 --reporter=list
 *
 * ── CORS ブリッジ不要（本worktreeでの変更点）─────────────────────
 * 元の spec（別 worktree・課題 #2425 足軽作業）は検証用 FE を port 3002 で
 * 起動する前提で、BE の allowed-origins（既定 localhost:3000,8080）に
 * 3002 が含まれないため CORS ブリッジ（page.route 中継）を必要としていた。
 * 本ファイルは既存の本陣 FE（:3000・origin/main dev server）をそのまま再利用する
 * 前提のため、3000 は既定で許可オリジンに含まれておりブリッジは不要
 * （memory: feedback_e2e_wsl2_cors_apibridge の適用条件外）。
 */

import { test, expect, type Page } from '@playwright/test'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3000'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const ADMIN_EMAIL = process.env.TEST_ADMIN_EMAIL ?? 'e2e-admin@test.mannschaft.local'
const ADMIN_PASSWORD = process.env.TEST_ADMIN_PASSWORD ?? 'TestPass2026!'

// #3・#4 は既存 seed データを再利用（別 worktree 足軽作業で投入済み・shared BE:8080 に現存）
const ORG_SLUG_PUBLIC_POST = 'org-000001'
const PUBLIC_POST_ID = 94
const MODERATION_CAMPAIGN_ID = '7f000101-9f88-18d5-819f-8850a3ce000d'

// #1・#2・#5 は本ファイル beforeAll で新規作成する使い捨てデータ（下記で採番）
const UNIQUE_SUFFIX = Date.now()
let orgSlug: string
let orgNumericId: number
let orgTournamentId: number
let teamSlug: string
let teamNumericId: number
let teamTournamentId: number
let teamEventId: number

interface Me {
  id: number
  email: string
  lastName: string
  firstName: string
  avatarUrl: string | null
  systemRole: string | null
  timezone: string | null
}

/**
 * API ログイン。access_token Cookie を page context に載せ、
 * localStorage['currentUser'] を addInitScript で初期注入する
 * （既存 real spec 群 / fixtures/auth.ts と同一の作法）。
 */
async function login(
  page: Page,
  credentials: { email: string, password: string },
): Promise<void> {
  const loginRes = await page.request.post(`${API_BASE}/api/v1/auth/login`, {
    data: { email: credentials.email, password: credentials.password },
  })
  if (!loginRes.ok()) {
    throw new Error(`ログイン失敗 (${credentials.email}): ${loginRes.status()} ${await loginRes.text()}`)
  }
  const meRes = await page.request.get(`${API_BASE}/api/v1/users/me`)
  if (!meRes.ok()) throw new Error(`/users/me 失敗: ${meRes.status()}`)
  const me = (await meRes.json()).data as Me
  await page.addInitScript((u) => {
    localStorage.setItem('currentUser', JSON.stringify(u))
  }, {
    id: me.id,
    email: me.email,
    fullName: `${me.lastName} ${me.firstName}`,
    profileImageUrl: me.avatarUrl,
    systemRole: me.systemRole ?? undefined,
    timezone: me.timezone ?? undefined,
  })
}

/**
 * Node（サーバーサイド）から直接 BE を叩く seed 専用ヘルパー群。
 * ブラウザコンテキストとは独立（Playwright request fixture 不要）。
 */
async function loginBearer(email: string, password: string): Promise<string> {
  const res = await fetch(`${API_BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!res.ok) throw new Error(`seed用ログイン失敗: ${res.status} ${await res.text()}`)
  const json = await res.json()
  return json.data.accessToken as string
}

async function apiCall(
  method: string,
  path: string,
  token: string,
  body?: Record<string, unknown>,
): Promise<{ data: Record<string, unknown> }> {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  const json = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw new Error(`seed用API呼び出し失敗 ${method} ${path}: ${res.status} ${text}`)
  }
  return json
}

test.beforeAll(async () => {
  const token = await loginBearer(USER_EMAIL, USER_PASSWORD)

  // ── #1 用: PUBLIC 組織 + LEAGUE 大会 + ディビジョン + 参加チーム2 + 順位表再計算 ──
  const orgRes = await apiCall('POST', '/api/v1/organizations', token, {
    name: `E2E大会順位検証組織${UNIQUE_SUFFIX}`,
    orgType: 'NPO',
    visibility: 'PUBLIC',
  })
  orgSlug = orgRes.data.slug as string
  orgNumericId = orgRes.data.numericId as number

  const tournamentRes = await apiCall(
    'POST', `/api/v1/organizations/${orgNumericId}/tournaments`, token, {
      name: `E2E順位表検証大会${UNIQUE_SUFFIX}`,
      format: 'LEAGUE',
      sport: 'SOCCER',
      visibility: 'PUBLIC',
    })
  orgTournamentId = tournamentRes.data.id as number

  const divisionRes = await apiCall(
    'POST',
    `/api/v1/organizations/${orgNumericId}/tournaments/${orgTournamentId}/divisions`,
    token,
    { name: 'Aブロック' },
  )
  const divisionId = divisionRes.data.id as number

  const participant1 = await apiCall('POST', '/api/v1/teams', token, {
    name: `E2E順位表検証チームA${UNIQUE_SUFFIX}`,
    visibility: 'PUBLIC',
  })
  const participant2 = await apiCall('POST', '/api/v1/teams', token, {
    name: `E2E順位表検証チームB${UNIQUE_SUFFIX}`,
    visibility: 'PUBLIC',
  })

  await apiCall(
    'POST',
    `/api/v1/organizations/${orgNumericId}/tournaments/${orgTournamentId}/divisions/${divisionId}/participants`,
    token,
    { teamId: participant1.data.numericId },
  )
  await apiCall(
    'POST',
    `/api/v1/organizations/${orgNumericId}/tournaments/${orgTournamentId}/divisions/${divisionId}/participants`,
    token,
    { teamId: participant2.data.numericId },
  )

  await apiCall(
    'POST',
    `/api/v1/organizations/${orgNumericId}/tournaments/${orgTournamentId}/divisions/${divisionId}/standings/recalculate`,
    token,
  )

  // 事前裏取り: 標準表が実際に2行になっていること（ブラウザ検証の土台）
  const standingsCheck = await apiCall(
    'GET',
    `/api/v1/organizations/${orgNumericId}/tournaments/${orgTournamentId}/divisions/${divisionId}/standings`,
    token,
  )
  const standingsRows = standingsCheck.data as unknown as unknown[]
  if (!Array.isArray(standingsRows) || standingsRows.length !== 2) {
    throw new Error(`順位表seedが2行になっていません: ${JSON.stringify(standingsCheck)}`)
  }

  // ── #2・#5 用: 使い捨てチーム + イベント ──
  const teamRes = await apiCall('POST', '/api/v1/teams', token, {
    name: `E2Eメンバー表点呼検証チーム${UNIQUE_SUFFIX}`,
    visibility: 'PUBLIC',
  })
  teamSlug = teamRes.data.slug as string
  teamNumericId = teamRes.data.numericId as number
  // roster ページは matchId 未指定時にtournamentId自体を参照しないため、
  // 同一 orgTournamentId を便宜上使い回す（実在確認は不要な設計。roster.vue参照）。
  teamTournamentId = orgTournamentId

  const eventRes = await apiCall('POST', `/api/v1/teams/${teamNumericId}/events`, token, {
    subtitle: `E2E点呼検証イベント${UNIQUE_SUFFIX}`,
    visibility: 'MEMBERS_ONLY',
    attendanceMode: 'RSVP',
  })
  teamEventId = eventRes.data.id as number
})

test.describe('PR #2450: 親子ルート地雷5件 index.vue 兄弟化 実機描画検証', () => {
  // ── #3: 公開組織投稿詳細（認証不要・最も検証しやすい） ──────────────
  test('#3 公開組織投稿詳細（/public/organizations/{slug}/posts/{postId}）が実描画される', async ({ page }) => {
    await page.goto(`${BASE_URL}/public/organizations/${ORG_SLUG_PUBLIC_POST}/posts/${PUBLIC_POST_ID}`)

    // 親 index.vue（組織公開ページ）のコンテンツではなく、子ページ固有のコンテンツが描画されること。
    // 地雷が残っていれば親の loading のまま固まり、この見出しは永遠に現れない。
    await expect(page.getByText('E2E test org pub')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText('org pub body test')).toBeVisible({ timeout: 5_000 })
  })

  // ── #1: 組織大会 順位表（大会一覧の子ルート） ─────────────────────
  test('#1 組織大会 順位表（/organizations/{slug}/tournaments/{tId}/standings）が実描画される', async ({ page }) => {
    await login(page, { email: USER_EMAIL, password: USER_PASSWORD })
    await page.goto(`${BASE_URL}/organizations/${orgSlug}/tournaments/${orgTournamentId}/standings`)

    // 親 tournaments/index.vue（大会一覧）ではなく、順位表固有のテーブルが描画されること。
    await expect(page.getByTestId('standings-table')).toBeVisible({ timeout: 20_000 })
    // 実データ（tournament_standings 2行・本ファイル beforeAll で新規作成）が反映されていることも確認する。
    const rows = page.getByTestId('standings-table').locator('tbody tr')
    await expect(rows).toHaveCount(2, { timeout: 10_000 })
  })

  // ── #2: チーム大会 メンバー表（大会一覧の子ルート） ─────────────────
  test('#2 チーム大会 メンバー表（/teams/{slug}/tournaments/{tId}/roster）が実描画される', async ({ page }) => {
    await login(page, { email: USER_EMAIL, password: USER_PASSWORD })
    await page.goto(`${BASE_URL}/teams/${teamSlug}/tournaments/${teamTournamentId}/roster`)

    // PageHeader の見出し（親一覧ページには存在しない固有文言）が描画されること。
    // 注: この検証環境では `tournament.json` 名前空間が i18n に読み込まれず
    // （[intlify] Not found 'tournament.roster.title' key... の警告が実際に出る。
    //  frontend/nuxt.config.ts の files 一覧には 'ja/tournament.json' が正しく
    //  含まれておりファイル自体も origin/main と byte-identical なため、
    //  この spec の対象=ルーティング修正とは無関係な別の環境依存事象と特定済み）、
    // 見出しが翻訳済みテキストでなく raw key のまま描画されることがある。
    // どちらで描画されても「子ルートが実際にマウントされた証跡」としては同等に有効なため、
    // 両方を許容する正規表現でマッチする。
    await expect(
      page.getByRole('heading', { name: /試合メンバー表|tournament\.roster\.title/ }),
    ).toBeVisible({ timeout: 15_000 })
    // matchId 未指定時は空状態が表示される（無限ローディングではないことの証跡）。
    await expect(page.getByText(/試合を選択|tournament\.roster\.select_match/)).toBeVisible({ timeout: 5_000 })
  })

  // ── #4: SYSTEM_ADMIN 広告審査詳細（審査キューの子ルート） ─────────────
  test('#4 広告審査詳細（/system-admin/advertising/moderation-queue/{id}）が実描画される', async ({ page }) => {
    await login(page, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD })
    await page.goto(`${BASE_URL}/system-admin/advertising/moderation-queue/${MODERATION_CAMPAIGN_ID}`)

    // 親 index.vue の見出し「審査キュー」ではなく、詳細ページ固有の見出し
    // 「キャンペーン審査詳細」が描画されること（=子ルートが実際にマウントされた証跡）。
    await expect(page.getByRole('heading', { name: 'キャンペーン審査詳細' })).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText('審査キュー', { exact: true })).not.toBeVisible()
  })

  // ── #5: チームイベント 点呼（イベント詳細の子ルート） ────────────────
  test('#5 チームイベント 点呼（/teams/{slug}/events/{eventId}/roll-call）が実描画される', async ({ page }) => {
    await login(page, { email: USER_EMAIL, password: USER_PASSWORD })
    await page.goto(`${BASE_URL}/teams/${teamSlug}/events/${teamEventId}/roll-call`)

    // 親 [eventId]/index.vue（EventDetail）ではなく、点呼ページ固有の見出しが描画されること。
    await expect(page.locator('.rc-page__title', { hasText: '点呼' })).toBeVisible({ timeout: 15_000 })
  })
})
