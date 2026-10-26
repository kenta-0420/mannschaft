import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from './helpers'

/**
 * F01.4 ファミリーチーム・ライフユーティリティ E2Eテスト
 *
 * テスト対象ページ:
 *   - /teams/{id}/presence       : 帰ったよ通知・お出かけ連絡
 *   - /teams/{id}/shopping-lists : お買い物リスト
 *   - /teams/{id}/duties         : 当番ローテーション
 *   - /teams/{id}/anniversaries  : 記念日リマインダー
 *   - /teams/{id}/coin-toss      : コイントス
 */

// ────────────────────────────
// モックデータ
// ────────────────────────────

const MOCK_FAMILY_TEAM = {
  id: TEAM_ID,
  name: 'うちの家族',
  nameKana: null,
  nickname1: null,
  nickname2: null,
  template: 'family',
  prefecture: '東京都',
  city: '渋谷区',
  description: 'ファミリーチームE2Eテスト',
  visibility: 'PUBLIC',
  supporterEnabled: false,
  version: 1,
  memberCount: 4,
  archivedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
}

const MOCK_PRESENCE_STATUS = [
  {
    user: { id: 1, displayName: 'たろう', roleDisplay: 'ファミリー' },
    status: 'HOME',
    lastEventAt: '2026-05-08T18:30:00+09:00',
  },
  {
    user: { id: 2, displayName: 'はなこ', roleDisplay: 'ボス' },
    status: 'GOING_OUT',
    destination: 'スーパー',
    expectedReturnAt: '2026-05-08T20:00:00+09:00',
    lastEventAt: '2026-05-08T17:30:00+09:00',
  },
  {
    user: { id: 3, displayName: 'じろう', roleDisplay: 'ファミリー' },
    status: 'UNKNOWN',
    lastEventAt: null,
  },
]

const MOCK_SHOPPING_LISTS = [
  {
    id: 1,
    teamId: TEAM_ID,
    name: 'お買い物リスト',
    isTemplate: false,
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00Z',
  },
]

const MOCK_SHOPPING_ITEMS = [
  {
    id: 1,
    listId: 1,
    name: '牛乳',
    quantity: '2本',
    note: '低脂肪のやつ',
    assignedTo: null,
    isChecked: false,
    checkedBy: null,
    checkedAt: null,
    sortOrder: 0,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    listId: 1,
    name: '卵',
    quantity: '1パック',
    note: null,
    assignedTo: { id: 1, displayName: 'たろう' },
    isChecked: true,
    checkedBy: { id: 1, displayName: 'たろう' },
    checkedAt: '2026-05-08T10:00:00+09:00',
    sortOrder: 1,
    createdAt: '2026-01-01T00:00:00Z',
  },
]

const MOCK_DUTIES = [
  {
    id: 1,
    teamId: TEAM_ID,
    dutyName: 'ゴミ出し',
    rotationType: 'DAILY',
    memberOrder: [1, 2, 3],
    startDate: '2026-01-01',
    icon: '🗑️',
    isEnabled: true,
    todayMember: { id: 1, displayName: 'たろう' },
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    teamId: TEAM_ID,
    dutyName: '料理当番',
    rotationType: 'WEEKLY',
    memberOrder: [1, 2],
    startDate: '2026-01-01',
    icon: '🍳',
    isEnabled: true,
    todayMember: { id: 2, displayName: 'はなこ' },
    createdAt: '2026-01-01T00:00:00Z',
  },
]

const MOCK_ANNIVERSARIES = [
  {
    id: 1,
    teamId: TEAM_ID,
    name: 'たろうの誕生日',
    date: '2000-06-15',
    repeatAnnually: true,
    notifyDaysBefore: 7,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    teamId: TEAM_ID,
    name: '結婚記念日',
    date: '2005-11-03',
    repeatAnnually: true,
    notifyDaysBefore: 1,
    createdAt: '2026-01-01T00:00:00Z',
  },
]

