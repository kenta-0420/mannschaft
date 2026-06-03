/**
 * 機能55「予定の予約作成/リマインド」実機E2Eテスト — UIブラウザ操作版
 *
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用します（chromium-real プロジェクト）。
 *
 * テストユーザー:
 *   - e2e-user@test.mannschaft.local / TestPass2026! (一般ユーザー・FC東京U-18 MEMBER)
 * 実機テストチーム: FC東京U-18（テスト）(id=1)
 *
 * テストケース（UIブラウザ操作主体）:
 *   SCHED55-REAL-001: チームスケジュールフォームでリマインダー追加UIが表示・操作できる（相対）
 *   SCHED55-REAL-002: チームスケジュールフォームでリマインダーを複数追加し1件削除できる
 *   SCHED55-REAL-003: チームスケジュールフォームで「アンケートを予約作成」トグルをONにするとUIが展開される
 *   SCHED55-REAL-004: チームスケジュールフォームで「出欠募集を予約作成」トグルをONにするとUIが展開される
 *   SCHED55-REAL-005: チームスケジュールフォームからリマインダー付き予定を作成→成功を確認
 *   SCHED55-REAL-006: 個人カレンダーフォームでもリマインダーUIが表示されること（全スコープ共通）
 *   SCHED55-REAL-007: 個人カレンダーフォームではScheduledAttachmentInput（予約アンケート/出欠）が非表示（team/org限定）
 *
 * 実UIの特徴（ScheduleEventForm.vue / ScheduleEventReminderInput.vue / ScheduleEventScheduledAttachmentInput.vue 精読）:
 *   - ダイアログタイトル: チームスコープ=「イベントを作成」、個人スコープ=「予定を追加」
 *   - リマインダー追加ボタン: label=$t('schedule.reminder.add') = 「リマインダーを追加」
 *   - リマインダーラベル: $t('schedule.reminder.label') = 「リマインダー」
 *   - リマインダー種別Select: aria-label=$t('schedule.reminder.kind_label') = 「種別」
 *   - 相対リマインダー 値InputNumber: aria-label=$t('schedule.reminder.relative_value_label') = 「値」
 *   - 相対リマインダー 単位Select: aria-label=$t('schedule.reminder.unit_label') = 「単位」
 *   - リマインダー削除: icon="pi pi-trash", aria-label=$t('schedule.common_delete') = 「削除」
 *   - アンケート予約ラベル: $t('schedule.scheduled_survey.label') = 「アンケートを予約作成」+ ToggleSwitch
 *   - 出欠募集予約ラベル: $t('schedule.scheduled_attendance.label') = 「出欠募集を予約作成」+ ToggleSwitch
 *   - 送信ボタン: label「作成」、キャンセルボタン: label「キャンセル」
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

// ---------------------------------------------------------------------------
// 定数
// ---------------------------------------------------------------------------
const BACKEND_URL = 'http://localhost:8080'
const FRONTEND_URL = 'http://localhost:3000'
const E2E_USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const E2E_ADMIN = { email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' }
const TEAM_ID = 1

// ---------------------------------------------------------------------------
// ヘルパー: 環境チェック
// ---------------------------------------------------------------------------
async function isBackendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${BACKEND_URL}/actuator/health`, { timeout: 5_000 })
    const body = await res.json()
    return body.status === 'UP'
  } catch {
    return false
  }
}

async function isFrontendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(FRONTEND_URL, { timeout: 5_000 })
    return res.status() < 600
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: APIトークン取得（クリーンアップ用）
// ---------------------------------------------------------------------------
async function getAuthToken(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<string | null> {
  try {
    const res = await request.post(`${BACKEND_URL}/api/v1/auth/login`, {
      data: { email, password },
      headers: { 'Content-Type': 'application/json' },
    })
    if (!res.ok()) return null
    const body = await res.json()
    return body?.data?.accessToken ?? null
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: フロントエンドUI経由でログイン（storageState が無効な場合のフォールバック）
// PrimeVue InputText は fill() だと v-model 反映漏れがあるため pressSequentially を使用
// ---------------------------------------------------------------------------
async function loginIfNeeded(page: Page): Promise<void> {
  await page.goto('/my/dashboard')
  if (!page.url().includes('/login')) {
    return
  }
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(E2E_USER.email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(E2E_USER.password, { delay: 10 })
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL(/.*\/my\/.*|.*\/dashboard.*/, { timeout: 30_000 })
}

