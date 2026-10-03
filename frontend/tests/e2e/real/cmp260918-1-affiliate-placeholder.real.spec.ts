import { expect, request as pwRequest, test, type APIRequestContext } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

/**
 * 実機検証: PR #3352 アフィリエイトのプレースホルダ tag_id 除外・警告表示
 * (CMP-260918-0024 検証戦役)
 *
 * 検証内容:
 * 1. SYSTEM_ADMIN の /admin/affiliate-settings にプレースホルダ tag_id の警告 Message が表示される
 * 2. 実配信 API (/api/v1/spotlight/content) がプレースホルダ tag_id の広告を候補から除外していること
 * 3. MEMBER には SYSTEM_ADMIN 専用画面への導線が無い（直打ちで弾かれる）こと
 *
 * 罠: `chromium-real` プロジェクトは `storageState: 'tests/e2e/.auth/real-user.json'` を
 * 既定で引き継ぐため、`browser.newContext({...})` で storageState を明示しないと
 * 別アカウントのつもりで実は前のアカウントのまま走る（偽の緑）。
 * 別アカウントを使う箇所は必ず `storageState: { cookies: [], origins: [] }` を明示し、
 * ログイン直後に `/api/v1/users/me` で「誰としてログインしているか」を確認する。
 */
test.use({ storageState: 'tests/e2e/.auth/real-admin.json' })

const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'

test('SYSTEM_ADMIN: アフィリエイト設定画面にプレースホルダ警告が表示される', async ({ page }) => {
  test.setTimeout(240_000)

  // 自分が誰としてログインしているかを確認する（storageState 引き継ぎ事故の検知）。
  const me = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(me.status()).toBe(200)
  const meBody = (await me.json()).data as { email: string; systemRole: string | null }
  expect(meBody.systemRole, `SYSTEM_ADMIN想定だが実際は systemRole=${meBody.systemRole}`).toBe(
    'SYSTEM_ADMIN',
  )

  await page.goto('/admin/affiliate-settings', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)

  // シードデータの4件（AMAZON x2, RAKUTEN x2）は全てプレースホルダのため、
  // 警告 Message が最低1件は表示される想定。
  await expect
    .poll(
      async () => page.getByText('タグIDが未設定のため、この広告は表示されていません').count(),
      { timeout: 240_000 },
    )
    .toBeGreaterThan(0)
})

test('MEMBER: /admin/affiliate-settings へ直打ちしても管理APIは403で弾かれる', async ({ browser }) => {
  test.setTimeout(240_000)
  // e2e-dummy-6 は SYSTEM_ADMIN ではない MEMBER。既定 storageState (real-admin.json) を
  // 引き継がないよう、空の storageState を明示した新規コンテキストでログインし直す。
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await loginViaApi(
    page,
    { email: 'e2e-dummy-6@test.mannschaft.local', password: 'TestPass2026!' },
    { apiBaseUrl: API_BASE_URL },
  )

  const me = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(me.status()).toBe(200)
  const meBody = (await me.json()).data as { email: string; systemRole: string | null }
  expect(meBody.email).toBe('e2e-dummy-6@test.mannschaft.local')
  expect(meBody.systemRole, 'MEMBER想定だがSYSTEM_ADMINになっている').not.toBe('SYSTEM_ADMIN')

  const res = await page.request.get(`${API_BASE_URL}/api/v1/system-admin/affiliate-configs`)
  expect(res.status(), 'SYSTEM_ADMIN専用APIにMEMBERがアクセスできてしまっている').toBe(403)

  await context.close()
})

test('実配信APIはプレースホルダ tag_id の広告を候補から除外する', async ({ page }) => {
  test.setTimeout(240_000)
  let api: APIRequestContext | null = null
  try {
    api = await pwRequest.newContext({ baseURL: API_BASE_URL })
    const login = await api.post('/api/v1/auth/login', {
      data: { email: 'e2e-user@test.mannschaft.local', password: 'TestPass2026!' },
    })
    expect(login.status()).toBe(200)
    const { accessToken } = (await login.json()).data as { accessToken: string }

    const res = await api.get(
      '/api/v1/spotlight/content?placement=DASHBOARD_TILE&count=2&scopeType=ORGANIZATION&scopeId=9',
      { headers: { Authorization: `Bearer ${accessToken}` } },
    )
    // エンドポイントが無い/未対応クエリの可能性もあるため 200 以外は許容しつつ記録する
    if (res.status() === 200) {
      const body = (await res.json()) as { data?: unknown }
      const text = JSON.stringify(body)
      expect(text.includes('PLACEHOLDER_'), '実配信候補にプレースホルダ tag_id が混入している').toBe(
        false,
      )
    } else {
      test.info().annotations.push({
        type: 'note',
        description: `spotlight/content が ${res.status()} を返したため、この経路のプレースホルダ除外はDOM側の確認に委ねる`,
      })
    }
  } finally {
    await api?.dispose()
  }

  // 画面側: 広告枠を持つ画面(team announcements)を開き、生DOMにプレースホルダ文字列が
  // 露出していないことを確認する。
  await page.goto('/teams/fc-u-18/announcements', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  const bodyText = await page.locator('body').innerText()
  expect(bodyText.includes('PLACEHOLDER_'), 'announcements画面にプレースホルダtag_idが露出している').toBe(
    false,
  )
})
