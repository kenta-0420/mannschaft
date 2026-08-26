import type { Page } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'
import { loginViaApi } from '../fixtures/auth'

/**
 * 認証つき遷移。
 *
 * storageState のアクセストークンが実行中に期限切れになり、リフレッシュトークンの
 * ローテーション競合等で /login に飛ばされた場合は API 再ログインしてから
 * 目的ルートへ再遷移する（本suite は30件規模で数分かかるため、単発の
 * storageState だけでは持たない。feedback_e2e_real_single_session_token_rotation 参照）。
 */
export async function gotoAuthed(page: Page, route: string): Promise<void> {
  await page.goto(route, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  if (page.url().includes('/login')) {
    await loginViaApi(
      page,
      {
        email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
        password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
      },
      { apiBaseUrl: process.env.API_BASE_URL ?? 'http://localhost:8080' },
    )
    await page.goto(route, { waitUntil: 'domcontentloaded' })
    await waitForHydration(page)
    if (page.url().includes('/login')) {
      throw new Error(`再ログイン後も /login に飛ばされる: ${route}`)
    }
  }
}
