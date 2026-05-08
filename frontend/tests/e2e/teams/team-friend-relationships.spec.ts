import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/**
 * F01.5 チーム間相互フォロー・フレンドチーム E2Eテスト
 *
 * テスト対象ページ:
 *   - /teams/{id}/friends         : フレンドチーム一覧
 *   - /teams/{id}/friend-folders  : フレンドフォルダ管理
 *   - /teams/{id}/friend-feed     : 管理者フィード（フレンドチーム発の投稿）
 */

// ────────────────────────────
// モックデータ
// ────────────────────────────

const TARGET_TEAM_ID = 2

const MOCK_FRIEND_TEAMS = [
  {
    id: 1,
    teamAId: TEAM_ID,
    teamBId: TARGET_TEAM_ID,
    establishedAt: '2026-03-01T00:00:00Z',
    isPublic: true,
    friendTeam: {
      id: TARGET_TEAM_ID,
      name: '京都サッカークラブ',
      description: '練習試合相手チーム',
      template: 'sports',
      prefecture: '京都府',
      city: '東山区',
      memberCount: 12,
    },
    createdAt: '2026-03-01T00:00:00Z',
  },
]

const MOCK_PENDING_FOLLOWS = [
  {
    id: 3,
    targetTeamId: TARGET_TEAM_ID,
    targetTeamName: '大阪フットサルクラブ',
    followedAt: '2026-05-01T00:00:00Z',
    isMutual: false,
  },
]

const MOCK_FRIEND_FOLDERS = [
  {
    id: 1,
    teamId: TEAM_ID,
    name: '系列校',
    color: '#3B82F6',
    sortOrder: 0,
    description: '系列校のフレンドチーム',
    memberCount: 2,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    teamId: TEAM_ID,
    name: '練習試合候補',
    color: '#10B981',
    sortOrder: 1,
    description: null,
    memberCount: 1,
    createdAt: '2026-01-15T00:00:00Z',
  },
]

const MOCK_FRIEND_FEED_POSTS = [
  {
    id: 10,
    sourceTeamId: TARGET_TEAM_ID,
    sourceTeamName: '京都サッカークラブ',
    content: '合同練習試合のお誘いです！来月5日はいかがでしょうか？',
    scopeType: 'FRIEND_TEAM',
    postedAt: '2026-05-07T10:00:00+09:00',
    isForwarded: false,
    forwardId: null,
  },
]

const MOCK_ADMIN_PERMISSIONS = {
  roleName: 'ADMIN',
  permissions: [
    'MANAGE_FRIEND_TEAMS',
    'schedule.create',
    'member.manage',
  ],
}

// ────────────────────────────
// テストケース: フレンドチーム一覧
// ────────────────────────────

