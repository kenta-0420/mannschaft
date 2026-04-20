import { test, expect, type Page, type Route } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F02.7 個人プロジェクトページ E2E テスト（MY-PROJ-001〜003）
 *
 * バックエンド起動を前提とせず、Playwright の route.fulfill で
 * `/api/v1/users/me/projects/...` を完全モックし、
 * 個人スコープでのゲート機能・権限判定を検証する。
 *
 * 既存 milestone-gate.spec.ts の書式に準拠:
 * - mockAuth で localStorage にダミートークンを投入
 * - page.route で個別 API をモック
 * - waitForHydration でハイドレーション完了を待機してから assert
 */

const PROJECT_ID = 500
const MILESTONE_A_ID = 601
const MILESTONE_B_ID = 602
const SELF_USER_ID = 1
const OTHER_USER_ID = 999

// ======== データ生成 ========

function buildOwnProject(): Record<string, unknown> {
  return {
    id: PROJECT_ID,
    title: '夏休みの宿題',
    emoji: '📚',
    color: '#4ECDC4',
    dueDate: '2026-08-31',
    daysRemaining: 133,
    status: 'ACTIVE',
    progressRate: 0.25,
    totalTodos: 12,
    completedTodos: 3,
    milestones: { total: 2, completed: 0 },
    createdBy: { id: SELF_USER_ID, displayName: 'e2e管理者' },
    createdAt: '2026-04-01T00:00:00Z',
  }
}

function buildForeignProject(): Record<string, unknown> {
  // 通常 /my/projects は自分のプロジェクトのみだが、
  // 権限チェックのエッジケース（共有閲覧等）を想定して他者作成プロジェクトを返す
  return {
    ...buildOwnProject(),
    createdBy: { id: OTHER_USER_ID, displayName: '他人ユーザー' },
  }
}

function buildMilestones() {
  return [
    {
      id: MILESTONE_A_ID,
      projectId: PROJECT_ID,
      title: '国語ドリル',
      dueDate: '2026-07-31',
      sortOrder: 0,
      completed: false,
      completedAt: null,
      createdAt: '2026-04-01T00:00:00Z',
      updatedAt: '2026-04-01T00:00:00Z',
      progressRate: 30.0,
      isLocked: false,
      lockedByMilestoneId: null,
      lockedByMilestoneTitle: null,
      completionMode: 'AUTO' as const,
      lockedTodoCount: 0,
      forceUnlocked: false,
      lockedAt: null,
      unlockedAt: null,
    },
    {
      id: MILESTONE_B_ID,
      projectId: PROJECT_ID,
      title: '算数ドリル',
      dueDate: '2026-08-15',
      sortOrder: 1,
      completed: false,
      completedAt: null,
      createdAt: '2026-04-01T00:00:00Z',
      updatedAt: '2026-04-01T00:00:00Z',
      progressRate: 0.0,
      isLocked: true,
      lockedByMilestoneId: MILESTONE_A_ID,
      lockedByMilestoneTitle: '国語ドリル',
      completionMode: 'AUTO' as const,
      lockedTodoCount: 2,
      forceUnlocked: false,
      lockedAt: '2026-04-01T00:00:00Z',
      unlockedAt: null,
    },
  ]
}

function buildGatesSummary() {
  return {
    projectId: PROJECT_ID,
    overallProgressRate: 25.0,
    gateCompletionRate: 0.0,
    totalMilestones: 2,
    completedMilestones: 0,
    lockedMilestones: 1,
    nextGate: {
      id: MILESTONE_B_ID,
      title: '算数ドリル',
      lockedReasonMilestoneId: MILESTONE_A_ID,
      lockedReasonMilestoneTitle: '国語ドリル',
      previousProgressRate: 30.0,
    },
    milestones: [
      {
        id: MILESTONE_A_ID,
        title: '国語ドリル',
        sortOrder: 0,
        isCompleted: false,
        isLocked: false,
        lockedByMilestoneId: null,
        lockedByMilestoneTitle: null,
        progressRate: 30.0,
        completionMode: 'AUTO' as const,
        totalTodos: 10,
        completedTodos: 3,
        lockedTodoCount: 0,
        lockedAt: null,
        completedAt: null,
      },
      {
        id: MILESTONE_B_ID,
        title: '算数ドリル',
        sortOrder: 1,
        isCompleted: false,
        isLocked: true,
        lockedByMilestoneId: MILESTONE_A_ID,
        lockedByMilestoneTitle: '国語ドリル',
        progressRate: 0.0,
        completionMode: 'AUTO' as const,
        totalTodos: 2,
        completedTodos: 0,
        lockedTodoCount: 2,
        lockedAt: '2026-04-01T00:00:00Z',
        completedAt: null,
      },
    ],
  }
}

// ======== モック／認証 ユーティリティ ========

/** E2E 用擬似認証情報を localStorage に投入（個人スコープは常に自分） */
async function mockAuth(page: Page): Promise<void> {
  await page.addInitScript(
    ({ userId }) => {
      localStorage.setItem(
        'accessToken',
        'eyJhbGciOiJIUzM4NCJ9.e2UyZV90ZXN0X3VzZXJ9.placeholder_for_e2e',
      )
      localStorage.setItem('refreshToken', 'e2e-refresh-token-placeholder')
      localStorage.setItem(
        'currentUser',
        JSON.stringify({
          id: userId,
          email: 'e2e@example.com',
          displayName: 'e2e管理者',
          profileImageUrl: null,
        }),
      )
    },
    { userId: SELF_USER_ID },
  )
}

