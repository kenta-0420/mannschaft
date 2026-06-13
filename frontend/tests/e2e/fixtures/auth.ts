import { test as base, type Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

/**
 * API ログインユーティリティ（storageState 生成用）。
 *
 * UI フォームを駆動せず、バックエンドの POST /api/v1/auth/login を直接叩いて認証する。
 * page.request は page のブラウザコンテキストと Cookie ジャーを共有するため、
 * 発行された access_token / refresh_token の HttpOnly Cookie がそのまま context に入る。
 * さらに /api/v1/users/me でプロフィールを取得し、アプリが認証状態判定に使う
 * localStorage['currentUser']（useAuthStore.loadFromStorage 参照）を書き込む。
 *
 * これによりログインページのハイドレーション完了に依存せず、確実に認証済み状態を作れる。
 */
export async function loginViaApi(
  page: Page,
  credentials: { email: string; password: string },
): Promise<void> {
  const loginRes = await page.request.post('/api/v1/auth/login', {
    data: { email: credentials.email, password: credentials.password },
  })
  if (!loginRes.ok()) {
    throw new Error(
      `API ログイン失敗 (${credentials.email}): ${loginRes.status()} ${await loginRes.text()}`,
    )
  }

  // フルプロフィール取得（access_token Cookie は page.request が自動送信する）
  const meRes = await page.request.get('/api/v1/users/me')
  if (!meRes.ok()) {
    throw new Error(`/api/v1/users/me 取得失敗: ${meRes.status()} ${await meRes.text()}`)
  }
  const me = (await meRes.json()).data as {
    id: number
    email: string
    lastName: string
    firstName: string
    avatarUrl: string | null
    systemRole: string | null
    timezone: string | null
  }

  // アプリのオリジンへ遷移してから localStorage を書き込む（オリジンに紐づくため）。
  // useAuthStore は currentUser の有無で isAuthenticated を判定する。
  await page.goto('/')
  await page.evaluate(
    (user) => {
      localStorage.setItem('currentUser', JSON.stringify(user))
    },
    {
      id: me.id,
      email: me.email,
      fullName: `${me.lastName} ${me.firstName}`,
      profileImageUrl: me.avatarUrl,
      systemRole: me.systemRole ?? undefined,
      timezone: me.timezone ?? undefined,
    },
  )
}

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
  // dev サーバーはダッシュボード SSR が重いため commit まで待ち、タイムアウトを 30s に延ばす
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
    waitUntil: 'commit',
  })
}
