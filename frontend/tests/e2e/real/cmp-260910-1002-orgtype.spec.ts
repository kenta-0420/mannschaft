import { expect, loginViaApi, test } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const credentials = {
  email: process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local',
  password: process.env.TEST_USER_PASSWORD ?? 'TestPass2026!',
}

// 認証は各テストの loginViaApi で行うため、real config の共有storageStateを要求しない。
test.use({ storageState: { cookies: [], origins: [] } })

test.describe('CMP-260910-1002 組織作成の不正orgType', () => {
  test('旧下書きのCLUBを実画面から送信すると400 COMMON_001になる', async ({ page }) => {
    await loginViaApi(page, credentials)
    const userId = await page.evaluate(() => {
      const user = JSON.parse(localStorage.getItem('currentUser') ?? '{}') as { id?: number }
      return user.id
    })
    expect(userId).toBeDefined()

    const draftKey = `entity-create-draft-${userId}-organization`
    const organizationName = `CMP-260910-1002 E2E ${Date.now()}`
    await page.evaluate(
      ({ key }) => {
        localStorage.setItem(
          key,
          JSON.stringify({
            name: '',
            nameKana: '',
            nickname1: '',
            description: '',
            visibility: 'PUBLIC',
            supporterEnabled: false,
            template: 'OTHER',
            orgType: 'CLUB',
          }),
        )
      },
      { key: draftKey },
    )

    try {
      await page.goto('/organizations')
      await waitForHydration(page)
      await page.getByRole('button', { name: '組織を作成', exact: true }).click()

      const dialog = page.getByRole('dialog')
      await expect(dialog).toBeVisible()
      await dialog.locator('input').first().fill(organizationName)

      // 組織作成は認証済みユーザーの自己スコープ機能であり、通常ユーザーと
      // SYSTEM_ADMINの間にUI/APIの認可・可視性分岐がないため、実機.mdの権限横断対象外。
      const createResponsePromise = page.waitForResponse(response => {
        const url = new URL(response.url())
        return response.request().method() === 'POST'
          && url.pathname === '/api/v1/organizations'
      })
      await page.getByTestId('entity-create-submit').click()
      const createResponse = await createResponsePromise
      expect(createResponse.status()).toBe(400)
      expect((await createResponse.json()).error.code).toBe('COMMON_001')

      await expect(page.getByText('入力内容に不備があります', { exact: true })).toBeVisible()

      // UI操作が誤って作成へ到達していないことを、後始末可能な検索APIで確認する。
      const searchResponse = await page.request.get(
        `${process.env.API_BASE_URL ?? ''}/api/v1/organizations/search?keyword=${encodeURIComponent(organizationName)}&page=0&size=20`,
      )
      expect(searchResponse.ok()).toBeTruthy()
      expect(JSON.stringify(await searchResponse.json())).not.toContain(organizationName)
    } finally {
      await page.evaluate(key => localStorage.removeItem(key), draftKey)
    }
  })
})
