/**
 * F18.1 実機: APIはログイン・使い捨てチーム作成・後始末だけに使用する。
 * 設定/受付QR発行/本人確定/スタッフ承認/取消/却下は実画面で操作する。
 * QRは画面のSVGをスクリーンショットし、既存ZXingで実デコードする。
 */
import { test, expect, chromium, type Page, type Browser, type BrowserContext, type Locator } from '@playwright/test'
import sharp from 'sharp'
import { BinaryBitmap, HybridBinarizer, RGBLuminanceSource, QRCodeReader } from '@zxing/library'
import { loginViaApi } from '../fixtures/auth'
import { waitForHydration } from '../helpers/wait'
import { readFileSync } from 'node:fs'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import QRCode from 'qrcode'

test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 390, height: 844 },
  isMobile: true,
  hasTouch: true,
  trace: 'off',
  screenshot: 'off',
  actionTimeout: 15_000,
})

// 実画面の期待値は出荷する辞書から取得する。locale番人UTとは別に、保存・再読込・操作を通す。
const localeNames = {
  ja: '日本語', en: 'English', zh: '中文（简体）', ko: '한국어', es: 'Español', de: 'Deutsch',
} as const
type LoyaltyLocale = keyof typeof localeNames
function textFor(locale: LoyaltyLocale) {
  const common = JSON.parse(readFileSync(resolve('app/locales', locale, 'common.json'), 'utf8'))
  const settings = JSON.parse(readFileSync(resolve('app/locales', locale, 'settings.json'), 'utf8'))
  const validation = JSON.parse(readFileSync(resolve('app/locales', locale, 'validation.json'), 'utf8'))
  const dictionary: Record<string, unknown> = { ...common, ...settings, ...validation }
  return (key: string, values: Record<string, string | number> = {}): string => {
    const value = key.split('.').reduce<unknown>((node, part) =>
      node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined,
    dictionary)
    if (typeof value !== 'string') throw new Error(`Missing locale text: ${locale}:${key}`)
    return value.replace(/\{(\w+)\}/g, (match, name: string) => String(values[name] ?? match))
  }
}

// 言語設定UIは対象外のため、専用actorの永続設定をfixtureで準備し実GETで確認する。
async function prepareLocale(page: Page, actor: { email: string; password: string }, locale: LoyaltyLocale) {
  const saved = await page.request.put(API + '/api/v1/users/me', { data: { locale, timezone: 'Asia/Tokyo' } })
  expect(saved.status()).toBe(200)
  const profile = await page.request.get(API + '/api/v1/users/me')
  expect(profile.status()).toBe(200)
  expect(((await profile.json()) as { data: { locale: string } }).data.locale).toBe(locale)
  await loginViaApi(page, actor, { apiBaseUrl: API })
  console.info('[loyalty-ui] persisted actor locale prepared', locale)
}

const API = process.env.API_BASE_URL
if (API !== 'http://localhost:8083') throw new Error('API_BASE_URL must select the dedicated localhost:8083 server')
const BASE = process.env.BASE_URL
if (!BASE) throw new Error('BASE_URL must explicitly select the isolated frontend server')
type ActorRole =
  | 'admin'
  | 'individual'
  | 'supporter'
  | 'member'
  | 'delegatedDeputy'
  | 'deputy'
  | 'otherTeamAdmin'
  | 'organizationAdmin'
  | 'systemAdmin'
  | 'resident1'
  | 'resident2'
  | 'resident3'
interface LoyaltyFixture {
  actors: Record<ActorRole, { email: string; password: string }>
  team: { slug: string; numericId: number }
}
const fixtureFile = process.env.LOYALTY_E2E_FIXTURE
if (!fixtureFile)
  throw new Error(
    'LOYALTY_E2E_FIXTURE must point to an ignored JSON fixture containing dedicated actors and team',
  )
const fixture = JSON.parse(readFileSync(fixtureFile, 'utf8')) as LoyaltyFixture
const ADMIN = fixture.actors.admin
const USER = fixture.actors.individual

async function decodeQr(page: Page, selector: string): Promise<string> {
  const screenshot = await page.locator(selector).screenshot()
  const { data, info } = await sharp(screenshot)
    .removeAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true })
  const pixels = new Uint8ClampedArray(info.width * info.height)
  for (let i = 0; i < pixels.length; i++)
    pixels[i] = Math.round(
      (data[i * info.channels]! + data[i * info.channels + 1]! + data[i * info.channels + 2]!) / 3,
    )
  return new QRCodeReader()
    .decode(
      new BinaryBitmap(
        new HybridBinarizer(new RGBLuminanceSource(pixels, info.width, info.height)),
      ),
    )
    .getText()
}

async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
}

async function cameraFrame(token: string): Promise<{ directory: string; file: string }> {
  const directory = await mkdtemp(join(tmpdir(), 'mannschaft-loyalty-camera-'))
  const file = join(directory, 'camera.y4m')
  const image = await QRCode.toBuffer(token, { width: 400, margin: 4, errorCorrectionLevel: 'H' })
  const luminance = await sharp(image)
    .resize(400, 400)
    .extend({ top: 40, bottom: 40, left: 120, right: 120, background: '#ffffff' })
    .greyscale()
    .raw()
    .toBuffer()
  await writeFile(
    file,
    Buffer.concat([
      Buffer.from('YUV4MPEG2 W640 H480 F30:1 Ip A1:1 C420jpeg\nFRAME\n'),
      luminance,
      Buffer.alloc((640 * 480) / 2, 128),
    ]),
  )
  return { directory, file }
}

