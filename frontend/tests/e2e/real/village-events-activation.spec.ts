/**
 * F17.2 村行事の活性化（Village Events Activation）— 実機 E2E（Wave1 + Wave3）。
 *
 * 設計書: docs/features/F17.2_village_events_activation.md（§4 寄合後半戦 / §6 年輪 /
 *         §8 相性表示 / §9 所属村一覧 / §10 差別化ガードレール）
 *
 * このテストは API モックを使わない実機テストである。
 * 実バックエンド（API_BASE / 既定 http://127.0.0.1:8080）と
 * 実フロントエンド（BASE_URL / 既定 http://127.0.0.1:3001）が起動済みの状態で、
 * page.goto → click / fill → expect(visible) の UI 操作で検証する。
 *
 * ## 認証・セッション設計（重要）
 *  - storageState を空にし、各 test 内で `loginViaApi` により毎回フレッシュにログインする
 *    （token ローテーションによる後続 test の失効を避ける single-session 設計）。
 *  - FE(3001) → BE(8080) はブラウザ直呼びだと CORS 許可オリジンに 3001 が無く全滅するため、
 *    `setupApiBridge`（page.route で横取り → Node fetch 中継 + ACAO 差し替え）で通す。
 *
 * ## 村・ユーザーの前提（seed-e2e-data.js）
 *  - 村作成 API（POST /villages）は運営権限必須（VILLAGE_037）＋レート上限（VILLAGE_010・1日3件）。
 *    そのため「使い捨て村を新規作成」はせず、**e2e-user が HEADMAN を務める seed 村**
 *    （`6e87b493-…`・COMMUNITY/FREE/PUBLIC）を土台にする（村作成が ops ゲートなのは
 *    village-join-request.spec.ts と同じ前提）。作成した寄合/歳時記は afterAll で片付ける。
 *  - 相性カード（§8）の「非メンバー閲覧」は、search で PUBLIC かつ非メンバーの村を
 *    実行時に発見して用いる（既存村の非メンバー状態＝タスクの許容パス）。
 *  - 所属村 Dialog（§9）の「別の村人」検証には e2e-dummy-1 を seed 村へ一時参加させ、
 *    afterAll で本人トークンにより自己退村させる。
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

test.use({ storageState: { cookies: [], origins: [] } })

const BASE_URL = process.env.BASE_URL ?? 'http://127.0.0.1:3001'
const API_BASE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8080'

// e2e-user は seed 村 6e87b493 の HEADMAN（村長）。
const USER_EMAIL = 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = 'TestPass2026!'
// 所属村 Dialog の「別の村人」役（seed 村へ一時参加させる）。
const DUMMY_EMAIL = 'e2e-dummy-1@test.mannschaft.local'
const DUMMY_PASSWORD = 'TestPass2026!'

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

// 詳細用に候補日付き寄合を作り、CONFIRMED まで進めずに返す（PLANNING）。
async function createPlanningMeetup(ctx: APIRequestContext, token: string, title: string): Promise<{ id: string, candidateDateIds: string[] }> {
  const res = await apiFetch(ctx, token, 'POST', `/api/v1/villages/${VILLAGE_ID}/meetups`, {
    title,
    candidateDates: [{ date: '2026-09-01' }, { date: '2026-09-02' }],
  })
  expect(res.status(), 'createMeetup').toBe(201)
  const created = (await res.json()).data
  const g = await apiFetch(ctx, token, 'GET', `/api/v1/villages/${VILLAGE_ID}/meetups/${created.id}`)
  const full = (await g.json()).data
  return { id: created.id, candidateDateIds: (full.candidateDates ?? []).map((c: { id: string }) => c.id) }
}

// ---------------------------------------------------------------------------
// 共通: 寄合詳細ダイアログを開く（一覧の該当カードをクリック）。
// ---------------------------------------------------------------------------
async function openMeetupRow(page: Page, title: string) {
  const row = page.locator('.village-meetup__row', { hasText: title }).first()
  await expect(row).toBeVisible({ timeout: 20_000 })
  await row.click()
  const dialog = page.locator('[role="dialog"]').filter({ hasText: title }).first()
  await expect(dialog).toBeVisible({ timeout: 15_000 })
  return dialog
}

async function gotoTab(page: Page, tab: string) {
  await page.goto(`${BASE_URL}/villages/${VILLAGE_ID}/${tab}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
  await expect(page).not.toHaveURL(/\/login/)
}

// ===========================================================================
// W1-② 寄合の後半戦（一気通貫）
// ===========================================================================
test.describe('F17.2 Wave1 ② 寄合後半戦', () => {
  test.setTimeout(180_000)

  const createdMeetupIds: string[] = []
  let sharedCtx: APIRequestContext
  let userToken = ''

  test.beforeAll(async ({ playwright }) => {
    sharedCtx = await playwright.request.newContext()
    userToken = (await loginToken(sharedCtx, USER_EMAIL, USER_PASSWORD)).token
  })

  test.afterAll(async () => {
    // PLANNING の寄合は cancel 可能（CONFIRMED は 409 で不可のため残置）。
    for (const id of createdMeetupIds) {
      await apiFetch(sharedCtx, userToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/meetups/${id}/cancel`).catch(() => {})
    }
    await sharedCtx.dispose()
  })

  test('VE-W1-2: 出欠/コメント/決まったこと/宿題の一気通貫＋ガードレール', async ({ page }) => {
    // --- 前準備: PLANNING 寄合を1件作る（UI で確定 → CONFIRMED 化する） ---
    const title = `寄合E2E-${Date.now()}`
    const meetup = await createPlanningMeetup(sharedCtx, userToken, title)
    createdMeetupIds.push(meetup.id)

    await setupApiBridge(page)
    await loginViaApi(page, USER_EMAIL, USER_PASSWORD)
    await gotoTab(page, 'meetups')

    // 1) PLANNING では後半戦セクションが出ない（§4.5）
    let dialog = await openMeetupRow(page, title)
    await expect(dialog.getByText('投票受付中', { exact: true })).toBeVisible()
    await expect(dialog.getByRole('heading', { name: '出欠', exact: true })).toHaveCount(0)

    // 2) 候補日を確定 → CONFIRMED 化（UI: 「この日に決定」）
    await dialog.getByRole('button', { name: 'この日に決定' }).first().click()
    await expect(page.getByText('開催を確定しました').or(page.locator('.p-toast-message'))).toBeVisible({ timeout: 10_000 }).catch(() => {})
    // ダイアログを閉じ、CONFIRMED フィルタで開き直して後半戦データをロードさせる
    await page.keyboard.press('Escape').catch(() => {})
    await page.getByRole('button', { name: '開催確定', exact: true }).click()
    dialog = await openMeetupRow(page, title)
    await expect(dialog.getByText('開催確定', { exact: true })).toBeVisible()

    // 後半戦セクションが出現
    await expect(dialog.getByRole('heading', { name: '出欠', exact: true })).toBeVisible()

    // 3) 出欠: 「行ける」→ 反映 → 「たぶん行ける」に変更（upsert・1件のまま）
    const attendanceChips = dialog.locator('div.rounded-full.border')
    await dialog.getByRole('button', { name: '行ける', exact: true }).click()
    await expect(attendanceChips).toHaveCount(1, { timeout: 10_000 })
    await expect(attendanceChips.first().locator('.p-badge', { hasText: '行ける' })).toBeVisible()

    await dialog.getByRole('button', { name: 'たぶん行ける', exact: true }).click()
    await expect(attendanceChips).toHaveCount(1) // upsert: 件数は増えない
    await expect(attendanceChips.first()).toContainText('たぶん行ける')
    // 直前の「行ける」バッジは残らない（同一レコードが更新された）
    await expect(attendanceChips.first().locator('.p-badge')).toHaveText('たぶん行ける')

    // 4) コメント: 投稿 → 昇順表示 → 自分のコメント削除
    const commentBody = `コメントE2E-${Date.now()}`
    await dialog.getByPlaceholder('コメントを書く').fill(commentBody)
    await dialog.getByRole('button', { name: '投稿する' }).click()
    await expect(dialog.getByText(commentBody)).toBeVisible({ timeout: 10_000 })
    // 削除（trash アイコン・confirm alertdialog を承認）
    await dialog.getByRole('button', { name: '削除' }).first().click()
    await page.locator('[role="alertdialog"]').getByRole('button', { name: 'はい' }).click()
    await expect(dialog.getByText(commentBody)).toHaveCount(0, { timeout: 10_000 })

    // 5) 決まったこと: 編集 → 保存 → リロードで永続化確認
    const decisionsNote = `決定事項E2E-${Date.now()}`
    await dialog.getByRole('button', { name: '編集', exact: true }).click()
    await dialog.getByPlaceholder('決まったこと・持ち物などを書いておきましょう').fill(decisionsNote)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(dialog.getByText(decisionsNote)).toBeVisible({ timeout: 10_000 })

    // 6) 宿題TODO: 作成 → 手挙げ → 完了チェック
    const todoTitle = `宿題E2E-${Date.now()}`
    await dialog.getByPlaceholder('何をやる？（例: お菓子を買う）').fill(todoTitle)
    await dialog.getByRole('button', { name: '追加', exact: true }).click()
    const todoRow = dialog.locator('div.border', { hasText: todoTitle }).first()
    await expect(todoRow).toBeVisible({ timeout: 10_000 })
    // 手を挙げる（claim）
    await todoRow.getByRole('button', { name: '手を挙げる' }).click()
    // 完了チェックボックス（本人＝手挙げ者に表示・§4.3）
    await todoRow.locator('.p-checkbox').click()
    await expect(todoRow.locator('.line-through')).toBeVisible({ timeout: 10_000 })

    // 7) 永続化: リロード → 再オープンで「決まったこと」が残る
    await gotoTab(page, 'meetups')
    await page.getByRole('button', { name: '開催確定', exact: true }).click()
    dialog = await openMeetupRow(page, title)
    await expect(dialog.getByText(decisionsNote)).toBeVisible({ timeout: 15_000 })

    // 8) ガードレール（§10・DOM 検査）: 未回答者一覧・欠席率・皆勤・実名(メール)が出ない
    const bodyText = (await page.locator('body').innerText()).toString()
    expect(bodyText).not.toContain('未回答')
    expect(bodyText).not.toContain('欠席率')
    expect(bodyText).not.toContain('皆勤')
    expect(bodyText).not.toContain('@test.mannschaft.local')
  })
})

// ===========================================================================
// W1-④ 歳時記×村史の年輪（去年の様子）
// ===========================================================================
test.describe('F17.2 Wave1 ④ 年輪', () => {
  test.setTimeout(150_000)

  const createdEventIds: string[] = []
  let sharedCtx: APIRequestContext
  let userToken = ''

  test.beforeAll(async ({ playwright }) => {
    sharedCtx = await playwright.request.newContext()
    userToken = (await loginToken(sharedCtx, USER_EMAIL, USER_PASSWORD)).token
  })

  test.afterAll(async () => {
    for (const id of createdEventIds) {
      await apiFetch(sharedCtx, userToken, 'DELETE', `/api/v1/villages/${VILLAGE_ID}/calendar-events/${id}`).catch(() => {})
    }
    await sharedCtx.dispose()
  })

  test('VE-W1-4: 歳時記作成→年輪を複数年/同一年に積む→year降順表示', async ({ page }) => {
    await setupApiBridge(page)
    await loginViaApi(page, USER_EMAIL, USER_PASSWORD)
    await gotoTab(page, 'calendar')

    // 1) 歳時記イベントを UI 作成（当月・1日）
    const evTitle = `歳時記E2E-${Date.now()}`
    await page.getByRole('button', { name: '行事を追加' }).click()
    const createDialog = page.locator('[role="dialog"]').filter({ hasText: '行事を追加' }).first()
    await expect(createDialog).toBeVisible()
    // 行事名（先頭の text input・label は for 紐付けが無いため位置で特定）
    await createDialog.locator('input[type="text"], input:not([type])').first().fill(evTitle)
    await createDialog.getByRole('button', { name: '保存', exact: true }).click()
    // 作成した行事の row が当月一覧に出る
    const evRow = page.locator('.village-calendar__row', { hasText: evTitle }).first()
    await expect(evRow).toBeVisible({ timeout: 15_000 })

    // 作成された calendar-event を cleanup 対象に登録（検索で id を引く）
    {
      const now = new Date()
      const list = await apiFetch(sharedCtx, userToken, 'GET', `/api/v1/villages/${VILLAGE_ID}/calendar-events?year=${now.getFullYear()}&month=${now.getMonth() + 1}`)
      const items = (await list.json()).data?.items ?? []
      const mine = (Array.isArray(items) ? items : []).find((e: { title: string, id: string }) => e.title === evTitle)
      if (mine) createdEventIds.push(mine.id)
    }

    // 2) 詳細ダイアログ →「去年の様子」に年輪を積む
    await evRow.click()
    const detail = page.locator('[role="dialog"]').filter({ hasText: evTitle }).first()
    await expect(detail).toBeVisible()
    await expect(detail.getByRole('heading', { name: '去年の様子' })).toBeVisible()

    async function addLog(year: number, note: string) {
      await detail.getByRole('button', { name: '様子を記録する' }).click()
      // year は PrimeVue InputNumber（既定は当年）。選択して上書きする。
      const yearInput = detail.locator('.p-inputnumber-input')
      await yearInput.click()
      await yearInput.press('Control+A')
      await yearInput.pressSequentially(String(year))
      await yearInput.press('Tab')
      await detail.getByPlaceholder('そのときの様子をひとことで').fill(note)
      await detail.getByRole('button', { name: '記録する' }).click()
      await expect(detail.getByText(note)).toBeVisible({ timeout: 10_000 })
    }

    // 2025 を2件（同一年に複数可・§6.3）、2024 を1件
    await addLog(2025, '去年は快晴だった')
    await addLog(2025, '去年は出店が多かった')
    await addLog(2024, '一昨年は雨だった')

    // 3) 検証: 3件とも表示・year降順（2025 が 2024 より上）
    await expect(detail.getByText('2025年の様子')).toHaveCount(2)
    await expect(detail.getByText('2024年の様子')).toHaveCount(1)
    const logYears = await detail.locator('span.font-medium', { hasText: '年の様子' }).allInnerTexts()
    const years = logYears.map(s => Number(s.replace(/[^0-9]/g, '')))
    const sorted = [...years].sort((a, b) => b - a)
    expect(years, `年輪は year 降順で並ぶ: ${JSON.stringify(years)}`).toEqual(sorted)
    expect(years[0]).toBe(2025)
  })
})

// ===========================================================================
// W3-⑤ 加入前相性表示カード
// ===========================================================================
test.describe('F17.2 Wave3 ⑤ 相性カード', () => {
  test.setTimeout(120_000)

  test('VE-W3-5: 非メンバーPUBLIC村で相性カード＋草分けアピール、メンバー村では非表示', async ({ page, playwright }) => {
    await setupApiBridge(page)
    await loginViaApi(page, USER_EMAIL, USER_PASSWORD)

    // 実行時に「PUBLIC かつ自分が非メンバー」の村を発見する（既存村の非メンバー状態）。
    const ctx = await playwright.request.newContext()
    const { token } = await loginToken(ctx, USER_EMAIL, USER_PASSWORD)
    const search = await apiFetch(ctx, token, 'GET', '/api/v1/villages/search?page=0&size=30')
    const content = (await search.json()).content ?? []
    let targetId: string | null = null
    for (const v of content) {
      const g = await apiFetch(ctx, token, 'GET', `/api/v1/villages/${v.id}`)
      const gv = (await g.json()).data
      if (gv?.visibility === 'PUBLIC' && gv.isMember === false) {
        const af = await apiFetch(ctx, token, 'GET', `/api/v1/villages/${v.id}/affinity/me`)
        if (af.status() === 200) { targetId = v.id; break }
      }
    }
    await ctx.dispose()
    expect(targetId, 'PUBLIC かつ非メンバーの村が見つからない（相性検証の前提未達）').toBeTruthy()

    // 1) 非メンバーとして PUBLIC 村の掲示板を開く → 相性カード表示
    await page.goto(`${BASE_URL}/villages/${targetId}/bulletin`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    const card = page.locator('[data-testid="village-affinity-card"]')
    await expect(card).toBeVisible({ timeout: 20_000 })
    await expect(card.getByText('相性のヒント')).toBeVisible()
    // 小規模村（メンバー10人以下）→ 草分けアピール文言（§8.8）
    await expect(page.locator('[data-testid="village-affinity-pioneer-appeal"]')).toBeVisible()
    await expect(page.getByText('村の草分けになれます！')).toBeVisible()

    // 2) 自分がメンバーの村（seed 村）では相性カードが出ない（AC-24c）
    await page.goto(`${BASE_URL}/villages/${VILLAGE_ID}/bulletin`, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})
    await expect(page.locator('[data-testid="village-affinity-card"]')).toHaveCount(0)
  })
})

// ===========================================================================
// W3-⑥ 村人ミニプロフィール（所属村一覧）Dialog
// ===========================================================================
test.describe('F17.2 Wave3 ⑥ 所属村Dialog', () => {
  test.setTimeout(150_000)

  let sharedCtx: APIRequestContext
  let dummyToken = ''
  let dummyUserId = 0
  let dummyMembershipId: string | null = null

  test.beforeAll(async ({ playwright }) => {
    sharedCtx = await playwright.request.newContext()
    const d = await loginToken(sharedCtx, DUMMY_EMAIL, DUMMY_PASSWORD)
    dummyToken = d.token
    dummyUserId = d.userId
    // dummy を seed 村へ一時参加させる（別の村人役）。
    const join = await apiFetch(sharedCtx, dummyToken, 'POST', `/api/v1/villages/${VILLAGE_ID}/memberships`, { subjectType: 'USER', subjectId: dummyUserId })
    if (join.status() === 201) dummyMembershipId = (await join.json()).data.id
  })

  test.afterAll(async () => {
    // dummy 本人トークンで自己退村（HEADMAN は他者 membership を DELETE できない＝BAN 扱いのため）。
    if (dummyMembershipId) {
      await apiFetch(sharedCtx, dummyToken, 'DELETE', `/api/v1/villages/${VILLAGE_ID}/memberships/${dummyMembershipId}`).catch(() => {})
    }
    await sharedCtx.dispose()
  })

  test('VE-W3-6: 村人名タップでDialog（実名なし・中立表示）＋自分は公開トグル', async ({ page }) => {
    await setupApiBridge(page)
    const myId = await loginViaApi(page, USER_EMAIL, USER_PASSWORD)
    await gotoTab(page, 'members')

    // メンバー表が描画される
    await expect(page.getByRole('heading', { name: '村人一覧' })).toBeVisible({ timeout: 20_000 })

    // 1) 別の村人（dummy・表示名 "#<id>"）の名前をタップ → プロフィール Dialog
    await page.getByRole('button', { name: `#${dummyUserId}`, exact: true }).click()
    const dialog = page.locator('[role="dialog"]').filter({ hasText: '村人プロフィール' }).first()
    await expect(dialog).toBeVisible({ timeout: 10_000 })
    await expect(dialog.getByRole('heading', { name: '所属村一覧' })).toBeVisible()
    // 他人には公開トグルを出さない
    await expect(dialog.locator('[data-testid="member-profile-public-toggle"]')).toHaveCount(0)
    // 実名(メール)/ニックネームを晒さない（§9.3・§10 G4）
    await expect(dialog).not.toContainText('@test.mannschaft.local')
    // 共通村はあるが公開村0件 → 一律403 → 中立表示（同居関係を漏らさない・§9.4）
    await expect(dialog.getByText('表示できる所属村はありません')).toBeVisible({ timeout: 10_000 })
    await page.keyboard.press('Escape').catch(() => {})
    await expect(dialog).toBeHidden({ timeout: 10_000 }).catch(() => {})

    // 2) 自分自身を開く → 公開トグルが表示され、切替が動く
    await page.getByRole('button', { name: `#${myId}`, exact: true }).click()
    const selfDialog = page.locator('[role="dialog"]').filter({ hasText: '村人プロフィール' }).first()
    await expect(selfDialog).toBeVisible({ timeout: 10_000 })
    const toggle = selfDialog.locator('[data-testid="member-profile-public-toggle"]')
    await expect(toggle).toBeVisible()
    // PrimeVue ToggleSwitch は ON 状態を root の class `p-toggleswitch-checked` で表す。
    const isOn = async () => ((await toggle.getAttribute('class')) ?? '').includes('p-toggleswitch-checked')
    const before = await isOn()
    // クリックで反転（onToggle が PATCH profile-visibility を叩き、成功で状態が変わる）
    await toggle.click()
    await expect.poll(isOn, { timeout: 10_000 }).toBe(!before)
    // 元に戻す（seed 村の状態を汚さない）
    await toggle.click()
    await expect.poll(isOn, { timeout: 10_000 }).toBe(before)
  })
})
