import { test, expect, type Page, type Route } from '@playwright/test'

/**
 * F15.3 マイスコープフォルダ統合UX — E2E 一連シナリオ
 *
 * 設計書: docs/features/F15.3_scope_folder_integration.md §14 Phase 2-D
 *
 * シナリオ:
 *   1. 招待リンクからフォルダ振り分けまでの完全フロー（招待→選択→参加→ハブタブ→ナビ→ダッシュボード通知タブ）
 *   2. ナビゲーション basePath ルールの遵守（§7.2: TEAM→/teams, ORGANIZATION→/organizations）
 *   3. フォルダ未選択時の自動振り分け（§13 #③: 未分類フォルダへの lazy 配置）
 *
 * 設計思想:
 *   - 既存 E2E 同様、`page.route()` で API レスポンスをモック化して安定動作させる
 *   - dev サーバ + バックエンドの状態に依存しない（CI 環境でも同一動作）
 *   - data-testid は最小限。基本は role/aria セレクタを優先
 */

// ──────────────────────────────────────────────────────────────────────────
// テスト共通フィクスチャ
// ──────────────────────────────────────────────────────────────────────────

const VALID_TOKEN = 'valid-folder-integration-token'

/** モックチーム情報（招待プレビュー & マイチーム） */
const MOCK_TEAM = {
  id: 9001,
  name: 'テスト部活チーム',
  type: 'TEAM' as const,
  description: 'F15.3 E2E 用',
  iconUrl: null,
  roleName: 'MEMBER',
  expiresAt: null,
  isValid: true,
}

/** マイスコープフォルダのモック（フォルダ「部活」(id=11) と 未分類(id=99)） */
const MOCK_TEAM_FOLDERS = [
  {
    id: 11,
    name: '部活',
    color: '#FF8800',
    icon: 'pi-users',
    isDefault: false,
    sortOrder: 1,
    itemScopeIds: [] as number[],
    notificationUnreadCount: 3,
  },
  {
    id: 99,
    name: 'デフォルト',
    color: null,
    icon: null,
    isDefault: true,
    sortOrder: 9999,
    itemScopeIds: [] as number[],
    notificationUnreadCount: 0,
  },
]

const MOCK_ORG_FOLDERS = [
  {
    id: 21,
    name: '町内会',
    color: '#3366FF',
    icon: 'pi-building',
    isDefault: false,
    sortOrder: 1,
    itemScopeIds: [] as number[],
    notificationUnreadCount: 1,
  },
  {
    id: 199,
    name: 'デフォルト',
    color: null,
    icon: null,
    isDefault: true,
    sortOrder: 9999,
    itemScopeIds: [] as number[],
    notificationUnreadCount: 0,
  },
]

const MOCK_ORG = {
  id: 7001,
  name: 'テスト町内会',
  nickname1: null,
  role: 'MEMBER' as const,
}

/**
 * 招待〜マイページ系 API をまとめてモックする。
 *
 * @param page Playwright ページ
 * @param opts 追加挙動
 *   - onJoin: `/api/v1/invite/{token}/join` 受信時のフック（folderId 検証用）
 *   - joinedFolderId: 参加後のマイチームフォルダ ID（未指定なら 99=未分類）
 */
