import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { test, expect } from '@playwright/test'
import { loginViaApi } from '../../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3005'
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8085'
const PASSWORD = 'TestPass2026!'

type SeedSummary = {
  orgId: number
  tournaments: Record<string, { tournamentId: number }>
}

const seed = JSON.parse(
  readFileSync(resolve(process.cwd(), '../backend/scripts/f087-e2e-seed-summary.json'), 'utf8'),
) as SeedSummary
const tournamentId = seed.tournaments.PUBLIC!.tournamentId
const tournamentPath = `/organizations/${seed.orgId}/tournaments/${tournamentId}`
const scoreEntryPath = `${tournamentPath}/score-entry`

test.describe('CMP-260820-1015 スコア一括入力の実機回帰', () => {
  test('組織管理者が画面からスコアを一括保存し、更新値を再表示できる', async ({ page }) => {
    await loginViaApi(
      page,
      { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD },
      { apiBaseUrl: API_BASE_URL },
    )

    await page.goto(`${BASE_URL}${tournamentPath}`, { waitUntil: 'domcontentloaded' })
    const scoreEntryLink = page.getByTestId('score-entry-nav-link')
    await expect(scoreEntryLink).toBeVisible()
    await scoreEntryLink.click()
    await expect(page).toHaveURL(new RegExp(`${scoreEntryPath}$`))

    const grid = page.getByTestId('score-entry-grid')
    await expect(grid).toBeVisible()
    const homeInput = page.getByTestId('score-entry-home-input').first()
    const awayInput = page.getByTestId('score-entry-away-input').first()
    await expect(homeInput).toBeVisible()
    await homeInput.fill('3')
    await awayInput.fill('1')

    const saved = page.waitForResponse(
      (response) => response.url().includes('/scores/batch') && response.request().method() === 'PUT',
    )
    await page.getByTestId('score-entry-save-button').click()
    expect((await saved).status()).toBe(204)
    await expect(page.getByText('スコアを保存しました').first()).toBeVisible()
    await expect(homeInput).toHaveValue('3')
    await expect(awayInput).toHaveValue('1')
  })

  test('一般会員にはスコア入力導線を表示せず、直URLでも編集画面を出さない', async ({ page }) => {
    await loginViaApi(
      page,
      { email: 'e2e-user@test.mannschaft.local', password: PASSWORD },
      { apiBaseUrl: API_BASE_URL },
    )

    await page.goto(`${BASE_URL}${tournamentPath}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByTestId('score-entry-nav-link')).toHaveCount(0)
    await page.goto(`${BASE_URL}${scoreEntryPath}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByText('この大会のスコアを編集する権限がありません')).toBeVisible()
    await expect(page.getByTestId('score-entry-grid')).toHaveCount(0)
  })

  test('非所属ユーザーは他組織の直URLからスコア入力画面を開けない', async ({ page }) => {
    await loginViaApi(
      page,
      { email: 'f087-outsider@test.mannschaft.local', password: PASSWORD },
      { apiBaseUrl: API_BASE_URL },
    )

    await page.goto(`${BASE_URL}${scoreEntryPath}`, { waitUntil: 'domcontentloaded' })
    await expect(page.getByText('この大会のスコアを編集する権限がありません')).toBeVisible()
    await expect(page.getByTestId('score-entry-grid')).toHaveCount(0)
  })
})
