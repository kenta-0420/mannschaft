/**
 * CMP-057 アリシゼーション実機試験。
 * 管理者・メンバー・部外者を独立した BrowserContext で動かし、
 * チームファイルの作成・共有・越境拒否を実UIと実ストレージで確認する。
 */
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'serial' })

const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'
const TEAM_PATH = '/teams/fc-u-18/files'
const PASSWORD = 'TestPass2026!'

const PERSONAS = {
  admin: { email: 'e2e-admin@test.mannschaft.local', password: PASSWORD },
  member: { email: 'e2e-user@test.mannschaft.local', password: PASSWORD },
  outsider: { email: 'e2e-outsider@test.mannschaft.local', password: PASSWORD },
} as const

async function openPersona(
  browser: Browser,
  credentials: { email: string, password: string },
): Promise<{ context: BrowserContext, page: Page }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE })
  return { context, page }
}

function isTeamFoldersRequest(response: { url(): string, request(): { method(): string } }): boolean {
  const url = new URL(response.url())
  return response.request().method() === 'GET'
    && url.pathname === '/api/v1/files/folders'
    && url.searchParams.get('scope_type') === 'TEAM'
}

async function cleanupDelete(page: Page, path: string): Promise<void> {
  try {
    await page.request.delete(`${API_BASE}${path}`)
  } catch (error) {
    console.warn(`アリシゼーション後始末に失敗しました: ${path}`, error)
  }
}

test('CMP057-ALICE: 管理者の実アップロードをメンバーは閲覧でき、部外者には漏れない', async ({ browser }) => {
  test.setTimeout(180_000)
  const admin = await openPersona(browser, PERSONAS.admin)
  const member = await openPersona(browser, PERSONAS.member)
  const outsider = await openPersona(browser, PERSONAS.outsider)
  let folderId: number | undefined
  let fileId: number | undefined

  try {
    const adminFoldersPromise = admin.page.waitForResponse(isTeamFoldersRequest)
    await admin.page.goto(TEAM_PATH, { waitUntil: 'domcontentloaded' })
    const adminFolders = await adminFoldersPromise
    expect(adminFolders.status()).toBe(200)
    const foldersUrl = adminFolders.url()
    await expect(admin.page).toHaveURL(/\/teams\/fc-u-18\/files/)
    await expect(admin.page.getByTestId('file-upload-input')).toBeAttached()

    const suffix = Date.now()
    const folderName = `cmp057-alice-${suffix}`
    const fileName = `cmp057-alice-${suffix}.txt`
    await admin.page.getByRole('button', { name: 'フォルダ作成', exact: true }).click()
    const dialog = admin.page.getByRole('dialog')
    await dialog.getByPlaceholder('フォルダ名').fill(folderName)
    const createFolderPromise = admin.page.waitForResponse(response =>
      response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/files/folders',
    )
    await dialog.getByRole('button', { name: '作成', exact: true }).click()
    const createFolderResponse = await createFolderPromise
    expect(createFolderResponse.status()).toBe(201)
    folderId = ((await createFolderResponse.json()) as { data: { id: number } }).data.id

    const adminFolder = admin.page.locator('button').filter({ hasText: folderName })
    await expect(adminFolder).toBeVisible()
    await adminFolder.click()

    const registerPromise = admin.page.waitForResponse(response =>
      response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/files',
    )
    await admin.page.getByTestId('file-upload-input').setInputFiles({
      name: fileName,
      mimeType: 'text/plain',
      buffer: Buffer.from('CMP-057 Alicization storage boundary'),
    })
    const registerResponse = await registerPromise
    expect(registerResponse.status()).toBe(201)
    fileId = ((await registerResponse.json()) as { data: { id: number } }).data.id
    await expect(admin.page.getByText(fileName, { exact: true })).toBeVisible()

    const memberFoldersPromise = member.page.waitForResponse(isTeamFoldersRequest)
    await member.page.goto(TEAM_PATH, { waitUntil: 'domcontentloaded' })
    expect((await memberFoldersPromise).status()).toBe(200)
    const memberFolder = member.page.locator('button').filter({ hasText: folderName })
    await expect(memberFolder).toBeVisible()
    await memberFolder.click()
    await expect(member.page.getByText(fileName, { exact: true })).toBeVisible()
    const memberDownload = await member.page.request.get(`${API_BASE}/api/v1/files/${fileId}/download-url`)
    expect(memberDownload.status()).toBe(200)

    const outsiderFoldersPromise = outsider.page.waitForResponse(isTeamFoldersRequest, { timeout: 15_000 })
    await outsider.page.goto(TEAM_PATH, { waitUntil: 'domcontentloaded' })
    const outsiderFolders = await outsiderFoldersPromise
    expect([403, 404]).toContain(outsiderFolders.status())
    await expect(outsider.page.getByText(fileName, { exact: true })).toHaveCount(0)

    const outsiderFolderDirect = await outsider.page.request.get(foldersUrl)
    expect([403, 404]).toContain(outsiderFolderDirect.status())
    const outsiderDownload = await outsider.page.request.get(`${API_BASE}/api/v1/files/${fileId}/download-url`)
    expect([403, 404]).toContain(outsiderDownload.status())
  } finally {
    if (fileId) await cleanupDelete(admin.page, `/api/v1/files/${fileId}`)
    if (folderId) await cleanupDelete(admin.page, `/api/v1/files/folders/${folderId}`)
    await Promise.all([admin.context.close(), member.context.close(), outsider.context.close()])
  }
})
