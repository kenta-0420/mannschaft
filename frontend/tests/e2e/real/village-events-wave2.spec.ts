/**
 * F17.2 村行事の活性化（Village Events Activation）— 実機 E2E（Wave2）。
 *
 * 設計書: docs/features/F17.2_village_events_activation.md
 *   §3 機能①フィード自動還流（AC-02b システム投稿の FE 表示契約）
 *   §5 機能③お祭りの参加レイヤー（RSVP・実況・§5.2/§5.4/§5.6・AC-14/15）
 *   §7 機能⑦村史（行事アーカイブ・§7.1 マスター裁定確定）
 *   §10 チームとの差別化ガードレール（G1 未回答者非表示 / G3 欠席率・皆勤なし / G4 実名なし）
 *
 * このテストは API モックを使わない実機テストである。
 * 実バックエンド（API_BASE / 既定 http://127.0.0.1:8080）と
 * 実フロントエンド（BASE_URL / 既定 http://127.0.0.1:3001）が起動済みの状態で、
 * page.goto → click / fill → expect(visible) の UI 操作で検証する。
 *
 * ## 認証・セッション設計（金型 village-events-activation.spec.ts を完全踏襲）
 *  - storageState を空にし、各 test 内で `loginViaApi` で毎回フレッシュにログインする
 *    （token ローテーションによる後続 test の失効を避ける single-session 設計）。
 *  - FE(3001) → BE(8080) はブラウザ直呼びだと CORS 許可オリジンに 3001 が無く全滅するため、
 *    `setupApiBridge`（page.route で横取り → Node fetch 中継 + ACAO 差し替え）で通す。
 *
 * ## 村・ユーザーの前提（seed-e2e-data.js）
 *  - 村作成は運営権限（VILLAGE_037）＋レート上限のため、e2e-user が HEADMAN を務める
 *    seed 村（`6e87b493-…`・COMMUNITY/FREE/PUBLIC）を土台にする。作成した寄合/祭は afterAll で片付ける。
 *  - システム投稿（①）の EVENT_CREATED は「寄合/祭/歳時記を作成」した契機で village タイムラインへ
 *    自動生成される（BE 配線・§3.4）。本 spec は「作成 → 村フィードに『村の行事案内』名義で出る」
 *    という FE 表示契約（AC-02b・空カード/空アバターにならないこと）を実機で検証する。
 *  - 祭 RSVP（③）は SCHEDULED/ACTIVE で受付。ACTIVE 祭は startsAt=過去 / endsAt=未来で作成すると
 *    初期 status が ACTIVE になる（BE: startsAt<=now<endsAt → ACTIVE・15分バッチ不要）ため、
 *    実況（live-posts）セクションも同一祭で検証できる。
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const BASE_URL = process.env.BASE_URL ?? 'http://127.0.0.1:3001'
const API_BASE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8080'

// e2e-user は seed 村 6e87b493 の HEADMAN（村長）。
const USER_EMAIL = 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = 'TestPass2026!'

// e2e-user が村長を務める seed 村（COMMUNITY / FREE / PUBLIC）。
const VILLAGE_ID = '6e87b493-512a-11f1-95e3-2ec96fe3ea06'

// ---------------------------------------------------------------------------
// API ブリッジ: FE(3001) 起源のブラウザ XHR を Node fetch で BE(8080) へ中継し、
// ACAO をブラウザ実 origin に差し替えて CORS を通す（既存 real spec の作法を踏襲）。
// ---------------------------------------------------------------------------
async function setupApiBridge(page: Page): Promise<void> {
  await page.route('**/api/v1/**', async (route) => {
    const req = route.request()

    // プリフライト（credentialed cross-origin の PUT/PATCH/DELETE/JSON POST 等）は
    // ブリッジ側で 204 + 許可ヘッダーを即返しする（BE は 3001 を許可オリジンに持たないため）。
    if (req.method() === 'OPTIONS') {
      await route.fulfill({
        status: 204,
        headers: {
          'access-control-allow-origin': BASE_URL,
          'access-control-allow-credentials': 'true',
          'access-control-allow-methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
          'access-control-allow-headers': req.headers()['access-control-request-headers'] ?? 'authorization,content-type',
        },
      })
      return
    }

    const url = req.url().replace(/^https?:\/\/[^/]+/, API_BASE)
    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries(req.headers())) {
      const lk = k.toLowerCase()
      // Origin/Referer/Host を落として「サーバー間リクエスト」として BE へ渡す
      // （Spring の CORS フィルタが 3001 を弾いて 403 化するのを避ける）。Cookie/Authorization は残す。
      if (lk === 'origin' || lk === 'referer' || lk === 'host') continue
      headers[k] = v
    }
    try {
      const bodyText = req.postData()
      const fetchRes = await fetch(url, { method: req.method(), headers, body: bodyText ?? undefined })
      const resBody = await fetchRes.arrayBuffer()
      const resHeaders: Record<string, string> = {}
      fetchRes.headers.forEach((v, k) => {
        const lk = k.toLowerCase()
        // 自前で CORS ヘッダーを付与するので BE 由来の CORS ヘッダーは捨てる。
        if (lk === 'access-control-allow-origin' || lk === 'access-control-allow-credentials') return
        // node fetch は本文を解凍済みなので、圧縮/長さ系ヘッダーはブラウザを壊すため落とす。
        if (lk === 'content-encoding' || lk === 'content-length' || lk === 'transfer-encoding') return
        resHeaders[k] = v
      })
      resHeaders['access-control-allow-origin'] = BASE_URL
      resHeaders['access-control-allow-credentials'] = 'true'
      await route.fulfill({ status: fetchRes.status, headers: resHeaders, body: Buffer.from(resBody) })
    }
    catch {
      await route.abort()
    }
  })
}

// ---------------------------------------------------------------------------
// ログイン: page.request で BE に直接ログインし、Cookie + Bearer + localStorage を設定。
// FE の authStore は localStorage.currentUser の有無で認証を判定する。
// ---------------------------------------------------------------------------
async function loginViaApi(page: Page, email: string, password: string): Promise<number> {
  const res = await page.request.post(`${API_BASE}/api/v1/auth/login`, {
    data: { email, password },
    headers: { 'Content-Type': 'application/json' },
  })
  if (!res.ok()) throw new Error(`ログイン失敗 (${email}): ${res.status()} ${await res.text()}`)
  const body = (await res.json()) as { data: { accessToken: string, userId: number, email?: string, fullName?: string } }
  const { accessToken, userId } = body.data
  await page.setExtraHTTPHeaders({ Authorization: `Bearer ${accessToken}` })
  await page.goto(BASE_URL + '/', { waitUntil: 'domcontentloaded' })
  await page.evaluate((user) => {
    localStorage.setItem('currentUser', JSON.stringify(user))
  }, { id: userId, email: body.data.email ?? email, fullName: body.data.fullName ?? 'E2Eユーザー', profileImageUrl: null })
  return userId
}

// 認証済みの独立 requestContext（token を明示指定）。setup/teardown 用の Node 側 API。
async function apiFetch(ctx: APIRequestContext, token: string, method: string, path: string, body?: unknown) {
  const opt: { headers: Record<string, string>, data?: unknown } = {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
  }
  if (body !== undefined) opt.data = body
  const res = await ctx.fetch(`${API_BASE}${path}`, { method, ...opt })
  return res
}

async function loginToken(ctx: APIRequestContext, email: string, password: string): Promise<{ token: string, userId: number }> {
  const res = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password } })
  expect(res.ok(), `login ${email}`).toBeTruthy()
  const b = (await res.json()).data
  return { token: b.accessToken, userId: b.userId }
}

// 候補日付き寄合を作る（PLANNING）。作成契機で EVENT_CREATED システム投稿が村フィードへ流れる（§3.4）。
async function createMeetup(ctx: APIRequestContext, token: string, title: string): Promise<string> {
  const res = await apiFetch(ctx, token, 'POST', `/api/v1/villages/${VILLAGE_ID}/meetups`, {
    title,
    candidateDates: [{ date: '2026-09-01' }, { date: '2026-09-02' }],
  })
  expect(res.status(), 'createMeetup').toBe(201)
  return (await res.json()).data.id
}

// startsAt=過去 / endsAt=未来 で祭を作ると初期 status=ACTIVE（BE: startsAt<=now<endsAt → ACTIVE）。
async function createActiveFestival(ctx: APIRequestContext, token: string, title: string): Promise<string> {
  const now = Date.now()
  const startsAt = new Date(now - 24 * 3600_000).toISOString().slice(0, 19) // 昨日
  const endsAt = new Date(now + 24 * 3600_000).toISOString().slice(0, 19) // 明日
  const res = await apiFetch(ctx, token, 'POST', `/api/v1/villages/${VILLAGE_ID}/festivals`, {
    title,
    description: 'E2E ACTIVE 祭',
    startsAt,
    endsAt,
    bannerR2Key: null,
    themeColorHex: null,
  })
  expect(res.status(), `createFestival ${await res.text().catch(() => '')}`).toBe(201)
  const created = (await res.json()).data
  expect(created.status, '作成直後は ACTIVE').toBe('ACTIVE')
  return created.id
}

async function gotoTab(page: Page, tab: string) {
  await page.goto(`${BASE_URL}/villages/${VILLAGE_ID}/${tab}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  await expect(page).not.toHaveURL(/\/login/)
}

// ===========================================================================
// W2-① 行事→村フィード自動還流（システム投稿の FE 表示契約・AC-02b）
// ===========================================================================
test.describe('F17.2 Wave2 ① フィード還流', () => {
  test.setTimeout(150_000)

  const createdMeetupIds: string[] = []
  let sharedCtx: APIRequestContext
  let userToken = ''

  test.beforeAll(async ({ playwright }) => {
    sharedCtx = await playwright.request.newContext()
    userToken = (await loginToken(sharedCtx, USER_EMAIL, USER_PASSWORD)).token
  })

  test.afterAll(async () => {
    // PLANNING の寄合は cancel 可能（システム投稿レコード自体は歴史として残る＝仕様どおり）。
    for (const id of createdMeetupIds) {
      await apiFetch(sharedCtx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/meetups/${id}/cancel`).catch(() => {})
    }
    await sharedCtx.dispose()
  })

  test('VE-W2-1: 行事作成→村フィードに「村の行事案内」名義のシステム投稿が出る（AC-02b・空カードにならない）', async ({ page }) => {
    // 1) 村長として寄合を新規作成（→ EVENT_CREATED システム投稿が村フィードへ自動生成される）
    const title = `寄合フィードE2E-${Date.now()}`
    createdMeetupIds.push(await createMeetup(sharedCtx, userToken, title))

    await setupApiBridge(page)
    await loginViaApi(page, USER_EMAIL, USER_PASSWORD)
    await gotoTab(page, 'timeline')

    // 2) 「村の行事案内」名義のシステム投稿カードが村タイムラインに出現する（AC-02b）
    const sysCard = page
      .locator('[data-testid="team-timeline-post"]')
      .filter({ has: page.locator('[data-testid="timeline-post-author-name"]', { hasText: '村の行事案内' }) })
      .first()
    await expect(sysCard).toBeVisible({ timeout: 25_000 })

    // 3) 投稿者名が「村の行事案内」で描画される（空文字＝名無しの空カードでないこと・§3.9）
    const authorName = sysCard.getByTestId('timeline-post-author-name')
    await expect(authorName).toHaveText('村の行事案内')
    expect((await authorName.innerText()).trim().length).toBeGreaterThan(0)

    // 4) システムアイコンのアバターが出る（空アバターでないこと。EVENT_CREATED → pi-calendar-plus）
    const avatar = sysCard.getByTestId('timeline-system-post-avatar')
    await expect(avatar).toBeVisible()
    await expect(avatar.locator('.pi').first()).toBeVisible()

    // 5) システム投稿種別バッジが出る（systemPostType 非 null で FE 分岐が働いた証跡）
    await expect(sysCard.getByTestId('timeline-system-post-badge')).toBeVisible()

    // 6) 認可 DOM 検査（§10 G4）: 実名（メール）・未回答者・欠席率・皆勤がフィードに出ない
    const bodyText = (await page.locator('body').innerText()).toString()
    expect(bodyText, '実名(メール)が露出しない').not.toContain('@test.mannschaft.local')
    expect(bodyText).not.toContain('未回答')
    expect(bodyText).not.toContain('欠席率')
    expect(bodyText).not.toContain('皆勤')
  })
})

// ===========================================================================
// W2-③ お祭りの参加レイヤー（RSVP・実況）
// ===========================================================================
test.describe('F17.2 Wave2 ③ 祭参加レイヤー', () => {
  test.setTimeout(180_000)

  let sharedCtx: APIRequestContext
  let userToken = ''
  let festivalId = ''
  let festivalTitle = ''

  test.beforeAll(async ({ playwright }) => {
    sharedCtx = await playwright.request.newContext()
    userToken = (await loginToken(sharedCtx, USER_EMAIL, USER_PASSWORD)).token
    festivalTitle = `祭参加E2E-${Date.now()}`
    festivalId = await createActiveFestival(sharedCtx, userToken, festivalTitle)
  })

  test.afterAll(async () => {
    // 自分の RSVP を取り消し → 祭を中止（ACTIVE でも中止可）。
    await apiFetch(sharedCtx, userToken, 'DELETE', `/api/v1/villages/${VILLAGE_ID}/festivals/${festivalId}/rsvp`).catch(() => {})
    await apiFetch(sharedCtx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/festivals/${festivalId}/cancel`).catch(() => {})
    await sharedCtx.dispose()
  })

  // 祭一覧（ACTIVE フィルタ）から対象祭の詳細ダイアログを開く。
  async function openFestival(page: Page) {
    await gotoTab(page, 'festivals')
    // 既定フィルタが ACTIVE なので、作成した ACTIVE 祭のカードがそのまま出る。
    const card = page.locator('.village-festival__card', { hasText: festivalTitle }).first()
    await expect(card).toBeVisible({ timeout: 20_000 })
    await card.click()
    const dialog = page.locator('[role="dialog"]').filter({ hasText: festivalTitle }).first()
    await expect(dialog).toBeVisible({ timeout: 15_000 })
    return dialog
  }

  test('VE-W2-2: RSVP「行く」→「たぶん行く」に upsert（一覧1件）＋役割ラベル＋回答者一覧にニックネーム（ABSENT/欠席なし）', async ({ page }) => {
    await setupApiBridge(page)
    await loginViaApi(page, USER_EMAIL, USER_PASSWORD)
    const dialog = await openFestival(page)

    // 参加表明セクションが出る（§5.6・ACTIVE は回答可）
    await expect(dialog.getByRole('heading', { name: '参加表明', exact: true })).toBeVisible()

    // ガードレール（§10 G3）: RSVP に ABSENT/欠席の選択肢が無い。GOING/MAYBE のみ。
    await expect(dialog.getByRole('button', { name: '行く', exact: true })).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'たぶん行く', exact: true })).toBeVisible()

    // 回答者チップ（displayName + status バッジ）。RSVP セクション内の丸チップのみを数える。
    const chips = dialog.locator('div.inline-flex.rounded-full.border')

    // 1) 「行く」→ 反映（自分の1件が出る）
    await dialog.getByRole('button', { name: '行く', exact: true }).click()
    await expect(chips).toHaveCount(1, { timeout: 15_000 })
    await expect(chips.first()).toContainText('行く')

    // 2) 役割ラベルを入力 →「たぶん行く」に変更（upsert・件数は増えない）
    const roleLabel = `出店係E2E-${Date.now() % 100000}`
    await dialog.getByPlaceholder('役割（任意・例: 出店係）').fill(roleLabel)
    await dialog.getByRole('button', { name: 'たぶん行く', exact: true }).click()
    await expect(chips).toHaveCount(1) // upsert: 1件のまま
    await expect(chips.first()).toContainText('たぶん行く')
    await expect(chips.first()).toContainText(roleLabel)

    // 3) 回答者一覧に村ニックネームが出る（実名/メールは出ない・§10 G4）
    const chipText = await chips.first().innerText()
    expect(chipText, '回答者はニックネーム表示').not.toContain('@test.mannschaft.local')

    // 4) ガードレール（§10 G1/G3・DOM 検査）: 未回答者一覧・欠席・皆勤・実名が出ない
    const bodyText = (await page.locator('body').innerText()).toString()
    expect(bodyText).not.toContain('欠席')
    expect(bodyText).not.toContain('ABSENT')
    expect(bodyText).not.toContain('未回答')
    expect(bodyText).not.toContain('皆勤')
    expect(bodyText).not.toContain('@test.mannschaft.local')
  })

  test('VE-W2-3: ACTIVE 祭で実況セクションが表示され、実況として投稿できる（§5.4）', async ({ page }) => {
    await setupApiBridge(page)
    await loginViaApi(page, USER_EMAIL, USER_PASSWORD)
    const dialog = await openFestival(page)

    // 実況セクション（ACTIVE 中のみ表示・§5.4）
    await expect(dialog.getByRole('heading', { name: '実況', exact: true })).toBeVisible({ timeout: 15_000 })

    // 実況として投稿 → 一覧に「投稿を見る」リンクが出る（案B: VILLAGE 投稿→祭タグ付け）
    const liveBody = `実況E2E-${Date.now()}`
    await dialog.getByPlaceholder('実況として投稿する内容を書く').fill(liveBody)
    await dialog.getByRole('button', { name: '実況として投稿', exact: true }).click()
    await expect(dialog.getByText('投稿を見る').first()).toBeVisible({ timeout: 15_000 })
  })
})

// ===========================================================================
// W2-⑦ 村史（行事アーカイブ）タブ
// ===========================================================================
test.describe('F17.2 Wave2 ⑦ 村史', () => {
  test.setTimeout(120_000)

  test('VE-W2-4: 村史タブが（アーカイブ0件でも）エラーなく描画される（空状態・§7）', async ({ page }) => {
    await setupApiBridge(page)
    await loginViaApi(page, USER_EMAIL, USER_PASSWORD)
    await gotoTab(page, 'chronicles')

    // 村史パネルの見出しが出る
    await expect(page.getByRole('heading', { name: '村史', exact: true })).toBeVisible({ timeout: 20_000 })

    // seed 村には ENDED 祭の編纂済みアーカイブが無いため、空状態が正しく出る（0件でもエラー表示しない）。
    // 陽性（アーカイブ実データ）は ENDED 祭編纂が要る＝重いため本 spec では未実施（下記報告参照）。
    await expect(page.getByText('村史はまだありません')).toBeVisible({ timeout: 15_000 })

    // エラートースト（取得失敗）が出ていないこと（根治治療: 空=正常、失敗=別物）
    await expect(page.getByText('村史の取得に失敗しました')).toHaveCount(0)

    // 認可 DOM 検査（§10 G4）: 実名（メール）が出ない
    const bodyText = (await page.locator('body').innerText()).toString()
    expect(bodyText).not.toContain('@test.mannschaft.local')
  })
})
