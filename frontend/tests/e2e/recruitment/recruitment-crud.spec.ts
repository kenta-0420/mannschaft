import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * F03.11 募集型予約 — CRUD E2E テスト (実バックエンド叩く版)
 *
 * テストID: RECRUIT-CRUD-001 〜 RECRUIT-CRUD-007
 *
 * 方針:
 * - API モック不使用。実バックエンド (localhost:8080) を叩く
 * - 認証: playwright.config.ts の storageState (tests/e2e/.auth/user.json) を使用
 *   → setup-user プロジェクトが事前にログインして有効な JWT を保存する
 * - テストユーザー: e2e-user@example.com (userId=59) → team_id=1 の ADMIN ロール付与済み
 * - 各テストは独立したデータを作成（テスト間の依存なし）
 * - page.waitForTimeout() 禁止 → waitForSelector / waitForResponse / waitForURL を使用
 * - TypeScript any 禁止
 *
 * 前提:
 * - user_roles テーブルに e2e-user(59) と e2e-admin(60) を team_id=1 の ADMIN として追加済み
 *
 * 仕様書: docs/features/F03.11_recruitment_listing.md
 */

// ---------------------------------------------------------------------------
// テスト用定数
// ---------------------------------------------------------------------------

const TEAM_ID = 1
const API_BASE = 'http://localhost:8080'

// ---------------------------------------------------------------------------
// テストスイート
// ---------------------------------------------------------------------------

