/**
 * CMP-261021-2133: 実BE・専用fixtureによる本人パスワード生成の実機試験。
 * API補助は認証前提と受信メール読取だけ。対象操作をAPIで代替しない。
 * 秘密を含むtrace/video/自動スクリーンショットを無効にし、入力をマスクして撮影する。
 */
import { test, expect, type Page, type Locator } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { waitForHydration } from '../helpers/wait'

interface UserFixture {
  role: string
  email: string
  password: string
  id: string | number
  accessToken: string
  refreshToken: string
  resetToken?: string
}
interface Fixtures { registrationEmail: string; users: UserFixture[] }
const fixturePath = process.env.PASSWORD_GENERATOR_FIXTURES
if (!fixturePath) throw new Error('PASSWORD_GENERATOR_FIXTURES に専用private JSONを指定してください')
const runtimeDir = dirname(fixturePath)
const fixtures = (): Fixtures => JSON.parse(readFileSync(fixturePath, 'utf8')) as Fixtures
function user(role: string): UserFixture {
  const result = fixtures().users.find(entry => entry.role === role)
  if (!result) throw new Error(`専用fixtureがありません: ${role}`)
  return result
}
function keepPassword(role: string, password: string): void {
  const data = fixtures()
  const entry = data.users.find(value => value.role === role)
  if (!entry) throw new Error('専用fixtureがありません')
  entry.password = password
  writeFileSync(fixturePath!, JSON.stringify(data, null, 2))
}
const generateButton = (page: Page): Locator => page.getByRole('button', { name: 'パスワードを生成', exact: true })
const copyButton = (page: Page): Locator => page.getByRole('button', { name: 'コピー', exact: true })
const newInputs = (page: Page): Locator => page.locator('input[autocomplete="new-password"]')
async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: join(runtimeDir, `${name}.png`), fullPage: true, mask: [page.locator('input')] })
  test.info().annotations.push({ type: '画面証拠', description: `${name}.png (${new URL(page.url()).pathname})` })
  if (await generateButton(page).count()) {
    const viewport = page.viewportSize()
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '対象フォーム390pxで横パンなし').toBe(true)
    for (const button of [generateButton(page), copyButton(page)]) {
      const box = await button.boundingBox()
      expect(!!box && box.width >= 44 && box.height >= 44, '対象フォームの追加操作領域44px以上').toBe(true)
    }
    await page.screenshot({ path: join(runtimeDir, `${name}-390.png`), fullPage: true, mask: [page.locator('input')] })
    if (viewport) await page.setViewportSize(viewport)
  }
}
async function session(page: Page, role: string): Promise<void> {
  const account = user(role)
  await page.context().addCookies([
    { name: 'access_token', value: account.accessToken, domain: 'localhost', path: '/', httpOnly: true, sameSite: 'Strict' },
    { name: 'refresh_token', value: account.refreshToken, domain: 'localhost', path: '/', httpOnly: true, sameSite: 'Strict' },
  ])
  const payload = JSON.parse(Buffer.from(account.accessToken.split('.')[1]!, 'base64url').toString()) as { exp: number }
  await page.addInitScript(({ id, email, expires }) => {
    localStorage.setItem('currentUser', JSON.stringify({ id, email, fullName: '生成実機専用' }))
    localStorage.setItem('tokenExpiresAt', String(expires * 1000))
  }, { id: account.id, email: account.email, expires: payload.exp })
}
async function generate(page: Page): Promise<string> {
  await expect(generateButton(page)).toBeEnabled()
  const input = newInputs(page).first()
  const beforeUrl = page.url()
  test.info().annotations.push({ type: 'UI操作', description: new URL(beforeUrl).pathname + ': 生成ボタンをキーボードEnterで操作、新規・確認欄一致、既存送信ボタンで保存' })
  await generateButton(page).focus()
  await page.keyboard.press('Enter')
  const value = await input.inputValue()
  expect(/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[A-Za-z0-9]{16}$/.test(value), '生成値が既存ポリシーを満たす').toBe(true)
  expect(page.url() === beforeUrl, '生成で遷移しない').toBe(true)
  for (const entry of await newInputs(page).all()) expect(await entry.inputValue() === value, '新規・確認が一致').toBe(true)
  expect(await input.getAttribute('type')).toBe('password')
  expect((await page.locator('body').innerText()).includes(value), '生成値を通知へ出さない').toBe(false)
  expect(await page.evaluate(secret => JSON.stringify([localStorage, sessionStorage]).includes(secret), value), '生成値を永続ストアへ出さない').toBe(false)
  return value
}
async function uiLogin(page: Page, email: string, password: string): Promise<void> {
  // 新規コンテキスト上のログインで永続化をUIから裏取りする。
  await page.goto('/login')
  await waitForHydration(page)
  await page.locator('#email').fill(email)
  await page.locator('input[type="password"]').fill(password)
  const response = page.waitForResponse(res => res.url().includes('/api/v1/auth/login') && res.request().method() === 'POST')
  await page.getByRole('button', { name: 'ログイン', exact: true }).click()
  expect((await response).ok(), '新パスワードによる実ログイン成功').toBe(true)
  await expect(page).not.toHaveURL(/\/login/)
}

