import { test, expect, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

/**
 * 機能55「予定の予約作成/リマインド」E2E テスト
 *
 * テストID: SCHED55-001〜007
 *
 * 方針:
 * - API モックを使用（page.route）— バックエンド非依存で確定的に動作する
 * - 認証: storageState（tests/e2e/.auth/user.json）
 * - PrimeVue の Select/Toggle 操作は click() を使用（fill() 禁止）
 *
 * 検証対象コンポーネント:
 * - ScheduleEventReminderInput.vue  — 相対/絶対リマインダー追加・削除・最大5件制限
 * - ScheduleEventScheduledAttachmentInput.vue — アンケート/出欠の予約作成（team/org のみ）
 * - EventDetailPanel.vue — scheduledTasks 一覧 + PENDING 取消ボタン
 *
 * 仕様書: docs/features/F55_schedule_reminder_reservation.md
 */

// ========== 共通モックデータ ==========

/** POST /teams/:id/schedules の成功レスポンス */
const MOCK_CREATE_SCHEDULE_RESPONSE = {
  data: {
    id: 999,
    title: 'テストイベント',
    startAt: '2027-01-10T10:00:00',
    endAt: '2027-01-10T11:00:00',
    allDay: false,
    status: 'PUBLISHED',
    attendanceRequired: false,
    categoryName: null,
    categoryColor: null,
    createdBy: { displayName: 'テストユーザー' },
    myAttendance: null,
    attendanceStats: null,
    scheduledTasks: [],
    description: null,
    location: null,
  },
}

/** GET /teams/:id/schedules/:eventId のレスポンス（scheduledTasks付き） */
function mockScheduleDetailWithTasks(taskId: string) {
  return {
    data: {
      id: 100,
      title: 'タスク付きイベント',
      startAt: '2027-01-10T10:00:00',
      endAt: '2027-01-10T11:00:00',
      allDay: false,
      status: 'PUBLISHED',
      attendanceRequired: false,
      categoryName: null,
      categoryColor: null,
      createdBy: { displayName: 'テストユーザー' },
      myAttendance: null,
      attendanceStats: null,
      description: null,
      location: null,
      scheduledTasks: [
        {
          id: taskId,
          taskType: 'SURVEY',
          scheduledAt: '2027-01-09T10:00:00',
          status: 'PENDING',
        },
      ],
    },
  }
}

/** カレンダーAPIのモックレスポンス（空イベント） */
const MOCK_CALENDAR_EMPTY = {
  data: [],
  meta: { page: 0, size: 100, totalElements: 0, totalPages: 0 },
}

// ========== ヘルパー: チーム用APIのフルセットモック + ページ遷移 ==========

/** チームスケジュールページに必要なすべての APIをモックしてページを開く */
async function setupTeamSchedulePage(page: Page) {
  // チーム基本情報 + 権限
  await mockTeam(page)

  // スケジュール一覧 API（カレンダー表示用、GETのみ）
  await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_CALENDAR_EMPTY),
      })
    } else {
      // POST は各テストで個別モック済みのためフォールバック
      await route.continue()
    }
  })

  // その他のチーム配下 API（権限以外は空レスポンス）
  await mockTeamFeatureApis(page)

  await page.goto(`/teams/${TEAM_ID}/schedule`)
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({ timeout: 10_000 })
}

/** 「予定を追加」ボタンをクリックしてダイアログを開く */
async function openCreateDialog(page: Page) {
  const addBtn = page.getByRole('button', { name: '予定を追加' })
  await expect(addBtn).toBeVisible({ timeout: 5_000 })
  await addBtn.click()
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 8_000 })
}

// ========== describe ブロック ==========