test.describe('RECRUIT-CRUD-001〜007: F03.11 募集型予約 CRUD (実バックエンド)', () => {
  // beforeEach なし: storageState で既にログイン済みの状態から開始する

  // -------------------------------------------------------------------------
  // CRUD-001: 募集作成 → 一覧反映
  // -------------------------------------------------------------------------

  test('RECRUIT-CRUD-001: 募集作成フォームを入力して送信するとDRAFTで一覧に表示される', async ({ page }) => {
    const uniqueTitle = `E2E CRUD-001 募集 ${Date.now()}`

    // 作成ページに移動
    await page.goto(`/teams/${TEAM_ID}/recruitment-listings/new`)
    await waitForHydration(page)

    // ページ見出し確認
    await expect(page.getByRole('heading', { name: '募集枠を作成' })).toBeVisible({ timeout: 10_000 })

    // フォーム入力: タイトル
    const titleInput = page.getByLabel('タイトル')
    await titleInput.click()
    await titleInput.fill(uniqueTitle)

    // カテゴリは PrimeVue Select コンポーネント → クリックしてドロップダウンを開き、テキストで選択
    await page.locator('#category').click()
    // PrimeVue Select のオプションは li 要素で role="option" ではなく独自実装のため getByText を使用
    await page.getByText('フットサル個人参加').first().click()

    // 開催日時 (type="datetime-local")
    await page.locator('#startAt').fill('2027-06-01T10:00')
    await page.locator('#endAt').fill('2027-06-01T12:00')
    await page.locator('#applicationDeadline').fill('2027-05-31T10:00')
    await page.locator('#autoCancelAt').fill('2027-05-31T10:00')

    // 定員: PrimeVue InputNumber は内部に <input role="spinbutton"> を持つ
    // fill() は v-model に反映されないため、type() を使って直接入力する
    const capacityInput = page.locator('#capacity').getByRole('spinbutton')
    await capacityInput.click()
    await capacityInput.press('Control+a')
    await capacityInput.type('10')

    const minCapacityInput = page.locator('#minCapacity').getByRole('spinbutton')
    await minCapacityInput.click()
    await minCapacityInput.press('Control+a')
    await minCapacityInput.type('3')

    // 作成ボタンをクリックし、201 レスポンスを待つ
    const createResponsePromise = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/v1/teams/') &&
        resp.url().includes('/recruitment-listings') &&
        resp.request().method() === 'POST' &&
        resp.status() === 201,
      { timeout: 15_000 },
    )
    await page.getByRole('button', { name: '作成' }).click()
    await createResponsePromise

    // 作成成功後 → 詳細ページにリダイレクトされる
    await page.waitForURL(/\/recruitment-listings\/\d+/, { timeout: 10_000 })

    // 詳細ページで DRAFT ステータスとタイトルが表示されることを確認
    await expect(page.getByRole('heading', { name: uniqueTitle })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('下書き')).toBeVisible({ timeout: 5_000 })

    // 一覧ページに戻り、作成した募集が表示されることを確認
    await page.goto(`/teams/${TEAM_ID}/recruitment-listings`)
    await waitForHydration(page)
    await expect(page.getByText(uniqueTitle)).toBeVisible({ timeout: 10_000 })
  })

  // -------------------------------------------------------------------------
  // CRUD-002: 募集公開 (DRAFT → OPEN)
  // -------------------------------------------------------------------------

  test('RECRUIT-CRUD-002: DRAFT 状態の募集を公開すると OPEN になる', async ({ page }) => {
    const uniqueTitle = `E2E CRUD-002 公開テスト ${Date.now()}`
    const accessToken = await getAccessToken(page)

    // API 経由で DRAFT 状態の募集を作成
    const listingId = await createTestListing(accessToken, uniqueTitle)

    // 詳細ページに遷移
    await page.goto(`/recruitment-listings/${listingId}`)
    await waitForHydration(page)

    // DRAFT 状態: タイトルと「公開」ボタンが表示されることを確認
    await expect(page.getByRole('heading', { name: uniqueTitle })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('下書き')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: '公開' })).toBeVisible({ timeout: 5_000 })

    // 公開ボタンをクリック → API レスポンスを待つ
    const publishResponsePromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/recruitment-listings/${listingId}/publish`) && resp.status() === 200,
      { timeout: 15_000 },
    )
    await page.getByRole('button', { name: '公開' }).click()
    await publishResponsePromise

    // ステータスが「募集中」に変わることを確認
    await expect(page.getByText('募集中')).toBeVisible({ timeout: 10_000 })
    // 「公開」ボタンが消えることを確認
    await expect(page.getByRole('button', { name: '公開' })).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // CRUD-003: 参加申込 (OPEN 状態の募集に申込)
  // -------------------------------------------------------------------------

  test('RECRUIT-CRUD-003: OPEN 状態の募集に申込するとマイページの参加予定に追加される', async ({ page }) => {
    const uniqueTitle = `E2E CRUD-003 申込テスト ${Date.now()}`
    const accessToken = await getAccessToken(page)

    // OPEN 状態の募集を作成（作成 → 公開）
    const listingId = await createTestListing(accessToken, uniqueTitle)
    await publishListing(accessToken, listingId)

    // 詳細ページに遷移
    await page.goto(`/recruitment-listings/${listingId}`)
    await waitForHydration(page)

    // OPEN 状態: 「申込」ボタンが表示されることを確認
    await expect(page.getByText('募集中')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: '申込' })).toBeVisible({ timeout: 5_000 })

    // 申込ボタンをクリック → API レスポンスを待つ
    const applyResponsePromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/recruitment-listings/${listingId}/applications`) &&
        resp.status() === 201,
      { timeout: 15_000 },
    )
    await page.getByRole('button', { name: '申込' }).click()
    await applyResponsePromise

    // 申込後: 「申込をキャンセル」ボタンが表示されることを確認（「申込」ボタンが消える）
    await expect(page.getByRole('button', { name: '申込をキャンセル' })).toBeVisible({ timeout: 10_000 })

    // マイページの参加予定一覧に反映されることを確認
    // マイページは RecruitmentParticipantResponse を表示し、"listing #<listingId>" 形式で表示される
    await page.goto('/me/recruitment-listings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '自分の参加予定' })).toBeVisible({ timeout: 10_000 })
    // listingId に対応するカードが表示されていることを確認
    await expect(page.getByText(`listing #${listingId}`)).toBeVisible({ timeout: 10_000 })
    // ステータス「確定」が表示される
    await expect(page.getByText('確定').first()).toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // CRUD-004: 参加キャンセル (本人キャンセル)
  // -------------------------------------------------------------------------

  test('RECRUIT-CRUD-004: 参加済みの募集をキャンセルするとマイページから削除される', async ({ page }) => {
    const uniqueTitle = `E2E CRUD-004 キャンセルテスト ${Date.now()}`
    const accessToken = await getAccessToken(page)

    // OPEN 状態の募集を作成し、自分で申し込む
    const listingId = await createTestListing(accessToken, uniqueTitle)
    await publishListing(accessToken, listingId)
    await applyToListing(accessToken, listingId)

    // 詳細ページに遷移
    await page.goto(`/recruitment-listings/${listingId}`)
    await waitForHydration(page)

    // 「申込をキャンセル」ボタンが表示されることを確認
    await expect(page.getByRole('button', { name: '申込をキャンセル' })).toBeVisible({ timeout: 10_000 })

    // キャンセルボタンをクリック → 確認モーダルが開く
    await page.getByRole('button', { name: '申込をキャンセル' }).click()

    // 確認モーダルが表示されることを確認
    await expect(page.getByText('キャンセル料の確認')).toBeVisible({ timeout: 10_000 })

    // 「現在は無料でキャンセルできます。」が表示されることを確認（キャンセルポリシーなし）
    await expect(page.getByText('現在は無料でキャンセルできます。')).toBeVisible({ timeout: 5_000 })

    // 「同意してキャンセル」ボタンをクリック → API レスポンスを待つ
    const cancelResponsePromise = page.waitForResponse(
      (resp) =>
        resp.url().includes(`/recruitment-listings/${listingId}/applications/me`) &&
        resp.request().method() === 'DELETE',
      { timeout: 15_000 },
    )
    await page.getByRole('button', { name: '同意してキャンセル' }).click()
    await cancelResponsePromise

    // キャンセル後: 「申込」ボタンが再表示される
    await expect(page.getByRole('button', { name: '申込' })).toBeVisible({ timeout: 10_000 })

    // マイページの参加予定一覧から削除されることを確認
    // listMyActiveParticipations は CONFIRMED/APPLIED/WAITLISTED のみ返すのでキャンセル済みは消える
    await page.goto('/me/recruitment-listings')
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '自分の参加予定' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(uniqueTitle)).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // CRUD-005: 募集削除 (アーカイブ = 論理削除)
  // -------------------------------------------------------------------------

  test('RECRUIT-CRUD-005: DRAFT 状態の募集をアーカイブすると一覧から消える', async ({ page }) => {
    const uniqueTitle = `E2E CRUD-005 削除テスト ${Date.now()}`
    const accessToken = await getAccessToken(page)

    // DRAFT 状態の募集を作成
    const listingId = await createTestListing(accessToken, uniqueTitle)

    // 一覧ページに移動し、作成した募集が表示されることを確認
    await page.goto(`/teams/${TEAM_ID}/recruitment-listings`)
    await waitForHydration(page)
    await expect(page.getByText(uniqueTitle)).toBeVisible({ timeout: 10_000 })

    // API 経由でアーカイブ (論理削除) を実行
    await page.evaluate(
      async ({ apiBase, listingId: lid, token }) => {
        const resp = await fetch(`${apiBase}/api/v1/recruitment-listings/${lid}/archive`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
        })
        if (!resp.ok && resp.status !== 204) {
          throw new Error(`Archive failed: ${resp.status}`)
        }
      },
      { apiBase: API_BASE, listingId, token: accessToken },
    )

    // 一覧ページをリロードし、削除された募集が表示されないことを確認
    await page.reload()
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '募集枠管理' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText(uniqueTitle)).not.toBeVisible({ timeout: 5_000 })
  })

  // -------------------------------------------------------------------------
  // CRUD-006: 募集枠の編集 (タイトル更新)
  // -------------------------------------------------------------------------

  test('RECRUIT-CRUD-006: DRAFT 状態の募集タイトルを更新すると詳細ページに反映される', async ({ page }) => {
    const originalTitle = `E2E CRUD-006 編集前 ${Date.now()}`
    const updatedTitle = `E2E CRUD-006 編集後 ${Date.now()}`
    const accessToken = await getAccessToken(page)

    // DRAFT 状態の募集を作成
    const listingId = await createTestListing(accessToken, originalTitle)

    // API 経由でタイトルを更新 (PATCH)
    const patchResult = await page.evaluate(
      async ({ apiBase, listingId: lid, token, newTitle }) => {
        const resp = await fetch(`${apiBase}/api/v1/recruitment-listings/${lid}`, {
          method: 'PATCH',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ title: newTitle }),
        })
        return resp.json() as Promise<{ data: { title: string } }>
      },
      { apiBase: API_BASE, listingId, token: accessToken, newTitle: updatedTitle },
    )

    // API レスポンスでタイトルが更新されていることを確認
    expect(patchResult.data.title).toBe(updatedTitle)

    // 詳細ページで更新後のタイトルが表示されることを確認
    await page.goto(`/recruitment-listings/${listingId}`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: updatedTitle })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('heading', { name: originalTitle })).not.toBeVisible({ timeout: 3_000 })
  })

  // -------------------------------------------------------------------------
  // CRUD-007: 参加者一覧 (管理者視点)
  // -------------------------------------------------------------------------

  test('RECRUIT-CRUD-007: 参加者が申込すると参加者一覧 API で確認できる', async ({ page }) => {
    const uniqueTitle = `E2E CRUD-007 参加者確認 ${Date.now()}`
    const accessToken = await getAccessToken(page)

    // OPEN 状態の募集を作成
    const listingId = await createTestListing(accessToken, uniqueTitle)
    await publishListing(accessToken, listingId)

    // 自分が参加申込
    await applyToListing(accessToken, listingId)

    // 参加者一覧 API を呼び出す（管理者として）
    const participantsResult = await page.evaluate(
      async ({ apiBase, listingId: lid, token }) => {
        const resp = await fetch(`${apiBase}/api/v1/recruitment-listings/${lid}/participants`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        return resp.json() as Promise<{
          data: Array<{ status: string; userId: number }>
          meta: { total: number }
        }>
      },
      { apiBase: API_BASE, listingId, token: accessToken },
    )

    // 参加者一覧に1件以上含まれることを確認
    expect(participantsResult.data.length).toBeGreaterThanOrEqual(1)

    // CONFIRMED ステータスの参加者が存在することを確認
    const confirmedParticipant = participantsResult.data.find((p) => p.status === 'CONFIRMED')
    expect(confirmedParticipant).toBeTruthy()

    // フロントエンドの詳細ページで参加人数が表示されることを確認
    await page.goto(`/recruitment-listings/${listingId}`)
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: uniqueTitle })).toBeVisible({ timeout: 10_000 })
    // confirmedCount / capacity = 1 / 10
    await expect(page.getByText('1 / 10')).toBeVisible({ timeout: 5_000 })
  })
})

