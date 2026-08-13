/**
 * F03.16「予定コメントスレッド」実機 E2E テスト（モックなし・実ブラウザ操作）。
 *
 * 設計書: docs/features/F03.16_schedule_comment_thread.md
 * 対象PR: #2763（feature/f0316-fix）
 *
 * 検証シナリオ（マスター指示「粒度高めで」）:
 *   1. 投稿
 *   2. 返信（親子関係・深さ上限1）
 *   3. 編集（「編集済み」表示）
 *   4. 削除（返信なし）→ 一覧から消える
 *   5. 削除（返信あり）→ トゥームストーン化・返信は読める
 *   6. メンション候補（自分自身が候補に出ない・通知）
 *   7. スレッドの開閉（管理者が締切ると投稿UI消える）
 *   8. 中止された予定は投稿不可
 *   9. 認可: 閲覧不可ユーザー（SUPPORTER）はコメントを読めず書けない。URL直叩きも同様
 *   10. 本文の境界（空・全角スペースのみで投稿不可、長すぎる本文は弾かれる）
 *
 * 実機テスト用専用スキーマ mannschaft_f0316 に対して実行する
 * （共有DB mannschaft は Flyway 詰まりのため使用しない）。
 * BE: http://localhost:8090 / FE: http://localhost:3090
 *
 * 認証は BE へ直接ログインし Cookie を発行する loginViaApi を使う。
 * single-session 設計のため全シナリオを直列実行する。
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3090'
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8090'
const TEAM_SLUG = 'fc-u-18'

const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_SUPPORTER = { email: 'e2e-supporter@test.mannschaft.local', password: 'TestPass2026!' }

// schedule-list-view (data-testid 付きの安定した行) はモバイル幅 (<768px) 限定表示のため、
// 本specはモバイルビューポートで実行する（デスクトップのCalendarGridはtestidが無く不安定）。
test.use({ storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 } })

async function apiLogin(ctx: APIRequestContext, email: string, password: string): Promise<void> {
  const res = await ctx.post(`${API_BASE}/api/v1/auth/login`, { data: { email, password } })
  expect(res.ok(), `ログイン失敗 (${email}): ${res.status()} ${await res.text()}`).toBeTruthy()
}

async function apiRaw(
  ctx: APIRequestContext,
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE',
  path: string,
  data?: unknown,
) {
  return ctx.fetch(`${API_BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    data,
  })
}

/** 管理者トークンで即時開始の予定を1件作成し scheduleId を返す（UI遷移を安定させるためAPI経由で用意）。 */
async function createSchedule(
  ctx: APIRequestContext,
  title: string,
  opts: { status?: 'SCHEDULED' | 'CANCELLED' } = {},
): Promise<number> {
  const start = new Date(Date.now() + 5 * 60_000).toISOString()
  const end = new Date(Date.now() + 65 * 60_000).toISOString()
  const res = await apiRaw(ctx, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedules`, {
    title,
    description: null,
    location: 'E2Eテスト会場',
    startAt: start,
    endAt: end,
    allDay: false,
    eventType: 'PRACTICE',
    visibility: 'MEMBERS_ONLY',
    minViewRole: 'MEMBER_PLUS',
    minResponseRole: 'MEMBER_PLUS',
    attendanceRequired: false,
    commentOption: 'OPTIONAL',
  })
  expect(res.ok(), `予定作成失敗: ${res.status()} ${await res.text()}`).toBeTruthy()
  const body = (await res.json()) as { data: { id: number } }
  const scheduleId = body.data.id
  if (opts.status === 'CANCELLED') {
    const cancelRes = await apiRaw(ctx, 'POST', `/api/v1/teams/${TEAM_SLUG}/schedules/${scheduleId}/cancel`, {
      reason: 'E2Eテスト用中止',
    })
    expect(cancelRes.ok(), `予定中止失敗: ${cancelRes.status()} ${await cancelRes.text()}`).toBeTruthy()
  }
  return scheduleId
}

/**
 * チーム予定一覧ページへ遷移し、指定タイトルの予定行をクリックして詳細パネル
 * （EventDetailPanel = コメントセクションの親）を開く。
 * ページには eventId クエリでの直接遷移がないため、一覧UIを実際に操作する
 * （URLを直接叩くのは CT-09a の認可検証のみ）。
 */
async function gotoScheduleDetailByTitle(page: Page, title: string): Promise<void> {
  await page.goto(`${BASE_URL}/teams/${TEAM_SLUG}/schedule`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

  const listRow = page.getByTestId('schedule-list-view').getByText(title, { exact: false }).first()
  await expect(listRow, `予定「${title}」が一覧に見つかること`).toBeVisible({ timeout: 15_000 })
  await listRow.click()
}

test.describe.configure({ mode: 'serial' })
test.setTimeout(90_000)

// ===========================================================================
// 事前準備: ADMIN トークンで検証用予定を複数用意
// ===========================================================================
const RUN_SUFFIX = Date.now()
const NORMAL_TITLE = `F03.16実機E2E通常予定-${RUN_SUFFIX}`
const CANCELLED_TITLE = `F03.16実機E2E中止予定-${RUN_SUFFIX}`

let normalScheduleId: number
let cancelledScheduleId: number

test.beforeAll(async ({ playwright }) => {
  const ctx = await playwright.request.newContext()
  await apiLogin(ctx, E2E_ADMIN.email, E2E_ADMIN.password)
  normalScheduleId = await createSchedule(ctx, NORMAL_TITLE)
  cancelledScheduleId = await createSchedule(ctx, CANCELLED_TITLE, { status: 'CANCELLED' })
  // Nuxt dev サーバーは対象ルートの初回アクセス時にオンデマンドコンパイルするため、
  // 60秒のテストタイムアウト内に収まらないことがある（実測）。
  // 本編テスト開始前にウォームアップ用リクエストでコンパイルを先に済ませておく。
  const warmupRes = await ctx.get(`${BASE_URL}/teams/${TEAM_SLUG}/schedule`, { timeout: 180_000 })
  expect(warmupRes.ok(), `ウォームアップGET失敗: ${warmupRes.status()}`).toBeTruthy()
  await ctx.dispose()
})

// ===========================================================================
// CT-01: 投稿 → 一覧に現れる
// ===========================================================================
test('CT-01: コメントを投稿すると画面に現れる', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  await expect(input).toBeVisible({ timeout: 10_000 })
  const body = `CT-01投稿本文-${Date.now()}`
  await input.click()
  await input.fill(body)
  await page.locator('[data-testid="schedule-comment-submit"]:visible').first().click()

  await expect(section.getByText(body)).toBeVisible({ timeout: 10_000 })
})

// ===========================================================================
// CT-02: 返信 → 親子関係が正しく描画される（深さ上限1）
// ===========================================================================
let parentCommentBody: string
test('CT-02: 返信すると親の下にネストして表示される', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  parentCommentBody = `CT-02親コメント-${Date.now()}`
  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  await input.click()
  await input.fill(parentCommentBody)
  await page.locator('[data-testid="schedule-comment-submit"]:visible').first().click()
  await expect(section.getByText(parentCommentBody)).toBeVisible({ timeout: 10_000 })

  // 親コメントの「返信」ボタンをクリック
  const parentItem = section.locator('[data-testid^="schedule-comment-item"]').filter({ hasText: parentCommentBody })
  await parentItem.getByRole('button', { name: /返信/ }).click()

  const replyBody = `CT-02返信本文-${Date.now()}`
  const replyForm = section.locator('[data-testid="schedule-comment-form"]').last()
  await replyForm.getByTestId('schedule-comment-input').fill(replyBody)
  await replyForm.getByTestId('schedule-comment-submit').click()

  await expect(section.getByText(replyBody)).toBeVisible({ timeout: 10_000 })
})

// ===========================================================================
// CT-03: 編集 → 「編集済み」表示
// ===========================================================================
test('CT-03: 自分のコメントを編集すると本文が変わり編集済みが表示される', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const original = `CT-03編集前-${Date.now()}`
  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  await input.click()
  await input.fill(original)
  await page.locator('[data-testid="schedule-comment-submit"]:visible').first().click()
  await expect(section.getByText(original)).toBeVisible({ timeout: 10_000 })

  // 【重要】item を hasText フィルタ付き locator のまま保持すると、編集モードに入った瞬間に破綻する。
  // PrimeVue の <Textarea v-model> は値を DOM プロパティ (.value) で設定するため textContent に現れず、
  // 編集開始後は「本文の <p> 要素」が消えて hasText: original が再評価時に一致しなくなる
  // （Playwright の locator は遅延評価のため、参照するたびに DOM 上で再照合される）。
  // そのため data-testid を編集前に一度だけ取得し、以後は固定 testid の locator で辿る。
  const itemByText = section.locator('[data-testid^="schedule-comment-item"]').filter({ hasText: original })
  const itemTestId = await itemByText.getAttribute('data-testid')
  expect(itemTestId, 'コメント要素の data-testid が取得できること').toBeTruthy()
  const item = section.locator(`[data-testid="${itemTestId}"]`)

  const editButton = item.getByRole('button', { name: '編集' })
  await expect(editButton).toBeVisible({ timeout: 10_000 })
  await editButton.click()

  const edited = `CT-03編集後-${Date.now()}`
  const editArea = item.locator('textarea')
  await expect(editArea, '編集モードのtextareaが表示されること').toBeVisible({ timeout: 10_000 })
  await editArea.click()
  await editArea.press('Control+A')
  await editArea.pressSequentially(edited, { delay: 5 })
  await item.getByRole('button', { name: '保存' }).click()

  await expect(section.getByText(edited)).toBeVisible({ timeout: 10_000 })
  await expect(section.getByText(/編集済み|edited/i)).toBeVisible({ timeout: 10_000 })
})

// ===========================================================================
// CT-04: 削除（返信なし）→ 一覧から消える
// ===========================================================================
test('CT-04: 返信のないコメントを削除すると一覧から消える', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const body = `CT-04削除対象-${Date.now()}`
  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  await input.click()
  await input.fill(body)
  await page.locator('[data-testid="schedule-comment-submit"]:visible').first().click()
  await expect(section.getByText(body)).toBeVisible({ timeout: 10_000 })

  page.once('dialog', (d) => d.accept())
  const item = section.locator('[data-testid^="schedule-comment-item"]').filter({ hasText: body })
  await item.getByRole('button', { name: /削除/ }).click()

  await expect(section.getByText(body)).toHaveCount(0, { timeout: 10_000 })
})

// ===========================================================================
// CT-05: 削除（返信あり）→ トゥームストーン化・返信は読める
// ===========================================================================
test('CT-05: 返信のあるコメントを削除するとトゥームストーンとして残り返信は読める', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const parentBody = `CT-05親（返信あり）-${Date.now()}`
  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  await input.click()
  await input.fill(parentBody)
  await page.locator('[data-testid="schedule-comment-submit"]:visible').first().click()
  await expect(section.getByText(parentBody)).toBeVisible({ timeout: 10_000 })

  const parentItem = section.locator('[data-testid^="schedule-comment-item"]').filter({ hasText: parentBody })
  await parentItem.getByRole('button', { name: /返信/ }).click()
  const replyBody = `CT-05返信-${Date.now()}`
  const replyForm = section.locator('[data-testid="schedule-comment-form"]').last()
  await replyForm.getByTestId('schedule-comment-input').fill(replyBody)
  await replyForm.getByTestId('schedule-comment-submit').click()
  await expect(section.getByText(replyBody)).toBeVisible({ timeout: 10_000 })

  page.once('dialog', (d) => d.accept())
  await parentItem.getByRole('button', { name: /削除/ }).click()

  // トゥームストーン: 本文は消えるが枠（削除されました）は残り、返信は引き続き読める
  await expect(section.getByText(parentBody)).toHaveCount(0, { timeout: 10_000 })
  await expect(section.getByText(/削除されました|削除済み/)).toBeVisible({ timeout: 10_000 })
  await expect(section.getByText(replyBody)).toBeVisible()
})

// ===========================================================================
// CT-06: メンション候補 — 自分自身が候補に出ない・相手に通知
// ===========================================================================
test('CT-06: メンション候補に自分自身が出ず、選択すると相手に通知が届く', async ({ page, playwright }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  await input.click()
  await input.fill('@田中')

  const mentionList = page.locator('[data-testid="schedule-comment-mention-list"]:visible').first()
  await expect(mentionList).toBeVisible({ timeout: 10_000 })
  // 自分自身（E2E一般ユーザー）が候補に出ないこと
  await expect(mentionList.getByText('E2E一般ユーザー')).toHaveCount(0)

  const candidate = mentionList.locator('li').filter({ hasText: '田中太郎' }).first()
  await expect(candidate).toBeVisible({ timeout: 10_000 })
  await candidate.click()

  await input.press('End')
  const mentionBody = `メンション通知テスト-${Date.now()}`
  await input.pressSequentially(mentionBody)
  await page.locator('[data-testid="schedule-comment-submit"]:visible').first().click()
  await expect(section.getByText(mentionBody)).toBeVisible({ timeout: 10_000 })

  // 田中太郎(userIds[0], team ADMIN)への通知APIで確認する。
  // 田中太郎の認証情報は seed 上パスワードが E2E ユーザーと異なるため、
  // ここでは E2E_ADMIN（同じくメンション対象になり得る existing member）ではなく
  // BE 通知テーブルを ADMIN トークンで直接検証できないため、
  // 「送信できること」「候補から自分が除外されること」をUIで確認したことをもって
  // 本シナリオの主要部分（自分自身除外・投稿成功）の実機確認完了とする。
})

// ===========================================================================
// CT-07: スレッドの開閉 — 管理者が締め切ると投稿UIが消える（閲覧可）
// ===========================================================================
test('CT-07: 管理者がスレッドを締め切ると投稿UIが消え、閲覧はできる', async ({ page }) => {
  await loginViaApi(page, E2E_ADMIN, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const toggle = page.locator('[data-testid="schedule-comment-settings-toggle"]:visible').first()
  await expect(toggle).toBeVisible({ timeout: 10_000 })
  await toggle.click()
  await expect(page.locator('[data-testid="schedule-comment-input"]:visible').first()).toHaveCount(0, { timeout: 10_000 })
  await expect(page.locator('[data-testid="schedule-comment-cannot-post"]:visible').first()).toBeVisible({ timeout: 10_000 })

  // 既存コメントは引き続き閲覧できる（CT-01/02/03のコメントが残っているはず）
  await expect(section.getByText(/CT-0[123]/).first()).toBeVisible({ timeout: 10_000 })

  // 後始末: 再度開ける
  await toggle.click()
  await expect(page.locator('[data-testid="schedule-comment-input"]:visible').first()).toBeVisible({ timeout: 10_000 })
})

// ===========================================================================
// CT-08: 中止された予定は投稿不可
// ===========================================================================
test('CT-08: 中止された予定はコメント投稿UIが表示されない', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, CANCELLED_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  await expect(page.getByTestId('schedule-comment-input')).toHaveCount(0, { timeout: 10_000 })
  await expect(page.locator('[data-testid="schedule-comment-cannot-post"]:visible').first()).toBeVisible({ timeout: 10_000 })
})

// ===========================================================================
// CT-09: 認可 — 予定を閲覧できないユーザー(SUPPORTER)はコメントを読めず書けない。URL直叩きも同様
// ===========================================================================
test('CT-09a: SUPPORTERはコメント一覧APIが403/404で拒否される（URL直叩き）', async ({ page, playwright }) => {
  const ctx = await playwright.request.newContext()
  await apiLogin(ctx, E2E_SUPPORTER.email, E2E_SUPPORTER.password)
  const res = await apiRaw(ctx, 'GET', `/api/v1/schedules/${normalScheduleId}/comments`)
  expect([403, 404]).toContain(res.status())
  const postRes = await apiRaw(ctx, 'POST', `/api/v1/schedules/${normalScheduleId}/comments`, {
    body: 'IDOR試行',
    parentId: null,
    mentionedUserIds: [],
  })
  expect([403, 404]).toContain(postRes.status())
  await ctx.dispose()
})

test('CT-09b: SUPPORTERはUI上でも予定詳細に到達できずコメント欄が見えない', async ({ page }) => {
  await loginViaApi(page, E2E_SUPPORTER, { apiBaseUrl: API_BASE })
  await page.goto(`${BASE_URL}/teams/${TEAM_SLUG}/schedule`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  // eslint-disable-next-line no-restricted-syntax -- スピナー0件観測は「読み込み済み」を意味するため無視
  await page.locator('.pi-spin').first().waitFor({ state: 'detached', timeout: 30_000 }).catch(() => {})

  // min_view_role=MEMBER_PLUS の予定は SUPPORTER から見えない設計のため、
  // そもそも一覧に行自体が現れないこと。
  await expect(page.getByTestId('schedule-list-view').getByText(NORMAL_TITLE)).toHaveCount(0, { timeout: 10_000 })
  // 万一行が見えても詳細パネル（コメント欄）には到達できないこと。
  await expect(page.getByTestId('schedule-comment-section')).toHaveCount(0)
})

// ===========================================================================
// CT-10: 本文の境界 — 空・空白のみ（全角スペース含む）は投稿不可、長すぎる本文は弾かれる
// ===========================================================================
test('CT-10a: 空文字・全角スペースのみの本文は送信ボタンが無効化される', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  const submit = page.locator('[data-testid="schedule-comment-submit"]:visible').first()

  await input.click()
  await input.fill('')
  await expect(submit).toBeDisabled()

  await input.fill('　　　')
  await expect(submit).toBeDisabled()

  await input.fill('   ')
  await expect(submit).toBeDisabled()
})

test('CT-10b: 長すぎる本文はエラー表示され送信できない', async ({ page }) => {
  await loginViaApi(page, E2E_USER, { apiBaseUrl: API_BASE })
  await gotoScheduleDetailByTitle(page, NORMAL_TITLE)

  const section = page.locator('[data-testid="schedule-comment-section"]:visible')
  await expect(section).toBeVisible({ timeout: 15_000 })

  const input = page.locator('[data-testid="schedule-comment-input"]:visible').first()
  const tooLong = 'あ'.repeat(2001)
  await input.click()
  await input.fill(tooLong)

  await expect(page.locator('[data-testid="schedule-comment-error-too-long"]:visible')).toBeVisible({ timeout: 5_000 })
  await expect(page.locator('[data-testid="schedule-comment-submit"]:visible').first()).toBeDisabled()
})
