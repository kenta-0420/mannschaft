import { expect, test } from '@playwright/test'
import { setupMemberAuth } from './_helpers'

const teams = [
  {
    id: 42,
    slug: 'team-a',
    name: 'チームA',
    nickname1: null,
    iconUrl: null,
    role: 'MEMBER',
    template: 'SPORTS',
    memberCount: 10,
  },
  {
    id: 43,
    slug: 'team-b',
    name: 'チームB',
    nickname1: null,
    iconUrl: null,
    role: 'MEMBER',
    template: 'SPORTS',
    memberCount: 8,
  },
]

test.describe('/my/shift-request 初期取得のfail-safe', () => {
  test('通信保留を有限時間で解除し、再試行で回復する', async ({ page }) => {
    test.setTimeout(180_000)
    let respond = false

    await setupMemberAuth(page)

    await page.route('**/api/v1/me/teams', async (route) => {
      if (!respond) {
        await new Promise<void>(() => {})
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: teams }),
      })
    })

    await page.goto('/my/shift-request', { waitUntil: 'domcontentloaded' })

    await expect(page.getByRole('alert')).toContainText(
      'チーム情報の読み込みに時間がかかっています',
      { timeout: 150_000 },
    )

    respond = true
    await page.getByRole('button', { name: '再試行' }).click()

    await expect(page.getByText('チームA')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByText('チームB')).toBeVisible({ timeout: 10_000 })
  })
})
