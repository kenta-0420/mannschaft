/**
 * 実機 E2E: ダッシュボードのウィジェット並び順「ジャンプ」根治の検証。
 *
 * 背景（マスター報告）:
 *   チーム/組織ダッシュボードをクライアント遷移（SPA）で開くと、デフォルト順で一旦描画され、
 *   API 応答後に保存順へ並び替わる「位置ジャンプ」が瞬間的に見えていた。
 *
 * 根治方針:
 *   並び順が確定（useAsyncData status=success/error）するまでウィジェットを描画しない。
 *   確定前はスケルトンのみ、確定後に保存順で初描画する。
 *
 * 決定的証拠（このテストが assert する 2 点）:
 *   1. 並び順確定前（GET /dashboard/widgets 遅延中）はスケルトンのみで、
 *      [data-widget-key] のウィジェットカードが DOM に 0 個であること（＝ジャンプの素が無い）。
 *   2. 確定後の [data-widget-key] の DOM 並び順が、事前に保存した「デフォルトと異なる順」と一致すること。
 *
 * 実行環境:
 *   - FE: 検証用 worktree dev サーバー（:3007）
 *   - BE: 本陣の実バックエンド（:8080・起動禁止）
 *   - BE は :3007 オリジンを CORS 許可していないため、ブラウザの /api/v1 リクエストは
 *     apibridge（page.route → route.fetch で :8080 へ node 中継 + 応答に ACAO 付与）で中継する。
 *   - apibridge は GET /api/v1/dashboard/widgets のみ 2000ms 遅延させ、遅延中の描画を観測する。
 */

import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

// localhost が IPv6(::1) に解決されると WSL2 の BE(IPv4 のみ) に届かず ECONNREFUSED になるため
// node 側の到達先は 127.0.0.1 に固定する（ブラウザの apiBase=localhost:8080 は bridge で 127.0.0.1 へ書換）。
const BE_BASE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8080'
const FE_ORIGIN = process.env.BASE_URL ?? 'http://127.0.0.1:3007'
const TEST_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const TEST_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

const WIDGETS_PATH = '/api/v1/dashboard/widgets'
const WIDGETS_GET_DELAY_MS = 2000

// team scope の BE WidgetKey enum → FE ケバブキー（= data-widget-key）逆引き表。
// useDashboardWidgets.ts の WidgetKeyMap（team 列）と一致させること。
const TEAM_BE_TO_FE: Record<string, string> = {
  TEAM_NOTICES: 'bulletin',
  TEAM_UPCOMING_EVENTS: 'upcoming-events',
  TEAM_TODO: 'todos',
  TEAM_LATEST_POSTS: 'timeline',
  TEAM_UNREAD_THREADS: 'chat',
  TEAM_SCHEDULE_CALENDAR: 'schedule',
  TEAM_MEMBERS: 'members',
  TEAM_ACTIVITY: 'activities',
  TEAM_GALLERY: 'gallery',
  TEAM_CIRCULATION: 'circulation',
  TEAM_SURVEYS: 'surveys',
  TEAM_SURVEY_RESULTS: 'survey-results',
  TEAM_MEMBER_ATTENDANCE: 'attendance-results',
  TEAM_BLOG: 'blog',
  TEAM_MEMBER_INFO: 'member-info',
  TEAM_TOURNAMENT_RECORD: 'team-standings-record',
  TEAM_DIVISION_STANDINGS: 'team-division-standings',
  TEAM_MATCH_SUMMARY: 'team-match-summary',
  TEAM_PROJECT_PROGRESS: 'projects',
}

interface BeWidget {
  widgetKey: string
  sortOrder: number
  visible: boolean
  moduleEnabled: boolean
}

/**
 * ブラウザの /api/v1 リクエストを :8080 へ node 中継する apibridge。
 * - CORS 回避のため応答に ACAO（実 origin）/ ACAC を付与する。
 * - ブラウザ origin が 127.0.0.1（:8080 の localhost と cross-site）だと SameSite 制約で
 *   認証 Cookie がブラウザから送出されないため、context の localhost Cookie を毎回読み取り
 *   node 側の転送リクエストへ明示注入する（トークンローテーションにも追従）。
 * - delayWidgetsGet=true のとき GET /dashboard/widgets だけ遅延を挿入し、確定前の描画を観測可能にする。
 */
