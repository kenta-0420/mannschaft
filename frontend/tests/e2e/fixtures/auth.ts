import { test as base, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

export type AuthFixtures = {
  authenticatedPage: Page
  adminPage: Page
}

/**
 * 認証済みページフィクスチャ
 *
 * playwright.config.ts の storageState で事前保存した認証情報を使うため、
 * このフィクスチャは既にログイン済みの Page を提供する。
 * setup/auth.setup.ts で storageState を生成すること。
 */
export const test = base.extend<AuthFixtures>({
  authenticatedPage: async ({ page }, use) => {
    await use(page)
  },
  adminPage: async ({ page }, use) => {
    await use(page)
  },
})

export { expect } from '@playwright/test'

/**
 * ログインユーティリティ（setup ファイルから呼び出す）
 */
export async function loginAs(
  page: Page,
  credentials: { email: string; password: string },
): Promise<void> {
  await page.goto('/login')
  await waitForHydration(page)

  // PrimeVue コンポーネントは fill() だと v-model に値が反映されない場合がある
  // click() でフォーカスしてから type() でキー入力する
  const emailInput = page.locator('input#email')
  await emailInput.click()
  await emailInput.pressSequentially(credentials.email, { delay: 10 })

  const passwordInput = page.locator('input[type="password"]')
  await passwordInput.click()
  await passwordInput.pressSequentially(credentials.password, { delay: 10 })

  await page.getByRole('button', { name: 'ログイン' }).click()
  // ログイン成功後は '/' にリダイレクトされる（login.vue の navigateTo(redirect) デフォルト値）
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 15_000 })
}
