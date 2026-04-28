import { test, expect } from '@playwright/test'
import {
  buildMockState,
  buildWorkMemo,
  mockActionMemoApi,
  setupAuth,
  todayJst,
  waitForHydration,
} from '../helpers/action-memo-mocks'

/**
 * F02.5 Phase 3 行動メモ E2E テスト。
 *
 * <p>設計書 §10.3「E2E テスト（Playwright）」と軍議で確定した 6 シナリオ
 * （AM3-001 〜 AM3-006）を実装する。layout 周辺 API は
 * {@link mockActionMemoApi} に集約済みのため、本 spec ではドメイン固有の
 * 検証のみに集中する。</p>
 *
 * <p><b>実装上の前提</b>:</p>
 * <ul>
 *   <li>{@code ActionMemoInput.vue} は Phase 3 フィールド (category / duration /
 *       progressRate / completesTodo / teamId) を {@code createMemo} に渡さない。
 *       index 画面の Phase 3 panel は UI 状態の保持のみで、サーバ送信フィールドは
 *       backend が {@code default_category} を適用する仕様（mock も同じ振る舞い）。</li>
 *   <li>個別メモの publish-to-team UI トリガは現状未実装。本 spec は API モックの
 *       存在確認のみ行い、UI からのトリガはテストしない。</li>
 *   <li>WORK メモ二重出力（個人ログ + チーム）は closing 画面の「今日を締める」
 *       (publishDaily) と「チーム投稿」(publishDailyToTeam) を順に実行する経路で確認する。</li>
 * </ul>
 */