async function mockBackendApis(
  page: Page,
  opts: {
    onJoin?: (folderId: number | null) => void
    joinedFolderId?: number
  } = {},
): Promise<void> {
  const joinedFolderId = opts.joinedFolderId ?? 99

  // 招待プレビュー
  await page.route(`**/api/v1/invite/${VALID_TOKEN}`, async (route: Route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TEAM }),
      })
    } else {
      await route.continue()
    }
  })

  // 招待参加 POST
  await page.route(
    `**/api/v1/invite/${VALID_TOKEN}/join`,
    async (route: Route) => {
      if (route.request().method() === 'POST') {
        let folderId: number | null = null
        try {
          const body = JSON.parse(route.request().postData() ?? '{}') as {
            folderId?: number | null
          }
          folderId = body.folderId ?? null
        } catch {
          folderId = null
        }
        opts.onJoin?.(folderId)

        // 参加後、マイチームフォルダの該当フォルダに item を加える
        const targetId = folderId ?? joinedFolderId
        const folder = MOCK_TEAM_FOLDERS.find(f => f.id === targetId)
        if (folder && !folder.itemScopeIds.includes(MOCK_TEAM.id)) {
          folder.itemScopeIds.push(MOCK_TEAM.id)
        }

        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: { success: true } }),
        })
      } else {
        await route.continue()
      }
    },
  )

  // マイチーム一覧
  await page.route('**/api/v1/me/teams', async (route: Route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            {
              id: MOCK_TEAM.id,
              name: MOCK_TEAM.name,
              nickname1: null,
              role: 'MEMBER',
              memberCount: 1,
            },
          ],
        }),
      })
    } else {
      await route.continue()
    }
  })

  // マイ組織一覧
  await page.route('**/api/v1/me/organizations', async (route: Route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_ORG] }),
      })
    } else {
      await route.continue()
    }
  })

  // マイスコープフォルダ一覧（scopeType クエリで TEAM/ORG を切替）
  await page.route(/.*\/api\/v1\/me\/scope-folders(\?|$)/, async (route: Route) => {
    const url = new URL(route.request().url())
    // 子パス（/default や /notifications/summary）は別ハンドラ。完全一致のみ処理
    if (url.pathname !== '/api/v1/me/scope-folders') {
      await route.continue()
      return
    }
    if (route.request().method() !== 'GET') {
      await route.continue()
      return
    }
    const scopeType = url.searchParams.get('scopeType')
    const data =
      scopeType === 'ORGANIZATION' ? MOCK_ORG_FOLDERS : MOCK_TEAM_FOLDERS
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data }),
    })
  })

  // 未分類フォルダ取得（lazy 生成エンドポイント）
  await page.route(
    '**/api/v1/me/scope-folders/default**',
    async (route: Route) => {
      const url = new URL(route.request().url())
      const scopeType = url.searchParams.get('scopeType')
      const folder =
        scopeType === 'ORGANIZATION'
          ? MOCK_ORG_FOLDERS.find(f => f.isDefault)!
          : MOCK_TEAM_FOLDERS.find(f => f.isDefault)!
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: folder }),
      })
    },
  )

  // 通知未読集計
  await page.route(
    '**/api/v1/me/scope-folders/notifications/summary**',
    async (route: Route) => {
      const url = new URL(route.request().url())
      const scopeType = url.searchParams.get('scopeType')
      const source =
        scopeType === 'ORGANIZATION' ? MOCK_ORG_FOLDERS : MOCK_TEAM_FOLDERS
      const summary = source.map(f => ({
        folderId: f.id,
        unreadCount: f.notificationUnreadCount ?? 0,
      }))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: summary }),
      })
    },
  )

  // 一般通知一覧（フォルダフィルタ問わず空で OK）
  await page.route(/.*\/api\/v1\/notifications(\?|$)/, async (route: Route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [],
          meta: { page: 0, size: 5, totalElements: 0, totalPages: 0 },
        }),
      })
    } else {
      await route.continue()
    }
  })
}

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 1: 招待→フォルダ選択→ハブ→ナビ→ダッシュボード通知タブ
// ──────────────────────────────────────────────────────────────────────────