async function installApiBridge(page: Page, delayWidgetsGet: boolean): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()
    const method = req.method()
    const origin = req.headers()['origin'] ?? FE_ORIGIN

    if (method === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'access-control-allow-origin': origin,
          'access-control-allow-credentials': 'true',
          'access-control-allow-methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
          'access-control-allow-headers':
            req.headers()['access-control-request-headers'] ?? 'authorization,content-type',
          'access-control-max-age': '600',
        },
      })
      return
    }

    const url = new URL(req.url())
    if (delayWidgetsGet && method === 'GET' && url.pathname === WIDGETS_PATH) {
      await new Promise((r) => setTimeout(r, WIDGETS_GET_DELAY_MS))
    }

    // BE ドメイン（localhost）の Cookie を毎回取得し、転送リクエストへ注入する。
    const beCookies = await page.context().cookies(BE_BASE)
    const cookieHeader = beCookies.map((c) => `${c.name}=${c.value}`).join('; ')
    const fwdHeaders: Record<string, string> = { ...req.headers() }
    if (cookieHeader) fwdHeaders['cookie'] = cookieHeader
    // node 中継は server-to-server。元の Origin/Referer（127.0.0.1:3007）を残すと
    // Spring の CORS フィルタが「許可外オリジン」として 403 を返すため除去する。
    delete fwdHeaders['origin']
    delete fwdHeaders['referer']

    // ブラウザは apiBase=localhost:8080 へ投げるが、localhost→::1 解決で WSL2 BE に届かない
    // ことがあるため、転送先は 127.0.0.1 に固定する。
    const targetUrl = req.url().replace('://localhost:8080', '://127.0.0.1:8080')
    const resp = await route.fetch({ url: targetUrl, headers: fwdHeaders })
    const body = await resp.body()
    const headers: Record<string, string> = {
      ...resp.headers(),
      'access-control-allow-origin': origin,
      'access-control-allow-credentials': 'true',
    }
    await route.fulfill({ status: resp.status(), headers, body })
  })
}

/** /me/teams から最初の所属チームを取得する。 */
async function resolveFirstTeam(page: Page): Promise<{ slug: string; name: string }> {
  const res = await page.request.get(`${BE_BASE}/api/v1/me/teams`)
  if (!res.ok()) throw new Error(`/me/teams 失敗: ${res.status()} ${await res.text()}`)
  const teams = (await res.json()).data as Array<{ slug: string; name: string; nickname1?: string }>
  if (!teams?.length) throw new Error('所属チームが 0 件。seed を確認すること。')
  const t = teams[0]!
  return { slug: t.slug, name: t.nickname1 || t.name }
}

/** チームカードのクリック対象文言（nickname1 || name）を取得する。 */
async function resolveTeamCardLabel(page: Page, slug: string): Promise<string> {
  const res = await page.request.get(`${BE_BASE}/api/v1/teams/${slug}`)
  if (!res.ok()) throw new Error(`/teams/${slug} 失敗: ${res.status()} ${await res.text()}`)
  const team = (await res.json()).data as { basicInfo?: { name?: string; nickname1?: string } }
  return team.basicInfo?.nickname1 || team.basicInfo?.name || ''
}

/**
 * チームの保存順を「デフォルトと明確に異なる順（= 現在順の逆順）」で保存する。
 * 戻り値は、FE が確実に描画する（moduleEnabled かつ visible かつ map 可能な）ウィジェットの
 * 期待 FE キー並び（保存後にこの相対順で DOM に並ぶはず）。
 */
async function saveReversedOrder(page: Page, slug: string): Promise<string[]> {
  const getRes = await page.request.get(`${BE_BASE}${WIDGETS_PATH}`, {
    params: { scopeType: 'team', scopeId: slug },
  })
  if (!getRes.ok()) throw new Error(`widgets GET 失敗: ${getRes.status()}`)
  const list = ((await getRes.json()).data as BeWidget[]).filter((w) => TEAM_BE_TO_FE[w.widgetKey])
  // 現在順（sortOrder 昇順・安定）。
  const current = [...list].sort((a, b) => a.sortOrder - b.sortOrder)

  // FE が確実に描画する renderable（module 有効 かつ visible）を抽出し、その逆順を「保存順」とする。
  const renderable = current.filter((w) => w.moduleEnabled && w.visible)
  expect(renderable.length, 'renderable ウィジェットが 2 件以上必要').toBeGreaterThanOrEqual(2)
  const desiredRenderable = [...renderable].reverse()

  // PUT は全件を contiguous な sortOrder で送り、ties を排除して完全に並びを制御する。
  // renderable（逆順）を先頭に、残り（module 無効等）を後ろに置く。
  const rest = current.filter((w) => !(w.moduleEnabled && w.visible))
  const fullOrder = [...desiredRenderable, ...rest]
  const widgets = fullOrder.map((w, i) => ({
    widgetKey: w.widgetKey,
    isVisible: w.visible,
    sortOrder: i,
  }))

  const putRes = await page.request.put(`${BE_BASE}${WIDGETS_PATH}`, {
    data: { scopeType: 'team', scopeId: slug, widgets },
  })
  if (!putRes.ok()) throw new Error(`widgets PUT 失敗: ${putRes.status()} ${await putRes.text()}`)

  // 期待 FE 並び（renderable のみの相対順）。
  return desiredRenderable.map((w) => TEAM_BE_TO_FE[w.widgetKey]!)
}