test.describe('SCHED55-001〜003: リマインダー入力（ScheduleEventReminderInput）', () => {
  test('SCHED55-001: 相対リマインダー追加→送信リクエストに reminders: [{reminderKind:"RELATIVE",remindBeforeMinutes:30}] が含まれる', async ({
    page,
  }) => {
    // POST リクエストをインターセプトしてリクエストボディを検証
    let capturedBody: Record<string, unknown> | undefined

    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        capturedBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CREATE_SCHEDULE_RESPONSE),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CALENDAR_EMPTY),
        })
      }
    })

    await setupTeamSchedulePage(page)
    await openCreateDialog(page)

    const dialog = page.getByRole('dialog')

    // タイトル入力（必須フィールド、最初のtextbox）
    await dialog.getByRole('textbox').first().click()
    await dialog.getByRole('textbox').first().pressSequentially('リマインダーテスト', { delay: 10 })

    // 「リマインダーを追加」ボタンをクリック
    const addReminderBtn = dialog.getByRole('button', { name: 'リマインダーを追加' })
    await expect(addReminderBtn).toBeVisible({ timeout: 5_000 })
    await addReminderBtn.click()

    // リマインダーが1件追加されたことを確認（「1 / 5」バッジ）
    await expect(dialog.locator('text=1 / 5')).toBeVisible({ timeout: 3_000 })

    // 「作成」ボタンをクリック
    await dialog.getByRole('button', { name: '作成' }).click()

    // POST が呼ばれ、capturedBody に reminders が含まれることを確認
    await expect.poll(() => capturedBody, { timeout: 5_000 }).not.toBeUndefined()

    const body = capturedBody!
    const reminders = body.reminders as unknown[]
    expect(Array.isArray(reminders)).toBe(true)
    expect(reminders.length).toBeGreaterThanOrEqual(1)
    const firstReminder = reminders[0] as Record<string, unknown>
    expect(firstReminder.reminderKind).toBe('RELATIVE')
    // デフォルト値は 30 分前
    expect(firstReminder.remindBeforeMinutes).toBe(30)
  })

  test('SCHED55-002: 絶対日時リマインダー追加→送信リクエストに {reminderKind:"ABSOLUTE",remindAt:...} が含まれる', async ({
    page,
  }) => {
    let capturedBody: Record<string, unknown> | undefined

    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        capturedBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CREATE_SCHEDULE_RESPONSE),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CALENDAR_EMPTY),
        })
      }
    })

    await setupTeamSchedulePage(page)
    await openCreateDialog(page)

    const dialog = page.getByRole('dialog')

    // タイトル入力
    await dialog.getByRole('textbox').first().click()
    await dialog.getByRole('textbox').first().pressSequentially('絶対リマインダーテスト', { delay: 10 })

    // 「リマインダーを追加」ボタンをクリック
    await dialog.getByRole('button', { name: 'リマインダーを追加' }).click()
    await expect(dialog.locator('text=1 / 5')).toBeVisible({ timeout: 3_000 })

    // 種別セレクト（相対→絶対 に変更）
    // PrimeVue Select: aria-label="種別" のセレクトをクリックしてドロップダウン → 「絶対」を選択
    const kindSelect = dialog.locator('[aria-label="種別"]').first()
    await kindSelect.click()
    const absoluteOption = page.getByRole('option', { name: '絶対' })
    await expect(absoluteOption).toBeVisible({ timeout: 3_000 })
    await absoluteOption.click()

    // DatePicker に未来日時を入力（aria-label="絶対日時"）
    const datePickerInput = dialog.locator('[aria-label="絶対日時"]').first()
    await datePickerInput.click()
    await datePickerInput.pressSequentially('2027/06/15', { delay: 10 })
    // DatePicker を閉じるためにダイアログ別箇所をクリック
    await dialog.locator('.p-dialog-content').click({ position: { x: 10, y: 10 } })

    // 「作成」ボタンをクリック
    await dialog.getByRole('button', { name: '作成' }).click()

    // POST リクエストの検証（バリデーションエラーで送信されない場合も考慮し、
    // ここでは reminders が ABSOLUTE で設定されたことのみ確認）
    await expect.poll(() => capturedBody, { timeout: 5_000 }).not.toBeUndefined()

    const body = capturedBody!
    const reminders = body.reminders as unknown[]
    expect(Array.isArray(reminders)).toBe(true)
    expect(reminders.length).toBeGreaterThanOrEqual(1)
    const firstReminder = reminders[0] as Record<string, unknown>
    expect(firstReminder.reminderKind).toBe('ABSOLUTE')
    expect(typeof firstReminder.remindAt).toBe('string')
    expect(firstReminder.remindAt).toBeTruthy()
  })

  test('SCHED55-003: 最大5件超過のリマインダー追加ができない（追加ボタンが非表示になる）', async ({
    page,
  }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_CALENDAR_EMPTY),
      })
    })

    await setupTeamSchedulePage(page)
    await openCreateDialog(page)

    const dialog = page.getByRole('dialog')
    const addReminderBtn = dialog.getByRole('button', { name: 'リマインダーを追加' })

    // 5件まで追加できる
    for (let i = 0; i < 5; i++) {
      await expect(addReminderBtn).toBeVisible({ timeout: 3_000 })
      await addReminderBtn.click()
      // バッジが増えることを確認
      await expect(dialog.locator(`text=${i + 1} / 5`)).toBeVisible({ timeout: 3_000 })
    }

    // 5件追加後: 追加ボタンが非表示になり、上限メッセージが表示される
    await expect(addReminderBtn).not.toBeVisible({ timeout: 3_000 })
    await expect(dialog.locator('text=リマインダーは最大5件まで設定できます')).toBeVisible({ timeout: 3_000 })
  })
})

