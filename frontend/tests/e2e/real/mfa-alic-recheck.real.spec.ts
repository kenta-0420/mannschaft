/**
 * 【隔離環境専用・手動実行】既定の CI / playwright.config.ts の対象外（chromium プロジェクトは real/ を除外、
 * CI は対象 spec を名指しで実行）。専用 config `playwright.mfa-real.config.ts` でのみ実行する。
 *
 * 前提（隔離環境。ポート: BE 8097 / FE 3011 / 偽 SES 4599 / MySQL 13400 / Valkey 16400）
 *   1. 隔離 MySQL・Valkey を docker で起動（コンテナ名は既定 mfa0910-mysql / mfa0910-valkey。
 *      MFA_MYSQL_CONTAINER / MFA_VALKEY_CONTAINER / MFA_MYSQL_PASSWORD で上書き可）
 *   2. 偽 SES を起動: node tests/e2e/real/support/fakeses.mjs   （SES エンドポイントをここへ向ける）
 *   3. BE を隔離 DB・Valkey・偽 SES で起動し、`mannschaft.auth.mfa.email-method-enabled=true` を指定（8097）
 *   4. FE を 3011 で起動（BE 8097 を指す）
 * 実行: cd frontend && BASE_URL=http://localhost:3011 API_BASE_URL=http://127.0.0.1:8097 \
 *       npx playwright test -c playwright.mfa-real.config.ts
 */
/**
 * CMP-261012-0910 アリシゼーション修正の再確認（実機）。デスクトップ(1280x720)とモバイル(390x844)の両方。
 * ALIC-1 TOTP 無効化→再設定 / ALIC-2 連打で validate 1回 / ALIC-3 モバイルの再認証エラー視認 /
 * ALIC-4 復旧の AUTH_073 / UX-1 a-e / main 取り込み回帰（メール優先ログイン→各2FA・パスキー登録ボタン）。
 * メールは fake SES 受信箱（:4599）から取り出す。BE 8097 / FE 3011 / 隔離 DB・Valkey。
 */
import { test, expect, type Page, type BrowserContext, type Browser } from '@playwright/test'
import { TOTP, NobleCryptoPlugin, ScureBase32Plugin } from 'otplib'
import { execSync } from 'child_process'
import fs from 'node:fs'
import path from 'node:path'
// 検証環境の dev サーバーは混雑時に初回表示へ 1 分近くかかるため、待ちを長めに取る（リロードはしない）
async function waitForHydration(page: Page): Promise<void> {
  await page.waitForFunction(() => {
    const el = document.querySelector('#__nuxt')
    return el !== null && '__vue_app__' in el && el.childElementCount > 0
  }, undefined, { timeout: 240_000 })
}

const FE = process.env.BASE_URL ?? 'http://localhost:3011'
const API = process.env.API_BASE_URL ?? 'http://127.0.0.1:8097'
const INBOX = process.env.MAIL_INBOX_URL ?? 'http://127.0.0.1:4599/inbox'
const EVIDENCE = process.env.EVIDENCE_DIR ?? path.resolve('test-results', 'alic-evidence')
// 隔離環境の docker コンテナ名・DB パスワード（環境に合わせて上書き可）
const MYSQL_CONTAINER = process.env.MFA_MYSQL_CONTAINER ?? 'mfa0910-mysql'
const VALKEY_CONTAINER = process.env.MFA_VALKEY_CONTAINER ?? 'mfa0910-valkey'
const MYSQL_PASSWORD = process.env.MFA_MYSQL_PASSWORD ?? 'root'
const PASSWORD = 'Passw0rd!2026a'
const STAMP = Date.now().toString(36)
fs.mkdirSync(EVIDENCE, { recursive: true })

type U = { email: string; nick: string; id?: number; secret?: string; lastTotp?: string; backup?: string[] }
type Mail = { at: string; to?: string[]; subject?: string; html?: string }

const VIEWPORTS = [
  { name: 'pc', size: { width: 1280, height: 720 }, mobile: false },
  { name: 'sp', size: { width: 390, height: 844 }, mobile: true },
]