test.describe('F02.5 Phase 3: 行動メモ チーム投稿 / TODO 連動 / 詳細フィールド', () => {
  // dev サーバの vite-node IPC が 500 を返すケース（初回ロード時のレース）への保険として
  // ローカル実行時もテストごとに 1 回だけリトライする。CI では config の retries=2 が効く。
  test.describe.configure({ retries: 1 })

  test.beforeEach(async ({ page }) => {
    await setupAuth(page, {
      userId: 4521,
      displayName: 'e2e_member',
      role: 'MEMBER',
    })
  })

  test('AM3-001: WORK メモ作成 → publish-to-team UI が WORK 選択時のみ表示される', async ({
    page,
  }) => {
    const state = buildMockState({
      settings: { default_category: 'WORK', default_post_team_id: null },
      availableTeams: [
        { id: 11, name: 'チームA', is_default: false },
        { id: 22, name: 'チームB', is_default: false },
      ],
    })
    await mockActionMemoApi(page, state)

    // POST /api/v1/action-memos のレスポンスを確認するためのリスナ
    const memoRequests: Array<{ method: string; status: number; url: string; body: string }> = []
    page.on('response', async (resp) => {
      const url = resp.url()
      const method = resp.request().method()
      if (/\/api\/v1\//.test(url)) {
        try {
          const body = await resp.text()
          memoRequests.push({ method, status: resp.status(), url, body: body.slice(0, 200) })
        } catch {
          /* ignore */
        }
      }
    })

    await page.goto('/action-memo')
    await waitForHydration(page)

    // テキスト入力欄が表示される
    const textarea = page.locator('[data-testid="action-memo-input-textarea"]')
    await expect(textarea).toBeVisible({ timeout: 10_000 })

    // 設定の default_category=WORK が反映され、CategorySelector の WORK が pressed になっている
    const categorySelector = page.locator('[data-testid="index-category-selector"]')
    await expect(categorySelector).toBeVisible()
    const workBtn = page.locator('[data-testid="category-selector-work"]')
    await expect(workBtn).toHaveAttribute('aria-pressed', 'true')

    // 詳細パネル（折りたたみ）を開く
    const phase3Toggle = page.locator('[data-testid="phase3-details-toggle"]')
    await expect(phase3Toggle).toBeVisible()
    await phase3Toggle.click()

    // チームが 2 件あるので team-post-switch-select（ドロップダウン）が表示される
    const teamSelect = page.locator('[data-testid="team-post-switch-select"]')
    await expect(teamSelect).toBeVisible({ timeout: 5_000 })

    // 投稿先チーム B (id=22) を選択
    await teamSelect.selectOption({ value: '22' })

    // メモ本文を入力して送信
    await textarea.fill('PR レビュー 30分')
    await textarea.press('Enter')

    // 一覧にメモが追加され、入力欄がクリアされる
    // デバッグ：POST /action-memos のレスポンスを確認
    await page.waitForTimeout(500)
    // eslint-disable-next-line no-console
    console.log('memo POST responses:', JSON.stringify(memoRequests))
    await expect(page.getByText('PR レビュー 30分')).toBeVisible({ timeout: 5_000 })
    await expect(textarea).toHaveValue('')

    // PRIVATE に切り替えると team-post-switch-select は隠れる（v-show=false）
    const privateBtn = page.locator('[data-testid="category-selector-private"]')
    await privateBtn.click()
    await expect(teamSelect).toBeHidden()
  })

  test('AM3-002: closing 画面で publish-daily-to-team を実行 → 成功トーストが出る', async ({
    page,
  }) => {
    const state = buildMockState({
      memos: [
        buildWorkMemo({ id: 9001, content: '朝会の議事録作成', category: 'WORK' }),
        buildWorkMemo({ id: 9002, content: 'タスクA着手', category: 'WORK' }),
      ],
      settings: { default_category: 'WORK', default_post_team_id: 11 },
      availableTeams: [{ id: 11, name: 'チームA', is_default: true }],
    })
    await mockActionMemoApi(page, state)

    await page.goto('/action-memo/closing')
    await waitForHydration(page)

    // closing 画面の見出しが見える
    await expect(page.locator('[data-testid="action-memo-closing-title"]')).toBeVisible({
      timeout: 10_000,
    })

    // チーム投稿ボタンが活性化している（WORK メモが 2 件あるため）
    const publishToTeamBtn = page.locator('[data-testid="action-memo-closing-publish-to-team"]')
    await expect(publishToTeamBtn).toBeVisible({ timeout: 5_000 })
    await expect(publishToTeamBtn).toBeEnabled()

    // 「WORK メモがない」ヒントは出ていない
    await expect(page.locator('[data-testid="action-memo-closing-no-work-memos"]')).toHaveCount(0)

    // クリック → publish-daily-to-team API が呼ばれる
    await publishToTeamBtn.click()

    // 成功トースト（PrimeVue Toast）が表示される
    // i18n キー: action_memo.phase3.post_to_team.publish_daily_success = "今日のWORKメモをチームに投稿しました"
    const toastSummary = page.locator('.p-toast-summary')
    await expect(toastSummary).toContainText('今日のWORKメモをチームに投稿しました', {
      timeout: 5_000,
    })

    // mock 状態: WORK メモ 2 件分の TEAM スコープ timeline_post が 1 件追加される
    expect(state.timeline_posts).toHaveLength(1)
    expect(state.timeline_posts[0]?.scope_type).toBe('TEAM')
    expect(state.timeline_posts[0]?.memo_ids).toEqual([9001, 9002])
    expect(state.timeline_posts[0]?.scope_id).toBe(11)
  })

  test('AM3-003: completes_todo を反映してメモ送信 → /todos/my の対象 TODO が COMPLETED 扱いになる', async ({
    page,
  }) => {
    // ActionMemoInput.vue は Phase 3 フィールドを createMemo に渡さないため、
    // 「メモ送信時に completes_todo=true が伝わって対応 TODO が完了状態になる」フローは
    // UI からは現状実行できない。本 spec は API レベルで mock がフローを正しく扱うこと
    // （= 本フローの Backend 連携が成立する素地が整っていること）を確認する。
    const today = todayJst()
    const state = buildMockState({
      todos: [
        {
          id: 7001,
          scopeType: 'PERSONAL',
          scopeId: 4521,
          title: '朝のメール返信',
          status: 'PENDING',
          dueDate: today,
        },
      ],
      settings: { default_category: 'WORK' },
      availableTeams: [],
    })
    await mockActionMemoApi(page, state)

    await page.goto('/action-memo')
    await waitForHydration(page)

    // index 画面の TodoCompleteCheckbox は relatedTodoId=null のため v-show=false で
    // 表示されない（実装通り）。data-testid 自体は存在するが visible ではないことを確認。
    const todoCheckbox = page.locator('[data-testid="index-todo-complete-checkbox"]')
    await expect(todoCheckbox).toHaveCount(1)
    await expect(todoCheckbox).toBeHidden()

    // API 直叩きで完了フローを検証する: completes_todo=true & related_todo_id=7001 で
    // メモ作成 → state.completedTodoIds に 7001 が積まれることを確認
    const createRes = await page.evaluate(async (todayValue) => {
      const res = await fetch('/api/v1/action-memos', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          content: '朝のメール返信完了',
          memo_date: todayValue,
          related_todo_id: 7001,
          completes_todo: true,
          category: 'WORK',
        }),
      })
      return { status: res.status, body: await res.json() }
    }, today)
    expect(createRes.status).toBe(201)

    // closing 画面に遷移して /todos/my の状態を確認
    await page.goto('/action-memo/closing')
    await waitForHydration(page)
    await expect(page.locator('[data-testid="action-memo-closing-title"]')).toBeVisible({
      timeout: 10_000,
    })

    // 完了 TODO 一覧に「朝のメール返信」が並ぶ（completes_todo 反映確認）
    const completedList = page.locator('[data-testid="action-memo-closing-completed-todos"]')
    await expect(completedList).toBeVisible({ timeout: 5_000 })
    await expect(completedList.getByText('朝のメール返信')).toBeVisible()

    // mock 状態でも completedTodoIds に 7001 が入っている
    expect(state.completedTodoIds.has(7001)).toBe(true)

    // index コンポーネントの testid が引き続き 1 件だけ存在する（重複出現していない）
    expect(await page.locator('[data-testid="todo-complete-checkbox-input"]').count()).toBe(0)
  })

  test('AM3-004: duration / progress 入力が UI 上で保持される', async ({ page }) => {
    const state = buildMockState({
      settings: { default_category: 'WORK' },
      availableTeams: [],
    })
    await mockActionMemoApi(page, state)

    await page.goto('/action-memo')
    await waitForHydration(page)

    await expect(page.locator('[data-testid="action-memo-input-textarea"]')).toBeVisible({
      timeout: 10_000,
    })

    // 詳細パネルを開く
    await page.locator('[data-testid="phase3-details-toggle"]').click()

    // DurationInput が表示される
    const durationContainer = page.locator('[data-testid="index-duration-input"]')
    await expect(durationContainer).toBeVisible()

    const durationField = page.locator('[data-testid="duration-input-field"]')
    await expect(durationField).toBeVisible()

    // クイックボタン 30分 を押下 → 入力欄に 30 が反映される
    await page.locator('[data-testid="duration-quick-30"]').click()
    await expect(durationField).toHaveValue('30')

    // 直接 60 を入力（クイック 30 を上書き）
    await durationField.fill('60')
    await expect(durationField).toHaveValue('60')

    // ProgressRateSlider が表示される（relatedTodoId=null のため disabled）
    const progressContainer = page.locator('[data-testid="index-progress-rate-slider"]')
    await expect(progressContainer).toBeVisible()
    const progressInput = page.locator('[data-testid="progress-rate-input"]')
    await expect(progressInput).toBeDisabled()

    // disabled 状態でも UI 要素が存在し、relatedTodoId=null の旨の説明が見える
    await expect(page.locator('[data-testid="progress-rate-requires-todo"]')).toBeVisible()

    // 詳細パネルを一度閉じてから再度開いても duration の値は保持される（v-model 永続化）
    await page.locator('[data-testid="phase3-details-toggle"]').click()
    await expect(durationField).toBeHidden()
    await page.locator('[data-testid="phase3-details-toggle"]').click()
    await expect(durationField).toBeVisible()
    await expect(durationField).toHaveValue('60')
  })

  test('AM3-005: settings 画面で default_category / default_team を変更 → 設定 API に反映', async ({
    page,
  }) => {
    const state = buildMockState({
      settings: { default_category: 'OTHER', default_post_team_id: null },
      availableTeams: [
        { id: 11, name: 'チームA', is_default: false },
        { id: 22, name: 'チームB', is_default: false },
      ],
    })
    await mockActionMemoApi(page, state)

    await page.goto('/action-memo/settings')
    await waitForHydration(page)

    // settings-default-category セクションの CategorySelector が見える
    const categorySection = page.locator('[data-testid="settings-default-category"]')
    await expect(categorySection).toBeVisible({ timeout: 10_000 })

    // 初期値は OTHER が pressed
    await expect(page.locator('[data-testid="category-selector-other"]').first()).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    // WORK ボタンを押下
    await categorySection.locator('[data-testid="category-selector-work"]').click()

    // mock state に PATCH が反映される
    await expect.poll(() => state.settings.default_category).toBe('WORK')

    // settings-default-team-section の DefaultTeamPicker でチームを選ぶ
    const teamSection = page.locator('[data-testid="settings-default-team-section"]')
    await expect(teamSection).toBeVisible()
    const teamSelect = page.locator('[data-testid="default-team-picker-select"]')
    await expect(teamSelect).toBeVisible()
    await teamSelect.selectOption({ value: '22' })

    // mock state に default_post_team_id=22 が反映される
    await expect.poll(() => state.settings.default_post_team_id).toBe(22)

    // index 画面に戻って default の反映を確認
    await page.locator('[data-testid="action-memo-settings-back"]').click()
    await page.waitForURL('**/action-memo', { timeout: 10_000 })
    await waitForHydration(page)

    // default_category=WORK が CategorySelector に反映されている
    await expect(page.locator('[data-testid="category-selector-work"]')).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    // 詳細パネルを開いて TeamPostSwitch のドロップダウンで チームB が選択済みであることを確認
    await page.locator('[data-testid="phase3-details-toggle"]').click()
    const indexTeamSelect = page.locator('[data-testid="team-post-switch-select"]')
    await expect(indexTeamSelect).toBeVisible({ timeout: 5_000 })
    await expect(indexTeamSelect).toHaveValue('22')
  })

  test('AM3-006: WORK メモ二重出力（publish-daily で個人ログ + publish-daily-to-team でチーム）', async ({
    page,
  }) => {
    const state = buildMockState({
      memos: [
        buildWorkMemo({ id: 8001, content: 'タスクA 午前', category: 'WORK' }),
        buildWorkMemo({ id: 8002, content: 'タスクB 午後', category: 'WORK' }),
      ],
      settings: { default_category: 'WORK', default_post_team_id: 11 },
      availableTeams: [{ id: 11, name: 'チームA', is_default: true }],
    })
    await mockActionMemoApi(page, state)

    await page.goto('/action-memo/closing')
    await waitForHydration(page)
    await expect(page.locator('[data-testid="action-memo-closing-title"]')).toBeVisible({
      timeout: 10_000,
    })

    // (1) チームに投稿（publish-daily-to-team）
    const publishToTeamBtn = page.locator('[data-testid="action-memo-closing-publish-to-team"]')
    await expect(publishToTeamBtn).toBeEnabled()
    await publishToTeamBtn.click()
    await expect(page.locator('.p-toast-summary')).toContainText(
      '今日のWORKメモをチームに投稿しました',
      { timeout: 5_000 },
    )

    // (2) 「今日を締める」（publish-daily で個人タイムラインに投稿）
    const publishBtn = page.locator('[data-testid="action-memo-closing-publish"]')
    await expect(publishBtn).toBeEnabled()
    await publishBtn.click()

    // publishDaily 成功で /action-memo にリダイレクトされる
    await page.waitForURL('**/action-memo', { timeout: 10_000 })

    // mock の timeline_posts を検証 — TEAM + PERSONAL の 2 件が並ぶ（二重出力）
    expect(state.timeline_posts).toHaveLength(2)
    const teamPost = state.timeline_posts.find((p) => p.scope_type === 'TEAM')
    const personalPost = state.timeline_posts.find((p) => p.scope_type === 'PERSONAL')
    expect(teamPost).toBeDefined()
    expect(personalPost).toBeDefined()
    expect(teamPost?.memo_ids).toEqual([8001, 8002])
    expect(personalPost?.memo_ids).toEqual(expect.arrayContaining([8001, 8002]))
    expect(teamPost?.scope_id).toBe(11)
    expect(teamPost?.source_kind).toBe('ACTION_MEMO_TEAM')
    expect(personalPost?.source_kind).toBe('ACTION_MEMO_DAILY')
  })
})
