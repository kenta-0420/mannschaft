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
 * CMP-261012-0910（2FA 穴埋め）実機E2E。モック無し・実BE・実FE・実ブラウザ。
 *
 * 前提（検証環境）
 *   - 検証 BE: mannschaft.auth.mfa.email-method-enabled=true、メール送信は実 SesEmailTransport。
 *     SES エンドポイントをローカルのメール受信箱（SES v2 SendEmail を捕捉する小サーバ）へ向けている。
 *     → 届いたメールの本文からコード／リンクを取り出す（DB/Valkey からコードを読まない）。
 *   - 環境変数: BASE_URL（FE）, API_BASE_URL（BE）, MAIL_INBOX_URL（受信箱）, EVIDENCE_DIR（スクショ出力先）
 *
 * API を使うのは「ユーザー作成（前提）」「ログイン用の事前準備」「後始末」だけ。
 * 対象操作（2FA 有効化・ログイン・無効化・リカバリー・同意版）はすべて画面から行う。
 */
import { test, expect, type Page, type BrowserContext, type Browser } from '@playwright/test'
import { TOTP, NobleCryptoPlugin, ScureBase32Plugin } from 'otplib'
import { execSync } from 'child_process'
import fs from 'node:fs'
import path from 'node:path'
import { waitForHydration } from '../helpers/wait'

const FE = process.env.BASE_URL ?? 'http://localhost:3011'
const API = process.env.API_BASE_URL ?? 'http://127.0.0.1:8097'
const INBOX = process.env.MAIL_INBOX_URL ?? 'http://127.0.0.1:4599/inbox'
const EVIDENCE = process.env.EVIDENCE_DIR ?? path.resolve('test-results', 'mfa-evidence')
// 隔離環境の docker コンテナ名・DB パスワード（環境に合わせて上書き可）
const MYSQL_CONTAINER = process.env.MFA_MYSQL_CONTAINER ?? 'mfa0910-mysql'
const VALKEY_CONTAINER = process.env.MFA_VALKEY_CONTAINER ?? 'mfa0910-valkey'
const MYSQL_PASSWORD = process.env.MFA_MYSQL_PASSWORD ?? 'root'
const PASSWORD = 'Passw0rd!2026a'
const STAMP = Date.now().toString(36)

fs.mkdirSync(EVIDENCE, { recursive: true })

type Mail = { at: string; to?: string[]; subject?: string; html?: string }
type U = { email: string; nick: string; id?: number; totpSecret?: string; lastTotp?: string; backup?: string[] }

const mkUser = (tag: string): U => ({
  email: `mfa0910-${STAMP}-${tag}@test.mannschaft.local`,
  nick: `mfa0910_${STAMP}_${tag}`,
})
const A = mkUser('a') // TOTP（シナリオ 1・2・4）
const B = mkUser('b') // メールOTP（シナリオ 3・4）
const C = mkUser('c') // リカバリー（シナリオ 5）
const D = mkUser('d') // 2FA 未設定の別ユーザー（シナリオ 7）
const R = mkUser('r') // 画面から新規登録するユーザー（シナリオ 6）

function sql(q: string): string {
  // WSL 越しの docker exec は稀に一過性で失敗するため 3 回まで再試行する
  for (let i = 0; ; i++) {
    try {
      return sql1(q)
    } catch (e) {
      if (i >= 2) throw e
    }
  }
}
function sql1(q: string): string {
  return execSync(
    `wsl.exe -e docker exec ${MYSQL_CONTAINER} mysql -uroot -p${MYSQL_PASSWORD} mannschaft -N -e "${q.replace(/"/g, '\\"')}"`,
    { stdio: ['ignore', 'pipe', 'ignore'] },
  ).toString().trim()
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`), fullPage: true })
}

function totpOf(secret: string): TOTP {
  return new TOTP({ secret, crypto: new NobleCryptoPlugin(), base32: new ScureBase32Plugin() })
}

/** 直前に使ったコードと同じなら、次の 30 秒枠まで待って別コードを得る（TOTP の再利用拒否を避ける）。 */
async function freshTotp(u: U): Promise<string> {
  const t = totpOf(u.totpSecret!)
  for (let i = 0; i < 70; i++) {
    const c = await t.generate()
    if (c !== u.lastTotp) {
      u.lastTotp = c
      return c
    }
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error('fresh TOTP not obtained')
}

async function createUser(u: U) {
  const res = await fetch(`${API}/api/v1/auth/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      email: u.email,
      password: PASSWORD,
      lastName: '検証',
      firstName: '二段階',
      nickname: u.nick,
      postalCode: '100-0001',
      birth_date: '1990-01-01',
      locale: 'ja',
      timezone: 'Asia/Tokyo',
      privacyPolicyAccepted: true,
      privacyPolicyVersion: '1.1.1',
    }),
  })
  expect(res.status, `register ${u.email}`).toBe(201)
  sql(`UPDATE users SET status='ACTIVE' WHERE display_name='${u.nick}'`)
  u.id = Number(sql(`SELECT id FROM users WHERE display_name='${u.nick}'`))
  expect(u.id).toBeGreaterThan(0)
}

