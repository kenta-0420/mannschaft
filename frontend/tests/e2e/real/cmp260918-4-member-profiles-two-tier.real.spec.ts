import {
  expect,
  request as pwRequest,
  test,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration, waitForSpinnerGone } from '../helpers/wait'

/**
 * 実機検証: PR #3357 メンバー紹介の年度別ページ二段構え化
 * (CMP-260918-1357 検証戦役)
 *
 * 確認内容:
 * 1. /organizations/org-000009/member-profiles が404/エラーにならず開く
 * 2. ADMIN操作でページ作成→メンバー追加→編集→削除ができる（画面操作）
 * 3. ADMINには操作ボタンが見え、MEMBERには見えない
 * 4. URL直打ちでもMEMBERが書き込めない（API直叩きで検証）
 * 5. 後始末: 作成したページ・メンバーは削除する
 *
 * 罠(殿が実測): このファイルは ADMIN(e2e-user) と MEMBER(e2e-dummy-6) を
 * 同一ファイル内で切り替える。`chromium-real` プロジェクトの既定 storageState
 * (`tests/e2e/.auth/real-user.json`) を引き継いだまま `browser.newContext({...})` の
 * storageState を明示しないと、別アカウントでログインしたつもりが実は前のアカウントの
 * ままになる（`/api/v1/users/me` の id が変わらない、という形で実測済み）。
 * このため各アカウント切替は必ず `browser.newContext({ storageState: { cookies: [], origins: [] } })`
 * を明示し、ログイン直後に `/api/v1/users/me` で本人確認する。
 *
 * また `networkidle` 待ちや単純な文字列比較でのローディング判定は不十分
 * （「loading」表示は1文字ずつ別要素になり得るため）。目的の文言が本文に現れることを
 * `expect.poll` で直接待ち、dev サーバー初回コンパイルを見込みタイムアウトは240秒とする。
 */
const API_BASE_URL = process.env.API_BASE_URL ?? 'http://localhost:8080'
const ORG_SLUG = 'org-000009'
const uniqueSuffix = Date.now()
const PAGE_SLUG = `cmp260918-verify-${uniqueSuffix}`
const PAGE_TITLE = `検証用ページ${uniqueSuffix}`
const MEMBER_NAME = `検証太郎${uniqueSuffix}`
const MEMBER_NAME_EDITED = `検証太郎${uniqueSuffix}編集済`

let api: APIRequestContext
let adminToken: string
let memberToken: string
let createdPageId: number | null = null

test.beforeAll(async () => {
  api = await pwRequest.newContext({ baseURL: API_BASE_URL })
  async function login(email: string): Promise<string> {
    const res = await api.post('/api/v1/auth/login', {
      data: { email, password: 'TestPass2026!' },
    })
    expect(res.status(), `${email} のログインに失敗`).toBe(200)
    return ((await res.json()).data as { accessToken: string }).accessToken
  }
  adminToken = await login('e2e-user@test.mannschaft.local')
  memberToken = await login('e2e-dummy-6@test.mannschaft.local')

  async function assertIdentity(token: string, expectedEmail: string): Promise<void> {
    const me = await api.get('/api/v1/users/me', { headers: { Authorization: `Bearer ${token}` } })
    expect(me.status()).toBe(200)
    const body = (await me.json()).data as { email: string }
    expect(body.email).toBe(expectedEmail)
  }
  await assertIdentity(adminToken, 'e2e-user@test.mannschaft.local')
  await assertIdentity(memberToken, 'e2e-dummy-6@test.mannschaft.local')
})