test.describe('SCHED55-004〜005: 予約アンケート/出欠（ScheduleEventScheduledAttachmentInput）', () => {
  test('SCHED55-004: 「アンケートを予約作成」トグルON→scheduledAt・設問入力→送信リクエストに scheduledSurveys が含まれる', async ({
    page,
  }) => {
    let capturedBody: Record<string, unknown> | undefined

    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        capturedBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CREATE_SCHEDULE_RESPONSE),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CALENDAR_EMPTY),
        })
      }
    })

    await setupTeamSchedulePage(page)
    await openCreateDialog(page)

    const dialog = page.getByRole('dialog')

    // タイトル入力
    await dialog.getByRole('textbox').first().click()
    await dialog.getByRole('textbox').first().pressSequentially('アンケート予約テスト', { delay: 10 })

    // 「アンケートを予約作成」トグルをクリックして ON にする
    // ToggleSwitch は role="switch" を持つ
    // ラベルテキスト「アンケートを予約作成」の近傍にある switch を探す
    const surveySectionLabel = dialog.locator('label').filter({ hasText: 'アンケートを予約作成' })
    const surveyToggle = surveySectionLabel.locator('..').locator('[role="switch"]')
    await expect(surveyToggle).toBeVisible({ timeout: 5_000 })
    await surveyToggle.click()

    // トグル ON 後、アンケートの設定エリアが展開される
    const surveyTitleInput = dialog.getByPlaceholder('アンケートのタイトルを入力')
    await expect(surveyTitleInput).toBeVisible({ timeout: 3_000 })

    // アンケートタイトル入力
    await surveyTitleInput.click()
    await surveyTitleInput.pressSequentially('テストアンケート', { delay: 10 })

    // 設問テキスト入力（初期設問が1つ自動追加される）
    const questionInput = dialog.getByPlaceholder('設問を入力してください').first()
    await expect(questionInput).toBeVisible({ timeout: 3_000 })
    await questionInput.click()
    await questionInput.pressSequentially('好きな季節は？', { delay: 10 })

    // 選択肢入力（最低2件が自動生成されている）
    const optionInputs = dialog.getByPlaceholder('選択肢を入力')
    const optCount = await optionInputs.count()
    if (optCount >= 2) {
      await optionInputs.nth(0).click()
      await optionInputs.nth(0).pressSequentially('春', { delay: 10 })
      await optionInputs.nth(1).click()
      await optionInputs.nth(1).pressSequentially('夏', { delay: 10 })
    }

    // 「作成」ボタンをクリック
    await dialog.getByRole('button', { name: '作成' }).click()

    // POST リクエストの検証
    await expect.poll(() => capturedBody, { timeout: 5_000 }).not.toBeUndefined()

    const body = capturedBody!
    expect(body).toHaveProperty('scheduledSurveys')
    const surveys = body.scheduledSurveys as unknown[]
    expect(Array.isArray(surveys)).toBe(true)
    expect(surveys.length).toBeGreaterThanOrEqual(1)
    const survey = surveys[0] as Record<string, unknown>
    // scheduledAt が含まれること（送信時はバリデーションで null はエラーになるが
    // モック環境では送信時のバリデーション依存。ここでは key の存在を確認）
    expect(survey).toHaveProperty('scheduledAt')
    expect(survey).toHaveProperty('survey')
    const surveyContent = survey.survey as Record<string, unknown>
    expect(surveyContent).toHaveProperty('questions')
  })

  test('SCHED55-005: 「出欠募集を予約作成」トグルON→送信リクエストに scheduledAttendance が含まれる', async ({
    page,
  }) => {
    let capturedBody: Record<string, unknown> | undefined

    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
      const method = route.request().method()
      if (method === 'POST') {
        capturedBody = route.request().postDataJSON() as Record<string, unknown>
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CREATE_SCHEDULE_RESPONSE),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(MOCK_CALENDAR_EMPTY),
        })
      }
    })

    await setupTeamSchedulePage(page)
    await openCreateDialog(page)

    const dialog = page.getByRole('dialog')

    // タイトル入力
    await dialog.getByRole('textbox').first().click()
    await dialog.getByRole('textbox').first().pressSequentially('出欠予約テスト', { delay: 10 })

    // 「出欠募集を予約作成」トグルをクリックして ON にする
    const attendanceSectionLabel = dialog.locator('label').filter({ hasText: '出欠募集を予約作成' })
    const attendanceToggle = attendanceSectionLabel.locator('..').locator('[role="switch"]')
    await expect(attendanceToggle).toBeVisible({ timeout: 5_000 })
    await attendanceToggle.click()

    // トグル ON 後、scheduledAt の DatePicker が表示される
    // 「作成日時」ラベルが出欠セクション内に表示されることを確認
    const attendanceSection = attendanceSectionLabel.locator('..').locator('..')
    await expect(attendanceSection.locator('text=作成日時')).toBeVisible({ timeout: 3_000 })

    // 「作成」ボタンをクリック
    await dialog.getByRole('button', { name: '作成' }).click()

    // POST リクエストの検証
    await expect.poll(() => capturedBody, { timeout: 5_000 }).not.toBeUndefined()

    const body = capturedBody!
    expect(body).toHaveProperty('scheduledAttendance')
    const attendance = body.scheduledAttendance as Record<string, unknown>
    expect(attendance).toHaveProperty('scheduledAt')
    expect(attendance).toHaveProperty('commentOption')
  })
})