test.beforeEach(async ({ page }) => {
  page.on('response', response => {
    const path = new URL(response.url()).pathname
    if (/^\/api\/v1\/(users\/me\/password|auth\/(register|verify-email|password-reset\/confirm|login))/.test(path)) {
      test.info().annotations.push({ type: '実API', description: `${response.request().method()} ${path}: ${response.status()}` })
    }
  })
})

test.afterEach(async ({ page }, info) => {
  // 値・メール・tokenは結果へ出さない。
  const evidencePath = join(runtimeDir, 'e2e-evidence.json')
  let previous: unknown[] = []
  try { previous = JSON.parse(readFileSync(evidencePath, 'utf8')) as unknown[] }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error }
  previous.push({ test: info.title, status: info.status, expectedStatus: info.expectedStatus, duration: info.duration,
    apiUse: '専用fixtureによる認証前提・受信メール読取のみ', finalScreen: new URL(page.url()).pathname, evidence: info.annotations, source: 'password-generator.real.spec.ts' })
  writeFileSync(evidencePath, JSON.stringify(previous, null, 2))
})

test('PG-01 AC-4/5/6/7/9/11/14 登録フォームの生成・手修正・実clipboard・PC/390px', async ({ page }) => {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto('/register')
  await waitForHydration(page)
  const input = newInputs(page).first()
  expect(await input.inputValue() === '', '初期描画で生成しない').toBe(true)
  await expect(copyButton(page)).toBeDisabled()
  await page.locator('#email').fill(fixtures().registrationEmail)
  await page.evaluate(() => navigator.clipboard.writeText('実機試験の前提値'))
  let writes = 0
  page.on('request', request => { if (request.method() !== 'GET' && request.url().includes('/api/v1/auth/register')) writes++ })
  await generate(page)
  expect(await page.evaluate(() => navigator.clipboard.readText()) === '実機試験の前提値', '生成だけではコピーしない').toBe(true)
  expect(writes, '生成だけでは登録送信しない').toBe(0)
  expect(await page.locator('#email').inputValue() === fixtures().registrationEmail, '他項目を保持').toBe(true)
  const edited = 'ManualEditedPass9'
  await input.fill(edited)
  await copyButton(page).click()
  expect(await page.evaluate(() => navigator.clipboard.readText()) === edited, 'クリック時点の手修正値をコピー').toBe(true)
  await expect(page.getByText('コピーしました', { exact: true })).toBeVisible()
  expect(await input.inputValue() === edited, 'コピーは値を変更しない').toBe(true)
  await shot(page, 'pg-register-pc')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '390pxで横パンなし').toBe(true)
  for (const button of [generateButton(page), copyButton(page)]) {
    const box = await button.boundingBox()
    expect(!!box && box.width >= 44 && box.height >= 44, '追加操作領域44px以上').toBe(true)
  }
  await shot(page, 'pg-register-mobile')
})