/** /teams 一覧からチームカードをクリックして SPA でチームダッシュボードへ遷移する。 */
async function spaNavigateToTeam(page: Page, slug: string, cardLabel: string): Promise<void> {
  await page.goto('/teams', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  const card = page.getByText(cardLabel, { exact: false }).first()
  await expect(card, `チームカード「${cardLabel}」が /teams に表示される`).toBeVisible({ timeout: 20_000 })
  await card.click()
  await page.waitForURL(new RegExp(`/teams/${slug.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(\\b|/|$)`), {
    timeout: 30_000,
  })
}

test.describe('ダッシュボード ウィジェット並び順ジャンプ根治', () => {
  test.setTimeout(120_000)

  test('DASH-JUMP-01: 確定前はスケルトンのみ（カード0個）、確定後は保存順で初描画される', async ({
    page,
  }, testInfo) => {
    await installApiBridge(page, true)
    await loginViaApi(page, { email: TEST_EMAIL, password: TEST_PASSWORD }, { apiBaseUrl: BE_BASE })

    const { slug } = await resolveFirstTeam(page)
    const cardLabel = await resolveTeamCardLabel(page, slug)
    expect(cardLabel, 'チーム表示名が取得できること').not.toEqual('')
    const expectedOrder = await saveReversedOrder(page, slug)
    console.log(`[DASH-JUMP-01] slug=${slug} label=${cardLabel} expected=${expectedOrder.join(',')}`)

    await spaNavigateToTeam(page, slug, cardLabel)

    const skeleton = page.locator('[data-testid="dashboard-widgets-skeleton"]')
    const cards = page.locator('[data-widget-key]')

    // 【決定的証拠 1】確定前: スケルトンが見え、ウィジェットカードは DOM に 0 個（ジャンプの素が無い）。
    await expect(skeleton, '確定前はスケルトンが表示される').toBeVisible({ timeout: 20_000 })
    expect(await cards.count(), '確定前はウィジェットカードが 0 個').toBe(0)

    const skeletonShot = testInfo.outputPath('01-skeleton-before-order-confirmed.png')
    await page.screenshot({ path: skeletonShot, fullPage: true })
    await testInfo.attach('01-skeleton', { path: skeletonShot, contentType: 'image/png' })

    // 確定後: スケルトンが消え、カードが出現する。
    await expect(skeleton, '確定後はスケルトンが消える').toBeHidden({ timeout: 20_000 })
    await expect(cards.first(), '確定後はウィジェットカードが描画される').toBeVisible({ timeout: 20_000 })

    // 【決定的証拠 2】renderable ウィジェットの DOM 相対順が保存順（逆順）と一致する。
    const domKeys = await cards.evaluateAll((els) => els.map((el) => el.getAttribute('data-widget-key')))
    const expectedSet = new Set(expectedOrder)
    const domRenderable = domKeys.filter((k): k is string => !!k && expectedSet.has(k))
    expect(domRenderable, '初描画の DOM 並び順が保存順（デフォルト順ではない）と一致').toEqual(expectedOrder)

    const confirmedShot = testInfo.outputPath('02-confirmed-saved-order.png')
    await page.screenshot({ path: confirmedShot, fullPage: true })
    await testInfo.attach('02-confirmed', { path: confirmedShot, contentType: 'image/png' })
  })

  test('DASH-JUMP-02: 遅延なし（実利用速度）でもジャンプせず保存順で表示される', async ({ page }, testInfo) => {
    await installApiBridge(page, false)
    await loginViaApi(page, { email: TEST_EMAIL, password: TEST_PASSWORD }, { apiBaseUrl: BE_BASE })

    const { slug } = await resolveFirstTeam(page)
    const cardLabel = await resolveTeamCardLabel(page, slug)
    const expectedOrder = await saveReversedOrder(page, slug)

    await spaNavigateToTeam(page, slug, cardLabel)

    const cards = page.locator('[data-widget-key]')
    await expect(cards.first()).toBeVisible({ timeout: 20_000 })
    const domKeys = await cards.evaluateAll((els) => els.map((el) => el.getAttribute('data-widget-key')))
    const expectedSet = new Set(expectedOrder)
    const domRenderable = domKeys.filter((k): k is string => !!k && expectedSet.has(k))
    expect(domRenderable, '遅延なしでも保存順で初描画される').toEqual(expectedOrder)

    const shot = testInfo.outputPath('03-no-delay-saved-order.png')
    await page.screenshot({ path: shot, fullPage: true })
    await testInfo.attach('03-no-delay', { path: shot, contentType: 'image/png' })
  })
})
