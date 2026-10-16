/**
 * F18.1 実機: APIはログイン・使い捨てチーム作成・後始末だけに使用する。
 * 設定/受付QR発行/本人確定/スタッフ承認/取消/却下は実画面で操作する。
 * QRは画面のSVGをスクリーンショットし、既存ZXingで実デコードする。
 */
import { test, expect, chromium, type Page, type BrowserContext } from '@playwright/test'
import sharp from 'sharp'
import { BinaryBitmap, HybridBinarizer, RGBLuminanceSource, QRCodeReader } from '@zxing/library'
import { loginViaApi } from '../fixtures/auth'
import { readFileSync } from 'node:fs'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import QRCode from 'qrcode'

test.use({
  storageState: { cookies: [], origins: [] },
  viewport: { width: 390, height: 844 },
  trace: 'off',
  screenshot: 'off',
})
const API = process.env.API_BASE_URL ?? 'http://localhost:8081'
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

test('AC01/16/20: サイドバーから会員QR・walletに到達し外部規約が妨げない', async ({ page }) => {
  await loginViaApi(page, USER, { apiBaseUrl: API })
  const external: string[] = []
  page.on('request', (request) => {
    if (/qrserver|create-qr-code/.test(request.url())) external.push(request.url())
  })
  await page.goto('/wallet')
  await expect(page.getByRole('heading', { name: 'チームポイント', exact: true })).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.getByRole('link', { name: '会員QR', exact: true }).last().click()
  await expect(page.getByTestId('member-qr')).toBeVisible()
  expect(await decodeQr(page, '[data-testid="member-qr"] .qr-code-image')).toBeTruthy()
  await page.getByRole('button', { name: '更新', exact: true }).click()
  await expect(page.getByTestId('member-qr')).toBeVisible()
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
    await page.goto(`/teams/${fixture.team.slug}/loyalty`)
    if (staffAllowed) {
      await expect(page.getByRole('button', { name: '会員QRをカメラで読取' })).toBeVisible()
      await expect(page.getByRole('link', { name: 'チームポイント設定', exact: true })).toHaveCount(
        settingsAllowed ? 1 : 0,
      )
    } else {
      await expect(page.getByTestId('load-error-state')).toBeVisible()
      await expect(page.getByRole('button', { name: '会員QRをカメラで読取' })).toHaveCount(0)
    }
    await page.goto(`/teams/${fixture.team.slug}/settings/loyalty`)
    if (settingsAllowed)
      await expect(page.getByRole('button', { name: '保存', exact: true }).first()).toBeVisible()
    else {
      await expect(page.getByTestId('load-error-state')).toBeVisible()
      await expect(page.getByRole('button', { name: '保存', exact: true })).toHaveCount(0)
    }
  })
}

test('AC05: 未ログインのスタッフURLはログインへ遷移する', async ({ page }) => {
  await page.goto(`/teams/${fixture.team.slug}/loyalty`)
  await expect(page).toHaveURL(/\/login\?redirect=/)
})