for (const [role, url] of [['change-password', '/settings/password'], ['change-account', '/settings/account'], ['change-profile', '/settings/profile']] as const) {
  test(`PG-CHANGE ${url} AC-1/4/6/10/11/12/13 本人の生成値で変更完遂`, async ({ page, browser }) => {
    await session(page, role)
    await page.goto(url)
    await waitForHydration(page)
    await expect(generateButton(page)).toBeVisible()
    const current = page.locator('input[autocomplete="current-password"]')
    await current.fill(user(role).password)
    await generate(page)
    expect(await current.inputValue() === user(role).password, '現在パスワードを保持').toBe(true)
    const submit = page.getByRole('button', { name: 'パスワードを変更', exact: true })
    await newInputs(page).first().fill('weak')
    await expect(submit).toBeDisabled()
    await expect(page.getByText('パスワードは8文字以上で入力してください', { exact: true })).toBeVisible()
    const password = await generate(page)
    await newInputs(page).nth(1).fill('MismatchPass9')
    await expect(submit).toBeDisabled()
    await expect(page.getByText('パスワードが一致しません', { exact: true })).toBeVisible()
    await newInputs(page).nth(1).fill(password)
    await shot(page, `pg-${role}`)
    const pending = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/users/me/password' && res.request().method() === 'PATCH')
    await submit.click()
    const response = await pending
    expect(response.ok(), '本人用既存PATCH成功').toBe(true)
    keepPassword(role, password)
    expect(new URL(response.url()).search === '', 'passwordをURLへ出さない').toBe(true)
    await expect(generateButton(page)).toBeDisabled()
    await expect(copyButton(page)).toBeDisabled()
    await expect(page).toHaveURL(/\/login/)
    await shot(page, `pg-${role}-final`)
    const context = await browser.newContext({ baseURL: 'http://localhost:3001' })
    try { await uiLogin(await context.newPage(), user(role).email, password) }
    finally { await context.close() }
  })
}

for (const [role, url] of [['oauth-password', '/settings/password'], ['oauth-account', '/settings/account']] as const) {
  test(`PG-OAUTH ${url} AC-1/11/12 初期設定をフォーム本文で完遂`, async ({ page, browser }) => {
    await session(page, role)
    await page.goto(url)
    await waitForHydration(page)
    await expect(generateButton(page)).toBeVisible()
    await expect(page.locator('input[autocomplete="current-password"]')).toHaveCount(0)
    const password = await generate(page)
    const pending = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/users/me/password/setup' && res.request().method() === 'POST')
    await page.getByRole('button', { name: 'パスワードを設定', exact: true }).click()
    const response = await pending
    expect(response.ok(), '既存setup API成功').toBe(true)
    keepPassword(role, password)
    expect(new URL(response.url()).search === '', 'OAuth生成値をURLへ出さない').toBe(true)
    expect(response.request().headers()['content-type']?.includes('application/x-www-form-urlencoded')).toBe(true)
    expect(new URLSearchParams(response.request().postData() ?? '').get('password') === password, 'フォーム本文で送信').toBe(true)
    await expect(page.locator('input[autocomplete="current-password"]')).toBeVisible()
    await shot(page, `pg-${role}-final`)
    const context = await browser.newContext({ baseURL: 'http://localhost:3001' })
    try { await uiLogin(await context.newPage(), user(role).email, password) }
    finally { await context.close() }
  })
}

