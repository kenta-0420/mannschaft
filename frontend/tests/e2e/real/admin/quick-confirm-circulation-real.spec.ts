import { expect, test } from '@playwright/test'
import { waitForHydration } from '../../helpers/wait'

const TEAM_SLUG = 'fc-u-18'
const CIRCULATION_PATH = `/teams/${TEAM_SLUG}/circulation`
const QUICK_CONFIRM_PATH = `/teams/${TEAM_SLUG}/settings/confirmable-notifications`

test.describe('CMP-260928-1214: クイック確認と回覧板の実機導線', () => {
  // Nuxt dev の初回ルートコンパイルと実 API 応答が競合しないよう直列で確認する。
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(300_000)

  test('管理者が回覧板からクイック確認へ移動し、用途を判別して戻れる', async ({ page }) => {
    const [permissionsResponse, circulationResponse] = await Promise.all([
      page.waitForResponse(response =>
        response.url().includes(`/api/v1/teams/${TEAM_SLUG}/me/permissions`)
        && response.request().method() === 'GET',
      ),
      page.waitForResponse(response =>
        response.url().includes(`/api/v1/teams/${TEAM_SLUG}/circulations`)
        && response.request().method() === 'GET',
      ),
      page.goto(CIRCULATION_PATH, { waitUntil: 'domcontentloaded' }),
    ])

    expect(permissionsResponse.status()).toBe(200)
    expect(circulationResponse.status()).toBe(200)
    await waitForHydration(page)

    await expect(page.getByRole('heading', { name: '回覧板', exact: true })).toBeVisible()
    await expect(page.getByText('文書への押印や回覧順序を管理する機能です。')).toBeVisible()

    const quickConfirmLink = page.getByRole('link', { name: 'クイック確認を開く' })
    await expect(quickConfirmLink).toHaveAttribute('href', QUICK_CONFIRM_PATH)
    await quickConfirmLink.click()

    await page.waitForURL(new RegExp(`${QUICK_CONFIRM_PATH.replaceAll('/', '\\/')}$`), { timeout: 180_000 })
    await expect(page.getByRole('heading', { name: 'クイック確認', exact: true })).toBeVisible()
    await expect(page.getByText('短い連絡への確認を集める機能です。')).toBeVisible()

    const circulationLink = page.getByRole('link', { name: '回覧板を開く' })
    await expect(circulationLink).toHaveAttribute('href', CIRCULATION_PATH)
    await circulationLink.click()

    await page.waitForURL(new RegExp(`${CIRCULATION_PATH.replaceAll('/', '\\/')}$`), { timeout: 180_000 })
    await expect(page.getByRole('heading', { name: '回覧板', exact: true })).toBeVisible()
  })

  test('既存のクイック確認URLへ直接アクセスしても名称と回覧板導線を維持する', async ({ page }) => {
    const response = await page.goto(QUICK_CONFIRM_PATH, { waitUntil: 'domcontentloaded' })
    expect(response?.status()).toBe(200)
    await waitForHydration(page)

    await expect(page).toHaveURL(new RegExp(`${QUICK_CONFIRM_PATH.replaceAll('/', '\\/')}$`))
    await expect(page.getByRole('heading', { name: 'クイック確認', exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: '回覧板を開く' })).toHaveAttribute('href', CIRCULATION_PATH)
    await expect(page.getByText('緊急確認', { exact: true })).toHaveCount(0)
  })
})