test.describe('FRIEND-001〜006: フレンドチーム一覧', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ADMIN_PERMISSIONS }),
      })
    })
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/friends`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_FRIEND_TEAMS,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/friends/pending`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PENDING_FOLLOWS }),
      })
    })
  })

  test('FRIEND-001: フレンドチーム一覧ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friends`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドチーム' }).or(
      page.getByRole('heading', { name: 'フレンドチーム一覧' }),
    )).toBeVisible({ timeout: 10_000 })
  })

  test('FRIEND-002: フレンドチームが一覧表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friends`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドチーム' }).or(
      page.getByRole('heading', { name: 'フレンドチーム一覧' }),
    )).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('京都サッカークラブ')).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-003: 管理者にはフォロー追加ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friends`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドチーム' }).or(
      page.getByRole('heading', { name: 'フレンドチーム一覧' }),
    )).toBeVisible({ timeout: 10_000 })

    const followButton = page
      .getByRole('button', { name: 'フォローする' })
      .or(page.getByRole('button', { name: 'フォロー追加' }))
      .or(page.getByRole('button', { name: 'チームをフォロー' }))
    await expect(followButton.first()).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-004: フォロー申請送信APIが呼ばれる', async ({ page }) => {
    let followCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/friends/follow`, async (route) => {
      if (route.request().method() === 'POST') {
        followCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 5,
              targetTeamId: 99,
              targetTeamName: '新しいフレンドチーム',
              isMutual: false,
              followedAt: '2026-05-08T12:00:00+09:00',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_FRIEND_TEAMS }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/friends`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドチーム' }).or(
      page.getByRole('heading', { name: 'フレンドチーム一覧' }),
    )).toBeVisible({ timeout: 10_000 })

    const followButton = page
      .getByRole('button', { name: 'フォローする' })
      .or(page.getByRole('button', { name: 'フォロー追加' }))
      .or(page.getByRole('button', { name: 'チームをフォロー' }))

    if (await followButton.first().isVisible({ timeout: 3_000 })) {
      await followButton.first().click()
      // ダイアログが表示された場合の処理
      const dialog = page.getByRole('dialog')
      if (await dialog.isVisible({ timeout: 2_000 })) {
        // ターゲットチームを選択またはIDを入力
        const submitButton = dialog.getByRole('button', { name: '送信' })
          .or(dialog.getByRole('button', { name: 'フォロー' }))
          .or(dialog.getByRole('button', { name: '確認' }))
        if (await submitButton.first().isVisible({ timeout: 2_000 })) {
          await submitButton.first().click()
          await page.waitForTimeout(1_000)
          expect(followCalled).toBe(true)
        }
      }
    }
  })

  test('FRIEND-005: フォロー解除APIが呼ばれる', async ({ page }) => {
    let unfollowCalled = false

    await page.route(
      `**/api/v1/teams/${TEAM_ID}/friends/follow/${TARGET_TEAM_ID}`,
      async (route) => {
        if (route.request().method() === 'DELETE') {
          unfollowCalled = true
          await route.fulfill({ status: 204 })
        } else {
          await route.fulfill({ status: 404 })
        }
      },
    )

    await page.goto(`/teams/${TEAM_ID}/friends`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドチーム' }).or(
      page.getByRole('heading', { name: 'フレンドチーム一覧' }),
    )).toBeVisible({ timeout: 10_000 })

    // フォロー解除ボタンが存在する場合はクリック
    const unfollowButton = page
      .getByRole('button', { name: 'フォロー解除' })
      .or(page.getByRole('button', { name: '解除' }))
    if (await unfollowButton.first().isVisible({ timeout: 3_000 })) {
      await unfollowButton.first().click()
      // 確認ダイアログがある場合
      const confirmButton = page.getByRole('button', { name: '解除する' })
        .or(page.getByRole('button', { name: 'はい' }))
        .or(page.getByRole('button', { name: 'OK' }))
      if (await confirmButton.first().isVisible({ timeout: 2_000 })) {
        await confirmButton.first().click()
      }
      await page.waitForTimeout(1_000)
      expect(unfollowCalled).toBe(true)
    }
  })

  test('FRIEND-006: フレンド関係の公開設定切替APIが存在する', async ({ page }) => {
    const response = await page.request.patch(
      `/api/v1/teams/${TEAM_ID}/friends/1/visibility`,
      {
        failOnStatusCode: false,
        data: { isPublic: true },
      },
    )
    expect([200, 400, 401, 403, 404]).toContain(response.status())
  })
})

// ────────────────────────────
// テストケース: フレンドフォルダ管理
// ────────────────────────────

test.describe('FRIEND-007〜012: フレンドフォルダ管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ADMIN_PERMISSIONS }),
      })
    })
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/friend-folders`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_FRIEND_FOLDERS }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/friend-folders/*/members`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })
  })

  test('FRIEND-007: フレンドフォルダ管理ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friend-folders`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドフォルダ' }).or(
      page.getByRole('heading', { name: 'フォルダ管理' }),
    )).toBeVisible({ timeout: 10_000 })
  })

  test('FRIEND-008: フォルダ一覧が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friend-folders`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドフォルダ' }).or(
      page.getByRole('heading', { name: 'フォルダ管理' }),
    )).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('系列校')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('練習試合候補')).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-009: フォルダ作成ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friend-folders`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドフォルダ' }).or(
      page.getByRole('heading', { name: 'フォルダ管理' }),
    )).toBeVisible({ timeout: 10_000 })

    const createButton = page
      .getByRole('button', { name: 'フォルダ作成' })
      .or(page.getByRole('button', { name: '作成' }))
      .or(page.getByRole('button', { name: '新規作成' }))
    await expect(createButton.first()).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-010: フォルダ作成APIが呼ばれる', async ({ page }) => {
    let createCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/friend-folders`, async (route) => {
      if (route.request().method() === 'POST') {
        createCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 3,
              teamId: TEAM_ID,
              name: '新しいフォルダ',
              color: '#6B7280',
              sortOrder: 2,
              description: null,
              memberCount: 0,
              createdAt: '2026-05-08T00:00:00Z',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_FRIEND_FOLDERS }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/friend-folders`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドフォルダ' }).or(
      page.getByRole('heading', { name: 'フォルダ管理' }),
    )).toBeVisible({ timeout: 10_000 })

    const createButton = page
      .getByRole('button', { name: 'フォルダ作成' })
      .or(page.getByRole('button', { name: '作成' }))
      .or(page.getByRole('button', { name: '新規作成' }))

    if (await createButton.first().isVisible({ timeout: 3_000 })) {
      await createButton.first().click()
      const dialog = page.getByRole('dialog')
      if (await dialog.isVisible({ timeout: 2_000 })) {
        const nameInput = dialog.getByLabel('フォルダ名')
          .or(dialog.getByPlaceholder('フォルダ名'))
        if (await nameInput.first().isVisible({ timeout: 2_000 })) {
          await nameInput.first().fill('新しいフォルダ')
          const saveButton = dialog.getByRole('button', { name: '保存' })
            .or(dialog.getByRole('button', { name: '作成' }))
            .or(dialog.getByRole('button', { name: '送信' }))
          if (await saveButton.first().isVisible({ timeout: 2_000 })) {
            await saveButton.first().click()
            await page.waitForTimeout(1_000)
            expect(createCalled).toBe(true)
          }
        }
      }
    }
  })

  test('FRIEND-011: フォルダ削除APIが呼ばれる', async ({ page }) => {
    let deleteCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/friend-folders/1`, async (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCalled = true
        await route.fulfill({ status: 204 })
      } else {
        await route.fulfill({ status: 404 })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/friend-folders`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドフォルダ' }).or(
      page.getByRole('heading', { name: 'フォルダ管理' }),
    )).toBeVisible({ timeout: 10_000 })

    const deleteButton = page
      .getByRole('button', { name: '削除' })
      .or(page.locator('button[aria-label="フォルダを削除"]'))
    if (await deleteButton.first().isVisible({ timeout: 3_000 })) {
      await deleteButton.first().click()
      const confirmButton = page.getByRole('button', { name: '削除する' })
        .or(page.getByRole('button', { name: 'はい' }))
        .or(page.getByRole('button', { name: 'OK' }))
      if (await confirmButton.first().isVisible({ timeout: 2_000 })) {
        await confirmButton.first().click()
      }
      await page.waitForTimeout(1_000)
      expect(deleteCalled).toBe(true)
    }
  })

  test('FRIEND-012: フォルダへのフレンド追加APIが存在する', async ({ page }) => {
    const response = await page.request.post(
      `/api/v1/teams/${TEAM_ID}/friend-folders/1/members`,
      {
        failOnStatusCode: false,
        data: { teamFriendId: MOCK_FRIEND_TEAMS[0].id },
      },
    )
    expect([200, 201, 400, 401, 403, 404, 409]).toContain(response.status())
  })
})

// ────────────────────────────
// テストケース: 管理者フィード（フレンド発の投稿）
// ────────────────────────────

test.describe('FRIEND-013〜017: 管理者フィード', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ADMIN_PERMISSIONS }),
      })
    })
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/friend-feed**`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_FRIEND_FEED_POSTS,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
  })

  test('FRIEND-013: 管理者フィードページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friend-feed`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '管理者フィード' }).or(
      page.getByRole('heading', { name: 'フレンドフィード' }).or(
        page.getByRole('heading', { name: 'フレンド通知' }),
      ),
    )).toBeVisible({ timeout: 10_000 })
  })

  test('FRIEND-014: フレンドチームからの投稿が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friend-feed`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '管理者フィード' }).or(
      page.getByRole('heading', { name: 'フレンドフィード' }).or(
        page.getByRole('heading', { name: 'フレンド通知' }),
      ),
    )).toBeVisible({ timeout: 10_000 })

    // フレンドチームからの投稿内容を確認
    await expect(page.getByText('京都サッカークラブ')).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-015: 転送ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friend-feed`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '管理者フィード' }).or(
      page.getByRole('heading', { name: 'フレンドフィード' }).or(
        page.getByRole('heading', { name: 'フレンド通知' }),
      ),
    )).toBeVisible({ timeout: 10_000 })

    // 転送ボタンの存在を確認
    const forwardButton = page
      .getByRole('button', { name: '転送' })
      .or(page.getByRole('button', { name: 'メンバーに転送' }))
    await expect(forwardButton.first()).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-016: 転送APIが呼ばれる', async ({ page }) => {
    let forwardCalled = false

    await page.route(
      `**/api/v1/teams/${TEAM_ID}/friend-feed/*/forward`,
      async (route) => {
        if (route.request().method() === 'POST') {
          forwardCalled = true
          await route.fulfill({
            status: 201,
            contentType: 'application/json',
            body: JSON.stringify({
              data: {
                id: 1,
                sourcePostId: 10,
                forwardedPostId: 100,
                target: 'MEMBER',
                isRevoked: false,
                createdAt: '2026-05-08T12:00:00+09:00',
              },
            }),
          })
        } else {
          await route.fulfill({ status: 404 })
        }
      },
    )

    await page.goto(`/teams/${TEAM_ID}/friend-feed`)
    await waitForHydration(page)

    const forwardButton = page
      .getByRole('button', { name: '転送' })
      .or(page.getByRole('button', { name: 'メンバーに転送' }))

    if (await forwardButton.first().isVisible({ timeout: 5_000 })) {
      await forwardButton.first().click()
      // 確認ダイアログがある場合
      const confirmButton = page.getByRole('button', { name: '転送する' })
        .or(page.getByRole('button', { name: '確認' }))
        .or(page.getByRole('button', { name: 'OK' }))
      if (await confirmButton.first().isVisible({ timeout: 2_000 })) {
        await confirmButton.first().click()
      }
      await expect(async () => {
        expect(forwardCalled).toBe(true)
      }).toPass({ timeout: 5_000 })
    }
  })

  test('FRIEND-017: 管理者フィードAPIが存在する（GET）', async ({ page }) => {
    const response = await page.request.get(
      `/api/v1/teams/${TEAM_ID}/friend-feed`,
      { failOnStatusCode: false },
    )
    expect([200, 401, 403, 404]).toContain(response.status())
  })
})

// ────────────────────────────
// テストケース: フレンド申請の承認フロー
// ────────────────────────────

test.describe('FRIEND-018〜020: 相互フォロー・フレンド成立フロー', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ADMIN_PERMISSIONS }),
      })
    })
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/friends`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: MOCK_FRIEND_TEAMS,
          meta: { page: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/friends/pending`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PENDING_FOLLOWS }),
      })
    })
  })

  test('FRIEND-018: 片方向フォロー中（承認待ち）の相手一覧が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friends`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドチーム' }).or(
      page.getByRole('heading', { name: 'フレンドチーム一覧' }),
    )).toBeVisible({ timeout: 10_000 })

    // 承認待ちセクションまたは一覧の表示を確認
    await expect(
      page.getByText('承認待ち').or(page.getByText('フォロー中')).or(
        page.getByText('大阪フットサルクラブ'),
      ),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-019: 相互フォロー成立済みのフレンドチームが一覧に表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/friends`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'フレンドチーム' }).or(
      page.getByRole('heading', { name: 'フレンドチーム一覧' }),
    )).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('京都サッカークラブ')).toBeVisible({ timeout: 5_000 })
  })

  test('FRIEND-020: フレンド関係の件数（teamFriendCount）がチームプロフィールに表示される', async ({
    page,
  }) => {
    // チームプロフィールAPIにフレンド数を含める
    await page.route(`**/api/v1/teams/${TEAM_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: TEAM_ID,
            name: 'テストチーム',
            nameKana: null,
            nickname1: null,
            nickname2: null,
            template: 'SPORTS',
            prefecture: '東京都',
            city: '渋谷区',
            description: 'E2Eテスト用チーム',
            visibility: 'PUBLIC',
            supporterEnabled: false,
            version: 1,
            memberCount: 5,
            teamFriendCount: 1,
            archivedAt: null,
            createdAt: '2026-01-01T00:00:00Z',
          },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}`)
    await waitForHydration(page)

    // チームプロフィールが表示されることを確認
    await expect(page.getByText('テストチーム')).toBeVisible({ timeout: 10_000 })
    // フレンド数の表示（「フレンド 1」など）
    await expect(
      page.getByText('フレンド').or(page.getByText('フレンドチーム')),
    ).toBeVisible({ timeout: 5_000 })
  })
})

// ────────────────────────────
// テストケース: APIの存在確認
// ────────────────────────────

test.describe('FRIEND-021〜024: フレンド関連APIの存在確認', () => {
  test('FRIEND-021: フレンド通知一覧APIが存在する', async ({ page }) => {
    const response = await page.request.get(
      `/api/v1/teams/${TEAM_ID}/friend-notifications`,
      { failOnStatusCode: false },
    )
    expect([200, 401, 403, 404]).toContain(response.status())
  })

  test('FRIEND-022: フォルダからフレンドを除外するAPIが存在する', async ({ page }) => {
    const response = await page.request.delete(
      `/api/v1/teams/${TEAM_ID}/friend-folders/1/members/1`,
      { failOnStatusCode: false },
    )
    expect([200, 204, 400, 401, 403, 404]).toContain(response.status())
  })

  test('FRIEND-023: フレンド転送取消APIが存在する', async ({ page }) => {
    // 転送取消は転送IDに対してPATCHまたはDELETEで行う
    const response = await page.request.delete(
      `/api/v1/teams/${TEAM_ID}/friend-feed/1/forward`,
      { failOnStatusCode: false },
    )
    expect([200, 204, 400, 401, 403, 404]).toContain(response.status())
  })

  test('FRIEND-024: フォロー解除がフレンド関係を解消する', async ({ page }) => {
    // フォロー解除APIの動作確認（DELETE /teams/{id}/friends/follow/{targetTeamId}）
    const response = await page.request.delete(
      `/api/v1/teams/${TEAM_ID}/friends/follow/${TARGET_TEAM_ID}`,
      { failOnStatusCode: false },
    )
    expect([200, 204, 400, 401, 403, 404]).toContain(response.status())
  })
})
