import { test, expect, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API_BASE = process.env.API_BASE_URL ?? 'http://127.0.0.1:8080'
const USER = { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' }
const RESIDENTS = [
  { name: '管理者', email: 'e2e-admin@test.mannschaft.local', password: 'TestPass2026!' },
  { name: '一般ユーザー', ...USER },
  { name: '組織外ユーザー', email: 'e2e-outsider@test.mannschaft.local', password: 'TestPass2026!' },
]

test.describe('郵便番号ポリシー実機E2E', () => {
  test.setTimeout(120_000)

  test('公開policy APIが実BEからJP規則を返す', async ({ request }) => {
    const response = await request.get(`${API_BASE}/api/v1/postal-code/policies`)
    expect(response.status()).toBe(200)
    const body = await response.json()
    const jp = (body.data as Array<{ countryCode: string; pattern: string; example: string }>).find(item => item.countryCode === 'JP')
    expect(jp).toMatchObject({ countryCode: 'JP', pattern: '^\\d{3}-?\\d{4}$', example: '123-4567' })
  })

  test('登録UIは郵便番号をmaxlength=20で切り詰める', async ({ page }) => {
    await page.goto('/register', { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    const input = page.locator('input#postalCode')
    await expect(input).toHaveAttribute('maxlength', '20')
    await input.fill('123456789012345678901')
    await expect(input).toHaveValue('12345678901234567890')
  })

  test('設定UIは郵便番号をmaxlength=20で切り詰める', async ({ browser }) => {
    const context = await browser.newContext()
    const page: Page = await context.newPage()
    try {
      await loginViaApi(page, USER, { apiBaseUrl: API_BASE })
      await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
      await waitForHydration(page)
      const postal = page.getByTestId('profile-postal-code')
      await expect(postal).toHaveAttribute('maxlength', '20')
      await postal.fill('123456789012345678901')
      await expect(postal).toHaveValue('12345678901234567890')
    } finally {
      await context.close()
    }
  })

  test('設定更新APIへ21文字を直送すると400で拒否する', async ({ page }) => {
    await loginViaApi(page, USER, { apiBaseUrl: API_BASE })
    const response = await page.request.put(`${API_BASE}/api/v1/users/me`, {
      data: { postalCode: '123456789012345678901' },
      headers: { 'Content-Type': 'application/json' },
    })
    expect(response.status()).toBe(400)
  })

  test('3住民の独立セッションで同じ郵便番号上限が適用される', async ({ browser }) => {
    for (const resident of RESIDENTS) {
      const context = await browser.newContext()
      const page = await context.newPage()
      try {
        await loginViaApi(page, resident, { apiBaseUrl: API_BASE })
        await page.goto('/settings/account', { waitUntil: 'domcontentloaded' })
        await waitForHydration(page)

        const postal = page.getByTestId('profile-postal-code')
        await expect(postal, `${resident.name}の郵便番号欄`).toHaveAttribute('maxlength', '20')
        await postal.fill('123456789012345678901')
        await expect(postal, `${resident.name}も20文字で切り詰められる`)
          .toHaveValue('12345678901234567890')
      } finally {
        await context.close()
      }
    }
  })
})