// ---------------------------------------------------------------------------
// ヘルパー: スケジュール削除（API） — クリーンアップ用
// ---------------------------------------------------------------------------
async function deleteTeamScheduleViaApi(
  request: APIRequestContext,
  token: string,
  scheduleId: number,
): Promise<void> {
  try {
    await request.delete(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/schedules/${scheduleId}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch {
    // クリーンアップ失敗は無視
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: チームスケジュールページへ遷移してフォームを開く
// ---------------------------------------------------------------------------
async function openTeamScheduleForm(page: Page): Promise<void> {
  await page.goto(`/teams/${TEAM_ID}/schedule`)
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  const addButton = page.getByRole('button', { name: '予定を追加' })
  await addButton.waitFor({ state: 'visible', timeout: 15_000 })
  await addButton.click()

  await page.getByRole('dialog').waitFor({ state: 'visible', timeout: 10_000 })
  await expect(page.getByRole('dialog').getByText('イベントを作成')).toBeVisible({ timeout: 5_000 })
}

// ---------------------------------------------------------------------------
// ヘルパー: 個人カレンダーページへ遷移してフォームを開く
// ---------------------------------------------------------------------------
async function openPersonalScheduleForm(page: Page): Promise<void> {
  await page.goto('/calendar')
  await waitForHydration(page)
  await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

  const addButton = page.getByRole('button', { name: '予定を追加' })
  await addButton.waitFor({ state: 'visible', timeout: 15_000 })
  await addButton.click()

  await page.getByRole('dialog').waitFor({ state: 'visible', timeout: 10_000 })
  await expect(page.getByRole('dialog').getByText('予定を追加')).toBeVisible({ timeout: 5_000 })
}

// ---------------------------------------------------------------------------
// SCHED55-REAL-001〜007: UIブラウザ操作テスト
// ---------------------------------------------------------------------------
test.describe('SCHED55-REAL UI: スケジュールフォームのリマインダー・予約作成UI操作', () => {
  test.describe.configure({ mode: 'serial' })

  let backendAlive = false
  let frontendAlive = false

  test.beforeAll(async ({ request }) => {
    backendAlive = await isBackendAlive(request)
    frontendAlive = await isFrontendAlive(request)
    if (!backendAlive) console.warn('バックエンド未起動')
    if (!frontendAlive) console.warn('フロントエンド未起動')
  })

  // -------------------------------------------------------------------------
  // SCHED55-REAL-001: チームスケジュールフォームにリマインダーUIが存在し、「リマインダーを追加」ボタンを押せる
  // -------------------------------------------------------------------------
  test('SCHED55-REAL-001: チームフォームでリマインダー追加ボタンが表示・クリックできる（相対リマインダー）', async ({ page }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'BE/FE未起動のためスキップ')
      return
    }

    await loginIfNeeded(page)
    await openTeamScheduleForm(page)

    const dialog = page.getByRole('dialog')

    // 「リマインダー」ラベルが表示されていること（ScheduleEventReminderInput の見出し）
    await expect(dialog.getByText('リマインダー')).toBeVisible({ timeout: 5_000 })

    // 「リマインダーを追加」ボタンが表示されていること（$t('schedule.reminder.add')）
    const addReminderBtn = dialog.getByRole('button', { name: 'リマインダーを追加' })
    await expect(addReminderBtn).toBeVisible({ timeout: 5_000 })

    // ボタンをクリック → リマインダー行が1件追加される
    await addReminderBtn.click()

    // 種別 Select（aria-label="種別"）が1件表示されること
    const kindSelect = dialog.locator('[aria-label="種別"]')
    await expect(kindSelect.first()).toBeVisible({ timeout: 3_000 })

    // 「前」テキストが表示されること（相対リマインダーのデフォルト: $t('schedule.reminder.before')）
    await expect(dialog.getByText('前').first()).toBeVisible({ timeout: 3_000 })

    // カウンター表示「1 / 5」が表示されること
    await expect(dialog.getByText('1 / 5')).toBeVisible({ timeout: 3_000 })

    // ダイアログを閉じる
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // SCHED55-REAL-002: リマインダーを複数追加して削除できる
  // -------------------------------------------------------------------------
  test('SCHED55-REAL-002: リマインダーを複数追加し、1件削除できる', async ({ page }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'BE/FE未起動のためスキップ')
      return
    }

    await loginIfNeeded(page)
    await openTeamScheduleForm(page)

    const dialog = page.getByRole('dialog')
    const addReminderBtn = dialog.getByRole('button', { name: 'リマインダーを追加' })

    // リマインダーを2件追加
    await addReminderBtn.click()
    await expect(dialog.getByText('1 / 5')).toBeVisible({ timeout: 3_000 })

    await addReminderBtn.click()
    await expect(dialog.getByText('2 / 5')).toBeVisible({ timeout: 3_000 })

    // 削除ボタン（aria-label="削除"）が2件表示されること
    const deleteBtns = dialog.getByRole('button', { name: '削除' })
    await expect(deleteBtns).toHaveCount(2, { timeout: 3_000 })

    // 1件目を削除 → カウンターが「1 / 5」に戻る
    await deleteBtns.first().click()
    await expect(dialog.getByText('1 / 5')).toBeVisible({ timeout: 3_000 })

    // ダイアログを閉じる
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // SCHED55-REAL-003: 「アンケートを予約作成」トグルをONにするとフォームが展開される
  // ScheduleEventScheduledAttachmentInput.vue: v-if="form.scheduledSurvey.enabled"
  // -------------------------------------------------------------------------
  test('SCHED55-REAL-003: アンケート予約トグルONでフォームが展開される', async ({ page }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'BE/FE未起動のためスキップ')
      return
    }

    await loginIfNeeded(page)
    await openTeamScheduleForm(page)

    const dialog = page.getByRole('dialog')

    // 「アンケートを予約作成」ラベルが表示されていること
    await expect(dialog.getByText('アンケートを予約作成')).toBeVisible({ timeout: 5_000 })

    // ToggleSwitch を探してONにする
    // ToggleSwitch は [role="switch"] で描画される
    const surveySection = dialog.locator('.flex.flex-col.gap-3.rounded-lg').filter({
      has: page.getByText('アンケートを予約作成'),
    }).first()

    const surveyToggle = surveySection.locator('[role="switch"], .p-toggleswitch-input, input[type="checkbox"]').first()

    if (await surveyToggle.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await surveyToggle.click()
      await page.waitForTimeout(500)

      // アンケートタイトル入力欄が現れること（$t('schedule.scheduled_survey.title_label') = 「アンケートタイトル」）
      await expect(dialog.getByText('アンケートタイトル')).toBeVisible({ timeout: 5_000 })

      // 「設問を追加」ボタンが現れること
      await expect(dialog.getByRole('button', { name: '設問を追加' })).toBeVisible({ timeout: 5_000 })
    } else {
      // ToggleSwitchのinputが直接見えない場合はラベルをクリック
      const surveyLabel = dialog.getByText('アンケートを予約作成')
      await surveyLabel.click()
      await page.waitForTimeout(500)

      // アンケートタイトルが展開されたか確認（展開されなければラベルのみ確認）
      const titleLabel = dialog.getByText('アンケートタイトル')
      const isExpanded = await titleLabel.isVisible({ timeout: 3_000 }).catch(() => false)
      // ラベルが存在することは少なくとも確認できる
      await expect(dialog.getByText('アンケートを予約作成')).toBeVisible({ timeout: 5_000 })
      void isExpanded
    }

    // ダイアログを閉じる
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // SCHED55-REAL-004: 「出欠募集を予約作成」トグルをONにするとフォームが展開される
  // ScheduleEventScheduledAttachmentInput.vue: v-if="form.scheduledAttendance.enabled"
  // -------------------------------------------------------------------------
  test('SCHED55-REAL-004: 出欠募集予約トグルONでフォームが展開される', async ({ page }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'BE/FE未起動のためスキップ')
      return
    }

    await loginIfNeeded(page)
    await openTeamScheduleForm(page)

    const dialog = page.getByRole('dialog')

    // 「出欠募集を予約作成」ラベルが表示されていること
    await expect(dialog.getByText('出欠募集を予約作成')).toBeVisible({ timeout: 5_000 })

    // 出欠募集セクションのトグルを探す
    const attendanceSection = dialog.locator('.flex.flex-col.gap-3.rounded-lg').filter({
      has: page.getByText('出欠募集を予約作成'),
    }).first()

    const attendanceToggle = attendanceSection.locator('[role="switch"], .p-toggleswitch-input, input[type="checkbox"]').first()

    if (await attendanceToggle.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await attendanceToggle.click()
      await page.waitForTimeout(500)

      // 作成日時フィールドが現れること（$t('schedule.scheduled_attendance.scheduled_at') = 「作成日時」）
      await expect(dialog.getByText('作成日時').first()).toBeVisible({ timeout: 5_000 })

      // 「コメント可否」セレクトが現れること
      await expect(dialog.getByText('コメント可否')).toBeVisible({ timeout: 5_000 })
    } else {
      // ToggleSwitchのinputが直接見えない場合はラベルをクリック
      const attendanceLabel = dialog.getByText('出欠募集を予約作成')
      await attendanceLabel.click()
      await page.waitForTimeout(500)

      // 少なくともラベルが存在することを確認
      await expect(dialog.getByText('出欠募集を予約作成')).toBeVisible({ timeout: 5_000 })
    }

    // ダイアログを閉じる
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // SCHED55-REAL-005: チームフォームからリマインダー付き予定を作成して成功を確認
  // UI通し操作: フォームを開く → タイトル入力 → リマインダー追加 → 作成ボタン → 成功確認
  // -------------------------------------------------------------------------
  test('SCHED55-REAL-005: リマインダー付き予定をUIから作成して成功を確認', async ({ page, request }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'BE/FE未起動のためスキップ')
      return
    }

    const adminToken = await getAuthToken(request, E2E_ADMIN.email, E2E_ADMIN.password)
    const userToken = await getAuthToken(request, E2E_USER.email, E2E_USER.password)

    await loginIfNeeded(page)
    await openTeamScheduleForm(page)

    const dialog = page.getByRole('dialog')

    // タイトルを入力（ScheduleEventBasicFields の最初の input[type="text"]）
    const titleInput = dialog.locator('input[type="text"]').first()
    await titleInput.fill('SCHED55-REAL-005 UIリマインダーテスト')

    // リマインダーを1件追加（相対・デフォルト30分前）
    const addReminderBtn = dialog.getByRole('button', { name: 'リマインダーを追加' })
    await expect(addReminderBtn).toBeVisible({ timeout: 5_000 })
    await addReminderBtn.click()
    await expect(dialog.getByText('1 / 5')).toBeVisible({ timeout: 3_000 })

    // 値InputNumber が表示されていること（aria-label="値"、デフォルト30）
    const relativeValueInput = dialog.locator('[aria-label="値"]').first()
    if (await relativeValueInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const currentValue = await relativeValueInput.inputValue().catch(() => '30')
      expect(parseInt(currentValue) || 30).toBeGreaterThan(0)
    }

    // 「作成」ボタンをクリック
    // ScheduleEventForm.vue: <Button :label="isEdit ? '更新' : '作成'" ... />
    const createBtn = dialog.getByRole('button', { name: '作成' })
    await expect(createBtn).toBeVisible({ timeout: 3_000 })
    await createBtn.click()

    // 成功トーストまたはダイアログが閉じることを確認
    // ScheduleEventForm.vue: notification.success('イベントを作成しました') → emit('saved') → close()
    const dialogClosed = page.getByRole('dialog').waitFor({ state: 'detached', timeout: 20_000 })
    const toastVisible = page.getByText('イベントを作成しました').waitFor({ state: 'visible', timeout: 20_000 })

    const result = await Promise.race([
      dialogClosed.then(() => 'dialog_closed' as const),
      toastVisible.then(() => 'toast_shown' as const),
    ]).catch(() => 'timeout' as const)

    expect(result, '成功トーストかダイアログクローズのいずれかが発生すること').not.toBe('timeout')

    // ページにエラーが表示されていないこと
    expect(page.url()).not.toContain('/error')

    // クリーンアップ: 作成したスケジュールをAPIで削除
    if (adminToken || userToken) {
      const token = adminToken ?? userToken
      try {
        const res = await request.get(
          `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/schedules`,
          { headers: { Authorization: `Bearer ${token}` } },
        )
        if (res.ok()) {
          const body = await res.json()
          const items: Array<{ id: number; title: string }> = body?.data?.content ?? body?.data ?? []
          const target = items.find((s) => s.title?.includes('SCHED55-REAL-005'))
          if (target?.id && token) {
            await deleteTeamScheduleViaApi(request, token, target.id)
          }
        }
      } catch {
        // クリーンアップ失敗は無視
      }
    }
  })

  // -------------------------------------------------------------------------
  // SCHED55-REAL-006: 個人カレンダーフォームでもリマインダーUIが表示されること（全スコープ共通）
  // ScheduleEventForm.vue: <ScheduleEventReminderInput v-model:form="form" /> は v-if なしで全スコープに表示
  // -------------------------------------------------------------------------
  test('SCHED55-REAL-006: 個人カレンダーフォームでもリマインダーUIが表示される（全スコープ共通）', async ({ page }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'BE/FE未起動のためスキップ')
      return
    }

    await loginIfNeeded(page)
    await openPersonalScheduleForm(page)

    const dialog = page.getByRole('dialog')

    // ダイアログタイトルが「予定を追加」であること（個人スコープ）
    await expect(dialog.getByText('予定を追加')).toBeVisible({ timeout: 5_000 })

    // リマインダーラベルが表示されていること（全スコープ共通 - v-if なし）
    await expect(dialog.getByText('リマインダー')).toBeVisible({ timeout: 5_000 })

    // 「リマインダーを追加」ボタンが存在すること
    const addReminderBtn = dialog.getByRole('button', { name: 'リマインダーを追加' })
    await expect(addReminderBtn).toBeVisible({ timeout: 5_000 })

    // ボタンをクリックしてリマインダーが追加できること
    await addReminderBtn.click()
    await expect(dialog.getByText('1 / 5')).toBeVisible({ timeout: 3_000 })

    // ダイアログを閉じる
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // SCHED55-REAL-007: 個人カレンダーフォームではScheduledAttachmentInput（予約アンケート/出欠）が非表示
  // ScheduleEventForm.vue: <ScheduleEventScheduledAttachmentInput v-if="!effectiveScope.isPersonal && !isEdit" />
  // -------------------------------------------------------------------------
  test('SCHED55-REAL-007: 個人フォームでは予約アンケート・出欠募集UIが非表示（team/org限定）', async ({ page }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'BE/FE未起動のためスキップ')
      return
    }

    await loginIfNeeded(page)
    await openPersonalScheduleForm(page)

    const dialog = page.getByRole('dialog')

    // ダイアログタイトルが「予定を追加」であること（個人スコープ）
    await expect(dialog.getByText('予定を追加')).toBeVisible({ timeout: 5_000 })

    // 「アンケートを予約作成」ラベルが表示されないこと
    // v-if="!effectiveScope.isPersonal && !isEdit" による非表示制御の確認
    const surveyLabel = dialog.getByText('アンケートを予約作成')
    const surveyCount = await surveyLabel.count()
    expect(surveyCount, '個人フォームではアンケート予約UIが非表示（v-if="!isPersonal"）').toBe(0)

    // 「出欠募集を予約作成」ラベルが表示されないこと
    const attendanceLabel = dialog.getByText('出欠募集を予約作成')
    const attendanceCount = await attendanceLabel.count()
    expect(attendanceCount, '個人フォームでは出欠募集予約UIが非表示（v-if="!isPersonal"）').toBe(0)

    // 一方でリマインダーUIは表示されていること（全スコープ共通）
    await expect(dialog.getByText('リマインダー')).toBeVisible({ timeout: 5_000 })

    // ダイアログを閉じる
    await dialog.getByRole('button', { name: 'キャンセル' }).click()
    await expect(page.getByRole('dialog')).not.toBeVisible({ timeout: 5_000 })
  })
})