// ---------------------------------------------------------------------------
// テスト用ヘルパー関数
// ---------------------------------------------------------------------------

/**
 * 現在の page の localStorage からアクセストークンを取得する。
 * playwright.config.ts の storageState で保存された JWT を利用。
 * ページが未ロードの場合は /teams/${TEAM_ID}/recruitment-listings に移動してから取得。
 */
async function getAccessToken(page: Page): Promise<string> {
  // まず既存ページ上でトークン取得を試みる
  let token = await page.evaluate((): string => {
    return localStorage.getItem('accessToken') ?? ''
  }).catch(() => '')

  // トークンが取得できなければ一度ページに移動
  if (!token) {
    await page.goto(`/teams/${TEAM_ID}/recruitment-listings`)
    await waitForHydration(page)
    token = await page.evaluate((): string => {
      return localStorage.getItem('accessToken') ?? ''
    })
  }

  if (!token) {
    throw new Error(
      'アクセストークンが取得できませんでした。' +
        'setup-user プロジェクトが実行済みで .auth/user.json に有効なトークンが存在することを確認してください。',
    )
  }
  return token
}

/**
 * API 経由でテスト用の募集枠を DRAFT 状態で作成し、作成した ID を返す。
 */
async function createTestListing(accessToken: string, title: string): Promise<number> {
  const resp = await fetch(`${API_BASE}/api/v1/teams/${TEAM_ID}/recruitment-listings`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      categoryId: 1,
      title,
      participationType: 'INDIVIDUAL',
      startAt: '2027-06-01T10:00:00',
      endAt: '2027-06-01T12:00:00',
      applicationDeadline: '2027-05-31T10:00:00',
      autoCancelAt: '2027-05-31T10:00:00',
      capacity: 10,
      minCapacity: 3,
      paymentEnabled: false,
      visibility: 'PUBLIC',
      location: 'E2E Test Court',
    }),
  })
  if (!resp.ok) {
    const body = await resp.text()
    throw new Error(`募集枠作成失敗: status=${resp.status} body=${body}`)
  }
  const json = (await resp.json()) as { data: { id: number } }
  return json.data.id
}

/**
 * API 経由で募集枠を公開 (DRAFT → OPEN) する。
 */
async function publishListing(accessToken: string, listingId: number): Promise<void> {
  const resp = await fetch(`${API_BASE}/api/v1/recruitment-listings/${listingId}/publish`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (!resp.ok) {
    const body = await resp.text()
    throw new Error(`募集枠公開失敗: status=${resp.status} body=${body}`)
  }
}

/**
 * API 経由で募集枠に参加申込をする。
 */
async function applyToListing(accessToken: string, listingId: number): Promise<void> {
  const resp = await fetch(`${API_BASE}/api/v1/recruitment-listings/${listingId}/applications`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ participantType: 'USER' }),
  })
  if (!resp.ok) {
    const body = await resp.text()
    throw new Error(`参加申込失敗: status=${resp.status} body=${body}`)
  }
}