test('PG-RESET AC-1/4/12 有効トークンの生成値でリセット完遂', async ({ page, browser }) => {
  const account = user('reset')
  if (!account.resetToken) throw new Error('有効な専用resetTokenがありません')
  await page.goto(`/reset-password?token=${encodeURIComponent(account.resetToken)}`)
  await waitForHydration(page)
  const password = await generate(page)
  await shot(page, 'pg-reset-form')
  const pending = page.waitForResponse(res => res.url().includes('/api/v1/auth/password-reset/confirm') && res.request().method() === 'POST')
  await page.locator('button[type="submit"]').click()
  expect((await pending).ok(), '既存reset API成功').toBe(true)
  keepPassword('reset', password)
  await expect(page.getByRole('button', { name: /ログイン/ })).toBeVisible()
  await shot(page, 'pg-reset-final')
  const context = await browser.newContext({ baseURL: 'http://localhost:3001' })
  try { await uiLogin(await context.newPage(), account.email, password) }
  finally { await context.close() }
})

test('PG-REGISTER AC-1/12 生成値で登録し受信メールのUI確認リンクまで完遂', async ({ page }) => {
  await page.goto('/register')
  await waitForHydration(page)
  await page.locator('#email').fill(fixtures().registrationEmail)
  const password = await generate(page)
  await page.locator('#postalCode').fill('100-0001')
  await page.locator('#birthDate').fill('1990-01-01')
  await page.locator('#lastName').fill('生成')
  await page.locator('#firstName').fill('実機')
  await page.locator('#displayName').fill('生成実機専用')
  for (const check of await page.getByRole('checkbox').all()) await check.check()
  const pending = page.waitForResponse(res => res.url().includes('/api/v1/auth/register') && res.request().method() === 'POST')
  await page.getByRole('button', { name: 'アカウント作成', exact: true }).click()
  expect((await pending).ok(), '既存register API成功').toBe(true)
  await expect(page).toHaveURL(/\/verify-email/)
  await expect(page.getByText(fixtures().registrationEmail, { exact: true })).toBeVisible()
  const data = fixtures()
  data.users.push({ role: 'registered-ui', email: data.registrationEmail, password, id: '', accessToken: '', refreshToken: '' })
  writeFileSync(fixturePath!, JSON.stringify(data, null, 2))
  let html = ''
  await expect.poll(async () => {
    const inbox = await page.request.get('http://localhost:4599/inbox')
    const messages = await inbox.json() as { to: string[]; html: string }[]
    html = messages.filter(message => message.to.includes(data.registrationEmail)).at(-1)?.html ?? ''
    return html.includes('verify-email?token=')
  }).toBe(true)
  const token = html.match(/verify-email\?token=([^"\s<]+)/)?.[1]?.replace(/&amp;.*/, '')
  if (!token) throw new Error('確認メールにtokenリンクがありません')
  const verified = page.waitForResponse(res => res.url().includes('/api/v1/auth/verify-email') && res.request().method() === 'POST')
  await page.goto(`/verify-email?token=${token}`)
  expect((await verified).ok(), '確認リンクから実API成功').toBe(true)
  await expect(page.getByText('認証が完了しました', { exact: true })).toBeVisible()
  await shot(page, 'pg-register-final')
})

test('PG-GUARD AC-13 未認証の3設定URL直打ちがログイン画面へ拒否', async ({ page }) => {
  for (const url of ['/settings/password', '/settings/account', '/settings/profile']) {
    await page.goto(url)
    await expect(page).toHaveURL(/\/login/)
    await expect(page.locator('#email')).toBeVisible()
    await expect(generateButton(page)).toHaveCount(0)
  }
  await shot(page, 'pg-guard-final')
})

for (const locale of ['ja', 'en', 'zh', 'ko', 'es', 'de']) {
  test(`PG-I18N ${locale} AC-14 6言語の生成・コピー文言が解決`, async ({ page }) => {
    const messages = JSON.parse(readFileSync(join(process.cwd(), `app/locales/${locale}/settings.json`), 'utf8')) as { settings: { password: { generator: { generate: string; copy: string } } } }
    await page.context().addCookies([{ name: 'i18n_locale', value: locale, domain: 'localhost', path: '/' }])
    await page.goto('/register')
    await waitForHydration(page)
    await expect(page.getByRole('button', { name: messages.settings.password.generator.generate, exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: messages.settings.password.generator.copy, exact: true })).toBeVisible()
  })
}