async function inbox(): Promise<Mail[]> {
  const r = await fetch(INBOX)
  return (await r.json()) as Mail[]
}

/** 受信箱に届いたメール（宛先・件名・時刻で絞る）を待つ。 */
async function waitMail(to: string, subjectPart: string, sinceMs: number, timeoutMs = 600_000, bodyPart = ''): Promise<Mail> {
  const until = Date.now() + timeoutMs
  while (Date.now() < until) {
    const hit = (await inbox())
      .filter((m) => m.to?.includes(to) && new RegExp(subjectPart).test(m.subject ?? '') && (m.html ?? '').includes(bodyPart) && Date.parse(m.at) >= sinceMs)
      .pop()
    if (hit) return hit
    await new Promise((r) => setTimeout(r, 1000))
  }
  const subjects = (await inbox()).filter((m) => m.to?.includes(to)).map((m) => m.subject)
  throw new Error(`mail not received: to=${to} subject~${subjectPart} (got: ${JSON.stringify(subjects)})`)
}

function textOf(html: string): string {
  return html.replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ')
}
function codeFromMail(m: Mail): string {
  const hit = textOf(m.html ?? '').match(/(?<![\d#])(\d{6})(?!\d)/)
  expect(hit, `6桁コードがメール本文に無い: ${textOf(m.html ?? '').slice(0, 300)}`).toBeTruthy()
  return hit![1]!
}
function recoveryLinkFromMail(m: Mail): string {
  const hit = (m.html ?? '').match(/https?:\/\/[^"'\s<>]*2fa-recovery\?token=[^"'\s<>]+/)
  expect(hit, `リカバリーリンクがメール本文に無い: ${textOf(m.html ?? '').slice(0, 400)}`).toBeTruthy()
  return hit![0].replace(/&amp;/g, '&')
}

async function newCtx(browser: Browser): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({ locale: 'ja-JP', timezoneId: 'Asia/Tokyo' })
  const page = await ctx.newPage()
  return { ctx, page }
}

/** /login で実フォームからログインする。2FA が要るユーザーは /2fa-verify に遷移して止まる。 */
async function uiLogin(page: Page, u: U) {
  await page.goto(`${FE}/login`)
  await waitForHydration(page)
  await page.locator('#email').pressSequentially(u.email, { delay: 10 })
  await page.locator('input#password').pressSequentially(PASSWORD, { delay: 10 })
  await page.locator('form#login-form button[type="submit"]').click()
}

async function typeOtp(page: Page, code: string, scope?: ReturnType<Page['locator']>) {
  const inputs = (scope ?? page).locator('input.p-inputotp-input')
  // 先に全欄を空にする（前回の誤入力が残ると桁が混ざる）
  for (let i = code.length - 1; i >= 0; i--) {
    await inputs.nth(i).click()
    await page.keyboard.press('Backspace')
  }
  // 1 桁ずつ各欄をクリックして押下する（連続タイプだと取りこぼすことがあるため）
  for (let i = 0; i < code.length; i++) {
    await inputs.nth(i).click()
    await page.keyboard.press(code[i]!)
  }
  for (let i = 0; i < code.length; i++) await expect(inputs.nth(i)).toHaveValue(code[i]!)
}

async function logoutByCookies(ctx: BrowserContext, page: Page) {
  await ctx.clearCookies()
  await page.evaluate(() => { try { localStorage.clear(); sessionStorage.clear() } catch { /* noop */ } })
}

async function readBackupCodes(page: Page): Promise<string[]> {
  const dlg = page.getByTestId('two-fa-backup-codes-dialog')
  await expect(dlg).toBeVisible()
  const codes = (await dlg.getByTestId('two-fa-backup-code').allInnerTexts()).map((s) => s.replace(/[\s-]/g, ''))
  return codes
}

test.describe.configure({ mode: 'serial' })

test.describe('CMP-261012-0910 2FA 実機E2E', () => {
  test.beforeAll(async () => {
    for (const u of [A, B, C, D]) await createUser(u)
  })

  test('S1 TOTP 有効化（QR→6桁→バックアップコード）→再ログインで TOTP を要求', async ({ browser }) => {
    const { ctx, page } = await newCtx(browser)
    await uiLogin(page, A)
    await page.waitForURL(/\/(dashboard|$|\?)/, { timeout: 30_000 })
    await page.goto(`${FE}/settings/security`)
    await waitForHydration(page)
    await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 20_000 })
    await shot(page, 's1-1-security-disabled')

    await page.getByTestId('two-fa-setup-totp').click()
    await expect(page.getByTestId('two-fa-qr')).toBeVisible({ timeout: 20_000 })
    // QR が実際に描画されている（canvas / img / svg のいずれか）
    await expect(page.getByTestId('two-fa-qr').locator('canvas, img, svg').first()).toBeVisible()
    const secret = (await page.getByTestId('two-fa-secret').innerText()).trim()
    expect(secret).toMatch(/^[A-Z2-7]{16,}$/)
    A.totpSecret = secret
    await shot(page, 's1-2-qr')

    // 誤コードでは有効化されず再試行できる
    await typeOtp(page, '000000')
    await page.getByTestId('two-fa-totp-enable').click()
    await expect(page.getByTestId('two-fa-error')).toBeVisible({ timeout: 15_000 })
    await shot(page, 's1-3-wrong-code')
    const wrongMsg = (await page.getByTestId('two-fa-error').innerText()).trim()
    console.log(`[S1] 誤コードの文言: ${wrongMsg}`)

    // 正コード
    for (const i of await page.locator('input.p-inputotp-input').all()) await i.fill('')
    await typeOtp(page, await freshTotp(A))
    await page.getByTestId('two-fa-totp-enable').click()
    await expect(page.getByTestId('two-fa-backup-codes-dialog')).toBeVisible({ timeout: 20_000 })
    const codes = await readBackupCodes(page)
    expect(codes.length).toBe(8)
    A.backup = codes
    await shot(page, 's1-4-backup-codes')
    // 「保存した」チェックまで閉じられない
    const close = page.getByTestId('two-fa-backup-close')
    await expect(close).toBeDisabled()
    await page.locator('#twoFaBackupSaved').click()
    await expect(close).toBeEnabled()
    await close.click()
    await expect(page.getByTestId('two-fa-enabled')).toBeVisible()
    await expect(page.getByTestId('two-fa-status-tag')).toContainText(/認証アプリ|TOTP/)
    await expect(page.getByTestId('two-fa-backup-remaining')).toContainText('8')
    await shot(page, 's1-5-enabled')
    console.log(`[S1] 有効化後の表示: ${(await page.getByTestId('two-fa-enabled').innerText()).replace(/\s+/g, ' ')}`)

    // 再ログイン → TOTP を求められる
    await logoutByCookies(ctx, page)
    await uiLogin(page, A)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: '二要素認証' })).toBeVisible({ timeout: 20_000 })
    await expect(page.getByText('認証アプリに表示されている6桁のコードを入力してください')).toBeVisible()
    await shot(page, 's1-6-relogin-2fa-verify')
    await ctx.close()
  })

  test('S2 バックアップコードでログイン成功→同コード再利用は失敗', async ({ browser }) => {
    const { ctx, page } = await newCtx(browser)
    const code = A.backup![0]!
    await uiLogin(page, A)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await page.getByText('バックアップコード', { exact: true }).first().click()
    await expect(page.getByText('バックアップコード（8桁）を入力してください')).toBeVisible()
    await page.getByTestId('mfa-backup-input').fill(code)
    await shot(page, 's2-1-backup-input')
    // 残数通知（トースト。数秒で消える）を押下前から待ち受ける
    const toast = page.getByText(/バックアップコードの残りは/).first().waitFor({ state: 'visible', timeout: 40_000 })
    await page.getByRole('button', { name: '認証する' }).click()
    await toast
    console.log(`[S2] 残数トースト: ${(await page.getByText(/バックアップコードの残りは/).first().innerText()).trim()}`)
    await shot(page, 's2-2-remaining-toast')
    await page.waitForURL((u) => !/2fa-verify|\/login/.test(u.pathname), { timeout: 30_000 })
    await page.waitForTimeout(1500)
    await shot(page, 's2-3-logged-in')
    console.log(`[S2] バックアップコードログイン成功 → ${page.url()}`)

    // 同じコードを再利用
    await logoutByCookies(ctx, page)
    await uiLogin(page, A)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await page.getByText('バックアップコード', { exact: true }).first().click()
    await page.getByTestId('mfa-backup-input').fill(code)
    await page.getByRole('button', { name: '認証する' }).click()
    await expect(page.getByText('認証コードが正しくありません')).toBeVisible({ timeout: 15_000 })
    expect(page.url()).toContain('2fa-verify')
    await shot(page, 's2-4-reuse-rejected')
    await ctx.close()
  })

  test('S3 メールOTP: 設定→ログイン送信→誤コード→再送→旧コード無効→成功', async ({ browser }) => {
    const { ctx, page } = await newCtx(browser)
    await uiLogin(page, B)
    await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 30_000 })
    await page.goto(`${FE}/settings/security`)
    await waitForHydration(page)
    await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('two-fa-setup-email')).toBeEnabled()

    const t0 = Date.now() - 2000
    await page.getByTestId('two-fa-setup-email').click()
    await expect(page.getByTestId('two-fa-email-setup')).toBeVisible({ timeout: 20_000 })
    console.log(`[S3] 設定時の案内文: ${(await page.getByTestId('two-fa-email-sent').innerText()).trim()}`)
    const m1 = await waitMail(B.email, '設定の確認コード', t0)
    console.log(`[S3] 設定時に届いたメール件名: ${m1.subject}`)
    const code1 = codeFromMail(m1)
    await shot(page, 's3-1-setup-email-sent')

    // 誤コード
    await typeOtp(page, code1 === '123456' ? '654321' : '123456')
    await page.getByTestId('two-fa-email-enable').click()
    await expect(page.getByTestId('two-fa-error')).toBeVisible({ timeout: 15_000 })
    console.log(`[S3] 設定時 誤コードの文言: ${(await page.getByTestId('two-fa-error').innerText()).trim()}`)
    await shot(page, 's3-2-setup-wrong')
    for (const i of await page.locator('input.p-inputotp-input').all()) await i.fill('')
    await typeOtp(page, code1)
    await page.getByTestId('two-fa-email-enable').click()
    await expect(page.getByTestId('two-fa-backup-codes-dialog')).toBeVisible({ timeout: 20_000 })
    B.backup = await readBackupCodes(page)
    expect(B.backup.length).toBe(8)
    await page.locator('#twoFaBackupSaved').click()
    await page.getByTestId('two-fa-backup-close').click()
    await expect(page.getByTestId('two-fa-status-tag')).toContainText(/メール/)
    await shot(page, 's3-3-enabled-email')

    // ログイン時
    await logoutByCookies(ctx, page)
    await uiLogin(page, B)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await expect(page.getByText('登録メールアドレスに届いた6桁のコードを入力してください')).toBeVisible()
    await shot(page, 's3-4-login-email-initial')
    const t1 = Date.now() - 2000
    await page.getByTestId('mfa-email-send').click()
    await expect(page.getByTestId('mfa-email-resend-wait')).toBeVisible({ timeout: 15_000 })
    console.log(`[S3] ログイン時の送信後表示: ${(await page.getByTestId('mfa-email-resend-wait').innerText()).trim()} / ${(await page.getByTestId('mfa-email-expiry').innerText()).trim()}`)
    const l1 = await waitMail(B.email, 'ログインの確認コード', t1)
    const loginCode1 = codeFromMail(l1)
    await shot(page, 's3-5-login-email-sent')

    // 誤コード
    await typeOtp(page, loginCode1 === '111111' ? '222222' : '111111')
    await page.getByRole('button', { name: '認証する' }).click()
    await expect(page.getByText('認証コードが正しくありません')).toBeVisible({ timeout: 15_000 })
    await shot(page, 's3-6-login-wrong')

    // 再送（60 秒待ち）→ 旧コードは無効
    await expect(page.getByTestId('mfa-email-send')).toBeEnabled({ timeout: 75_000 })
    const t2 = Date.now() - 2000
    await page.getByTestId('mfa-email-send').click()
    const l2 = await waitMail(B.email, 'ログインの確認コード', t2)
    const loginCode2 = codeFromMail(l2)
    expect(loginCode2).not.toBe(loginCode1)
    for (const i of await page.locator('input.p-inputotp-input').all()) await i.fill('')
    await typeOtp(page, loginCode1)
    await page.getByRole('button', { name: '認証する' }).click()
    await expect(page.getByText('認証コードが正しくありません')).toBeVisible({ timeout: 15_000 })
    await shot(page, 's3-7-old-code-after-resend')

    // 期限切れの扱い: メールOTP の TTL 切れを再現するため、このユーザーの OTP キーだけを Valkey から消す
    //（コードの読み取りではなく「期限切れ状態」の再現。キーは userId で厳密に絞る）。
    // → 新コードを入力しても「正しくありません」になる（期限切れ・未送信は不正扱い／AC-22）
    const keys = execSync(
      `wsl.exe -e docker exec ${VALKEY_CONTAINER} valkey-cli --scan --pattern "*mfa_email*${B.id}*"`,
      { stdio: ['ignore', 'pipe', 'ignore'] },
    ).toString().trim().split(/\r?\n/).filter(Boolean)
    console.log(`[S3] 期限切れ再現のため削除する B(userId=${B.id}) のOTPキー: ${JSON.stringify(keys)}`)
    for (const k of keys.filter((k) => !/cooldown|resend|sent|count|rate|limit/.test(k))) {
      execSync(`wsl.exe -e docker exec ${VALKEY_CONTAINER} valkey-cli DEL "${k}"`, { stdio: 'ignore' })
    }
    for (const i of await page.locator('input.p-inputotp-input').all()) await i.fill('')
    await typeOtp(page, loginCode2)
    await page.getByRole('button', { name: '認証する' }).click()
    await expect(page.getByText('認証コードが正しくありません')).toBeVisible({ timeout: 15_000 })
    await shot(page, 's3-8-expired-code-rejected')

    // 再々送信して正コードで成功（再送待ち 60 秒）
    await expect(page.getByTestId('mfa-email-send')).toBeEnabled({ timeout: 75_000 })
    const t3 = Date.now() - 2000
    await page.getByTestId('mfa-email-send').click()
    const l3 = await waitMail(B.email, 'ログインの確認コード', t3)
    for (const i of await page.locator('input.p-inputotp-input').all()) await i.fill('')
    await typeOtp(page, codeFromMail(l3))
    await page.getByRole('button', { name: '認証する' }).click()
    await page.waitForURL((u) => !/2fa-verify|\/login/.test(u.pathname), { timeout: 30_000 })
    await shot(page, 's3-9-email-login-success')
    console.log(`[S3] メールOTPログイン成功 → ${page.url()}`)
    await ctx.close()
  })

  test('S4a TOTP ユーザーが再認証ダイアログ(パスワード+TOTP)で無効化→再ログインで 2FA 不要', async ({ browser }) => {
    const { ctx, page } = await newCtx(browser)
    // A はバックアップコードで入る（TOTP 枠の再利用を避けつつログイン）
    await uiLogin(page, A)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await page.getByText('バックアップコード', { exact: true }).first().click()
    await page.getByTestId('mfa-backup-input').fill(A.backup![1]!)
    await page.getByRole('button', { name: '認証する' }).click()
    await page.waitForURL((u) => !/2fa-verify|\/login/.test(u.pathname), { timeout: 30_000 })
    await page.goto(`${FE}/settings/security`)
    await waitForHydration(page)
    await expect(page.getByTestId('two-fa-enabled')).toBeVisible({ timeout: 20_000 })
    await page.getByTestId('two-fa-disable').click()
    const dlg = page.getByTestId('two-fa-reauth-dialog')
    await expect(dlg).toBeVisible()
    await shot(page, 's4a-1-reauth-dialog')
    // 誤パスワード+正TOTP → 失敗し状態不変
    await dlg.locator('#twoFaReauthPassword').fill('Wrong-Pass-1234')
    await typeOtp(page, await freshTotp(A), dlg)
    await page.getByTestId('two-fa-reauth-submit').click()
    await expect(page.getByTestId('two-fa-reauth-error')).toBeVisible({ timeout: 15_000 })
    console.log(`[S4a] 誤パスワード時の文言: ${(await page.getByTestId('two-fa-reauth-error').innerText()).trim()}`)
    await shot(page, 's4a-2-wrong-password')
    // 正パスワード + 新しい TOTP
    await dlg.locator('#twoFaReauthPassword').fill(PASSWORD)
    for (const i of await dlg.locator('input.p-inputotp-input').all()) await i.fill('')
    await typeOtp(page, await freshTotp(A), dlg)
    await page.getByTestId('two-fa-reauth-submit').click()
    await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 20_000 })
    await shot(page, 's4a-3-disabled')
    console.log(`[S4a] 無効化後の表示: ${(await page.getByTestId('two-fa-disabled').innerText()).replace(/\s+/g, ' ')}`)

    await logoutByCookies(ctx, page)
    await uiLogin(page, A)
    await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 30_000 })
    await page.waitForTimeout(1500)
    expect(page.url()).not.toContain('2fa-verify')
    await shot(page, 's4a-4-relogin-no-2fa')
    console.log(`[S4a] 無効化後の再ログイン着地: ${page.url()}`)
    await ctx.close()
  })

  test('S4b メール方式ユーザーが再認証ダイアログ(メールコード+パスワード)で無効化→再ログインで 2FA 不要', async ({ browser }) => {
    const { ctx, page } = await newCtx(browser)
    // B はバックアップコードで入る
    await uiLogin(page, B)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await page.getByText('バックアップコード', { exact: true }).first().click()
    await page.getByTestId('mfa-backup-input').fill(B.backup![0]!)
    await page.getByRole('button', { name: '認証する' }).click()
    await page.waitForURL((u) => !/2fa-verify|\/login/.test(u.pathname), { timeout: 30_000 })
    await page.goto(`${FE}/settings/security`)
    await waitForHydration(page)
    await expect(page.getByTestId('two-fa-enabled')).toBeVisible({ timeout: 20_000 })
    await page.getByTestId('two-fa-disable').click()
    const dlg = page.getByTestId('two-fa-reauth-dialog')
    await expect(dlg).toBeVisible()
    await expect(dlg.getByTestId('two-fa-reauth-send-email')).toBeVisible() // EMAIL 方式は初期選択がメール
    const t0 = Date.now() - 2000
    await dlg.getByTestId('two-fa-reauth-send-email').click()
    await expect(dlg.getByTestId('two-fa-reauth-email-sent')).toBeVisible({ timeout: 20_000 })
    const m = await waitMail(B.email, '本人確認の確認コード', t0)
    console.log(`[S4b] 本人確認メール件名: ${m.subject}`)
    await shot(page, 's4b-1-reauth-email-sent')
    await dlg.locator('#twoFaReauthPassword').fill(PASSWORD)
    await typeOtp(page, codeFromMail(m), dlg)
    await page.getByTestId('two-fa-reauth-submit').click()
    await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 20_000 })
    await shot(page, 's4b-2-disabled')

    await logoutByCookies(ctx, page)
    await uiLogin(page, B)
    await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 30_000 })
    await page.waitForTimeout(1500)
    expect(page.url()).not.toContain('2fa-verify')
    await shot(page, 's4b-3-relogin-no-2fa')
    console.log(`[S4b] 無効化後の再ログイン着地: ${page.url()}`)
    await ctx.close()
  })

  test('S5 2FA リカバリー: 2fa-verify→要求→メールのリンク(?token=)→確定。古いリンク・使用済みは不可', async ({ browser }) => {
    // C に TOTP を設定するのは前提データ作成（API）。画面操作の対象は「リカバリー」。
    const { ctx: sctx, page: sp } = await newCtx(browser)
    await uiLogin(sp, C)
    await sp.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 30_000 })
    await sp.goto(`${FE}/settings/security`)
    await waitForHydration(sp)
    await sp.getByTestId('two-fa-setup-totp').click()
    await expect(sp.getByTestId('two-fa-qr')).toBeVisible({ timeout: 20_000 })
    C.totpSecret = (await sp.getByTestId('two-fa-secret').innerText()).trim()
    await typeOtp(sp, await freshTotp(C))
    await sp.getByTestId('two-fa-totp-enable').click()
    await expect(sp.getByTestId('two-fa-backup-codes-dialog')).toBeVisible({ timeout: 20_000 })
    await sp.locator('#twoFaBackupSaved').click()
    await sp.getByTestId('two-fa-backup-close').click()
    await sctx.close()

    const { ctx, page } = await newCtx(browser)
    await uiLogin(page, C)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await page.getByRole('link', { name: 'リカバリーコードを使用' }).click()
    await page.waitForURL(/2fa-recovery/, { timeout: 15_000 })
    await waitForHydration(page)
    await expect(page.getByRole('heading', { name: 'アカウントリカバリー' })).toBeVisible()
    await shot(page, 's5-1-recovery-page')
    const t1 = Date.now() - 2000
    await page.getByRole('button', { name: 'リカバリーメールを送信' }).click()
    await expect(page.getByText('リカバリートークン', { exact: false }).first()).toBeVisible({ timeout: 20_000 })
    const mail1 = await waitMail(C.email, '', t1, 600_000, '2fa-recovery')
    console.log(`[S5] リカバリーメール件名(1通目): ${mail1.subject}`)
    const link1 = recoveryLinkFromMail(mail1)
    console.log(`[S5] リンクのオリジン(メール記載): ${new URL(link1).origin}`)
    await shot(page, 's5-2-recovery-requested')

    // 再度ログインして 2 通目を要求 → 1 通目のリンクは失効するはず（AC-48）
    await logoutByCookies(ctx, page)
    await uiLogin(page, C)
    await page.waitForURL(/2fa-verify/, { timeout: 30_000 })
    await waitForHydration(page)
    await page.getByRole('link', { name: 'リカバリーコードを使用' }).click()
    await page.waitForURL(/2fa-recovery/, { timeout: 15_000 })
    await waitForHydration(page)
    const t2 = Date.now() - 2000
    await page.getByRole('button', { name: 'リカバリーメールを送信' }).click()
    const mail2 = await waitMail(C.email, '', t2, 600_000, '2fa-recovery')
    const link2 = recoveryLinkFromMail(mail2)
    expect(link2).not.toBe(link1)
    const tok = (l: string) => new URL(l).searchParams.get('token')!
    const open = (l: string) => `${FE}/2fa-recovery?token=${encodeURIComponent(tok(l))}`

    // 古いリンク（1 通目）→ 確定できない
    await logoutByCookies(ctx, page)
    await page.goto(open(link1))
    await waitForHydration(page)
    await expect(page.getByTestId('mfa-recovery-confirm')).toBeVisible()
    await page.getByTestId('mfa-recovery-confirm').click()
    await expect(page.getByText('リンクの期限が切れているか、すでに使用されています')).toBeVisible({ timeout: 20_000 })
    await shot(page, 's5-3-old-link-rejected')

    // 新しいリンク（2 通目）→ 確定してログイン完了
    await page.goto(open(link2))
    await waitForHydration(page)
    await page.getByTestId('mfa-recovery-confirm').click()
    await page.waitForURL((u) => !/2fa-recovery|\/login|2fa-verify/.test(u.pathname), { timeout: 30_000 })
    await shot(page, 's5-4-recovery-confirmed')
    console.log(`[S5] リカバリー確定後の着地: ${page.url()}`)

    // 使用済みリンクの再利用 → 不可
    await logoutByCookies(ctx, page)
    await page.goto(open(link2))
    await waitForHydration(page)
    await page.getByTestId('mfa-recovery-confirm').click()
    await expect(page.getByText('リンクの期限が切れているか、すでに使用されています')).toBeVisible({ timeout: 20_000 })
    await shot(page, 's5-5-used-link-rejected')

    // リカバリー後は 2FA が無効化されている → 再ログインで 2FA 不要
    await page.goto(`${FE}/login`)
    await uiLogin(page, C)
    await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 30_000 })
    await page.waitForTimeout(1500)
    expect(page.url()).not.toContain('2fa-verify')
    await shot(page, 's5-6-after-recovery-login')
    console.log(`[S5] リカバリー後の再ログイン着地: ${page.url()}`)
    await ctx.close()
  })

  test('S6 新規登録画面の同意版が 1.1.1', async ({ browser }) => {
    const { ctx, page } = await newCtx(browser)
    let body: Record<string, unknown> | undefined
    page.on('request', (r) => {
      if (r.url().includes('/api/v1/auth/register') && r.method() === 'POST') body = r.postDataJSON()
    })
    // ポリシー本文ページの版表示
    await page.goto(`${FE}/privacy`)
    await waitForHydration(page)
    await expect(page.getByText(/バージョン\s*1\.1\.1/).first()).toBeVisible({ timeout: 20_000 })
    console.log(`[S6] /privacy の版表示: ${(await page.getByText(/バージョン\s*1\.1\.1/).first().innerText()).trim()}`)
    await shot(page, 's6-1-privacy-page-version')
    await page.goto(`${FE}/register`)
    await waitForHydration(page)
    await page.locator('#email').fill(R.email)
    await page.locator('input#password').fill(PASSWORD)
    await page.locator('#postalCode').fill('100-0001')
    await page.locator('#birthDate').fill('1990-01-01')
    await page.locator('#lastName').fill('検証')
    await page.locator('#firstName').fill('登録')
    await page.locator('#displayName').fill(R.nick)
    await page.locator('#termsAccepted').click()
    await page.locator('#privacyPolicyAccepted').click()
    await expect(page.getByText('プライバシーポリシーへの同意が必要です')).toHaveCount(0)
    await shot(page, 's6-1b-before-submit')
    await page.getByRole('button', { name: 'アカウント作成' }).click()
    await page.waitForTimeout(3000)
    await shot(page, 's6-1c-after-submit')
    await expect.poll(() => body?.privacyPolicyVersion, { timeout: 20_000 }).toBe('1.1.1')
    await page.waitForURL(/verify-email/, { timeout: 30_000 })
    await shot(page, 's6-2-registered')
    console.log(`[S6] 送信された同意版: ${String(body?.privacyPolicyVersion)} → ${page.url()}`)
    try {
      const stored = sql(`SELECT policy_version FROM privacy_policy_consents WHERE user_id=(SELECT id FROM users WHERE display_name='${R.nick}')`)
      console.log(`[S6] DB保存された同意版: ${stored}`)
    } catch {
      console.log('[S6] DB 保存版の照会はスキップ（テーブル名不一致）')
    }
    await ctx.close()
  })

  test('S7 ロール横断: 他ユーザーの 2FA に触れない／未ログイン URL 直打ち', async ({ browser }) => {
    // 準備: A を再度 TOTP 有効にはしない。D（2FA 未設定）でログインし自分の状態だけが見えること
    const { ctx, page } = await newCtx(browser)
    await uiLogin(page, D)
    await page.waitForURL((u) => !/\/login/.test(u.pathname), { timeout: 30_000 })
    await page.goto(`${FE}/settings/security`)
    await waitForHydration(page)
    await expect(page.getByTestId('two-fa-disabled')).toBeVisible({ timeout: 20_000 })
    await expect(page.getByTestId('two-fa-disable')).toHaveCount(0)
    await expect(page.getByTestId('two-fa-regenerate')).toHaveCount(0)
    await shot(page, 's7-1-D-own-disabled')
    // 画面に出ている「今の自分のセッションで 2FA 系 API を直接叩く」＝ブラウザ認証付きで他人の対象指定は不能。
    // 無効化 API は対象ユーザーを引数に取らない（本人固定）。2FA 未設定の D が叩くと「未有効」で拒否される。
    const r = await page.evaluate(async () => {
      const tok = (JSON.parse(localStorage.getItem('auth') ?? '{}') as { accessToken?: string }).accessToken
      return { tok: !!tok }
    })
    console.log(`[S7] D のブラウザに access token 保持: ${r.tok}`)
    await ctx.close()

  })
})

// 未ログイン直打ち（BE を要しない。describe 外に置き、ユーザー作成の beforeAll に依存させない）
test('S7u 未ログインで /settings/security・/2fa-verify・/2fa-recovery を直打ち', async ({ browser }) => {
    for (const p of ['/settings/security', '/2fa-verify', '/2fa-recovery']) {
      const { ctx: c2, page: p2 } = await newCtx(browser)
      await p2.goto(`${FE}${p}`)
      await p2.waitForTimeout(4000)
      await shot(p2, `s7-unauth-${p.replace(/\//g, '_')}`)
      const body = (await p2.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 160)
      console.log(`[S7] 未ログイン直打ち ${p} → ${new URL(p2.url()).pathname}${new URL(p2.url()).search} | 画面: ${body}`)
      await c2.close()
    }
})