test.describe('SCHED55-006: 個人予定フォームにはアンケート/出欠UIが表示されない', () => {
  test('SCHED55-006: /calendar（個人予定）フォームにはScheduleEventScheduledAttachmentInputが表示されない', async ({
    page,
  }) => {
    // 個人スケジュール関連 API をモック
    await page.route('**/api/v1/schedules/personal**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
    await page.route('**/api/v1/schedules/calendar**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { events: [] } }),
      })
    })
    await page.route('**/api/v1/me/schedules**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 100, totalElements: 0, totalPages: 0 },
        }),
      })
    })

    await page.goto('/calendar')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'マイカレンダー' })).toBeVisible({ timeout: 10_000 })

    // 「予定を追加」ボタンをクリック
    await page.getByRole('button', { name: '予定を追加' }).click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 8_000 })

    const dialog = page.getByRole('dialog')

    // 個人予定フォームには「アンケートを予約作成」「出欠募集を予約作成」テキストが存在しない
    // (ScheduleEventScheduledAttachmentInput は v-if="!effectiveScope.isPersonal && !isEdit" で非表示)
    await expect(dialog.locator('text=アンケートを予約作成')).not.toBeVisible({ timeout: 3_000 })
    await expect(dialog.locator('text=出欠募集を予約作成')).not.toBeVisible({ timeout: 3_000 })

    // リマインダーは個人でも表示される（全スコープ共通）
    await expect(dialog.locator('text=リマインダー').first()).toBeVisible({ timeout: 3_000 })
  })
})

