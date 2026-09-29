import { expect, test } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const TEAM_SLUG = 'fc-u-18'

test.describe('CMP-260928-1214: 一般メンバーのクイック確認導線', () => {
  test.setTimeout(300_000)

  test('一般メンバーの回覧板には管理者向けクイック確認導線を表示しない', async ({ page }) => {
    const [permissionsResponse, circulationResponse] = await Promise.all([
      page.waitForResponse(response =>
        response.url().includes(`/api/v1/teams/${TEAM_SLUG}/me/permissions`)
        && response.request().method() === 'GET',
        { timeout: 180_000 },
      ),
      page.waitForResponse(response =>
        response.url().includes(`/api/v1/teams/${TEAM_SLUG}/circulations`)
        && response.request().method() === 'GET',
        { timeout: 180_000 },
      ),
      page.goto(`/teams/${TEAM_SLUG}/circulation`, { waitUntil: 'domcontentloaded' }),
    ])

    expect(permissionsResponse.status()).toBe(200)
    expect(circulationResponse.status()).toBe(200)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回覧板', exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'クイック確認を開く' })).toHaveCount(0)
    await expect(page.getByText('情報を取得できませんでした')).not.toBeVisible()
  })
})