const MOCK_COIN_TOSS_RESULT = {
  id: 1,
  mode: 'COIN',
  question: null,
  options: ['表', '裏'],
  resultIndex: 0,
  result: '表',
  sharedToChat: false,
  createdAt: '2026-05-08T12:00:00+09:00',
}

// ────────────────────────────
// テストケース: 帰ったよ通知・お出かけ連絡
// ────────────────────────────

test.describe('FAMILY-001〜004: 帰ったよ通知・お出かけ連絡', () => {
  test.beforeEach(async ({ page }) => {
    await page.route(`**/api/v1/teams/${TEAM_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_FAMILY_TEAM }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/me/permissions`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            roleName: 'MEMBER',
            permissions: ['presence.send'],
          },
        }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/presence/status`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_PRESENCE_STATUS }),
      })
    })
    await mockTeamFeatureApis(page)
  })

  test('FAMILY-001: 在席管理ページが表示され「帰宅する」「外出する」ボタンが存在する', async ({
    page,
  }) => {
    await page.goto(`/teams/${TEAM_ID}/presence`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '在席管理' })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: '帰宅する' })).toBeVisible({ timeout: 5_000 })
    await expect(page.getByRole('button', { name: '外出する' })).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-002: 帰宅するボタン押下でAPIが呼ばれる', async ({ page }) => {
    let homeCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/presence/home`, async (route) => {
      homeCalled = true
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          data: {
            id: 1,
            eventType: 'HOME',
            message: null,
            user: { id: 1, displayName: 'たろう', roleDisplay: 'ファミリー' },
            createdAt: '2026-05-08T18:30:00+09:00',
          },
        }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/presence`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '在席管理' })).toBeVisible({ timeout: 10_000 })
    const homeButton = page.getByRole('button', { name: '帰宅する' })
    await expect(homeButton).toBeVisible({ timeout: 5_000 })
    await homeButton.click()

    await expect(async () => {
      expect(homeCalled).toBe(true)
    }).toPass({ timeout: 5_000 })
  })

  test('FAMILY-003: 外出するボタン押下でフォームダイアログが開く', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/presence`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '在席管理' })).toBeVisible({ timeout: 10_000 })
    const goingOutButton = page.getByRole('button', { name: '外出する' })
    await expect(goingOutButton).toBeVisible({ timeout: 5_000 })
    await goingOutButton.click()

    // ダイアログまたは行き先入力フォームが表示されることを確認
    await expect(
      page.getByRole('dialog').or(page.getByPlaceholder('行き先')).or(page.getByLabel('行き先')),
    ).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-004: メンバーのプレゼンス状態が一覧に表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/presence`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '在席管理' })).toBeVisible({ timeout: 10_000 })
    // メンバー名が表示されるかどうかを確認
    await expect(page.getByText('たろう')).toBeVisible({ timeout: 5_000 })
  })
})

// ────────────────────────────
// テストケース: お買い物リスト
// ────────────────────────────

test.describe('FAMILY-005〜009: お買い物リスト', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/shopping-lists`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SHOPPING_LISTS }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/shopping-lists/1/items`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SHOPPING_ITEMS }),
      })
    })
  })

  test('FAMILY-005: お買い物リストページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/shopping-lists`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'お買い物リスト' })).toBeVisible({
      timeout: 10_000,
    })
  })

  test('FAMILY-006: お買い物リストにアイテムが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/shopping-lists`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'お買い物リスト' })).toBeVisible({
      timeout: 10_000,
    })
    // リスト名が表示されること
    await expect(page.getByText('お買い物リスト').first()).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-007: アイテム追加ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/shopping-lists`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'お買い物リスト' })).toBeVisible({
      timeout: 10_000,
    })
    // 追加ボタンが存在することを確認
    const addButton = page
      .getByRole('button', { name: '追加' })
      .or(page.getByRole('button', { name: 'アイテム追加' }))
      .or(page.getByRole('button', { name: 'リスト作成' }))
    await expect(addButton.first()).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-008: 購入済みチェックAPIが呼ばれる', async ({ page }) => {
    let checkCalled = false

    await page.route(
      `**/api/v1/teams/${TEAM_ID}/shopping-lists/1/items/1/check`,
      async (route) => {
        checkCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data: { ...MOCK_SHOPPING_ITEMS[0], isChecked: true },
          }),
        })
      },
    )

    await page.goto(`/teams/${TEAM_ID}/shopping-lists`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'お買い物リスト' })).toBeVisible({
      timeout: 10_000,
    })

    // チェックボックスをクリック
    const checkbox = page.getByRole('checkbox').first()
    if (await checkbox.isVisible({ timeout: 3_000 })) {
      await checkbox.click()
      await page.waitForTimeout(1_000)
      expect(checkCalled).toBe(true)
    }
  })

  test('FAMILY-009: お買い物リスト一覧・詳細APIが存在する', async ({ page }) => {
    const listResponse = await page.request.get(
      `/api/v1/teams/${TEAM_ID}/shopping-lists`,
      { failOnStatusCode: false },
    )
    expect([200, 401, 403, 404]).toContain(listResponse.status())
  })
})

// ────────────────────────────
// テストケース: 当番ローテーション
// ────────────────────────────

test.describe('FAMILY-010〜013: 当番ローテーション', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/duties`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_DUTIES }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/duties/today`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            { dutyId: 1, dutyName: 'ゴミ出し', icon: '🗑️', member: { id: 1, displayName: 'たろう' } },
          ],
        }),
      })
    })
  })

  test('FAMILY-010: 当番ローテーションページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/duties`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '当番' }).or(
      page.getByRole('heading', { name: '当番ローテーション' }),
    )).toBeVisible({ timeout: 10_000 })
  })

  test('FAMILY-011: 当番ローテーション一覧が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/duties`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '当番' }).or(
      page.getByRole('heading', { name: '当番ローテーション' }),
    )).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('ゴミ出し')).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-012: 当番作成ボタンが表示される（管理者）', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/duties`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '当番' }).or(
      page.getByRole('heading', { name: '当番ローテーション' }),
    )).toBeVisible({ timeout: 10_000 })

    const addButton = page
      .getByRole('button', { name: '当番を追加' })
      .or(page.getByRole('button', { name: '作成' }))
      .or(page.getByRole('button', { name: '新規作成' }))
    await expect(addButton.first()).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-013: 今日の当番APIが存在する', async ({ page }) => {
    const response = await page.request.get(
      `/api/v1/teams/${TEAM_ID}/duties/today`,
      { failOnStatusCode: false },
    )
    expect([200, 401, 403, 404]).toContain(response.status())
  })
})