test.afterAll(async () => {
  // 後始末: 作成したページが残っていれば削除する（メンバーはページのCASCADE DELETEに委ねず明示削除は不要 - ページ削除で足りる想定だが、
  // 念のためページが取れなければ何もしない）。
  if (createdPageId != null) {
    await api
      .delete(`/api/v1/team/pages/${createdPageId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      })
      .catch(() => {})
  }
  await api.dispose()
})

/** 空の storageState を明示した新規コンテキストで API ログインし、本人確認まで行う。 */
async function openAs(
  browser: Browser,
  credentials: { email: string; password: string },
): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  const page = await context.newPage()
  await loginViaApi(page, credentials, { apiBaseUrl: API_BASE_URL })

  const me = await page.request.get(`${API_BASE_URL}/api/v1/users/me`)
  expect(me.status()).toBe(200)
  const meBody = (await me.json()).data as { email: string }
  expect(meBody.email, 'ログイン後の本人確認に失敗（別アカウントのまま走っている疑い）').toBe(
    credentials.email,
  )
  return page
}

/** 本文に目的の文言が現れるまで直接待つ（loading表示の1文字ずつ分割・HMRの揺れに強い）。 */
async function waitForBodyText(page: Page, text: string, timeout = 240_000): Promise<void> {
  await expect
    .poll(async () => (await page.locator('body').innerText()).includes(text), {
      timeout,
      message: `本文に「${text}」が現れなかった`,
    })
    .toBe(true)
}

test.describe.configure({ mode: 'serial' })

test('① 組織メンバー紹介ページが404/エラーにならず開く（ADMIN）', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, {
    email: 'e2e-user@test.mannschaft.local',
    password: 'TestPass2026!',
  })

  const res = await page.goto(`/organizations/${ORG_SLUG}/member-profiles`, {
    waitUntil: 'domcontentloaded',
  })
  await waitForHydration(page)
  await waitForSpinnerGone(page)

  expect(res?.status() ?? 200).toBeLessThan(400)
  await waitForBodyText(page, 'メンバー紹介')

  // ADMINには「新規ページ作成」ボタンが見えるはず
  await expect(page.getByRole('button', { name: '新規ページ作成' })).toBeVisible({
    timeout: 240_000,
  })

  await page.context().close()
})

test('② MEMBERにはページ作成ボタンが見えない（操作ボタンの権限差）', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, {
    email: 'e2e-dummy-6@test.mannschaft.local',
    password: 'TestPass2026!',
  })

  await page.goto(`/organizations/${ORG_SLUG}/member-profiles`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)

  await waitForBodyText(page, 'メンバー紹介')
  await expect(page.getByRole('button', { name: '新規ページ作成' })).toHaveCount(0)

  await page.context().close()
})

test('③ ADMIN: ページ作成→メンバー追加→編集→削除→ページ削除（画面操作）', async ({ browser }) => {
  test.setTimeout(240_000)
  const page = await openAs(browser, {
    email: 'e2e-user@test.mannschaft.local',
    password: 'TestPass2026!',
  })

  await page.goto(`/organizations/${ORG_SLUG}/member-profiles`, { waitUntil: 'domcontentloaded' })
  await waitForHydration(page)
  await waitForSpinnerGone(page)
  await waitForBodyText(page, 'メンバー紹介')

  // --- ページ作成 ---
  await page.getByRole('button', { name: '新規ページ作成' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('input').first().fill(PAGE_TITLE) // タイトル
  const slugInput = dialog.locator('input[placeholder="members-2026"]')
  await slugInput.fill(PAGE_SLUG)
  await dialog.getByRole('button', { name: /作成|保存|OK/ }).last().click()

  // 作成成功のトースト待ち兼ねてページ一覧に戻ってカードが出るのを待つ
  const pageCard = page.locator('.p-card', { hasText: PAGE_TITLE })
  await expect(pageCard).toBeVisible({ timeout: 30_000 })

  // 作成されたページのIDを後始末用に取得
  const listRes = await api.get(`/api/v1/team/pages?organizationId=9&size=100`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  })
  const created = ((await listRes.json()).data as Array<{ id: number; slug: string }>).find(
    (p) => p.slug === PAGE_SLUG,
  )
  expect(created, '作成したページがAPI一覧から見つからない').toBeTruthy()
  createdPageId = created!.id

  // --- ページを開いてメンバー追加 ---
  await pageCard.getByRole('button', { name: 'メンバーを見る' }).click()
  await expect(page.getByRole('heading', { name: PAGE_TITLE })).toBeVisible({ timeout: 30_000 })

  await page.getByRole('button', { name: 'メンバー追加' }).click()
  const memberDialog = page.getByRole('dialog')
  await memberDialog.locator('input').first().fill(MEMBER_NAME)
  await memberDialog.getByRole('button', { name: /追加|保存|OK/ }).last().click()

  const memberCard = page.getByText(MEMBER_NAME, { exact: false })
  await expect(memberCard.first()).toBeVisible({ timeout: 30_000 })

  // --- 編集 ---
  const editButton = page.locator('[aria-label="編集"], button:has(.pi-pencil)').first()
  await editButton.click()
  const editDialog = page.getByRole('dialog')
  const nameInput = editDialog.locator('input').first()
  await nameInput.fill(MEMBER_NAME_EDITED)
  await editDialog.getByRole('button', { name: /保存|更新|OK/ }).last().click()
  await expect(page.getByText(MEMBER_NAME_EDITED, { exact: false }).first()).toBeVisible({
    timeout: 30_000,
  })

  // --- 削除 ---
  const deleteButton = page.locator('button:has(.pi-trash)').first()
  await deleteButton.click()
  // 確認ダイアログが出る場合は確定する
  const confirmButton = page.getByRole('button', { name: /削除|OK|はい/ }).last()
  if (await confirmButton.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await confirmButton.click()
  }
  await expect(page.getByText(MEMBER_NAME_EDITED, { exact: false })).toHaveCount(0, {
    timeout: 30_000,
  })

  // --- ページ削除（後始末を画面操作でも行う） ---
  await page.getByRole('button', { name: 'ページ一覧に戻る' }).click()
  const deletePageButton = page.locator('.p-card', { hasText: PAGE_TITLE }).locator('button:has(.pi-trash)')
  await deletePageButton.click()
  const deletePageConfirm = page.getByRole('button', { name: /削除|OK|はい/ }).last()
  if (await deletePageConfirm.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await deletePageConfirm.click()
  }
  await expect(page.locator('.p-card', { hasText: PAGE_TITLE })).toHaveCount(0, { timeout: 30_000 })

  // 画面から削除できたので、afterAll の後始末APIコールは不要（保険として残すが404は許容）

  await page.context().close()
})

test('④ URL直打ちでもMEMBERはページ作成APIに書き込めない（権限横断）', async () => {
  test.setTimeout(60_000)
  const res = await api.post('/api/v1/team/pages', {
    headers: { Authorization: `Bearer ${memberToken}` },
    data: {
      organizationId: 9,
      title: `不正作成試行${uniqueSuffix}`,
      slug: `cmp260918-illegal-${uniqueSuffix}`,
      pageType: 'YEARLY',
      year: new Date().getFullYear(),
      visibility: 'MEMBERS_ONLY',
    },
  })
  expect([403, 404]).toContain(res.status())
})

test('⑤ 他テナント(非所属)は組織メンバー紹介ページのAPIにアクセスできない', async () => {
  test.setTimeout(60_000)
  const outsiderLogin = await api.post('/api/v1/auth/login', {
    data: { email: 'e2e-supporter@test.mannschaft.local', password: 'TestPass2026!' },
  })
  expect(outsiderLogin.status()).toBe(200)
  const { accessToken } = (await outsiderLogin.json()).data as { accessToken: string }

  const res = await api.get('/api/v1/team/pages?organizationId=9&size=100', {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  // 組織非所属者は403/404、あるいは可視性がPUBLICなページのみ0件で200になる設計もあり得るため、
  // 「所属者専用ページの内容が漏れていない」ことを主眼に確認する。
  expect([200, 403, 404]).toContain(res.status())
  if (res.status() === 200) {
    const body = (await res.json()) as { data: unknown[] }
    // MEMBERS_ONLY可視性のページが非所属者に見えてはならない
    const leaked = JSON.stringify(body).includes('MEMBERS_ONLY')
    expect(leaked, '非所属者にMEMBERS_ONLYページの内容が漏洩している').toBe(false)
  }
})