/** 個人プロジェクト一覧・詳細・マイルストーン・ゲート・TODO をまとめてモック */
async function installMyProjectMocks(
  page: Page,
  project: Record<string, unknown>,
  milestones: ReturnType<typeof buildMilestones>,
  summary: ReturnType<typeof buildGatesSummary>,
): Promise<void> {
  // 一覧
  await page.route('**/api/v1/users/me/projects', async (route: Route) => {
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [project] }),
    })
  })

  // 詳細
  await page.route(`**/api/v1/users/me/projects/${PROJECT_ID}`, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: project }),
    })
  })

  // マイルストーン一覧
  await page.route(
    `**/api/v1/users/me/projects/${PROJECT_ID}/milestones`,
    async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: milestones }),
      })
    },
  )

  // ゲートサマリー
  await page.route(`**/api/v1/users/me/projects/${PROJECT_ID}/gates`, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: summary }),
    })
  })

  // プロジェクト内 TODO 一覧
  await page.route(`**/api/v1/users/me/projects/${PROJECT_ID}/todos`, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

// ======== テスト本体 ========

test.describe('F02.7 個人プロジェクトページ', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  test('MY-PROJ-001: 個人プロジェクト一覧が表示される', async ({ page }) => {
    const project = buildOwnProject()
    await installMyProjectMocks(page, project, buildMilestones(), buildGatesSummary())

    await page.goto('/my/projects')
    await waitForHydration(page)

    // 見出しが i18n キー（project.my_projects = "個人プロジェクト"）で表示される
    await expect(page.getByRole('heading', { name: '個人プロジェクト' })).toBeVisible({
      timeout: 15_000,
    })

    // プロジェクトカードが表示される
    const card = page.locator(`[data-testid="my-project-card-${PROJECT_ID}"]`)
    await expect(card).toBeVisible({ timeout: 10_000 })
    await expect(card.locator('h3', { hasText: '夏休みの宿題' })).toBeVisible()

    // 進捗率 (0.25 * 100 = 25%)
    await expect(card.locator('text=/25\\s*%/')).toBeVisible()
  })

  test('MY-PROJ-002: 個人プロジェクト詳細でゲート機能が動作する（作成者=自分）', async ({
    page,
  }) => {
    const project = buildOwnProject()
    await installMyProjectMocks(page, project, buildMilestones(), buildGatesSummary())

    await page.goto(`/my/projects/${PROJECT_ID}`)
    await waitForHydration(page)

    // タイトル表示
    await expect(page.getByRole('heading', { name: '夏休みの宿題' })).toBeVisible({
      timeout: 15_000,
    })

    // GateProgressGauge 表示確認（i18n: project.gate_progress = "ゲート進捗"）
    await expect(page.getByRole('heading', { name: /ゲート進捗/ })).toBeVisible({
      timeout: 10_000,
    })
    // next_gate (算数ドリル) が表示されている
    await expect(page.locator('strong', { hasText: '算数ドリル' })).toBeVisible()

    // ロック中のマイルストーン B に対して ForceUnlockDialog が開ける（creator=自分のため表示される）
    const forceUnlockTrigger = page.locator(
      `[data-testid="force-unlock-trigger-${MILESTONE_B_ID}"]`,
    )
    await expect(forceUnlockTrigger).toBeVisible({ timeout: 10_000 })
    await forceUnlockTrigger.click()

    const dialog = page.locator('[data-testid="force-unlock-dialog"]')
    await expect(dialog).toBeVisible({ timeout: 5_000 })

    // 未入力時は Submit disabled
    const submitBtn = page.locator('[data-testid="force-unlock-submit"]')
    await expect(submitBtn).toBeDisabled()

    // CompletionModeToggle も creator なので表示されている
    const toggles = page.locator('[data-testid="completion-mode-toggle"]')
    await expect(toggles.first()).toBeVisible({ timeout: 5_000 })

    // InitializeGateButton も creator なので表示されている
    await expect(page.locator('[data-testid="initialize-gate-button"]')).toBeVisible()
  })

  test('MY-PROJ-003: 他人作成のプロジェクトでは ForceUnlock / CompletionModeToggle が非表示', async ({
    page,
  }) => {
    // createdBy.id = OTHER_USER_ID のプロジェクト（共有閲覧等のエッジケース）
    const project = buildForeignProject()
    await installMyProjectMocks(page, project, buildMilestones(), buildGatesSummary())

    await page.goto(`/my/projects/${PROJECT_ID}`)
    await waitForHydration(page)

    // タイトルは表示される
    await expect(page.getByRole('heading', { name: '夏休みの宿題' })).toBeVisible({
      timeout: 15_000,
    })

    // GateProgressGauge 自体は閲覧可能
    await expect(page.getByRole('heading', { name: /ゲート進捗/ })).toBeVisible({
      timeout: 10_000,
    })

    // creator ではないため ForceUnlock ボタンは非表示（canForceUnlock=false）
    const forceUnlockTrigger = page.locator(
      `[data-testid="force-unlock-trigger-${MILESTONE_B_ID}"]`,
    )
    await expect(forceUnlockTrigger).toHaveCount(0)

    // CompletionModeToggle も非表示（canEdit=false）
    const toggles = page.locator('[data-testid="completion-mode-toggle"]')
    await expect(toggles).toHaveCount(0)

    // InitializeGateButton も非表示
    await expect(page.locator('[data-testid="initialize-gate-button"]')).toHaveCount(0)
  })
})
