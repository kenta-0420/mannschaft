/**
 * F03.18 予定アクティビティフィード — 実機 E2E（閲覧者＝一般メンバー視点・UI 駆動）。
 *
 * 【このファイルの立ち位置】
 *   予定の作成・更新・日程変更・削除が、**別のメンバーのダッシュボード**の
 *   「最近のアクティビティ」ウィジェット（WidgetRecentActivity.vue → ActivityItem.vue）に
 *   実際に描画されることを、実ブラウザ・実 API・実 DB で確かめる。モックは一切使わない。
 *
 * 【ファイル分割の理由】
 *   実機 E2E の鉄則「1ファイル＝1ログイン」に従う。本ファイルはブラウザで
 *   **e2e-user（fc-u-18 の MEMBER）だけ**がログインし、フィードの「見え方」を UI で検証する。
 *   予定を作る側（e2e-admin）は browser を使わず APIRequestContext で駆動する
 *   （別ユーザーのトークン系列であり、ブラウザ側セッションと干渉しない）。
 *   可視性の縮小・繰り返し予定・5分マージ・組織スコープ・ページ送りは UI に出口が無い
 *   （ウィジェットは最新20件を1ページ描画するだけでページ送り UI を持たない）ため、
 *   API 完結の schedule-activity-feed-visibility.spec.ts に分離した。
 *
 * 【前提】
 *   - BE: F03.18 ブランチのビルドが 8081 で稼働（API_BASE_URL で指定）
 *   - FE: 同ブランチの dev サーバー（BASE_URL で指定）
 *   - seed: e2e-user(MEMBER) / e2e-admin(ADMIN) がともに fc-u-18 に所属
 *
 * 実行例:
 *   cd frontend && BASE_URL=http://localhost:3007 API_BASE_URL=http://localhost:8081 \
 *     npx playwright test --config=playwright-f0318.config.ts tests/e2e/real/schedule-activity-feed.spec.ts
 */

import { test, expect, request as pwRequest, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const API_V1 = `${API}/api/v1`
const TEAM_SLUG = 'fc-u-18'

const VIEWER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const ACTOR = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }

test.describe.configure({ mode: 'serial' })

let context: BrowserContext
let page: Page
let actorApi: APIRequestContext
let actorToken: string

const stamp = Date.now()

function h(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
}

/** 予定を作成する（操作者＝e2e-admin）。戻り値は schedule id。 */
async function createSchedule(title: string, extra: Record<string, unknown> = {}): Promise<number> {
  const start = Date.now() + 7 * 24 * 60 * 60 * 1000
  const res = await actorApi.post(`${API_V1}/teams/${TEAM_SLUG}/schedules`, {
    headers: h(actorToken),
    data: {
      title,
      startAt: new Date(start).toISOString(),
      endAt: new Date(start + 60 * 60 * 1000).toISOString(),
      allDay: false,
      eventType: 'PRACTICE',
      visibility: 'MEMBERS_ONLY',
      minViewRole: 'ANYONE',
      attendanceRequired: false,
      ...extra,
    },
  })
  expect(res.status(), `予定作成が 201: ${title}`).toBe(201)
  return (await res.json() as { data: { id: number } }).data.id
}

/** 予定を更新する（操作者＝e2e-admin）。 */
async function updateSchedule(id: number, body: Record<string, unknown>): Promise<void> {
  const res = await actorApi.patch(`${API_V1}/teams/${TEAM_SLUG}/schedules/${id}?updateScope=THIS_ONLY`, {
    headers: h(actorToken),
    data: body,
  })
  expect(res.status(), `予定更新が 200: id=${id}`).toBe(200)
}

/** 予定を削除する（操作者＝e2e-admin）。 */
async function deleteSchedule(id: number): Promise<void> {
  const res = await actorApi.delete(`${API_V1}/teams/${TEAM_SLUG}/schedules/${id}?updateScope=THIS_ONLY`, {
    headers: h(actorToken),
  })
  expect(res.status(), `予定削除が 204: id=${id}`).toBe(204)
}

/**
 * ダッシュボードを開き直しながら、指定テキストがウィジェットに現れるまで待つ。
 *
 * フィード行の書き込みは AFTER_COMMIT の @Async であり、API が 2xx を返した時点では
 * まだ行が無い。UI 側にも自動更新は無いため、リロードで取り直す。
 */
async function reloadUntilVisible(text: string | RegExp, timeoutMs = 45_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    await page.goto('/dashboard')
    await waitForHydration(page)
    // 「最近のアクティビティ」は personal スコープのパネルにしか無い（useDashboardWidgets の
    // scope: ['personal']）。ダッシュボードは前回選択したスコープ（チーム等）を復元するため、
    // 毎回「個人」タブへ切り替えてからウィジェットを探す。
    await page.getByRole('button', { name: '個人' }).first().click()
    await waitForSpinnerGone(page)
    // ウィジェット自体の描画（並び順確定 → onMounted の取得）を待つ。
    await page.getByRole('heading', { name: '最近のアクティビティ' })
      .waitFor({ state: 'visible', timeout: 30_000 })
    const locator = page.getByText(text).first()
    if (await locator.waitFor({ state: 'visible', timeout: 8_000 }).then(() => true, () => false)) {
      return
    }
    if (Date.now() > deadline) {
      throw new Error(`ダッシュボードに現れませんでした: ${text}`)
    }
    await page.waitForTimeout(2_000)
  }
}