function sql(q: string): string {
  for (let i = 0; ; i++) {
    try {
      return execSync(`wsl.exe -e docker exec ${MYSQL_CONTAINER} mysql -uroot -p${MYSQL_PASSWORD} mannschaft -N -e "${q.replace(/"/g, '\\"')}"`, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim()
    } catch (e) {
      if (i >= 2) throw e
    }
  }
}
function totpOf(secret: string) {
  return new TOTP({ secret, crypto: new NobleCryptoPlugin(), base32: new ScureBase32Plugin() })
}
async function freshTotp(u: U): Promise<string> {
  for (let i = 0; i < 70; i++) {
    const c = await totpOf(u.secret!).generate()
    if (c !== u.lastTotp) { u.lastTotp = c; return c }
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error('no fresh totp')
}
async function createUser(tag: string): Promise<U> {
  const u: U = { email: `alic-${STAMP}-${tag}@test.mannschaft.local`, nick: `alic_${STAMP}_${tag}` }
  const res = await fetch(`${API}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: u.email, password: PASSWORD, lastName: '再確認', firstName: tag, nickname: u.nick, postalCode: '100-0001', birth_date: '1990-01-01', locale: 'ja', timezone: 'Asia/Tokyo', privacyPolicyAccepted: true, privacyPolicyVersion: '1.1.1' }),
  })
  expect(res.status, `register ${tag}`).toBe(201)
  sql(`UPDATE users SET status='ACTIVE' WHERE display_name='${u.nick}'`)
  return u
}
async function waitMail(to: string, subjectRe: string, sinceMs: number, bodyPart = ''): Promise<Mail> {
  const until = Date.now() + 300_000
  while (Date.now() < until) {
    const all = (await (await fetch(INBOX)).json()) as Mail[]
    const hit = all.filter((m) => m.to?.includes(to) && new RegExp(subjectRe).test(m.subject ?? '') && (m.html ?? '').includes(bodyPart) && Date.parse(m.at) >= sinceMs).pop()
    if (hit) return hit
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error(`mail not received ${to} ${subjectRe}`)
}
const codeOf = (m: Mail) => (m.html ?? '').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').match(/(?<![\d#])(\d{6})(?!\d)/)![1]!

async function newCtx(browser: Browser, vp: (typeof VIEWPORTS)[number]): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo', viewport: vp.size, isMobile: vp.mobile, hasTouch: vp.mobile })
  // 外部 Web フォント(Google Fonts)の取得待ちで DOMContentLoaded が止まるのを避ける（アプリの API は一切横取りしない）
  await ctx.route(/fonts\.(googleapis|gstatic)\.com/, (r) => r.abort())
  return { ctx, page: await ctx.newPage() }
}
async function uiLogin(page: Page, u: U) {
  await page.goto(`${FE}/login`, { waitUntil: "domcontentloaded", timeout: 240_000 })
  await waitForHydration(page)
  await page.locator('#email').pressSequentially(u.email, { delay: 10 })
  await page.locator('input#password').pressSequentially(PASSWORD, { delay: 10 })
  await page.locator('form#login-form button[type="submit"]').click()
}
async function typeOtp(page: Page, code: string, scope?: ReturnType<Page['locator']>) {
  const inputs = (scope ?? page).locator('input.p-inputotp-input')
  for (let i = code.length - 1; i >= 0; i--) { await inputs.nth(i).click(); await page.keyboard.press('Backspace') }
  for (let i = 0; i < code.length; i++) { await inputs.nth(i).click(); await page.keyboard.press(code[i]!) }
  for (let i = 0; i < code.length; i++) await expect(inputs.nth(i)).toHaveValue(code[i]!)
}
async function logout(ctx: BrowserContext, page: Page) {
  await ctx.clearCookies()
  await page.evaluate(() => { try { localStorage.clear(); sessionStorage.clear() } catch { /* noop */ } })
}
async function readBackup(page: Page): Promise<string[]> {
  const dlg = page.getByTestId('two-fa-backup-codes-dialog')
  await expect(dlg).toBeVisible({ timeout: 20_000 })
  return (await dlg.getByTestId('two-fa-backup-code').allInnerTexts()).map((s) => s.replace(/[\s-]/g, ''))
}
async function closeBackup(page: Page) {
  await page.locator('#twoFaBackupSaved').click()
  await page.getByTestId('two-fa-backup-close').click()
  await expect(page.getByTestId('two-fa-enabled')).toBeVisible()
}

test.describe.configure({ mode: 'serial' })

for (const vp of VIEWPORTS) {
  test.describe(`ALIC 再確認 [${vp.name} ${vp.size.width}x${vp.size.height}]`, () => {
    const shot = async (page: Page, name: string) => page.screenshot({ path: path.join(EVIDENCE, `${vp.name}-${name}.png`), fullPage: false })
    let P: U, Q: U
    test.beforeAll(async () => {
      P = await createUser(`${vp.name}p`)
      Q = await createUser(`${vp.name}q`)
    })

    test(`ALIC-1/3 TOTP 有効化→無効化(エラー視認)→再設定 [${vp.name}]`, async ({ browser }) => {
      const { ctx, page } = await newCtx(browser, vp)
      const setupStatuses: number[] = []
      page.on('response', (r) => { if (/\/api\/v1\/auth\/2fa\/setup$/.test(r.url()) && r.request().method() === 'POST') setupStatuses.push(r.status()) })
      await uiLogin(page, P)
      await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 240_000, waitUntil: 'commit' })
      await page.goto(`${FE}/settings/security`, { waitUntil: "domcontentloaded", timeout: 240_000 })
      await waitForHydration(page)
      await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 30_000 })
      // 回帰: パスキー登録ボタンが設定画面に出ている
      await expect(page.getByRole('button', { name: /パスキーを登録/ })).toBeVisible()
      await shot(page, 'a1-0-security-passkey-btn')
      // 1回目の有効化
      await page.getByTestId('two-fa-setup-totp').click()
      await expect(page.getByTestId('two-fa-qr')).toBeVisible({ timeout: 20_000 })
      P.secret = (await page.getByTestId('two-fa-secret').innerText()).trim()
      await typeOtp(page, await freshTotp(P))
      await page.getByTestId('two-fa-totp-enable').click()
      P.backup = await readBackup(page)
      await closeBackup(page)
      await shot(page, 'a1-1-enabled-first')
      // 無効化（ALIC-3: 誤パスワードのエラーがダイアログ内で見える）
      await page.getByTestId('two-fa-disable').click()
      const dlg = page.getByTestId('two-fa-reauth-dialog')
      await expect(dlg).toBeVisible()
      await dlg.locator('#twoFaReauthPassword').fill('Wrong-Pass-1234')
      await typeOtp(page, await freshTotp(P), dlg)
      await page.getByTestId('two-fa-reauth-submit').click()
      const err = page.getByTestId('two-fa-reauth-error')
      await expect(err).toBeVisible({ timeout: 15_000 })
      await expect(err).toBeInViewport({ ratio: 1 })
      // エラーがフォーム上部（送信ボタンより上）にある
      const eb = (await err.boundingBox())!
      const sb = (await page.getByTestId('two-fa-reauth-submit').boundingBox())!
      expect(eb.y + eb.height, 'エラーは送信ボタンより上').toBeLessThanOrEqual(sb.y)
      console.log(`[ALIC-3 ${vp.name}] エラー文言: ${(await err.innerText()).trim()} / error.bottom=${Math.round(eb.y + eb.height)} submit.top=${Math.round(sb.y)} viewportH=${vp.size.height}`)
      await shot(page, 'a3-reauth-error')
      // 正しい入力で無効化
      await dlg.locator('#twoFaReauthPassword').fill(PASSWORD)
      await typeOtp(page, await freshTotp(P), dlg)
      await page.getByTestId('two-fa-reauth-submit').click()
      await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 20_000 })
      await shot(page, 'a1-2-disabled')
      // 再設定（ALIC-1: 500 にならず QR が出る）
      await page.getByTestId('two-fa-setup-totp').click()
      await expect(page.getByTestId('two-fa-qr')).toBeVisible({ timeout: 20_000 })
      await expect(page.getByTestId('two-fa-qr').locator('canvas, img, svg').first()).toBeVisible()
      P.secret = (await page.getByTestId('two-fa-secret').innerText()).trim()
      await expect(page.getByTestId('two-fa-error')).toHaveCount(0)
      await shot(page, 'a1-3-resetup-qr')
      await typeOtp(page, await freshTotp(P))
      await page.getByTestId('two-fa-totp-enable').click()
      P.backup = await readBackup(page)
      expect(P.backup.length).toBe(8)
      await closeBackup(page)
      await shot(page, 'a1-4-resetup-enabled')
      console.log(`[ALIC-1 ${vp.name}] /2fa/setup のステータス列: ${JSON.stringify(setupStatuses)}`)
      expect(setupStatuses.every((s) => s >= 200 && s < 300)).toBeTruthy()
      expect(setupStatuses.length).toBeGreaterThanOrEqual(2)

      // UX-1 e: 使い方モーダルにメール2FAの説明
      await page.getByRole('button', { name: '使い方' }).first().click()
      await expect(page.getByText('メールによる2段階認証').first()).toBeVisible({ timeout: 10_000 })
      await shot(page, 'ux1e-help-modal')
      await ctx.close()
    })

    test(`ALIC-2/UX-1/回帰 2fa-verify（連打1回・文言・フォーカス・TOTP/バックアップ） [${vp.name}]`, async ({ browser }) => {
      const { ctx, page } = await newCtx(browser, vp)
      // バックアップコード経路: UX-1 c フォーカス、b リンク名
      await uiLogin(page, P)
      await page.waitForURL(/2fa-verify/, { timeout: 240_000, waitUntil: 'commit' })
      await waitForHydration(page)
      await expect(page.getByRole('link', { name: 'メールでアカウントを回復する' })).toBeVisible()
      await shot(page, 'ux1b-recovery-link')
      await page.getByText('バックアップコード', { exact: true }).first().click()
      await expect(page.getByTestId('mfa-backup-input')).toBeFocused()
      await shot(page, 'ux1c-backup-focus')
      await page.getByTestId('mfa-backup-input').fill(P.backup![0]!)
      await page.getByRole('button', { name: '認証する' }).click()
      await page.waitForURL((u) => !/2fa-verify|\/login/.test(u.pathname), { timeout: 240_000, waitUntil: 'commit' })
      await shot(page, 'reg-backup-login-ok')
      // TOTP 経路 + ALIC-2 連打
      await logout(ctx, page)
      let validateCalls = 0
      page.on('request', (r) => { if (/\/api\/v1\/auth\/2fa\/validate$/.test(r.url()) && r.method() === 'POST') validateCalls++ })
      await uiLogin(page, P)
      await page.waitForURL(/2fa-verify/, { timeout: 240_000, waitUntil: 'commit' })
      await waitForHydration(page)
      await typeOtp(page, await freshTotp(P))
      await page.evaluate(() => {
        const b = Array.from(document.querySelectorAll('button[type="submit"]')).pop() as HTMLButtonElement
        b.click(); b.click(); b.click(); b.click()
      })
      await page.waitForURL((u) => !/2fa-verify|\/login/.test(u.pathname), { timeout: 240_000, waitUntil: 'commit' })
      await page.waitForTimeout(1500)
      console.log(`[ALIC-2 ${vp.name}] validate 呼び出し回数: ${validateCalls}`)
      expect(validateCalls).toBe(1)
      await shot(page, 'a2-totp-login-ok')
      await ctx.close()
    })

    test(`メール方式: 設定→メール優先ログイン→メールOTP / ALIC-3(メール) / UX-1 a,d [${vp.name}]`, async ({ browser }) => {
      const { ctx, page } = await newCtx(browser, vp)
      await uiLogin(page, Q)
      await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 240_000, waitUntil: 'commit' })
      await page.goto(`${FE}/settings/security`, { waitUntil: "domcontentloaded", timeout: 240_000 })
      await waitForHydration(page)
      await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 30_000 })
      const t0 = Date.now() - 2000
      await page.getByTestId('two-fa-setup-email').click()
      await expect(page.getByTestId('two-fa-email-setup')).toBeVisible({ timeout: 20_000 })
      await typeOtp(page, codeOf(await waitMail(Q.email, '設定の確認コード', t0)))
      await page.getByTestId('two-fa-email-enable').click()
      Q.backup = await readBackup(page)
      await closeBackup(page)
      await logout(ctx, page)
      await uiLogin(page, Q)
      await page.waitForURL(/2fa-verify/, { timeout: 240_000, waitUntil: 'commit' })
      await waitForHydration(page)
      // UX-1 a: 送信前の案内文
      await expect(page.getByText('「認証コードをメールで送る」を押すと、登録メールアドレスに6桁のコードが届きます')).toBeVisible()
      await shot(page, 'ux1a-email-unsent-subtitle')
      const t1 = Date.now() - 2000
      await page.getByTestId('mfa-email-send').click()
      await expect(page.getByTestId('mfa-email-resend-wait')).toBeVisible({ timeout: 20_000 })
      await typeOtp(page, codeOf(await waitMail(Q.email, 'ログインの確認コード', t1)))
      await page.getByRole('button', { name: '認証する' }).click()
      await page.waitForURL((u) => !/2fa-verify|\/login/.test(u.pathname), { timeout: 240_000, waitUntil: 'commit' })
      await shot(page, 'reg-email-login-ok')
      // UX-1 d: 設定検索「バックアップ」→セキュリティ
      await page.goto(`${FE}/settings`, { waitUntil: "domcontentloaded", timeout: 240_000 })
      await waitForHydration(page)
      await page.getByPlaceholder(/検索/).first().fill('バックアップ')
      await expect(page.getByText('セキュリティ').first()).toBeVisible({ timeout: 10_000 })
      await shot(page, 'ux1d-settings-search')
      // ALIC-3 メール方式の無効化ダイアログ（誤パスワード）
      await page.goto(`${FE}/settings/security`, { waitUntil: "domcontentloaded", timeout: 240_000 })
      await waitForHydration(page)
      await expect(page.getByTestId('two-fa-enabled')).toBeVisible({ timeout: 30_000 })
      await page.getByTestId('two-fa-disable').click()
      const dlg = page.getByTestId('two-fa-reauth-dialog')
      await dlg.locator('#twoFaReauthPassword').fill('Wrong-Pass-1234')
      const t2 = Date.now() - 2000
      await dlg.getByTestId('two-fa-reauth-send-email').click()
      await typeOtp(page, codeOf(await waitMail(Q.email, '本人確認の確認コード', t2)), dlg)
      await page.getByTestId('two-fa-reauth-submit').click()
      const err = page.getByTestId('two-fa-reauth-error')
      await expect(err).toBeVisible({ timeout: 15_000 })
      await expect(err).toBeInViewport({ ratio: 1 })
      await shot(page, 'a3-email-reauth-error')
      await ctx.close()
    })

    test(`ALIC-1b 未確定のセットアップを破棄して再開始（本来の再現経路） [${vp.name}]`, async ({ browser }) => {
      const U = await createUser(`${vp.name}s`)
      const { ctx, page } = await newCtx(browser, vp)
      const statuses: number[] = []
      page.on('response', (r) => { if (/\/api\/v1\/auth\/2fa\/setup$/.test(r.url()) && r.request().method() === 'POST') statuses.push(r.status()) })
      await uiLogin(page, U)
      await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 240_000, waitUntil: 'commit' })
      await page.goto(`${FE}/settings/security`, { waitUntil: 'domcontentloaded', timeout: 240_000 })
      await waitForHydration(page)
      await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 60_000 })
      for (let round = 1; round <= 3; round++) {
        await page.getByTestId('two-fa-setup-totp').click()
        await expect(page.getByTestId('two-fa-qr')).toBeVisible({ timeout: 30_000 })
        await expect(page.getByTestId('two-fa-error')).toHaveCount(0)
        if (round < 3) await page.getByTestId('two-fa-totp-cancel').click()
        await expect(round < 3 ? page.getByTestId('two-fa-disabled') : page.getByTestId('two-fa-totp-setup')).toBeVisible()
      }
      await shot(page, 'a1b-resetup-third-qr')
      U.secret = (await page.getByTestId('two-fa-secret').innerText()).trim()
      await typeOtp(page, await freshTotp(U))
      await page.getByTestId('two-fa-totp-enable').click()
      await readBackup(page)
      await closeBackup(page)
      await shot(page, 'a1b-enabled')
      console.log(`[ALIC-1b ${vp.name}] /2fa/setup ステータス列: ${JSON.stringify(statuses)}`)
      expect(statuses.length).toBe(3)
      expect(statuses.every((s) => s >= 200 && s < 300)).toBeTruthy()
      await ctx.close()
    })

    test(`ALIC-4 復旧: 不正/期限切れ session のエラー文言 [${vp.name}]`, async ({ browser }) => {
      const { ctx, page } = await newCtx(browser, vp)
      await page.goto(`${FE}/2fa-recovery?session=bogus-session-token`, { waitUntil: "domcontentloaded", timeout: 240_000 })
      await waitForHydration(page)
      await page.getByRole('button', { name: 'リカバリーメールを送信' }).click()
      const msg = page.getByTestId('mfa-recovery-session-expired')
      await expect(msg).toBeVisible({ timeout: 20_000 })
      console.log(`[ALIC-4 ${vp.name}] 画面文言: ${(await msg.innerText()).replace(/\s+/g, ' ')} | トースト: ${(await page.getByText('認証の有効期限が切れました').first().innerText().catch(() => '(なし)')).trim()}`)
      await shot(page, 'a4-recovery-session-expired')
      await ctx.close()
    })
  })
}
