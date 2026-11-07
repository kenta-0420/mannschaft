/** Zod 4移行の実機互換確認。API補助はログインと自case所有IDの削除のみ。 */
import { test, expect, type Page, type Request, type TestInfo } from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'

const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
const TEAM = process.env.E2E_CALENDAR_TEAM ?? 'fc-u-18'
const ADMIN = { email: 'e2e-dummy-1@test.mannschaft.local', password: 'TestPass2026!' }
const RUN = `ZOD4-${Date.now()}`
const teamPath = `/api/v1/teams/${TEAM}`

test.use({ storageState: { cookies: [], origins: [] } })
test.describe.configure({ mode: 'default', timeout: 120_000 })

function observePosts(page: Page, path: string) {
  const requests: Request[] = []
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === path) requests.push(request)
  })
  return requests
}

async function openAdminPage(page: Page, suffix: string, collection: string) {
  await loginViaApi(page, ADMIN, { apiBaseUrl: API })
  await page.addLocatorHandler(page.getByTestId('member-permission-setup'), async dialog => {
    await dialog.getByRole('button', { name: 'あとで決める', exact: true }).click()
  })
  const loaded = page.waitForResponse(response =>
    new URL(response.url()).pathname === `${teamPath}/${collection}` && response.request().method() === 'GET')
  await page.goto(`/teams/${TEAM}/${suffix}`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  expect((await loaded).status(), 'seedの管理者権限で実BEの対象一覧を取得できる').toBe(200)
}

async function saveEvidence(info: TestInfo, name: string, value: unknown) {
  await info.attach(name, { body: JSON.stringify(value, null, 2), contentType: 'application/json' })
}

async function cleanup(page: Page, path: string, id: number | undefined) {
  if (id === undefined) return
  const response = await page.request.delete(`${API}${path}/${id}`)
  expect(response.status(), `自caseで作成したID=${id}だけを削除`).toBe(204)
}

test('ZOD4-001: 登録の未同意と非同期郵便番号エラーを表示し、メールを伴う登録POSTを送らない', async ({ page }, info) => {
  const posts = observePosts(page, '/api/v1/auth/register')
  const policy = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/postal-code/policies')
  await page.goto('/register', { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  expect((await policy).status(), '非同期検証に実BEの郵便番号規則を使用').toBe(200)
  await page.locator('#email').fill(`${RUN.toLowerCase()}@example.invalid`)
  await page.locator('input#password').fill('TestPass2026!')
  await page.locator('#lastName').fill('互換')
  await page.locator('#firstName').fill('確認')
  await page.locator('#displayName').fill(RUN)
  await page.locator('#birthDate').fill('2000-01-01')
  await page.locator('#postalCode').fill('123-4567')
  await page.getByRole('button', { name: 'アカウント作成', exact: true }).click()
  await expect(page.getByText('利用規約への同意が必要です', { exact: true })).toBeVisible()
  await expect(page.getByText('プライバシーポリシーへの同意が必要です', { exact: true })).toBeVisible()
  expect(posts).toHaveLength(0)
  await page.screenshot({ path: info.outputPath('register-consent-errors.png') })

  await page.locator('#termsAccepted input').check()
  await page.locator('#privacyPolicyAccepted input').check()
  await page.locator('#postalCode').fill('invalid')
  await page.getByRole('button', { name: 'アカウント作成', exact: true }).click()
  await expect(page.getByText('郵便番号の形式が正しくありません（例: 123-4567）', { exact: true })).toBeVisible()
  await expect(page.getByText('利用規約への同意が必要です', { exact: true })).toHaveCount(0)
  await expect(page.getByText('プライバシーポリシーへの同意が必要です', { exact: true })).toHaveCount(0)
  expect(posts, '未同意・invalidの両送信で登録APIを呼ばない').toHaveLength(0)
  await saveEvidence(info, 'registration-post-count', { registrationPosts: posts.length })
  await page.screenshot({ path: info.outputPath('register-postal-error.png') })
})

test('ZOD4-002: テンプレートのキャンセル再表示で値をresetし、false/nullの初期値で作成できる', async ({ page }, info) => {
  const path = `${teamPath}/form-templates`
  let ownedId: number | undefined
  await openAdminPage(page, 'forms/templates', 'form-templates')
  try {
    const create = page.getByRole('button', { name: 'テンプレート作成', exact: true })
    await expect(create).toBeVisible()
    await create.click()
    const dialog = page.getByRole('dialog', { name: 'フォームテンプレート作成', exact: true })
    await dialog.getByPlaceholder('フォーム名', { exact: true }).fill(`${RUN} 破棄`)
    await dialog.getByPlaceholder('フォームの説明').fill('キャンセル時に破棄する説明')
    await dialog.locator('#reqApproval').check()
    await dialog.locator('#allowEdit').check()
    await dialog.getByRole('button', { name: 'キャンセル', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await create.click()
    await expect(dialog.getByPlaceholder('フォーム名', { exact: true })).toHaveValue('')
    await expect(dialog.getByPlaceholder('フォームの説明')).toHaveValue('')
    await expect(dialog.locator('#reqApproval')).not.toBeChecked()
    await expect(dialog.locator('#allowEdit')).not.toBeChecked()
    const nullableInputs = dialog.locator('input.p-inputnumber-input, input.p-datepicker-input')
    await expect(nullableInputs, '期限・目標提出数・最大提出回数の3欄を確認').toHaveCount(3)
    for (const input of await nullableInputs.all()) {
      await expect(input).toHaveValue('')
    }
    const name = `${RUN} テンプレート`
    await dialog.getByPlaceholder('フォーム名', { exact: true }).fill(name)
    const saved = page.waitForResponse(response =>
      new URL(response.url()).pathname === path && response.request().method() === 'POST')
    await dialog.getByRole('button', { name: '作成', exact: true }).click()
    const response = await saved
    expect(response.status()).toBe(201)
    const body = await response.json() as { data: { id: number; workflow: { requiresApproval: boolean }; editPolicy: { allowEditAfterSubmit: boolean; maxSubmissionsPerUser: number | null }; timeline: { deadline: string | null }; stats: { targetCount: number | null } } }
    ownedId = body.data.id
    const payload = response.request().postDataJSON() as Record<string, unknown>
    expect(payload).toMatchObject({ name, requiresApproval: false, allowEditAfterSubmit: false, fields: [] })
    expect(body.data.workflow.requiresApproval).toBe(false)
    // FEのnullable初期値は送信本文から省略され、BEは数値の未指定を既存仕様の0へ変換する。
    for (const key of ['maxSubmissionsPerUser', 'deadline', 'targetCount']) {
      expect(payload).not.toHaveProperty(key)
    }
    expect(body.data.editPolicy).toMatchObject({ allowEditAfterSubmit: false, maxSubmissionsPerUser: 0 })
    expect(body.data.timeline.deadline).toBeNull()
    expect(body.data.stats.targetCount).toBe(0)
    await expect(dialog).not.toBeVisible()
    await expect(page.getByRole('cell', { name, exact: true })).toBeVisible()
    await saveEvidence(info, 'template-defaults', { status: response.status(), payload, saved: body.data })
    await page.screenshot({ path: info.outputPath('template-created.png') })
  } finally { await cleanup(page, path, ownedId) }
})

test('ZOD4-003: 住戸の空白必須エラーは送信せず、有効値はtrimした本文で実BEに保存する', async ({ page }, info) => {
  const path = `${teamPath}/dwelling-units`
  const posts = observePosts(page, path)
  let ownedId: number | undefined
  await openAdminPage(page, 'residents', 'dwelling-units')
  try {
    await page.getByTestId('dwelling-unit-create-button').click()
    const form = page.getByTestId('dwelling-unit-create-form')
    await form.getByTestId('dwelling-unit-number-input').fill('   ')
    await page.getByTestId('dwelling-unit-create-submit').click()
    await expect(page.getByTestId('dwelling-unit-number-error')).toBeVisible()
    expect(posts).toHaveLength(0)
    const number = `${RUN}-101`
    await form.getByTestId('dwelling-unit-number-input').fill(`  ${number}  `)
    await form.locator('#dwelling-unit-type').fill('  TEST  ')
    await form.locator('#dwelling-unit-layout').fill('  1K  ')
    const saved = page.waitForResponse(response =>
      new URL(response.url()).pathname === path && response.request().method() === 'POST')
    await page.getByTestId('dwelling-unit-create-submit').click()
    const response = await saved
    expect(response.status()).toBe(201)
    const body = await response.json() as { data: { id: number; unitNumber: string; unitType: string; layout: string; floor: number | null; areaSqm: number | null } }
    ownedId = body.data.id
    const payload = response.request().postDataJSON() as Record<string, unknown>
    expect(payload).toMatchObject({ unitNumber: number, unitType: 'TEST', layout: '1K', floor: null, areaSqm: null })
    expect(body.data).toMatchObject({ unitNumber: number, unitType: 'TEST', layout: '1K', floor: null, areaSqm: null })
    await expect(form).not.toBeVisible()
    await expect(page.getByText(number, { exact: true }).first()).toBeVisible()
    await saveEvidence(info, 'dwelling-trim', { status: response.status(), payload, saved: body.data })
    await page.screenshot({ path: info.outputPath('dwelling-created.png') })
  } finally { await cleanup(page, path, ownedId) }
})
