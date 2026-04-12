import { test, expect } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { TEAM_ID, mockTeam, mockTeamFeatureApis } from '../teams/helpers'

const MOCK_SCREENS = [
  {
    id: 1,
    scopeType: 'TEAM',
    scopeId: TEAM_ID,
    name: 'エントランスモニター',
    description: 'エントランスに設置した大型モニター',
    layout: 'FULLSCREEN',
    defaultSlideDuration: 10,
    transitionEffect: 'FADE',
    isActive: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    scopeType: 'TEAM',
    scopeId: TEAM_ID,
    name: 'ロビーディスプレイ',
    description: null,
    layout: 'SPLIT_HORIZONTAL',
    defaultSlideDuration: 15,
    transitionEffect: 'SLIDE',
    isActive: false,
    createdAt: '2026-02-01T00:00:00Z',
  },
]

const MOCK_SLOTS = [
  {
    id: 1,
    screenId: 1,
    slotType: 'ANNOUNCEMENT',
    contentSourceId: null,
    durationSeconds: 10,
    displayCondition: null,
    sortOrder: 0,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 2,
    screenId: 1,
    slotType: 'IMAGE',
    contentSourceId: 'image-123',
    durationSeconds: 5,
    displayCondition: null,
    sortOrder: 1,
    createdAt: '2026-01-02T00:00:00Z',
  },
]

const MOCK_TOKENS = [
  {
    id: 1,
    screenId: 1,
    token: 'sgn_tok_xxxxxxxxxxx',
    label: 'エントランスPC',
    lastSeenAt: '2026-04-10T08:00:00Z',
    createdAt: '2026-01-01T00:00:00Z',
  },
]

test.describe('SIGNAGE: デジタルサイネージモード', () => {
  test.beforeEach(async ({ page }) => {
    await mockTeam(page)
    await mockTeamFeatureApis(page)
  })

  test('SIGNAGE-001: サイネージページが表示される', async ({ page }) => {
    await page.route('**/api/signage/screens**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/signage`)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: 'デジタルサイネージ' })).toBeVisible({
      timeout: 10_000,
    })
    await expect(page.getByRole('button', { name: '画面を追加' })).toBeVisible()
  })

  test('SIGNAGE-002: スロット一覧の取得と表示（GET）', async ({ page }) => {
    await page.route('**/api/signage/screens**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SCREENS }),
      })
    })
    await page.route('**/api/signage/screens/1/slots', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SLOTS }),
      })
    })
    await page.route('**/api/signage/screens/1/tokens', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TOKENS }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/signage`)
    await waitForHydration(page)

    await expect(page.getByText('エントランスモニター')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('ロビーディスプレイ')).toBeVisible()

    // 管理ボタンをクリックしてスロット一覧を確認
    await page.getByRole('button', { name: '管理' }).first().click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })
  })

  test('SIGNAGE-003: スロットを設定できる（POST）', async ({ page }) => {
    let slotCreateCalled = false

    await page.route('**/api/signage/screens**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SCREENS }),
      })
    })
    await page.route('**/api/signage/screens/1/slots', async (route) => {
      if (route.request().method() === 'POST') {
        slotCreateCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 3,
              screenId: 1,
              slotType: 'URL',
              contentSourceId: 'https://example.com',
              durationSeconds: 20,
              displayCondition: null,
              sortOrder: 2,
              createdAt: '2026-04-12T00:00:00Z',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_SLOTS }),
        })
      }
    })
    await page.route('**/api/signage/screens/1/tokens', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TOKENS }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/signage`)
    await waitForHydration(page)

    await expect(page.getByText('エントランスモニター')).toBeVisible({ timeout: 10_000 })

    // 管理パネルを開く
    await page.getByRole('button', { name: '管理' }).first().click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

    // スロット追加ボタンをクリック
    await page.getByRole('button', { name: 'スロット追加' }).click()
    await expect(page.getByText('スロットを追加')).toBeVisible({ timeout: 10_000 })

    // 表示時間を入力して追加
    await page.getByRole('button', { name: '追加' }).click()

    expect(slotCreateCalled).toBe(true)
  })

  test('SIGNAGE-004: サイネージトークンを発行できる（POST /token）', async ({ page }) => {
    let tokenIssueCalled = false

    await page.route('**/api/signage/screens**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SCREENS }),
      })
    })
    await page.route('**/api/signage/screens/1/slots', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SLOTS }),
      })
    })
    await page.route('**/api/signage/screens/1/tokens', async (route) => {
      if (route.request().method() === 'POST') {
        tokenIssueCalled = true
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({
            data: {
              id: 2,
              screenId: 1,
              token: 'sgn_tok_new_yyy',
              label: null,
              lastSeenAt: null,
              createdAt: '2026-04-12T00:00:00Z',
            },
          }),
        })
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: MOCK_TOKENS }),
        })
      }
    })

    await page.goto(`/teams/${TEAM_ID}/signage`)
    await waitForHydration(page)

    await expect(page.getByText('エントランスモニター')).toBeVisible({ timeout: 10_000 })

    // 管理パネルを開く
    await page.getByRole('button', { name: '管理' }).first().click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

    // トークン発行ボタンをクリック
    await page.getByRole('button', { name: 'トークン発行' }).click()

    expect(tokenIssueCalled).toBe(true)
  })

  test('SIGNAGE-005: サイネージトークンが表示される', async ({ page }) => {
    await page.route('**/api/signage/screens**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SCREENS }),
      })
    })
    await page.route('**/api/signage/screens/1/slots', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SLOTS }),
      })
    })
    await page.route('**/api/signage/screens/1/tokens', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TOKENS }),
      })
    })

    await page.goto(`/teams/${TEAM_ID}/signage`)
    await waitForHydration(page)

    await expect(page.getByText('エントランスモニター')).toBeVisible({ timeout: 10_000 })

    // 管理パネルを開いてトークンを確認
    await page.getByRole('button', { name: '管理' }).first().click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

    await expect(page.getByText('sgn_tok_xxxxxxxxxxx')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('エントランスPC')).toBeVisible()
  })

  test('SIGNAGE-006: コンテンツを削除できる（DELETE）', async ({ page }) => {
    let slotDeleteCalled = false

    await page.route('**/api/signage/screens**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SCREENS }),
      })
    })
    await page.route('**/api/signage/screens/1/slots/1', async (route) => {
      if (route.request().method() === 'DELETE') {
        slotDeleteCalled = true
        await route.fulfill({ status: 204 })
      } else {
        await route.continue()
      }
    })
    await page.route('**/api/signage/screens/1/slots', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_SLOTS }),
      })
    })
    await page.route('**/api/signage/screens/1/tokens', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: MOCK_TOKENS }),
      })
    })

    // dialog の confirm をモック
    page.on('dialog', (dialog) => dialog.accept())

    await page.goto(`/teams/${TEAM_ID}/signage`)
    await waitForHydration(page)

    await expect(page.getByText('エントランスモニター')).toBeVisible({ timeout: 10_000 })

    // 管理パネルを開く
    await page.getByRole('button', { name: '管理' }).first().click()
    await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 })

    // スロットの削除ボタンをクリック
    const slotDeleteBtn = page
      .getByRole('dialog')
      .locator('button')
      .filter({ has: page.locator('.pi-trash') })
      .first()
    await slotDeleteBtn.click()

    expect(slotDeleteCalled).toBe(true)
  })
})