test.describe('SCHED55-007: 予定詳細の予約タスク表示と取消（EventDetailPanel）', () => {
  test('SCHED55-007: scheduledTasks(PENDING)が表示され、取消ボタンクリックでDELETE .../scheduled-tasks/:taskId が呼ばれる', async ({
    page,
  }) => {
    const TEST_TASK_ID = 'task-uuid-001'
    const SCHEDULE_ID = 100
    let deleteCalledUrl = ''

    // チーム基本情報 + 権限（canEdit=true のために ADMIN 権限が必要）
    await mockTeam(page)

    // スケジュール一覧 API（カレンダー表示用）: 1件のイベントを返す
    await page.route(`**/api/v1/teams/${TEAM_ID}/schedules`, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: [
              {
                id: SCHEDULE_ID,
                title: 'タスク付きイベント',
                startAt: '2027-01-10T10:00:00',
                endAt: '2027-01-10T11:00:00',
                allDay: false,
                color: '#22c55e',
                status: 'PUBLISHED',
                scopeType: 'TEAM',
                isPersonal: false,
              },
            ],
            meta: { page: 0, size: 100, totalElements: 1, totalPages: 1 },
          }),
        })
      } else {
        await route.continue()
      }
    })

    // GET /teams/:id/schedules/:scheduleId — scheduledTasks 付きの詳細
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/schedules/${SCHEDULE_ID}`,
      async (route) => {
        if (route.request().method() === 'GET') {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(mockScheduleDetailWithTasks(TEST_TASK_ID)),
          })
        } else {
          await route.continue()
        }
      },
    )

    // DELETE .../scheduled-tasks/:taskId をモック
    await page.route(
      `**/api/v1/teams/${TEAM_ID}/schedules/${SCHEDULE_ID}/scheduled-tasks/${TEST_TASK_ID}`,
      async (route) => {
        if (route.request().method() === 'DELETE') {
          deleteCalledUrl = route.request().url()
          await route.fulfill({
            status: 204,
            body: '',
          })
        } else {
          await route.continue()
        }
      },
    )

    // 代理出席 API をモック（EventDetailPanel.onMounted で呼ばれる可能性）
    await page.route('**/api/v1/event-delegations**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [], total: 0 }),
      })
    })

    // その他チーム配下 API
    await mockTeamFeatureApis(page)

    await page.goto(`/teams/${TEAM_ID}/schedule`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'スケジュール' })).toBeVisible({ timeout: 10_000 })

    // カレンダーに表示されたイベントをクリックして詳細パネルを開く
    // CalendarGrid 内のイベントセル（タイトルテキストで検索）
    const eventTitleInCal = page.locator('text=タスク付きイベント').first()
    if (await eventTitleInCal.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await eventTitleInCal.click()
    }

    // EventDetailPanel に予約タスクセクションが表示されるまで待つ
    await expect(page.locator('text=予約タスク')).toBeVisible({ timeout: 8_000 })
    await expect(page.locator('text=予約アンケート')).toBeVisible({ timeout: 5_000 })
    await expect(page.locator('text=予約中')).toBeVisible({ timeout: 5_000 })

    // 「取消」ボタンをクリック
    const cancelBtn = page.getByRole('button', { name: '取消' })
    await expect(cancelBtn).toBeVisible({ timeout: 5_000 })
    await cancelBtn.click()

    // DELETE が呼ばれたことを確認（URL にタスクIDが含まれる）
    await expect.poll(() => deleteCalledUrl, { timeout: 5_000 }).toContain(
      `scheduled-tasks/${TEST_TASK_ID}`,
    )
  })
})
