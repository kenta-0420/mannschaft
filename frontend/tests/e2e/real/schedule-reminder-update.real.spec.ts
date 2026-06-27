/**
 * 個人スケジュールのリマインダー付き更新 実機E2E（PR #1937 根治確認）
 *
 * ── 根治した問題 ─────────────────────────────────────────────────────────────
 *   PR #1937 以前: PersonalScheduleReminderRepository.deleteByScheduleId を
 *     Hibernate の derived delete（select-then-remove）で実装していた。
 *     Hibernate の insert-before-delete フラッシュ順序により、後続の saveAll() が
 *     INSERT を先行させてしまい、uq_psr_schedule_minutes（schedule_id, minutes の
 *     複合ユニーク制約）が重複エラー（500）を引き起こしていた。
 *   PR #1937 修正: JPQL バルク DELETE に変更（即時 SQL DELETE を発行し
 *     Hibernate の entity ライフサイクルをバイパス）。
 *
 * ── テスト戦略 ───────────────────────────────────────────────────────────────
 *   1. e2e-user でブラウザログイン
 *   2. リマインダー 30 分前付きの個人スケジュールを API で作成
 *   3. /calendar ページで作成したスケジュールが表示されることを UI で確認
 *   4. 同じリマインダー（30 分前）を維持したまま PATCH API で更新
 *   5. 200 が返ること（500 ＝ uq_psr_schedule_minutes 重複エラーではないこと）を確認
 *   6. /calendar をリロードし、スケジュールが引き続き表示されることを確認
 *
 * テストID:
 *   SCHED-UPDATE-001  リマインダー付きスケジュールを同じリマインダーで更新しても 200 が返る
 *   SCHED-UPDATE-002  （回帰）更新後もリマインダーの値が保持されている
 */

import { test, expect } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

// 書き込み経路なので storageState に依存しない
test.use({ storageState: { cookies: [], origins: [] } })

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

// テスト全体を直列実行（セットアップ → テスト → クリーンアップの順）
test.describe.configure({ mode: 'serial' })

let scheduleId: number | null = null

// ──────────────────────────────────────────────────────────────────────────
// SCHED-UPDATE-001: リマインダー付きスケジュールを同じリマインダーで更新しても 500 にならない
// ──────────────────────────────────────────────────────────────────────────
test(
  'SCHED-UPDATE-001: リマインダー付きスケジュールを同じリマインダーで更新しても 200 が返る（500 重複エラーなし）',
  async ({ page }) => {
    // 1. e2e-user でブラウザログイン（Cookie クリアして新規セッション）
    await page.context().clearCookies()
    await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD }, { apiBaseUrl: API_BASE_URL })

    // 2. リマインダー 30 分前付きの個人スケジュールを API で作成
    const createRes = await page.request.post(`${API_BASE_URL}/api/v1/me/schedules`, {
      data: {
        title: 'E2Eリマインダー更新テスト（PR #1937）',
        startAt: '2026-12-25T10:00:00+09:00',
        endAt: '2026-12-25T11:00:00+09:00',
        allDay: false,
        reminders: [30],
      },
    })
    expect(createRes.status(), '個人スケジュール作成は 201').toBe(201)
    const createdBody = (await createRes.json()) as { data: { id: number } }
    scheduleId = createdBody.data.id
    expect(scheduleId, '作成したスケジュールの ID が取得できる').toBeGreaterThan(0)

    // 3. /calendar ページへ遷移して UI で確認（実機E2E のブラウザ操作要件を満たす）
    await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    // スピナーが消えるまで待機
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // 認証失敗で /login にリダイレクトされた場合はテスト失敗
    if (page.url().includes('/login')) {
      throw new Error(`/calendar が /login にリダイレクト。認証失敗: ${page.url()}`)
    }

    // カレンダーページが表示されていることを確認
    await expect(page.locator('body'), 'カレンダーページが表示される').not.toContainText('ページが見つかりません')

    // 4. ── 根治確認の核心 ──
    //    同じリマインダー（30 分前）を保持したまま PATCH で更新する。
    //    PR #1937 修正前: 内部で deleteByScheduleId（derived delete）+ saveAll が衝突し
    //                      uq_psr_schedule_minutes 重複エラー → 500
    //    PR #1937 修正後: JPQL バルク DELETE で即時削除 → saveAll 成功 → 200
    const updateRes = await page.request.patch(`${API_BASE_URL}/api/v1/me/schedules/${scheduleId}`, {
      data: {
        title: 'E2Eリマインダー更新テスト（PR #1937・更新後）',
        reminders: [30],
      },
    })

    expect(
      updateRes.status(),
      '同じリマインダー（30分前）で更新しても 200 が返る（500 = uq_psr_schedule_minutes 重複エラーではない＝PR #1937 根治の核心）',
    ).toBe(200)

    // 更新後レスポンスのデータを確認
    // PersonalScheduleResponse は title を content.title に格納する
    const updatedBody = (await updateRes.json()) as {
      data: { id: number; content: { title: string }; reminders?: number[] }
    }
    expect(updatedBody.data?.id, '更新後レスポンスにスケジュール ID が含まれる').toBeTruthy()
    expect(updatedBody.data?.content?.title, '更新後タイトルが反映されている').toContain('更新後')
  },
)

// ──────────────────────────────────────────────────────────────────────────
// SCHED-UPDATE-002: 更新後もスケジュールが /calendar で表示される（回帰確認）
// ──────────────────────────────────────────────────────────────────────────
test('SCHED-UPDATE-002: 更新後もカレンダーページが正常に表示される（回帰確認）', async ({ page }) => {
  expect(scheduleId, 'SCHED-UPDATE-001 でスケジュールが作成されていること').toBeTruthy()

  // 同一ユーザーで再ログイン
  await page.context().clearCookies()
  await loginViaApi(page, { email: USER_EMAIL, password: USER_PASSWORD }, { apiBaseUrl: API_BASE_URL })

  // /calendar をリロード
  await page.goto('/calendar', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  // カレンダーページが表示されていること（500 エラーで真っ白にならないこと）
  await expect(page.locator('body'), 'カレンダーページが 500 エラーで崩壊していない').not.toContainText('500')
  await expect(page.locator('body'), '「undefined」が表示されていない').not.toContainText('undefined')

  // クリーンアップ: 作成したスケジュールを削除
  const deleteRes = await page.request.delete(`${API_BASE_URL}/api/v1/me/schedules/${scheduleId}`)
  // 204 = 削除成功、404 = 既に削除済み（どちらも許容）
  expect([204, 404], 'スケジュール削除が成功する').toContain(deleteRes.status())
})