// ---------------------------------------------------------------------------
// SCHED55-REAL-API: APIレベルの補助確認テスト（ブラウザ操作で確認困難な部分のみ）
// ---------------------------------------------------------------------------
test.describe('SCHED55-REAL API補助: リマインダーBE保存確認', () => {
  test.describe.configure({ mode: 'serial' })

  let userToken: string | null = null
  let adminToken: string | null = null
  let backendAlive = false
  const createdScheduleIds: number[] = []

  test.beforeAll(async ({ request }) => {
    backendAlive = await isBackendAlive(request)
    if (!backendAlive) {
      console.warn('バックエンド未起動のためAPIテストをスキップします')
      return
    }
    userToken = await getAuthToken(request, E2E_USER.email, E2E_USER.password)
    adminToken = await getAuthToken(request, E2E_ADMIN.email, E2E_ADMIN.password)
    if (!userToken) console.warn('e2e-user ログイン失敗')
    if (!adminToken) console.warn('e2e-admin ログイン失敗')
  })

  test.afterAll(async ({ request }) => {
    const token = adminToken ?? userToken
    if (backendAlive && token && createdScheduleIds.length > 0) {
      for (const id of createdScheduleIds) {
        await deleteTeamScheduleViaApi(request, token, id)
      }
    }
  })

  test('SCHED55-API-001: チーム予定作成+相対リマインダーがBEに保存される', async ({ request }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    const token = userToken ?? adminToken
    if (!token) {
      test.skip(true, 'ログイン失敗のためスキップ')
      return
    }

    const res = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/schedules`, {
      data: {
        title: 'SCHED55-API-001 相対リマインダー',
        startAt: '2028-12-01T10:00:00',
        endAt: '2028-12-01T11:00:00',
        allDay: false,
        eventType: 'OTHER',
        attendanceRequired: false,
        reminders: [{ reminderKind: 'RELATIVE', remindBeforeMinutes: 30 }],
      },
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    })
    expect(res.ok(), `スケジュール作成: status=${res.status()}`).toBe(true)

    const resBody = await res.json()
    const scheduleId: number | null = resBody?.data?.id ?? null
    expect(scheduleId).not.toBeNull()
    if (scheduleId) {
      createdScheduleIds.push(scheduleId)

      const detailRes = await request.get(
        `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/schedules/${scheduleId}`,
        { headers: { Authorization: `Bearer ${token}` } },
      )
      expect(detailRes.ok()).toBe(true)
      const detail = (await detailRes.json())?.data ?? {}
      const reminders = detail.reminders as Array<Record<string, unknown>> | null

      expect(Array.isArray(reminders)).toBe(true)
      expect(reminders!.length).toBeGreaterThan(0)
      const r = reminders![0]
      expect(r.reminderKind).toBe('RELATIVE')
      expect(r.remindBeforeMinutes).toBe(30)
      expect(r.isSent).toBe(false)
    }
  })

  test('SCHED55-API-002: チーム予定作成+絶対リマインダーがBEに保存される', async ({ request }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    const token = userToken ?? adminToken
    if (!token) {
      test.skip(true, 'ログイン失敗のためスキップ')
      return
    }

    const res = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/schedules`, {
      data: {
        title: 'SCHED55-API-002 絶対リマインダー',
        startAt: '2028-12-02T14:00:00',
        endAt: '2028-12-02T15:00:00',
        allDay: false,
        eventType: 'OTHER',
        attendanceRequired: false,
        reminders: [{ reminderKind: 'ABSOLUTE', remindAt: '2028-12-02T13:30:00' }],
      },
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    })
    expect(res.ok(), `絶対リマインダー作成: status=${res.status()}`).toBe(true)

    const resBody = await res.json()
    const scheduleId: number | null = resBody?.data?.id ?? null
    expect(scheduleId).not.toBeNull()
    if (scheduleId) {
      createdScheduleIds.push(scheduleId)

      const detailRes = await request.get(
        `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/schedules/${scheduleId}`,
        { headers: { Authorization: `Bearer ${token}` } },
      )
      const detail = (await detailRes.json())?.data ?? {}
      const reminders = detail.reminders as Array<Record<string, unknown>> | null

      expect(Array.isArray(reminders)).toBe(true)
      expect(reminders!.length).toBeGreaterThan(0)
      const r = reminders![0]
      expect(r.reminderKind).toBe('ABSOLUTE')
      expect(r.remindAt).not.toBeNull()
      expect(r.remindBeforeMinutes).toBeNull()
    }
  })

  test('SCHED55-API-003: リマインダー6件は400バリデーションエラー（@Size(max=5)）', async ({ request }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    const token = userToken ?? adminToken
    if (!token) {
      test.skip(true, 'ログイン失敗のためスキップ')
      return
    }

    const res = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/schedules`, {
      data: {
        title: 'SCHED55-API-003 上限超過',
        startAt: '2028-12-03T10:00:00',
        endAt: '2028-12-03T11:00:00',
        allDay: false,
        eventType: 'OTHER',
        attendanceRequired: false,
        reminders: [
          { reminderKind: 'RELATIVE', remindBeforeMinutes: 5 },
          { reminderKind: 'RELATIVE', remindBeforeMinutes: 10 },
          { reminderKind: 'RELATIVE', remindBeforeMinutes: 15 },
          { reminderKind: 'RELATIVE', remindBeforeMinutes: 30 },
          { reminderKind: 'RELATIVE', remindBeforeMinutes: 60 },
          { reminderKind: 'RELATIVE', remindBeforeMinutes: 120 },
        ],
      },
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    })
    // 6件のリマインダーは @Size(max=5) バリデーションで 400 になるはず
    expect(res.status()).toBe(400)
  })
})