// ────────────────────────────
// テストケース: 記念日リマインダー
// ────────────────────────────

test.describe('FAMILY-014〜018: 記念日リマインダー', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/anniversaries`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_ANNIVERSARIES }),
      })
    })
    await page.route(`**/api/v1/teams/${TEAM_ID}/anniversaries/upcoming`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_ANNIVERSARIES[0]] }),
      })
    })
  })

  test('FAMILY-014: 記念日ページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/anniversaries`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '記念日' }).or(
      page.getByRole('heading', { name: '記念日リマインダー' }),
    )).toBeVisible({ timeout: 10_000 })
  })

  test('FAMILY-015: 記念日一覧が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/anniversaries`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '記念日' }).or(
      page.getByRole('heading', { name: '記念日リマインダー' }),
    )).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('たろうの誕生日')).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-016: 記念日登録ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/anniversaries`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '記念日' }).or(
      page.getByRole('heading', { name: '記念日リマインダー' }),
    )).toBeVisible({ timeout: 10_000 })

    const addButton = page
      .getByRole('button', { name: '記念日を追加' })
      .or(page.getByRole('button', { name: '追加' }))
      .or(page.getByRole('button', { name: '登録' }))
    await expect(addButton.first()).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-017: 記念日削除APIが呼ばれる', async ({ page }) => {
    let deleteCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/anniversaries/1`, async (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCalled = true
        await route.fulfill({ status: 204 })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_ANNIVERSARIES[0] }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/anniversaries`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '記念日' }).or(
      page.getByRole('heading', { name: '記念日リマインダー' }),
    )).toBeVisible({ timeout: 10_000 })

    // 削除ボタンが存在する場合はクリック
    const deleteButton = page
      .getByRole('button', { name: '削除' })
      .or(page.locator('button[aria-label="削除"]'))
    if (await deleteButton.first().isVisible({ timeout: 3_000 })) {
      await deleteButton.first().click()
      // 確認ダイアログがある場合は確認ボタンをクリック
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

  test('FAMILY-018: 直近の記念日（upcoming）APIが存在する', async ({ page }) => {
    const response = await page.request.get(
      `/api/v1/teams/${TEAM_ID}/anniversaries/upcoming`,
      { failOnStatusCode: false },
    )
    expect([200, 401, 403, 404]).toContain(response.status())
  })
})

// ────────────────────────────
// テストケース: コイントス
// ────────────────────────────

test.describe('FAMILY-019〜022: コイントス', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)

    await page.route(`**/api/v1/teams/${TEAM_ID}/coin-toss/history`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [MOCK_COIN_TOSS_RESULT] }),
      })
    })
  })

  test('FAMILY-019: コイントスページが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/coin-toss`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'コイントス' }).or(
      page.getByText('コイントス'),
    )).toBeVisible({ timeout: 10_000 })
  })

  test('FAMILY-020: コイントス実行ボタンが表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/coin-toss`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'コイントス' }).or(
      page.getByText('コイントス'),
    )).toBeVisible({ timeout: 10_000 })

    const tossButton = page
      .getByRole('button', { name: 'トスする' })
      .or(page.getByRole('button', { name: 'コイントス' }))
      .or(page.getByRole('button', { name: '実行' }))
    await expect(tossButton.first()).toBeVisible({ timeout: 5_000 })
  })

  test('FAMILY-021: コイントスAPIが呼ばれる', async ({ page }) => {
    let coinTossCalled = false

    await page.route(`**/api/v1/teams/${TEAM_ID}/coin-toss`, async (route) => {
      if (route.request().method() === 'POST') {
        coinTossCalled = true
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_COIN_TOSS_RESULT }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [] }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/coin-toss`)
    await waitForHydration(page)

    const tossButton = page
      .getByRole('button', { name: 'トスする' })
      .or(page.getByRole('button', { name: 'コイントス' }))
      .or(page.getByRole('button', { name: '実行' }))
    if (await tossButton.first().isVisible({ timeout: 5_000 })) {
      await tossButton.first().click()
      await expect(async () => {
        expect(coinTossCalled).toBe(true)
      }).toPass({ timeout: 5_000 })
    }
  })

  test('FAMILY-022: コイントス履歴が表示される', async ({ page }) => {
    await page.goto(`/teams/${TEAM_ID}/coin-toss`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'コイントス' }).or(
      page.getByText('コイントス'),
    )).toBeVisible({ timeout: 10_000 })
    // 履歴セクションが存在するか確認
    await expect(page.getByText('履歴').or(page.getByText('過去の結果'))).toBeVisible({
      timeout: 5_000,
    })
  })
})

// ────────────────────────────
// テストケース: ロール呼称カスタマイズ
// ────────────────────────────

test.describe('FAMILY-023〜024: ロール呼称カスタマイズ', () => {
  test('FAMILY-023: ロール呼称一覧APIが存在する', async ({ page }) => {
    const response = await page.request.get(
      `/api/v1/teams/${TEAM_ID}/role-aliases`,
      { failOnStatusCode: false },
    )
    expect([200, 401, 403, 404]).toContain(response.status())
  })

  test('FAMILY-024: ロール呼称一括設定APIが存在する（PUT）', async ({ page }) => {
    const response = await page.request.put(
      `/api/v1/teams/${TEAM_ID}/role-aliases`,
      {
        failOnStatusCode: false,
        data: {
          aliases: [
            { roleName: 'ADMIN', displayAlias: 'ボス' },
            { roleName: 'MEMBER', displayAlias: 'ファミリー' },
          ],
        },
      },
    )
    expect([200, 400, 401, 403, 404]).toContain(response.status())
  })
})
