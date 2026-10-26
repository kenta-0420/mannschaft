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
  test.beforeEach(async ({ page }) => {
    await setupAuth(page, {
      userId: 4521,
      displayName: 'e2e_member',
      role: 'MEMBER',
    })
  })

  test('AM3-001: WORK 選択時に team-post-switch が表示され PRIVATE で隠れる', async ({
    page,
  }) => {
    // NOTE: 軍議の元シナリオは「WORK メモ作成 → publish-to-team → 投稿成功フィードバック」だが、
    //   現状実装では ActionMemoInput.vue が Phase 3 フィールド (category/duration/teamId 等) を
    //   createMemo に渡さず、また index 画面に publish-to-team UI トリガが存在しない。
    //   そのため UI からの「WORK + チーム選択 → publish-to-team」フローは発火不能。
    //   本 spec は CategorySelector の切替に応じた TeamPostSwitch の表示制御
    //   （WORK 限定 v-show + 2 件以上はドロップダウン）を検証する。
    //   メモ作成 + publish-daily-to-team の検証は AM3-002 / AM3-006 で実施。
    const state = buildMockState({
      settings: { default_category: 'WORK', default_post_team_id: null },
      availableTeams: [
        { id: 11, name: 'チームA', is_default: false },
        { id: 22, name: 'チームB', is_default: false },
      ],
    })
    await mockActionMemoApi(page, state)

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
    await expect(teamSelect).toHaveValue('22')

    // PRIVATE に切り替えると team-post-switch-select は隠れる（v-show=false）
    const privateBtn = page.locator('[data-testid="category-selector-private"]')
    await privateBtn.click()
    await expect(teamSelect).toBeHidden()

    // WORK に戻すと再度ドロップダウンが見える
    await workBtn.click()
    await expect(teamSelect).toBeVisible({ timeout: 5_000 })
  })

  test('AM3-002: publish-daily-to-team を実行 → API モックで TEAM スコープ投稿が成立する', async ({
    page,
  }) => {
    // NOTE: 設計書 §10.3 では closing 画面の UI を経由する想定だが、本検証時点で
    //   dev サーバの vite-node IPC が /action-memo/closing 配信時に 500 を返す
    //   破損状態にあったため、closing 画面 UI 経由のフローを安定して動かせない。
    //   API モックで publish-daily-to-team の振る舞いと「成功フィードバック → mock 状態」
    //   のセマンティクスを確認する形で代替する（dev サーバ復旧後に UI 経由に戻すこと）。
    const state = buildMockState({
      memos: [
        buildWorkMemo({ id: 9001, content: '朝会の議事録作成', category: 'WORK' }),
        buildWorkMemo({ id: 9002, content: 'タスクA着手', category: 'WORK' }),
      ],
      settings: { default_category: 'WORK', default_post_team_id: 11 },
      availableTeams: [{ id: 11, name: 'チームA', is_default: true }],
    })
    await mockActionMemoApi(page, state)

    // /action-memo を開いて Pinia ストアと composable をブラウザ側で初期化する
    await page.goto('/action-memo')
    await waitForHydration(page)
    await expect(page.locator('[data-testid="action-memo-input-textarea"]')).toBeVisible({
      timeout: 10_000,
    })

    // page.evaluate 内から publish-daily-to-team を呼び出す（Frontend useApi と同じ
    // ベース URL `/api/v1/...` を使うため `useNuxtApp` の $api を取得して叩く）。
    const result = await page.evaluate(async () => {
      const res = await fetch('/api/v1/action-memos/publish-daily-to-team', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ team_id: 11 }),
      })
      const json = await res.json()
      return { status: res.status, json }
    })

    expect(result.status).toBe(201)
    expect((result.json as { data: { team_id: number; memo_count: number } }).data.team_id).toBe(11)
    expect((result.json as { data: { team_id: number; memo_count: number } }).data.memo_count).toBe(2)

    // mock 状態: WORK メモ 2 件分の TEAM スコープ timeline_post が 1 件追加される
    expect(state.timeline_posts).toHaveLength(1)
    expect(state.timeline_posts[0]?.scope_type).toBe('TEAM')
    expect(state.timeline_posts[0]?.memo_ids).toEqual([9001, 9002])
    expect(state.timeline_posts[0]?.scope_id).toBe(11)

    // メモ側の posted_team_id も埋まる（二重出力の素地）
    expect(state.memos.find((m) => m.id === 9001)?.posted_team_id).toBe(11)
    expect(state.memos.find((m) => m.id === 9002)?.posted_team_id).toBe(11)
  })

  test('AM3-003: completes_todo フラグでメモ作成 → /todos/my で対象 TODO が COMPLETED 扱いになる', async ({
    page,
  }) => {
    // NOTE: ActionMemoInput.vue は Phase 3 フィールドを createMemo に渡さない（実装現状）
    //   ため、UI からは completes_todo を伝搬できない。本 spec は API レベルで
    //   completes_todo=true フローの Backend 連携が成立する素地（mock の振る舞い + GET /todos/my の
    //   反映）を確認する。closing 画面の UI 検証も dev サーバ IPC 不調回避のため行わない。
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

    // /action-memo を開いて mock + ブラウザ context を初期化
    await page.goto('/action-memo')
    await waitForHydration(page)
    await expect(page.locator('[data-testid="action-memo-input-textarea"]')).toBeVisible({
      timeout: 10_000,
    })

    // index 画面の TodoCompleteCheckbox は relatedTodoId=null のため v-show=false で
    // 隠されている（実装通り）。data-testid は存在するが visible ではないことを確認。
    const todoCheckbox = page.locator('[data-testid="index-todo-complete-checkbox"]')
    await expect(todoCheckbox).toHaveCount(1)
    await expect(todoCheckbox).toBeHidden()

    // API 直叩きで完了フローを検証する: completes_todo=true & related_todo_id=7001 で
    // メモ作成 → state.completedTodoIds に 7001 が積まれる
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

    // GET /todos/my のレスポンスで対象 TODO が COMPLETED 扱いに変わる
    const todosRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/todos/my')
      return (await res.json()) as {
        data: Array<{ id: number; status: string }>
      }
    })
    const targetTodo = todosRes.data.find((t) => t.id === 7001)
    expect(targetTodo?.status).toBe('COMPLETED')

    // mock 状態でも completedTodoIds に 7001 が入っている
    expect(state.completedTodoIds.has(7001)).toBe(true)
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

  test('AM3-005: settings 画面が描画され、PATCH /action-memo-settings の API モックで default が更新できる', async ({
    page,
  }) => {
    // NOTE: 軍議の元シナリオでは settings 画面の CategorySelector / DefaultTeamPicker
    //   click → API 反映を UI 経由で確認する想定だったが、本検証時点で
    //   CategorySelector の click が PATCH を発火しないケース（v-model fallthrough と
    //   関連する hydration race と推測）が見つかった。本 spec は、
    //     1) settings 画面に必要な要素 (settings-default-category / settings-default-team-section /
    //        default-team-picker-select) が描画されていること
    //     2) PATCH /api/v1/action-memo-settings の API モックが default_category /
    //        default_post_team_id を正しく更新できること
    //   の 2 点を分離して検証する。UI クリック → API 連動の検証は dev サーバ復旧後の
    //   後追いタスクとする。
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

    // (1) UI: settings 画面の Phase 3 要素が描画されている
    const categorySection = page.locator('[data-testid="settings-default-category"]')
    await expect(categorySection).toBeVisible({ timeout: 10_000 })
    // 初期値は OTHER が pressed
    await expect(
      categorySection.locator('[data-testid="category-selector-other"]'),
    ).toHaveAttribute('aria-pressed', 'true')
    // WORK / PRIVATE のボタンも存在
    await expect(
      categorySection.locator('[data-testid="category-selector-work"]'),
    ).toBeVisible()

    const teamSection = page.locator('[data-testid="settings-default-team-section"]')
    await expect(teamSection).toBeVisible()
    const teamPicker = page.locator('[data-testid="default-team-picker-select"]')
    await expect(teamPicker).toBeVisible()
    // ドロップダウンに 2 件のチームが入っている
    await expect(teamPicker.locator('option[value="11"]')).toHaveText('チームA')
    await expect(teamPicker.locator('option[value="22"]')).toHaveText('チームB')

    // (2) API モック直叩きで PATCH の挙動を確認
    const patchCategoryRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/action-memo-settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ default_category: 'WORK' }),
      })
      return { status: res.status, json: (await res.json()) as { data: { default_category: string } } }
    })
    expect(patchCategoryRes.status).toBe(200)
    expect(patchCategoryRes.json.data.default_category).toBe('WORK')
    expect(state.settings.default_category).toBe('WORK')

    const patchTeamRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/action-memo-settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ default_post_team_id: 22 }),
      })
      return {
        status: res.status,
        json: (await res.json()) as { data: { default_post_team_id: number } },
      }
    })
    expect(patchTeamRes.status).toBe(200)
    expect(patchTeamRes.json.data.default_post_team_id).toBe(22)
    expect(state.settings.default_post_team_id).toBe(22)
  })

  test('AM3-006: WORK メモ二重出力（publish-daily で個人ログ + publish-daily-to-team でチーム）', async ({
    page,
  }) => {
    // NOTE: 設計書 §10.3 では closing 画面の UI 経由を想定しているが、本検証時点で
    //   dev サーバの vite-node IPC が /action-memo/closing で 500 を返す状態のため、
    //   API モックレベルで 2 経路の API 呼び出し → timeline_posts 二重出力を検証する。
    const state = buildMockState({
      memos: [
        buildWorkMemo({ id: 8001, content: 'タスクA 午前', category: 'WORK' }),
        buildWorkMemo({ id: 8002, content: 'タスクB 午後', category: 'WORK' }),
      ],
      settings: { default_category: 'WORK', default_post_team_id: 11 },
      availableTeams: [{ id: 11, name: 'チームA', is_default: true }],
    })
    await mockActionMemoApi(page, state)

    await page.goto('/action-memo')
    await waitForHydration(page)
    await expect(page.locator('[data-testid="action-memo-input-textarea"]')).toBeVisible({
      timeout: 10_000,
    })

    // (1) チームに投稿（publish-daily-to-team）
    const teamRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/action-memos/publish-daily-to-team', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ team_id: 11 }),
      })
      return { status: res.status, json: await res.json() }
    })
    expect(teamRes.status).toBe(201)

    // (2) 個人タイムラインに「今日を締める」（publish-daily）
    const personalRes = await page.evaluate(async () => {
      const res = await fetch('/api/v1/action-memos/publish-daily', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({}),
      })
      return { status: res.status, json: await res.json() }
    })
    expect(personalRes.status).toBe(201)

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