async function cleanupCameraFrame(frame: { directory: string; file: string }) {
  if (
    dirname(frame.file) !== frame.directory ||
    dirname(resolve(frame.directory)) !== resolve(tmpdir()) ||
    !basename(frame.directory).startsWith('mannschaft-loyalty-camera-')
  )
    throw new Error('Temporary camera cleanup path guard failed')
  await rm(frame.directory, { recursive: true, force: true })
}

test('AC01/16/20: サイドバーから会員QR・walletに到達し外部規約が妨げない', async ({ page }) => {
  await loginViaApi(page, USER, { apiBaseUrl: API })
  const external: string[] = []
  page.on('request', (request) => {
    if (/qrserver|create-qr-code/.test(request.url())) external.push(request.url())
  })
  await page.goto('/dashboard', { waitUntil: 'commit' })
  await waitForHydration(page)
  await page.getByRole('button', { name: 'メニューを開く', exact: true }).click()
  const navigation = page.getByRole('navigation', { name: 'グローバルナビゲーション', exact: true })
  await expect(navigation).toBeVisible()
  await navigation.getByRole('link', { name: '会員QR', exact: true }).click()
  await expect(page).toHaveURL(/\/my\/member-qr$/)
  await expect(page.getByTestId('member-qr')).toBeVisible()
  await page.getByRole('button', { name: 'メニューを開く', exact: true }).click()
  await expect(navigation).toBeVisible()
  await navigation.getByRole('link', { name: 'ポイントカード', exact: true }).click()
  await expect(page).toHaveURL(/\/wallet$/)
  await waitForHydration(page)
  await expect(page.getByRole('heading', { name: 'チームポイント', exact: true })).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('link', { name: '会員QR', exact: true }).last().click()
  await expect(page.getByTestId('member-qr')).toBeVisible()
  expect(await decodeQr(page, '[data-testid="member-qr"] .qr-code-image')).toBeTruthy()
  const refresh = page.getByRole('button', { name: '更新', exact: true })
  await refresh.focus()
  await expect(refresh).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('member-qr')).toBeVisible()
  await expect(page.getByRole('img', { name: '会員QR', exact: true })).toBeVisible()
  await expect(page.getByTestId('member-qr').getByRole('status')).not.toHaveText('')
  expect(external).toEqual([])
  await noOverflow(page)
})

