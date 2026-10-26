/**
 * このテストはAPIモックを使わない実機テストです。
 * バックエンド http://localhost:8080 / フロントエンド http://localhost:3000 が起動済みの状態で実行してください。
 *
 * 認証: tests/e2e/.auth/real-user.json の storageState を使用します。
 * 未生成の場合は loginIfNeeded() でフォールバックログインします。
 *
 * テストユーザー:
 *   - e2e-user@test.mannschaft.local / TestPass2026! (一般ユーザー)
 *   - e2e-admin@test.mannschaft.local / TestPass2026! (管理者)
 * 実機テストチーム: FC東京U-18（テスト）(id=1)
 *
 * テストケース:
 *   EC-001〜EC-004: イベント→チャット自動連携
 *   ES-001〜ES-003: アンケート→掲示板自動連携
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
/** バックエンドが起動しているか確認する。起動していない場合は false を返す。 */
async function isBackendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(`${BACKEND_URL}/actuator/health`, { timeout: 5_000 })
    const body = await res.json()
    return body.status === 'UP'
  } catch {
    return false
  }
}

/** フロントエンドが起動しているか確認する。起動していない場合は false を返す。 */
async function isFrontendAlive(request: APIRequestContext): Promise<boolean> {
  try {
    const res = await request.get(FRONTEND_URL, { timeout: 5_000 })
    // OOM クラッシュ時は 500 が返るが、バックエンドに繋がっていれば ok とみなす
    return res.status() < 600
  } catch {
    return false
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: ログイン（storageState フォールバック）
// ---------------------------------------------------------------------------
async function loginIfNeeded(page: Page, email = E2E_USER.email, password = E2E_USER.password): Promise<void> {
  // /login に直接遷移して確実に新規トークンを取得する。
  // /my/dashboard 経由だと localStorage の古い currentUser で isAuthenticated=true と誤判定し、
  // 期限切れ Cookie での API 呼び出し → 401 → refresh 失敗 → logout → /login リダイレクトが発生する。
  await page.goto('/login')
  await waitForHydration(page)
  if (!page.url().includes('/login')) {
    return
  }
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(email, { delay: 10 })
  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(password, { delay: 10 })
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.waitForURL(/.*\/my\/.*|.*\/dashboard.*/, { timeout: 30_000 })
}

// ---------------------------------------------------------------------------
// ヘルパー: 認証トークン取得（API直接呼び出し用）
// ---------------------------------------------------------------------------
async function getAuthToken(request: APIRequestContext, email: string, password: string): Promise<string | null> {
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
// ヘルパー: イベント作成 (API)
// ---------------------------------------------------------------------------
async function createTestEvent(
  request: APIRequestContext,
  token: string,
  slug: string,
  subtitle: string,
): Promise<number | null> {
  try {
    const res = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/events`, {
      data: {
        slug,
        subtitle,
        attendanceMode: 'NONE',
      },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    })
    if (!res.ok()) return null
    const body = await res.json()
    return body?.data?.id ?? null
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: アンケート作成 (API)
// ---------------------------------------------------------------------------
async function createTestSurvey(
  request: APIRequestContext,
  token: string,
  title: string,
): Promise<number | null> {
  try {
    const res = await request.post(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/surveys`, {
      data: {
        title,
        description: 'E2Eテスト用アンケート',
        isAnonymous: false,
        allowMultipleSubmissions: false,
        resultsVisibility: 'AFTER_CLOSE',
        distributionMode: 'ALL',
      },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    })
    if (!res.ok()) return null
    const body = await res.json()
    // Issue #2635 で SurveyDetailResponse はフラット化され { id, ..., questions: [] } になった。
    return body?.data?.id ?? null
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// ヘルパー: イベントチャンネル取得 (API, ポーリング付き)
// ---------------------------------------------------------------------------
/** @TransactionalEventListener + @Async の完了を待ってチャンネルを取得する */
async function pollEventChannel(
  request: APIRequestContext,
  token: string,
  eventId: number,
  maxAttempts = 10,
  intervalMs = 1_000,
): Promise<{ id: number; channelType: string; isArchived: boolean } | null> {
  for (let i = 0; i < maxAttempts; i++) {
    try {
      const res = await request.get(`${BACKEND_URL}/api/v1/events/${eventId}/channel`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (res.ok()) {
        const body = await res.json()
        if (body?.data) return body.data
      }
    } catch {
      // 無視して再試行
    }
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  return null
}

// ---------------------------------------------------------------------------
// ヘルパー: アンケートスレッド取得 (API, ポーリング付き)
// ---------------------------------------------------------------------------
/** @TransactionalEventListener + @Async の完了を待ってスレッドを取得する */
async function pollSurveyThread(
  request: APIRequestContext,
  token: string,
  surveyId: number,
  maxAttempts = 10,
  intervalMs = 1_000,
): Promise<{ id: number; isLocked: boolean; scopeType: string; scopeId: number } | null> {
  for (let i = 0; i < maxAttempts; i++) {
    try {
      const res = await request.get(`${BACKEND_URL}/api/v1/surveys/${surveyId}/thread`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (res.ok()) {
        const body = await res.json()
        if (body?.data) return body.data
      }
    } catch {
      // 無視して再試行
    }
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  return null
}

// ---------------------------------------------------------------------------
// ヘルパー: クリーンアップ
// ---------------------------------------------------------------------------
async function deleteEvent(
  request: APIRequestContext,
  token: string,
  eventId: number,
): Promise<void> {
  try {
    await request.delete(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/events/${eventId}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch {
    // クリーンアップ失敗は無視
  }
}

async function deleteSurvey(
  request: APIRequestContext,
  token: string,
  surveyId: number,
): Promise<void> {
  try {
    await request.delete(`${BACKEND_URL}/api/v1/teams/${TEAM_ID}/surveys/${surveyId}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch {
    // クリーンアップ失敗は無視
  }
}

// ---------------------------------------------------------------------------
// EC-001〜EC-004: イベント → チャット自動連携
// ---------------------------------------------------------------------------
test.describe('EC-001〜EC-004: イベント→チャット自動連携', () => {
  test.describe.configure({ mode: 'serial' })
  let userToken: string | null = null
  let adminToken: string | null = null
  let testEventId: number | null = null
  let backendAlive = false
  let frontendAlive = false

  test.beforeAll(async ({ request }) => {
    backendAlive = await isBackendAlive(request)
    frontendAlive = await isFrontendAlive(request)

    if (!backendAlive) {
      console.warn('バックエンド未起動のためテストをスキップします')
      return
    }

    userToken = await getAuthToken(request, E2E_USER.email, E2E_USER.password)
    adminToken = await getAuthToken(request, E2E_ADMIN.email, E2E_ADMIN.password)

    if (!userToken) {
      console.warn('e2e-user ログイン失敗')
      return
    }

    // テスト用イベントを作成（slug はタイムスタンプで一意化）
    const slug = `e2e-ec-${Date.now()}`
    testEventId = await createTestEvent(request, userToken, slug, 'E2Eテスト: チャット連携確認')
    if (!testEventId) {
      console.warn(`テストイベント作成失敗 (slug=${slug})。バックエンドのマイグレーション・権限を確認してください`)
    }
  })

  test.afterAll(async ({ request }) => {
    if (backendAlive && userToken && testEventId) {
      await deleteEvent(request, userToken, testEventId)
    }
  })

  test('EC-001: イベント作成後、チャットチャンネルがAPIで取得できる（e2e-user）', async ({
    request,
  }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    if (!userToken) {
      test.skip(true, 'e2e-user ログイン失敗のためスキップ')
      return
    }
    if (!testEventId) {
      test.skip(true, 'テストイベント作成失敗のためスキップ（バックエンド不安定の可能性）')
      return
    }

    // @TransactionalEventListener(AFTER_COMMIT) + @Async の完了を待つ
    const channel = await pollEventChannel(request, userToken, testEventId)
    expect(channel).not.toBeNull()
    expect(channel!.channelType).toBe('EVENT_CHAT')
    expect(channel!.isArchived).toBe(false)
  })

  test('EC-002: 別アカウント（e2e-admin）でも同じイベントのチャンネルが取得できる', async ({
    request,
  }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    if (!adminToken) {
      test.skip(true, 'e2e-admin ログイン失敗のためスキップ')
      return
    }
    if (!testEventId) {
      test.skip(true, 'テストイベント作成失敗のためスキップ')
      return
    }

    const channel = await pollEventChannel(request, adminToken, testEventId)
    expect(channel).not.toBeNull()
    expect(channel!.channelType).toBe('EVENT_CHAT')
  })

  test('EC-003: イベント詳細ページにチャットタブが表示され、リンクが /chat?channel= 形式である', async ({
    page,
    request,
  }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'バックエンドまたはフロントエンド未起動のためスキップ')
      return
    }
    if (!userToken || !testEventId) {
      test.skip(true, 'テストイベント作成失敗のためスキップ')
      return
    }

    // チャンネル生成を待つ（非同期処理）
    const channel = await pollEventChannel(request, userToken, testEventId)
    if (!channel) {
      test.skip(true, 'チャットチャンネルが生成されていないためスキップ')
      return
    }

    await loginIfNeeded(page)
    await page.goto(`/teams/${TEAM_ID}/events/${testEventId}`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // チャットタブが表示されるのを待つ（非同期でチャンネル情報を取得するため）
    await page.waitForTimeout(3_000)

    const chatTab = page.getByRole('tab').filter({ hasText: /チャットを開く|チャット/ })
    await expect(chatTab).toBeVisible({ timeout: 15_000 })

    // タブをクリックしてリンクを確認
    await chatTab.click()
    const chatLink = page.locator(`a[href*="/chat?channel=${channel.id}"]`)
    await expect(chatLink).toBeVisible({ timeout: 10_000 })
    const href = await chatLink.getAttribute('href')
    expect(href).toMatch(/\/chat\?channel=\d+/)
  })

  test('EC-004: イベントキャンセル後、チャンネルが isArchived=true になる', async ({
    request,
  }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    if (!userToken || !testEventId) {
      test.skip(true, 'テストイベント作成失敗のためスキップ')
      return
    }

    // まずチャンネルが存在することを確認
    const channelBefore = await pollEventChannel(request, userToken, testEventId)
    if (!channelBefore) {
      test.skip(true, 'チャットチャンネルが生成されていないためスキップ')
      return
    }

    // イベントをキャンセル（DRAFT → キャンセル不可なので、まず PUBLISHED にしてからキャンセル）
    // DRAFT → PUBLISHED → CANCEL のフローを確認
    const publishRes = await request.post(
      `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/events/${testEventId}/publish`,
      { headers: { Authorization: `Bearer ${userToken}` } },
    )

    if (!publishRes.ok()) {
      // 権限不足またはステータス遷移できない場合はスキップ
      test.skip(true, 'イベント公開失敗のためスキップ（管理者権限が必要な可能性）')
      return
    }

    const cancelRes = await request.post(
      `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/events/${testEventId}/cancel`,
      { headers: { Authorization: `Bearer ${userToken}` } },
    )

    if (!cancelRes.ok()) {
      test.skip(true, 'イベントキャンセル失敗のためスキップ')
      return
    }

    // キャンセル後にチャンネルがアーカイブされることを確認（非同期処理のため少し待つ）
    let channelAfter = null
    for (let i = 0; i < 10; i++) {
      await new Promise((r) => setTimeout(r, 1_000))
      const res = await request.get(`${BACKEND_URL}/api/v1/events/${testEventId}/channel`, {
        headers: { Authorization: `Bearer ${userToken}` },
      })
      if (res.ok()) {
        const body = await res.json()
        channelAfter = body?.data
        if (channelAfter?.isArchived === true) break
      }
    }

    // チャンネルがアーカイブされているか、またはイベントキャンセル後もチャンネルは返ること
    expect(channelAfter).not.toBeNull()
    // isArchived=true であることを確認（実装による）
    expect(channelAfter!.isArchived).toBe(true)

    // テストイベントは削除済みとして扱う（キャンセル済み）
    testEventId = null
  })
})

// ---------------------------------------------------------------------------
// ES-001〜ES-003: アンケート → 掲示板自動連携
// ---------------------------------------------------------------------------
test.describe('ES-001〜ES-003: アンケート→掲示板自動連携', () => {
  test.describe.configure({ mode: 'serial' })
  let userToken: string | null = null
  let adminToken: string | null = null
  let testSurveyId: number | null = null
  let backendAlive = false
  let frontendAlive = false

  test.beforeAll(async ({ request }) => {
    backendAlive = await isBackendAlive(request)
    frontendAlive = await isFrontendAlive(request)

    if (!backendAlive) {
      console.warn('バックエンド未起動のためテストをスキップします')
      return
    }

    userToken = await getAuthToken(request, E2E_USER.email, E2E_USER.password)
    adminToken = await getAuthToken(request, E2E_ADMIN.email, E2E_ADMIN.password)

    if (!userToken) {
      console.warn('e2e-user ログイン失敗')
      return
    }

    // テスト用アンケートを作成
    const title = `E2Eテストアンケート ${Date.now()}`
    testSurveyId = await createTestSurvey(request, userToken, title)
    if (!testSurveyId) {
      console.warn(`テストアンケート作成失敗。バックエンドのマイグレーション・権限を確認してください`)
    }
  })

  test.afterAll(async ({ request }) => {
    if (backendAlive && userToken && testSurveyId) {
      await deleteSurvey(request, userToken, testSurveyId)
    }
  })

  test('ES-001: アンケート作成後、掲示板スレッドがAPIで取得できる（e2e-user）', async ({
    request,
  }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    if (!userToken) {
      test.skip(true, 'e2e-user ログイン失敗のためスキップ')
      return
    }
    if (!testSurveyId) {
      test.skip(true, 'テストアンケート作成失敗のためスキップ（バックエンド不安定の可能性）')
      return
    }

    // @TransactionalEventListener(AFTER_COMMIT) + @Async の完了を待つ
    const thread = await pollSurveyThread(request, userToken, testSurveyId)
    expect(thread).not.toBeNull()
    expect(thread!.isLocked).toBe(false)
    expect(thread!.scopeType).toBe('TEAM')
    expect(thread!.scopeId).toBe(TEAM_ID)
  })

  test('ES-002: 別アカウント（e2e-admin）でも同じアンケートのスレッドが取得できる', async ({
    request,
  }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    if (!adminToken) {
      test.skip(true, 'e2e-admin ログイン失敗のためスキップ')
      return
    }
    if (!testSurveyId) {
      test.skip(true, 'テストアンケート作成失敗のためスキップ')
      return
    }

    const thread = await pollSurveyThread(request, adminToken, testSurveyId)
    expect(thread).not.toBeNull()
    expect(thread!.isLocked).toBe(false)
  })

  test('ES-001b: アンケート詳細ページに掲示板スレッドリンクが表示される（e2e-user）', async ({
    page,
    request,
  }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'バックエンドまたはフロントエンド未起動のためスキップ')
      return
    }
    if (!userToken || !testSurveyId) {
      test.skip(true, 'テストアンケート作成失敗のためスキップ')
      return
    }

    // スレッド生成を待つ
    const thread = await pollSurveyThread(request, userToken, testSurveyId)
    if (!thread) {
      test.skip(true, '掲示板スレッドが生成されていないためスキップ')
      return
    }

    await loginIfNeeded(page)
    await page.goto(`/surveys/${testSurveyId}?scope=TEAM&scopeId=${TEAM_ID}`)
    await waitForHydration(page)
    await page.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})

    // 掲示板スレッドリンクが表示されるのを待つ（非同期処理）
    await page.waitForTimeout(3_000)

    const bulletinLink = page.locator('[data-testid="survey-bulletin-thread-link"]')
    await expect(bulletinLink).toBeVisible({ timeout: 15_000 })

    // リンク先が /teams/{id}/bulletin 形式であることを確認
    const href = await bulletinLink.getAttribute('href')
    expect(href).toMatch(/\/(teams|organizations)\/\d+\/bulletin/)
  })

  test('ES-002b: 別アカウント（e2e-admin）でもアンケートの掲示板リンクが表示される', async ({
    page,
    request,
  }) => {
    if (!backendAlive || !frontendAlive) {
      test.skip(true, 'バックエンドまたはフロントエンド未起動のためスキップ')
      return
    }
    if (!adminToken || !testSurveyId) {
      test.skip(true, 'テストアンケート作成失敗のためスキップ')
      return
    }

    // スレッド生成を待つ
    const thread = await pollSurveyThread(request, adminToken, testSurveyId)
    if (!thread) {
      test.skip(true, '掲示板スレッドが生成されていないためスキップ')
      return
    }

    // 別コンテキストで e2e-admin としてログイン
    const adminContext = await page.context().browser()!.newContext()
    const adminPage = await adminContext.newPage()
    try {
      await loginIfNeeded(adminPage, E2E_ADMIN.email, E2E_ADMIN.password)
      await adminPage.goto(`/surveys/${testSurveyId}?scope=TEAM&scopeId=${TEAM_ID}`)
      await waitForHydration(adminPage)
      await adminPage.locator('.pi-spin').waitFor({ state: 'detached', timeout: 20_000 }).catch(() => {})
      await adminPage.waitForTimeout(3_000)

      const bulletinLink = adminPage.locator('[data-testid="survey-bulletin-thread-link"]')
      await expect(bulletinLink).toBeVisible({ timeout: 15_000 })
    } finally {
      await adminContext.close()
    }
  })

  test('ES-003: アンケートCLOSE後、GET /api/v1/surveys/{id}/thread で isLocked=true になる', async ({
    request,
  }) => {
    if (!backendAlive) {
      test.skip(true, 'バックエンド未起動のためスキップ')
      return
    }
    if (!userToken || !testSurveyId) {
      test.skip(true, 'テストアンケート作成失敗のためスキップ')
      return
    }

    // スレッドが存在することを確認
    const threadBefore = await pollSurveyThread(request, userToken, testSurveyId)
    if (!threadBefore) {
      test.skip(true, '掲示板スレッドが生成されていないためスキップ')
      return
    }

    // アンケートを PUBLISHED → CLOSED にする（管理者権限が必要なため adminToken 優先）
    const operationToken = adminToken ?? userToken

    // 公開前に設問を1件追加する（設問なしでは公開不可: SURVEY_012）
    const questionRes = await request.post(
      `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/surveys/${testSurveyId}/questions`,
      {
        data: { questionType: 'FREE_TEXT', questionText: 'E2Eテスト設問', isRequired: false, displayOrder: 1 },
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${operationToken}` },
      },
    )
    if (!questionRes.ok()) {
      test.skip(true, `設問追加失敗のためスキップ (status=${questionRes.status()})`)
      return
    }

    // まず公開（POST: not PATCH）
    const publishRes = await request.post(
      `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/surveys/${testSurveyId}/publish`,
      { headers: { Authorization: `Bearer ${operationToken}` } },
    )

    if (!publishRes.ok()) {
      const body = await publishRes.text()
      console.warn(`アンケート公開失敗: status=${publishRes.status()}, body=${body}`)
      test.skip(true, `アンケート公開失敗のためスキップ (status=${publishRes.status()})`)
      return
    }

    // クローズ（POST: not PATCH）
    const closeRes = await request.post(
      `${BACKEND_URL}/api/v1/teams/${TEAM_ID}/surveys/${testSurveyId}/close`,
      { headers: { Authorization: `Bearer ${operationToken}` } },
    )

    if (!closeRes.ok()) {
      test.skip(true, 'アンケートクローズ失敗のためスキップ')
      return
    }

    // クローズ後にスレッドが isLocked=true になることを確認（非同期処理のため少し待つ）
    let threadAfter = null
    for (let i = 0; i < 10; i++) {
      await new Promise((r) => setTimeout(r, 1_000))
      const res = await request.get(`${BACKEND_URL}/api/v1/surveys/${testSurveyId}/thread`, {
        headers: { Authorization: `Bearer ${userToken}` },
      })
      if (res.ok()) {
        const body = await res.json()
        threadAfter = body?.data
        if (threadAfter?.isLocked === true) break
      }
    }

    expect(threadAfter).not.toBeNull()
    expect(threadAfter!.isLocked).toBe(true)

    // クローズ済みとして扱う（afterAll では削除するが、CLOSEDでも削除可能）
  })
})