test('AC03/11/15: 実会員QRをカメラ映像から読取り付与・交換し本人履歴を更新する', async ({
  page,
}) => {
  test.setTimeout(180_000)
  await loginViaApi(page, USER, { apiBaseUrl: API })
  await page.goto('/my/member-qr')
  await expect(page.getByTestId('member-qr')).toBeVisible()
  const token = await decodeQr(page, '[data-testid="member-qr"] .qr-code-image')
  const frame = await cameraFrame(token)
  const cameraBrowser = await chromium.launch({
    args: [
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      `--use-file-for-fake-video-capture=${frame.file}`,
      '--host-resolver-rules=MAP localhost 127.0.0.1',
    ],
  })
  try {
    const staff = await cameraBrowser.newPage({
      baseURL: process.env.BASE_URL ?? 'http://localhost:3002',
      viewport: { width: 390, height: 844 },
      permissions: ['camera'],
      locale: 'ja-JP',
    })
    await loginViaApi(staff, ADMIN, { apiBaseUrl: API })
    await staff.goto(`/teams/${fixture.team.slug}/loyalty`)
    await staff.getByRole('button', { name: '会員QRをカメラで読取' }).click()
    await expect(staff.getByRole('button', { name: '内容を確認して確定' })).toBeVisible()
    const awarded = staff.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/awards$/.test(response.url()),
    )
    await staff.getByRole('button', { name: '内容を確認して確定' }).click()
    expect((await awarded).status()).toBe(200)
    await page.goto(`/my/team-points/${fixture.team.numericId}`)
    await expect(page.getByTestId('loyalty-balance')).toBeVisible()
    await expect(page.getByText(/· 加算 ·/)).toBeVisible()
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
    await expect(page.getByText(/· 特典交換 ·/)).toBeVisible()
    await noOverflow(page)
  } finally {
    await cameraBrowser.close()
    await rm(frame.directory, { recursive: true, force: true })
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
    await page.goto(`/teams/${slug}/settings/loyalty`)
    await expect(page.getByRole('heading', { name: 'チームポイント設定' })).toBeVisible()
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
      baseURL: process.env.BASE_URL ?? 'http://localhost:3002',
      locale: 'ja-JP',
    })
    const member = await memberContext.newPage()
    await loginViaApi(member, USER, { apiBaseUrl: API })
    const writes: string[] = []
    member.on('request', (request) => {
      if (request.method() === 'POST' && /\/self-awards$|\/self-checkin$/.test(request.url()))
        writes.push(request.url())
    })
    await member.goto(url)
    await expect(member.getByRole('heading', { name: '受付内容の確認' })).toBeVisible()
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
    await member.goto(url)
    await member.getByRole('button', { name: '内容を確認して確定' }).click()
    await expect(member.getByText('承認待ち', { exact: true })).toBeVisible()
    await page.goto(`/teams/${slug}/loyalty`)
    await page.getByRole('button').filter({ hasText: '来店ポイント' }).last().click()
    await page.getByRole('button', { name: '却下', exact: true }).click()
    await member.getByRole('link', { name: '受付申請', exact: true }).last().click()
    await member.getByRole('link').filter({ hasText: '却下済み' }).click()
    await expect(member.getByText('却下済み', { exact: true })).toBeVisible()
    await member.goto(url)
    await member.getByRole('button', { name: '内容を確認して確定' }).click()
    await expect(member.getByText('承認待ち', { exact: true })).toBeVisible()
    code = (await member.getByText(/^照合コード /).textContent())!.replace('照合コード ', '').trim()
    await member.getByRole('link', { name: '受付申請', exact: true }).last().click()
    await member.getByRole('link').filter({ hasText: '承認待ち' }).click()
    detailUrl = member.url()
    await page.goto(`/teams/${slug}/loyalty`)
    await page.getByRole('button').filter({ hasText: '来店ポイント' }).last().click()
    await page.getByLabel('本人画面の照合コード').fill(code)
    const approved = page.waitForResponse(
      (response) => response.request().method() === 'POST' && /\/approve$/.test(response.url()),
    )
    await page.getByRole('button', { name: '承認して加算', exact: true }).click()
    expect((await approved).status()).toBe(200)
    await member.goto(detailUrl)
    await expect(member.getByText('承認済み', { exact: true })).toBeVisible()
    await member.getByRole('link', { name: 'チームポイント', exact: true }).click()
    await expect(member.getByTestId('loyalty-balance')).toHaveText('1ポイント')
    await expect(member.getByText('来店ポイント · 加算 · 1')).toBeVisible()
    await noOverflow(member)
    // 非所属個人のスタッフ/settings直打ちは表示とBE拒否の両方を確認する。
    await member.goto(`/teams/${slug}/loyalty`)
    await expect(member.getByTestId('load-error-state')).toBeVisible()
    await expect(member.getByRole('button', { name: '承認して加算', exact: true })).toHaveCount(0)
    await member.goto(`/teams/${slug}/settings/loyalty`)
    await expect(member.getByTestId('load-error-state')).toBeVisible()
    await expect(member.getByRole('button', { name: '保存', exact: true })).toHaveCount(0)
    expect(team.numericId).toBeGreaterThan(0)
  } finally {
    await memberContext?.close()
    const cleanup = await page.request.delete(`${API}/api/v1/teams/${slug}`)
    expect([200, 204]).toContain(cleanup.status())
  }
})