test.beforeAll(async ({ browser }) => {
  // 操作者（e2e-admin）は API 専用コンテキスト。ブラウザには一切ログインさせない。
  actorApi = await pwRequest.newContext()
  const login = await actorApi.post(`${API_V1}/auth/login`, { data: ACTOR })
  expect(login.status(), 'e2e-admin の API ログインが 200').toBe(200)
  actorToken = (await login.json() as { data: { accessToken: string } }).data.accessToken

  // 閲覧者（e2e-user）はこのファイル専用に 1 回だけログインする（1ファイル=1ログイン）。
  context = await browser.newContext()
  page = await context.newPage()
  await loginViaApi(page, VIEWER, { apiBaseUrl: API })
})

test.afterAll(async () => {
  await context?.close()
  await actorApi?.dispose()
})

test('FEED-UI-001: 他メンバーが作った予定が「予定を作成しました」としてフィードに出る', async () => {
  const title = `F0318UI-作成-${stamp}`
  const id = await createSchedule(title)

  await reloadUntilVisible(title)
  await expect(page.getByText('が予定を作成しました').first()).toBeVisible()

  // 作成者自身のフィードには出ない（自分の行動は除外される）。
  const actorFeed = await actorApi.get(`${API_V1}/dashboard/activity?limit=50`, { headers: h(actorToken) })
  expect(actorFeed.status()).toBe(200)
  const actorItems = (await actorFeed.json() as { data: { items: Array<{ targetId: number }> } }).data.items
  expect(actorItems.some(a => a.targetId === id), '作成者自身のフィードには出ない').toBe(false)
})

test('FEED-UI-002: タイトル変更が「更新」行になり、変更前→変更後が UI に出る', async () => {
  const before = `F0318UI-更新前-${stamp}`
  const after = `F0318UI-更新後-${stamp}`
  const id = await createSchedule(before)
  await reloadUntilVisible(before)

  await updateSchedule(id, { title: after })

  // 3行目の差分表示（detail.fields → ActivityItem の「タイトル: 変更前 → 変更後」）
  await reloadUntilVisible(new RegExp(`${before}\\s*→\\s*${after}`))
  await expect(page.getByText('が予定を更新しました').first()).toBeVisible()
  await expect(page.getByText(new RegExp(`タイトル: ${before} → ${after}`)).first()).toBeVisible()
})

test('FEED-UI-003: 開始日時の変更は「日程を変更しました」行になる', async () => {
  const title = `F0318UI-日程-${stamp}`
  const id = await createSchedule(title)
  await reloadUntilVisible(title)

  const newStart = Date.now() + 14 * 24 * 60 * 60 * 1000
  await updateSchedule(id, {
    startAt: new Date(newStart).toISOString(),
    endAt: new Date(newStart + 60 * 60 * 1000).toISOString(),
  })

  await reloadUntilVisible('が予定の日程を変更しました')
  await expect(page.getByText('開始日時:').first()).toBeVisible()
})

test('FEED-UI-004: 削除は「キャンセルしました」行として所属メンバーに見える', async () => {
  const title = `F0318UI-削除-${stamp}`
  const id = await createSchedule(title)
  await reloadUntilVisible(title)

  await deleteSchedule(id)

  await reloadUntilVisible('が予定をキャンセルしました')
  await expect(page.getByText(title).first()).toBeVisible()
})

test('FEED-UI-005: 説明文だけを変えても説明文の中身はフィードに出ない', async () => {
  const title = `F0318UI-説明-${stamp}`
  const secret = `機微情報-${stamp}`
  const id = await createSchedule(title, { description: '初期の説明' })
  await reloadUntilVisible(title)

  await updateSchedule(id, { description: secret })

  await reloadUntilVisible('詳細が更新されました')
  // 説明文の中身がページ本文のどこにも現れない（漏洩防止）。
  await expect(page.getByText(secret)).toHaveCount(0)
})

test('FEED-UI-006: SCHEDULE の行をタップすると対象予定へ遷移する', async () => {
  const title = `F0318UI-遷移-${stamp}`
  const id = await createSchedule(title)
  await reloadUntilVisible(title)

  const row = page.getByText(title).first().locator('xpath=ancestor::a[1]')
  await expect(row, 'SCHEDULE 行はリンク（a 要素）として描画される').toHaveAttribute(
    'href', new RegExp(`/calendar\\?scheduleId=${id}`),
  )
  await row.click()
  await expect(page).toHaveURL(new RegExp(`/calendar\\?scheduleId=${id}`))
})