for (const role of [
  'admin',
  'individual',
  'supporter',
  'member',
  'delegatedDeputy',
  'deputy',
  'otherTeamAdmin',
  'organizationAdmin',
  'systemAdmin',
] as const) {
  test(`AC05/06/32: ${role}のスタッフと設定導線・URL直打ち`, async ({ page }) => {
    await loginViaApi(page, fixture.actors[role], { apiBaseUrl: API })
    const staffAllowed = role === 'admin' || role === 'delegatedDeputy'
    const settingsAllowed = role === 'admin'
    await page.goto(`/teams/${fixture.team.slug}/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(page)
    if (staffAllowed) {
      await expect(page.getByRole('button', { name: '会員QRをカメラで読取' })).toBeVisible()
      await expect(page.getByRole('link', { name: 'チームポイント設定', exact: true })).toHaveCount(
        settingsAllowed ? 1 : 0,
      )
    } else {
      await expect(page.getByTestId('load-error-state')).toBeVisible()
      await expect(page.getByRole('button', { name: '会員QRをカメラで読取' })).toHaveCount(0)
    }
    await page.goto(`/teams/${fixture.team.slug}/settings/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(page)
    if (settingsAllowed)
      await expect(page.getByRole('button', { name: '保存', exact: true }).first()).toBeVisible()
    else {
      await expect(page.getByTestId('load-error-state')).toBeVisible()
      await expect(page.getByRole('button', { name: '保存', exact: true })).toHaveCount(0)
    }
  })
}

test('AC05: 未ログインのスタッフURLはログインへ遷移する', async ({ page }) => {
  await page.goto(`/teams/${fixture.team.slug}/loyalty`, { waitUntil: 'commit' })
  await waitForHydration(page)
  await expect(page).toHaveURL(/\/login\?redirect=/)
})

test('AC03/11/15: 実会員QRをカメラ映像から読取り付与・交換し本人履歴を更新する', async ({
  page,
}) => {
  test.setTimeout(180_000)
  await loginViaApi(page, USER, { apiBaseUrl: API })
  await page.goto('/my/member-qr', { waitUntil: 'commit' })
  await waitForHydration(page)
  await expect(page.getByTestId('member-qr')).toBeVisible()
  const token = await decodeQr(page, '[data-testid="member-qr"] .qr-code-image')
  const frame = await cameraFrame(token)
  let cameraBrowser: Browser | undefined
  try {
    cameraBrowser = await chromium.launch({
      args: [
        '--use-fake-ui-for-media-stream',
        '--use-fake-device-for-media-stream',
        `--use-file-for-fake-video-capture=${frame.file}`,
        '--host-resolver-rules=MAP localhost 127.0.0.1',
      ],
    })
    const staff = await cameraBrowser.newPage({
      baseURL: BASE,
      viewport: { width: 390, height: 844 },
      isMobile: true,
      hasTouch: true,
      permissions: ['camera'],
      locale: 'ja-JP',
    })
    await loginViaApi(staff, ADMIN, { apiBaseUrl: API })
    await staff.goto(`/teams/${fixture.team.slug}/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(staff)
    await staff.getByRole('button', { name: '会員QRをカメラで読取' }).click()
    await expect(staff.getByRole('button', { name: '内容を確認して確定' })).toBeVisible()
    const awarded = staff.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/awards$/.test(response.url()),
    )
    await staff.getByRole('button', { name: '内容を確認して確定' }).click()
    expect((await awarded).status()).toBe(200)
    await page.goto(`/my/team-points/${fixture.team.numericId}`, { waitUntil: 'commit' })
    await waitForHydration(page)
    await expect(page.getByTestId('loyalty-balance')).toBeVisible()
    await expect(page.getByTestId('loyalty-balance')).toHaveText('25ポイント')
    await expect(page.getByRole('progressbar', { name: 'E2E特典', exact: true })).toHaveAttribute('value', '20')
    await expect(page.getByText(textFor('ja')('loyalty.remaining', { points: 0 }), { exact: true })).toBeVisible()
    await expect(page.getByText(/· 加算 ·/)).toBeVisible()
    await staff.getByRole('button', { name: '次の受付を開始', exact: true }).click()
    await staff.getByText('特典交換', { exact: true }).click()
    await staff.getByLabel('特典', { exact: true }).click()
    await staff.getByRole('option').first().click()
    await staff.getByRole('button', { name: '会員QRをカメラで読取' }).click()
    await expect(staff.getByRole('button', { name: '内容を確認して確定' })).toBeVisible()
    const redeemed = staff.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/redemptions$/.test(response.url()),
    )
    await staff.getByRole('button', { name: '内容を確認して確定' }).click()
    expect((await redeemed).status()).toBe(200)
    await page.getByRole('button', { name: '更新', exact: true }).click()
    await expect(page.getByTestId('loyalty-balance')).toHaveText('5ポイント')
    await expect(page.getByRole('progressbar', { name: 'E2E特典', exact: true })).toHaveAttribute('value', '5')
    await expect(page.getByText(textFor('ja')('loyalty.remaining', { points: 15 }), { exact: true })).toBeVisible()
    await expect(page.getByText(/· 特典交換 ·/)).toBeVisible()
    await noOverflow(page)
  } finally {
    try {
      await cameraBrowser?.close()
    } finally {
      await cleanupCameraFrame(frame)
    }
  }
})

test('AC17/21/22/23/31/32: 設定→印刷QR実読取→本人申請→別スタッフ承認→本人残高履歴', async ({
  page,
  browser,
}) => {
  test.setTimeout(180_000)
  await loginViaApi(page, ADMIN, { apiBaseUrl: API })
  const suffix = Date.now().toString(36)
  const slug = `e2e-loyalty-${suffix}`
  const created = await page.request.post(`${API}/api/v1/teams`, {
    data: { name: `E2Eポイント${suffix}`, slug, visibility: 'MEMBERS_AND_ABOVE' },
  })
  expect(created.status()).toBe(201)
  const team = ((await created.json()) as { data: { numericId: number; slug: string } }).data
  let memberContext: BrowserContext | undefined
  try {
    await page.goto(`/teams/${slug}/settings/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(page)
    await expect(page.getByRole('heading', { level: 1, name: 'チームポイント設定' })).toBeVisible()
    await page.getByText('ポイントプログラムを有効化', { exact: true }).click()
    await page.getByText('メンバー以上', { exact: true }).click()
    await page.getByRole('option', { name: '全認証済み個人（非所属を含む）' }).click()
    await page.getByRole('button', { name: '保存', exact: true }).first().click()
    const rule = page
      .locator('form')
      .filter({ has: page.getByText('1回の加算数', { exact: true }) })
    await rule.getByRole('textbox').fill('来店ポイント')
    await rule.getByRole('button', { name: '保存', exact: true }).click()
    const reward = page
      .locator('form')
      .filter({ has: page.getByText('必要ポイント', { exact: true }) })
      .last()
    await reward.getByRole('textbox').first().fill('来店特典')
    await reward.getByRole('spinbutton').fill('1')
    await reward.getByRole('button', { name: '保存', exact: true }).click()
    await page.getByText('新しい受付拠点名', { exact: false }).locator('input').fill('ポイント受付')
    await page.getByRole('button', { name: '受付拠点を作成', exact: true }).click()
    await page.getByRole('button', { name: 'QRプレビュー', exact: true }).click()
    await expect(page.getByTestId('loyalty-reception-print')).toBeVisible()
    const url = await decodeQr(page, '[data-testid="loyalty-reception-print"] .qr-code-image')
    expect(new URL(url).pathname).toBe('/my/team-points/receive')
    expect(new URL(url).search).toBe('')
    await noOverflow(page)
    memberContext = await browser.newContext({
      viewport: { width: 390, height: 844 },
      isMobile: true,
      hasTouch: true,
      baseURL: BASE,
      locale: 'ja-JP',
    })
    const member = await memberContext.newPage()
    await loginViaApi(member, USER, { apiBaseUrl: API })
    const writes: string[] = []
    member.on('request', (request) => {
      if (request.method() === 'POST' && /\/self-awards$|\/self-checkin$/.test(request.url()))
        writes.push(request.url())
    })
    await member.goto(url, { waitUntil: 'commit' })
    await waitForHydration(member)
    await expect(member.getByRole('heading', { level: 1, name: '受付内容の確認' })).toBeVisible()
    await expect(member.getByRole('button', { name: '内容を確認して確定' })).toBeVisible()
    expect(member.url()).not.toContain('locationToken')
    expect(writes).toEqual([])
    await member.getByRole('button', { name: '内容を確認して確定' }).click()
    await expect(member.getByText('承認待ち', { exact: true })).toBeVisible()
    let code = (await member.getByText(/^照合コード /).textContent())!
      .replace('照合コード ', '')
      .trim()
    await member.getByRole('link', { name: '受付申請', exact: true }).last().click()
    await member.getByRole('link').filter({ hasText: '承認待ち' }).click()
    let detailUrl = member.url()
    await member.getByRole('button', { name: '申請を取り消す', exact: true }).click()
    await expect(member.getByText('取消済み', { exact: true })).toBeVisible()
    await member.goto(url, { waitUntil: 'commit' })
    await waitForHydration(member)
    await member.getByRole('button', { name: '内容を確認して確定' }).click()
    await expect(member.getByText('承認待ち', { exact: true })).toBeVisible()
    await page.goto(`/teams/${slug}/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(page)
    await page.getByRole('button').filter({ hasText: '来店ポイント' }).last().click()
    await page.getByRole('button', { name: '却下', exact: true }).click()
    await member.getByRole('link', { name: '受付申請', exact: true }).last().click()
    await member.getByRole('link').filter({ hasText: '却下済み' }).click()
    await expect(member.getByText('却下済み', { exact: true })).toBeVisible()
    await member.goto(url, { waitUntil: 'commit' })
    await waitForHydration(member)
    await member.getByRole('button', { name: '内容を確認して確定' }).click()
    await expect(member.getByText('承認待ち', { exact: true })).toBeVisible()
    code = (await member.getByText(/^照合コード /).textContent())!.replace('照合コード ', '').trim()
    await member.getByRole('link', { name: '受付申請', exact: true }).last().click()
    await member.getByRole('link').filter({ hasText: '承認待ち' }).click()
    detailUrl = member.url()
    // 本人専用UUIDを別のログイン主体から開いても申請者名・照合コードを開示しない。
    await page.goto(new URL(detailUrl).pathname, { waitUntil: 'commit' })
    await waitForHydration(page)
    await expect(page.getByTestId('load-error-state')).toBeVisible()
    await expect(page.getByText(/^照合コード /)).toHaveCount(0)
    await page.goto(`/teams/${slug}/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(page)
    await page.getByRole('button').filter({ hasText: '来店ポイント' }).last().click()
    await page.getByLabel('本人画面の照合コード').fill(code)
    const approved = page.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/approve$/.test(response.url()),
    )
    await page.getByRole('button', { name: '承認して加算', exact: true }).click()
    expect((await approved).status()).toBe(200)
    await member.goto(detailUrl, { waitUntil: 'commit' })
    await waitForHydration(member)
    await expect(member.getByText('承認済み', { exact: true })).toBeVisible()
    await member.getByRole('link', { name: 'チームポイント', exact: true }).click()
    await expect(member.getByTestId('loyalty-balance')).toHaveText('1ポイント')
    await expect(member.getByText('来店ポイント · 加算 · 1')).toBeVisible()
    await noOverflow(member)
    // 非所属個人のスタッフ/settings直打ちは表示とBE拒否の両方を確認する。
    await member.goto(`/teams/${slug}/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(member)
    await expect(member.getByTestId('load-error-state')).toBeVisible()
    await expect(member.getByRole('button', { name: '承認して加算', exact: true })).toHaveCount(0)
    await member.goto(`/teams/${slug}/settings/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(member)
    await expect(member.getByTestId('load-error-state')).toBeVisible()
    await expect(member.getByRole('button', { name: '保存', exact: true })).toHaveCount(0)
    await page.goto(`/teams/${slug}/settings/loyalty`, { waitUntil: 'commit' })
    await waitForHydration(page)
    await page.getByText('スタッフ承認（確定まで加算なし）', { exact: true }).click()
    await page.getByRole('option', { name: '自動加算', exact: true }).click()
    await page.getByRole('button', { name: '保存', exact: true }).first().click()
    const dialog = page.getByRole('alertdialog')
    await expect(dialog.getByText(/固定QRの撮影・遠隔再利用/)).toBeVisible()
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await loginViaApi(member, fixture.actors.resident1, { apiBaseUrl: API })
    await member.goto(url, { waitUntil: 'commit' })
    await waitForHydration(member)
    await expect(member.getByText(/固定QRの撮影・遠隔再利用/)).toBeVisible()
    await member.getByRole('button', { name: '内容を確認して確定' }).click()
    await expect(member.getByTestId('loyalty-balance')).toHaveText('1ポイント')
    await page.getByTestId('loyalty-location-select').click()
    await page.getByRole('option', { name: 'ポイント受付', exact: true }).click()
    await page.getByRole('button', { name: '受付拠点を停止', exact: true }).click()
    await expect(page.getByRole('alertdialog').getByText(/チェックインも停止/)).toBeVisible()
    await page
      .getByRole('alertdialog')
      .getByRole('button', { name: '受付拠点を停止', exact: true })
      .click()
    await loginViaApi(member, fixture.actors.resident2, { apiBaseUrl: API })
    await member.goto(url, { waitUntil: 'commit' })
    await waitForHydration(member)
    await expect(member.getByTestId('load-error-state')).toBeVisible()
    await expect(member.getByRole('button', { name: '内容を確認して確定' })).toHaveCount(0)
    expect(team.numericId).toBeGreaterThan(0)
  } finally {
    await memberContext?.close()
    const cleanup = await page.request.delete(`${API}/api/v1/teams/${slug}`)
    expect([200, 204]).toContain(cleanup.status())
  }
})

for (const locale of Object.keys(localeNames) as LoyaltyLocale[]) {
  test(`AC20/31: ${locale}のQR・財布・申請承認・設定確認・実カメラ拒否`, async ({ page, browser }) => {
    test.setTimeout(360_000)
    const t = textFor(locale)
    const suffix = `${locale}-${Date.now().toString(36)}`
    const slug = `e2e-loyalty-locale-${suffix}`
    const ruleName = `Visit ${suffix}`
    const locationName = `Reception ${suffix}`
    let memberContext: BrowserContext | undefined
    let member: Page | undefined
    let createdTeam = false
    let primaryError: unknown
    const failures: Array<{ method: string; path: string; status?: number }> = []
    page.on('requestfailed', request => {
      const item = { method: request.method(), path: new URL(request.url()).pathname }
      failures.push(item)
      const reason = request.failure()?.errorText ?? ''
      console.info('[loyalty-ui] request failed', JSON.stringify(item), /^net::[A-Z0-9_]+$/.test(reason) ? reason : 'request-failed')
    })
    page.on('response', response => {
      if (response.status() < 400) return
      const item = { method: response.request().method(), path: new URL(response.url()).pathname, status: response.status() }
      failures.push(item)
      console.info('[loyalty-ui] HTTP failure', JSON.stringify(item))
    })
    page.on('framenavigated', frame => {
      if (frame === page.mainFrame()) console.info('[loyalty-ui] document path', new URL(frame.url()).pathname)
    })
    page.on('pageerror', error => console.info('[loyalty-ui] browser error type', error.name))
    page.on('console', message => {
      if (message.type() !== 'error') return
      const location = message.location()
      console.info('[loyalty-ui] browser console error source', location.url ? new URL(location.url).pathname : 'unknown', location.lineNumber)
    })
    try {
      // 同じactorの並行ログインを避け、全caseはreal configのworkers=1で順次実行する。
      console.info(`[loyalty-ui] ${locale}: dedicated admin login`)
      await loginViaApi(page, ADMIN, { apiBaseUrl: API })
      console.info(`[loyalty-ui] ${locale}: admin authenticated`)
      await prepareLocale(page, ADMIN, locale)
      const created = await page.request.post(`${API}/api/v1/teams`, {
        data: { name: `Locale ${suffix}`, slug, visibility: 'MEMBERS_AND_ABOVE' },
      })
      expect(created.status()).toBe(201)
      createdTeam = true
      const team = ((await created.json()) as { data: { numericId: number } }).data
      await page.goto(`/teams/${slug}/settings/loyalty`, { waitUntil: 'commit' })
      await waitForHydration(page)
      await expect(page.getByRole('heading', { level: 1, name: t('loyalty.settings.title'), exact: true })).toBeVisible()
      await page.getByText(t('loyalty.settings.enabled'), { exact: true }).click()
      await page.getByText(t('loyalty.audience.membersAndAbove'), { exact: true }).click()
      await page.getByRole('option', { name: t('loyalty.audience.allAuthenticated'), exact: true }).click()
      await page.getByRole('button', { name: t('loyalty.save'), exact: true }).first().click()
      await expect(page.getByText(t('loyalty.saved'), { exact: true })).toBeVisible()
      const rule = page.locator('form').filter({ has: page.getByText(t('loyalty.rule.amount'), { exact: true }) })
      // 実SFC/VeeValidateの入力境界を各言語で通す。対象操作のAPI呼出やレスポンスmockはしない。
      const validationKeys = await page.locator('#__nuxt').evaluate(element => {
        const app = (element as unknown as Element & { __vue_app__: { config: { globalProperties: {
          $nuxt: { $i18n: { te: (key: string) => boolean } },
        } } } }).__vue_app__
        return { flat: app.config.globalProperties.$nuxt.$i18n.te('required'), nested: app.config.globalProperties.$nuxt.$i18n.te('validation.required') }
      })
      expect(validationKeys.flat).toBe(true)
      await test.info().attach(`validation-namespace-${locale}`, { body: JSON.stringify(validationKeys), contentType: 'application/json' })
      const reward = page.locator('form').filter({ has: page.getByText(t('loyalty.reward.amount'), { exact: true }) })
      const reception = page.locator('form').filter({ has: page.getByRole('button', { name: t('loyalty.reception.create'), exact: true }) })
      async function invalidName(form: Locator, numeric: boolean) {
        console.info(`[loyalty-ui] ${locale}: form required/long name/numeric validation`)
        const name = form.locator('input').first()
        await name.fill('')
        await form.getByRole('button').click()
        await expect(form.getByRole('alert').first()).toBeVisible()
        expect.soft(await form.getByRole('alert').allTextContents()).toContain(t('required'))
        const requiredMessages = await form.getByRole('alert').allTextContents()
        await name.fill('a'.repeat(101))
        await form.getByRole('button').click()
        await expect.poll(() => form.getByRole('alert').allTextContents()).not.toEqual(requiredMessages)
        expect.soft(await form.getByRole('alert').allTextContents()).toContain(t('max_length', { max: 100 }))
        const longNameMessages = await form.getByRole('alert').allTextContents()
        await name.fill(`Valid ${suffix}`)
        if (numeric) {
          await form.getByRole('spinbutton').fill('')
          await form.getByRole('spinbutton').blur()
          await form.getByRole('button').click()
          await expect.poll(async () => {
            const messages = await form.getByRole('alert').allTextContents()
            return messages.length > 0 && JSON.stringify(messages) !== JSON.stringify(longNameMessages)
          }).toBe(true)
          expect.soft(await form.getByRole('alert').allTextContents()).toContain(t('invalid_format'))
          await form.getByRole('spinbutton').fill('20')
          await form.getByRole('spinbutton').blur()
        }
      }
      await invalidName(rule, true)
      await invalidName(reward, true)
      await invalidName(reception, false)
      const rewardPath = `/api/v1/teams/${team.numericId}/loyalty/rewards`
      let invalidDescriptionSends = 0
      const observeDescription = (request: import('@playwright/test').Request) => {
        if (request.method() === 'POST' && new URL(request.url()).pathname === rewardPath
          && request.postDataJSON()?.description?.length === 501) invalidDescriptionSends++
      }
      page.on('request', observeDescription)
      try {
        await reward.getByRole('textbox').first().fill(`Reward ${suffix}`)
        await reward.locator('textarea').fill('a'.repeat(501))
        await reward.getByRole('button').click()
        await expect.poll(async () => invalidDescriptionSends > 0
          || (await reward.getByRole('alert').allTextContents()).includes(t('max_length', { max: 500 }))).toBe(true)
        expect.soft(invalidDescriptionSends, '501文字は実APIへ送信する前に拒否する').toBe(0)
        expect.soft(await reward.getByRole('alert').allTextContents()).toContain(t('max_length', { max: 500 }))
        await reward.locator('textarea').fill('a'.repeat(500))
        const rewardCreated = page.waitForResponse(response => response.request().method() === 'POST'
          && new URL(response.url()).pathname === rewardPath && response.request().postDataJSON()?.description?.length === 500)
        await reward.getByRole('button').click()
        expect((await rewardCreated).status()).toBe(201)
      } finally {
        page.off('request', observeDescription)
      }
      // 最初は同じ既存caseのフォーム部分だけでred/greenを取り、その後19件全体を実行する。
      if (process.env.LOYALTY_FORM_REGRESSION_ONLY === 'true') return
      memberContext = await browser.newContext({
        baseURL: BASE, viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
        storageState: { cookies: [], origins: [] },
      })
      member = await memberContext.newPage()
      member.on('pageerror', error => console.info('[loyalty-ui] member browser error type', error.name))
      member.on('requestfailed', request => console.info('[loyalty-ui] member request failed', new URL(request.url()).pathname))
      console.info(`[loyalty-ui] ${locale}: dedicated member login`)
      await loginViaApi(member, USER, { apiBaseUrl: API })
      console.info(`[loyalty-ui] ${locale}: member authenticated; locale operation`)
      await prepareLocale(member, USER, locale)
      await member.goto('/my/member-qr', { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByRole('heading', { level: 1, name: t('loyalty.memberQr.title'), exact: true })).toBeVisible()
      await expect(member.getByTestId('member-qr')).toBeVisible()
      await member.getByRole('button', { name: t('loyalty.refresh'), exact: true }).click()
      await expect(member.getByTestId('member-qr')).toBeVisible()
      await noOverflow(member)
      await member.goto('/wallet', { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByRole('heading', { name: t('loyalty.wallet.title'), exact: true })).toBeVisible()
      await expect(member.getByRole('dialog')).toHaveCount(0)

      await rule.getByRole('textbox').fill(ruleName)
      await rule.getByRole('spinbutton').fill('1')
      await rule.getByRole('button', { name: t('loyalty.save'), exact: true }).click()
      await page.getByText(t('loyalty.reception.newLocation'), { exact: false }).locator('input').fill(locationName)
      await page.getByRole('button', { name: t('loyalty.reception.create'), exact: true }).click()
      await page.getByRole('button', { name: t('loyalty.reception.preview'), exact: true }).click()
      await expect(page.getByText(t('loyalty.reception.printInstructions'), { exact: true })).toBeVisible()
      const url = await decodeQr(page, '[data-testid="loyalty-reception-print"] .qr-code-image')
      await noOverflow(page)
      await page.goto(`/teams/${slug}/loyalty`, { waitUntil: 'commit' })
      await waitForHydration(page)
      await expect(page.getByText(t('loyalty.requests.empty'), { exact: true })).toBeVisible()
      // ブラウザの実permissionをdenyし、getUserMediaの拒否をUIから通す（API/関数のmockなし）。
      const cdp = await page.context().newCDPSession(page)
      try {
        const { targetInfo } = await cdp.send('Target.getTargetInfo')
        await cdp.send('Browser.setPermission', {
          permission: { name: 'camera' }, setting: 'denied', origin: BASE,
          browserContextId: targetInfo.browserContextId,
        })
        await expect.poll(() => page.evaluate(async () =>
          (await navigator.permissions.query({ name: 'camera' as PermissionName })).state,
        )).toBe('denied')
        await page.getByRole('button', { name: t('loyalty.scan.camera'), exact: true }).click()
        await expect(page.getByRole('alert').filter({ hasText: t('loyalty.scan.cameraDenied') })).toBeVisible()
      } finally {
        await page.context().clearPermissions()
        await cdp.detach()
      }
      const applicant = member
      async function saveProgram(autoWarning = false) {
        const saved = page.waitForResponse(response => response.request().method() === 'PUT'
          && new URL(response.url()).pathname === `/api/v1/teams/${team.numericId}/loyalty/settings`)
        await page.getByRole('button', { name: t('loyalty.save'), exact: true }).first().click()
        if (autoWarning) {
          const dialog = page.getByRole('alertdialog')
          await expect(dialog.getByText(t('loyalty.mode.autoWarning'), { exact: true })).toBeVisible()
          await dialog.getByRole('button', { name: t('loyalty.save'), exact: true }).click()
        }
        expect((await saved).status()).toBe(200)
        await expect(page.getByText(t('loyalty.saved'), { exact: true })).toBeVisible()
      }
      async function submitReception() {
        await applicant.goto(url, { waitUntil: 'commit' })
        await waitForHydration(applicant)
        await expect(applicant.getByRole('heading', { level: 1, name: t('loyalty.self.confirm'), exact: true })).toBeVisible()
        await expect(applicant.getByText(t('loyalty.mode.staffApproval'), { exact: true })).toBeVisible()
        await applicant.getByRole('button', { name: t('loyalty.confirm'), exact: true }).click()
        await expect(applicant.getByRole('status')).toHaveText(t('loyalty.requests.status.pending'))
      }
      async function openPendingRequest() {
        await applicant.getByRole('link', { name: t('loyalty.requests.title'), exact: true }).last().click()
        await applicant.getByRole('link').filter({ hasText: ruleName })
          .filter({ hasText: t('loyalty.requests.status.pending') }).click()
        return applicant.url()
      }
      // 対象条件も実設定UIで変更し、非所属本人の003を各言語で観測する。
      await page.goto(`/teams/${slug}/settings/loyalty`, { waitUntil: 'commit' })
      await waitForHydration(page)
      await page.getByText(t('loyalty.audience.allAuthenticated'), { exact: true }).click()
      await page.getByRole('option', { name: t('loyalty.audience.membersAndAbove'), exact: true }).click()
      await saveProgram()
      const ineligible = member.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/v1/loyalty/self-award-bootstrap/me')
      await member.goto(url, { waitUntil: 'commit' })
      await waitForHydration(member)
      expect(((await (await ineligible).json()) as { error: { code: string } }).error.code).toBe('LOYALTY_003')
      await expect(member.getByText(t('loyalty.error.ineligible'), { exact: true })).toBeVisible()
      await expect(member.getByRole('button', { name: t('loyalty.confirm'), exact: true })).toHaveCount(0)
      await page.getByText(t('loyalty.audience.membersAndAbove'), { exact: true }).click()
      await page.getByRole('option', { name: t('loyalty.audience.allAuthenticated'), exact: true }).click()
      await saveProgram()
      await member.goto(url, { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByRole('heading', { level: 1, name: t('loyalty.self.confirm'), exact: true })).toBeVisible()
      await expect(member.getByText(t('loyalty.mode.staffApproval'), { exact: true })).toBeVisible()
      await expect(member.getByTestId('loyalty-balance')).toHaveText(t('loyalty.balance', { points: 0 }))
      expect(new URL(member.url()).hash).toBe('')
      await member.getByRole('button', { name: t('loyalty.confirm'), exact: true }).click()
      await expect(member.getByRole('status')).toHaveText(t('loyalty.requests.status.pending'))
      await openPendingRequest()
      await member.getByRole('button', { name: t('loyalty.requests.cancel'), exact: true }).click()
      await expect(member.getByRole('status')).toHaveText(t('loyalty.requests.status.cancelled'))
      await expect(member.getByText(t('loyalty.requests.reason.userCancelled'), { exact: true })).toBeVisible()
      await submitReception()
      const rejectedDetail = await openPendingRequest()
      await page.goto(`/teams/${slug}/loyalty`, { waitUntil: 'commit' })
      await waitForHydration(page)
      await page.getByRole('button').filter({ hasText: ruleName }).click()
      const rejected = page.waitForResponse(response => response.request().method() === 'POST' && /\/reject$/.test(response.url()))
      await page.getByRole('button', { name: t('loyalty.requests.reject'), exact: true }).click()
      expect((await rejected).status()).toBe(200)
      await member.goto(rejectedDetail, { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByRole('status')).toHaveText(t('loyalty.requests.status.rejected'))
      await expect(member.getByText(t('loyalty.requests.reason.staffRejected'), { exact: true })).toBeVisible()
      await submitReception()
      const invalidatedDetail = await openPendingRequest()
      // 同じ申請の最新previewを開いた後、mode変更でprogram版だけ更新する。加算数1は変えない。
      await member.goto(url, { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByRole('button', { name: t('loyalty.confirm'), exact: true })).toBeVisible()
      await page.goto(`/teams/${slug}/settings/loyalty`, { waitUntil: 'commit' })
      await waitForHydration(page)
      await page.getByText(t('loyalty.mode.staffApproval'), { exact: true }).click()
      await page.getByRole('option', { name: t('loyalty.mode.auto'), exact: true }).click()
      await saveProgram(true)
      const changed = member.waitForResponse(response => response.request().method() === 'POST' && /\/self-awards$/.test(response.url()))
      await member.getByRole('button', { name: t('loyalty.confirm'), exact: true }).click()
      expect(((await (await changed).json()) as { error: { code: string } }).error.code).toBe('LOYALTY_007')
      await expect(member.getByText(t('loyalty.error.changed'), { exact: true })).toBeVisible()
      await member.goto(invalidatedDetail, { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByRole('status')).toHaveText(t('loyalty.requests.status.invalidated'))
      await expect(member.getByText(t('loyalty.requests.reason.versionChanged'), { exact: true })).toBeVisible()
      // TTL短縮や時計操作でなく、設定版変更による無効化をUIで確認して再申請する。
      await page.getByText(t('loyalty.mode.auto'), { exact: true }).click()
      await page.getByRole('option', { name: t('loyalty.mode.staffApproval'), exact: true }).click()
      await saveProgram()
      await submitReception()
      const codePattern = t('loyalty.requests.code', { code: '__CODE__' })
      const [prefix, postfix] = codePattern.split('__CODE__')
      const codeLine = (await member.getByText(new RegExp(`^${prefix!.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)).textContent())!
      const code = codeLine.slice(prefix!.length, postfix ? -postfix.length : undefined).trim()
      expect(code).toMatch(/^[A-Z2-7]{8}$/)
      await expect(member.getByText(t('loyalty.requests.showStaff'), { exact: true })).toBeVisible()
      const detailUrl = await openPendingRequest()
      await page.goto(`/teams/${slug}/loyalty`, { waitUntil: 'commit' })
      await waitForHydration(page)
      await page.getByRole('button').filter({ hasText: ruleName }).click()
      await expect(page.getByText(t('loyalty.requests.compareBeforeApprove'), { exact: true })).toBeVisible()
      await page.getByLabel(t('loyalty.requests.enterCode')).fill(code)
      const approved = page.waitForResponse(response => response.request().method() === 'POST' && /\/approve$/.test(response.url()))
      await page.getByRole('button', { name: t('loyalty.requests.approve'), exact: true }).click()
      expect((await approved).status()).toBe(200)
      await expect(page.getByText(t('loyalty.requests.empty'), { exact: true })).toBeVisible()
      await member.goto(detailUrl, { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByRole('status')).toHaveText(t('loyalty.requests.status.approved'))
      await member.getByRole('link', { name: t('loyalty.wallet.title'), exact: true }).click()
      await expect(member.getByTestId('loyalty-balance')).toHaveText(t('loyalty.balance', { points: 1 }))
      await expect(member.getByText(`${ruleName} · ${t('loyalty.history.award')} · 1`)).toBeVisible()
      await member.getByRole('button', { name: t('loyalty.refresh'), exact: true }).click()
      await expect(member.getByTestId('loyalty-balance')).toHaveText(t('loyalty.balance', { points: 1 }))
      await noOverflow(member)
      await member.goto('/wallet', { waitUntil: 'commit' })
      await waitForHydration(member)
      await member.getByRole('link').filter({ hasText: `Locale ${suffix}` }).click()
      await expect(member.getByTestId('loyalty-balance')).toHaveText(t('loyalty.balance', { points: 1 }))
      expect(team.numericId).toBeGreaterThan(0)

      await page.goto(`/teams/${slug}/settings/loyalty`, { waitUntil: 'commit' })
      await waitForHydration(page)
      await page.getByTestId('loyalty-location-select').click()
      await page.getByRole('option', { name: locationName, exact: true }).click()
      await page.getByRole('button', { name: t('loyalty.reception.stop'), exact: true }).click()
      const stopDialog = page.getByRole('alertdialog')
      await expect(stopDialog.getByText(t('loyalty.reception.sharedLocationStopWarning'), { exact: true })).toBeVisible()
      await stopDialog.getByRole('button', { name: t('loyalty.reception.stop'), exact: true }).click()
      // 停止を読取で観測してから本人受付を再表示する。エラー応答自体を偽装しない。
      await expect(page.getByRole('button', { name: t('loyalty.reception.preview'), exact: true })).toBeDisabled()
      await member.goto(url, { waitUntil: 'commit' })
      await waitForHydration(member)
      await expect(member.getByTestId('load-error-state')).toBeVisible()
      await expect(member.getByRole('button', { name: t('loyalty.confirm'), exact: true })).toHaveCount(0)
      await expect(member.getByText(t('loyalty.self.rescan'), { exact: true })).toBeVisible()
      await noOverflow(member)
      await test.info().attach(`locale-state-${locale}`, {
        body: JSON.stringify({ locale, staff: 'admin', applicant: 'individual', cleanupTargetLocale: 'ja', viewport: '390x844' }),
        contentType: 'application/json',
      })
    } catch (error) {
      primaryError = error
      await test.info().attach('primary-ui-failure', {
        body: JSON.stringify({ errorType: error instanceof Error ? error.name : typeof error,
          path: new URL(page.url()).pathname, memberPath: member ? new URL(member.url()).pathname : null, failedRequests: failures }), contentType: 'application/json',
      })
      throw error
    } finally {
      try {
      // 次case/住民へアカウント言語を持ち越さない。対象操作と同じ実UIから戻す。
      try {
        if (member && !member.isClosed()) await prepareLocale(member, USER, 'ja')
      } finally {
        await memberContext?.close()
        try {
          await prepareLocale(page, ADMIN, 'ja')
        } finally {
          if (createdTeam) {
            const cleanup = await page.request.delete(`${API}/api/v1/teams/${slug}`)
            expect([200, 204]).toContain(cleanup.status())
          }
        }
      }
      } catch (cleanupError) {
        test.info().annotations.push({ type: 'cleanup-error', description: cleanupError instanceof Error ? cleanupError.name : typeof cleanupError })
        expect.soft(false, `${primaryError ? '本来の失敗とは別に' : ''}locale/team cleanupが失敗した`).toBe(true)
      }
    }
  })
}