test.describe('F15.3 シナリオ 1: 招待からフォルダ振り分けまでの完全フロー', () => {
  test('SF-INT-01: 招待→「部活」フォルダ選択→参加→ハブで部活タブ確認→ナビ「部活」遷移→通知タブ確認', async ({
    page,
  }) => {
    let receivedFolderId: number | null | undefined = undefined
    await mockBackendApis(page, {
      onJoin: (folderId) => {
        receivedFolderId = folderId
      },
      joinedFolderId: 11, // 「部活」フォルダ
    })

    // 1. 招待リンクへアクセス
    await page.goto(`/invite/${VALID_TOKEN}`)

    // 2. プレビュー表示
    await expect(page.getByText(MOCK_TEAM.name)).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('参加ロール')).toBeVisible()

    // 3. InviteFolderPicker が出現するまで待機
    const picker = page.getByTestId('invite-folder-picker')
    await expect(picker).toBeVisible({ timeout: 5_000 })

    // 4. 「部活」フォルダを選択（select 要素は label「部活」を value で持つ）
    const select = picker.locator('select')
    // フォルダ一覧フェッチ後に option が並ぶのを待つ
    await expect(select.locator('option')).toContainText(['部活'])
    await select.selectOption({ label: '部活' })

    // 5. 参加ボタン押下
    await page.getByTestId('invite-join-button').click()

    // 6. ダッシュボードへ遷移
    await page.waitForURL(/\/dashboard/, { timeout: 10_000 })

    // POST body の folderId 検証
    expect(receivedFolderId).toBe(11)

    // 7. /teams ハブへ移動
    await page.goto('/teams')
    const teamTabs = page.getByTestId('scope-folder-tabs-TEAM')
    await expect(teamTabs).toBeVisible({ timeout: 10_000 })
    // 「部活」タブ（id=11）の存在を確認
    const buKatsuTab = page.getByTestId('scope-folder-tab-11')
    await expect(buKatsuTab).toBeVisible()
    await expect(buKatsuTab).toContainText('部活')

    // 8. ナビバー「チーム ▼」を開く
    const teamDropdownToggle = page.getByTestId('scope-nav-dropdown-toggle-TEAM')
    await expect(teamDropdownToggle).toBeVisible()
    await teamDropdownToggle.click()
    // メニュー開閉確認
    await expect(teamDropdownToggle).toHaveAttribute('aria-expanded', 'true')
    // ドロップダウン内に「部活」フォルダが表示されている
    const folderItemInDropdown = page.getByTestId(
      'scope-nav-dropdown-folder-11',
    )
    await expect(folderItemInDropdown).toBeVisible()
    await expect(folderItemInDropdown).toContainText('部活')

    // 9. ドロップダウンから「部活」をクリック → /teams?folder=11 へ遷移
    await folderItemInDropdown.click()
    await page.waitForURL(/\/teams\?folder=11/, { timeout: 5_000 })

    // 10. ダッシュボードへ戻り、WidgetNotices のフォルダタブを確認
    await page.goto('/dashboard')
    const noticesTabList = page.getByTestId('widget-notices-folder-tabs')
    await expect(noticesTabList).toBeVisible({ timeout: 10_000 })
    // 「部活」タブ（key=TEAM-11）が存在し、件数バッジ(3) が表示されている
    const buKatsuNoticeTab = page.getByTestId('widget-notices-tab-TEAM-11')
    await expect(buKatsuNoticeTab).toBeVisible()
    await expect(buKatsuNoticeTab).toContainText('部活')
    // バッジは Badge コンポーネント描画。テキストとして '3' が含まれることを確認
    await expect(buKatsuNoticeTab).toContainText('3')
  })
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 2: ナビ basePath ルールの遵守（§7.2）
// ──────────────────────────────────────────────────────────────────────────

test.describe('F15.3 シナリオ 2: ナビ basePath ルールの遵守 (§7.2)', () => {
  test.beforeEach(async ({ page }) => {
    await mockBackendApis(page)
  })

  test('SF-INT-02a: 「チーム ▼」ドロップダウンから遷移すると全 URL が /teams 配下になる', async ({
    page,
  }) => {
    await page.goto('/dashboard')
    await page.waitForLoadState('networkidle')

    // ナビ「チーム ▼」を開く
    const teamToggle = page.getByTestId('scope-nav-dropdown-toggle-TEAM')
    await expect(teamToggle).toBeVisible({ timeout: 10_000 })
    await teamToggle.click()
    await expect(teamToggle).toHaveAttribute('aria-expanded', 'true')

    // フォルダ「部活」(id=11) → /teams?folder=11
    await page.getByTestId('scope-nav-dropdown-folder-11').click()
    await page.waitForURL(/\/teams(\?|$)/, { timeout: 5_000 })
    expect(new URL(page.url()).pathname).toBe('/teams')

    // 再度開いて、個別チームジャンプ → /teams/{id}
    await teamToggle.click()
    await expect(teamToggle).toHaveAttribute('aria-expanded', 'true')
    await page.getByTestId(`scope-nav-dropdown-scope-${MOCK_TEAM.id}`).click()
    await page.waitForURL(new RegExp(`/teams/${MOCK_TEAM.id}`), {
      timeout: 5_000,
    })
    expect(new URL(page.url()).pathname.startsWith('/teams/')).toBe(true)
    expect(new URL(page.url()).pathname.startsWith('/organizations')).toBe(false)
  })

  test('SF-INT-02b: 「組織 ▼」ドロップダウンから遷移すると全 URL が /organizations 配下になる', async ({
    page,
  }) => {
    await page.goto('/dashboard')
    await page.waitForLoadState('networkidle')

    const orgToggle = page.getByTestId('scope-nav-dropdown-toggle-ORGANIZATION')
    await expect(orgToggle).toBeVisible({ timeout: 10_000 })
    await orgToggle.click()
    await expect(orgToggle).toHaveAttribute('aria-expanded', 'true')

    // 組織フォルダ「町内会」(id=21) → /organizations?folder=21
    await page.getByTestId('scope-nav-dropdown-folder-21').click()
    await page.waitForURL(/\/organizations(\?|$)/, { timeout: 5_000 })
    expect(new URL(page.url()).pathname).toBe('/organizations')
    expect(new URL(page.url()).pathname.startsWith('/teams')).toBe(false)

    // 個別組織ジャンプ → /organizations/{id}
    await orgToggle.click()
    await expect(orgToggle).toHaveAttribute('aria-expanded', 'true')
    await page.getByTestId(`scope-nav-dropdown-scope-${MOCK_ORG.id}`).click()
    await page.waitForURL(new RegExp(`/organizations/${MOCK_ORG.id}`), {
      timeout: 5_000,
    })
    expect(new URL(page.url()).pathname.startsWith('/organizations/')).toBe(true)
    expect(new URL(page.url()).pathname.startsWith('/teams')).toBe(false)
  })
})

// ──────────────────────────────────────────────────────────────────────────
// シナリオ 3: フォルダ未選択時の自動振り分け
// ──────────────────────────────────────────────────────────────────────────

test.describe('F15.3 シナリオ 3: 未選択時に未分類フォルダへ自動配置 (§13 #③)', () => {
  test('SF-INT-03: 招待画面でフォルダ未選択のまま参加→ハブで「未分類」タブが該当チームを含む', async ({
    page,
  }) => {
    let receivedFolderId: number | null | undefined = undefined
    await mockBackendApis(page, {
      onJoin: (folderId) => {
        receivedFolderId = folderId
      },
      // 未指定で参加した場合は「未分類」(id=99) に振り分ける挙動を Backend が担保
      joinedFolderId: 99,
    })

    await page.goto(`/invite/${VALID_TOKEN}`)
    await expect(page.getByText(MOCK_TEAM.name)).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('invite-folder-picker')).toBeVisible()

    // フォルダ未選択のまま「参加する」を押下
    await page.getByTestId('invite-join-button').click()

    // ダッシュボードへ遷移
    await page.waitForURL(/\/dashboard/, { timeout: 10_000 })

    // POST body は folderId 未指定 → サーバには null（または undefined）として届く
    // フロントは未選択時に body から folderId を省略するため、ハンドラには null が記録される
    expect(receivedFolderId === null || receivedFolderId === undefined).toBe(true)

    // /teams ハブで「未分類」タブが存在することを確認
    await page.goto('/teams')
    const teamTabs = page.getByTestId('scope-folder-tabs-TEAM')
    await expect(teamTabs).toBeVisible({ timeout: 10_000 })
    const defaultTab = page.getByTestId('scope-folder-tab-default')
    await expect(defaultTab).toBeVisible()
    // 未分類タブの件数表示にチームが含まれている（itemScopeIds.length 表示）
    // モックの未分類フォルダ(id=99) には join 後に MOCK_TEAM.id が追加される
    await expect(defaultTab).toContainText('(1)')
  })
})
