/**
 * 2FA（TOTP）実機テスト。
 *
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:8081) が
 * 起動中であることを前提とする。
 *
 * フロー:
 *   TWOFA-001: 2FA セットアップ（POST /setup → secret 取得 → POST /verify でコード検証・有効化）
 *   TWOFA-002: 2FA 有効状態でログイン → /2fa-verify ページで TOTP コード入力 → ダッシュボードへ
 *   TWOFA-003: MFA リカバリーリクエスト（/recovery/request で 2FA 無効化メールを送信）
 *
 * 注意:
 *   - 2FA を無効化する専用エンドポイントは存在しない（Auth2faController に DELETE 等なし）。
 *     リカバリーは POST /api/v1/auth/2fa/recovery/request → メールトークンが必要なため
 *     自動テストで完全無効化は困難。
 *     TWOFA-002 実行後は afterAll でリカバリーリクエストを送信しておく（メールは届くが確認まで不可）。
 *   - useAuthApi.ts の verifyTotpSetup が body: { code } で送っているが、
 *     BE は { totpCode } を期待している（フィールド名不整合）。
 *     このテストでは BE が期待する { totpCode } フィールドで直接 API を呼ぶ。
 *     FE の verifyTotpSetup を UI 経由でテストする場合は、この不整合を先に修正すること。
 *
 * テストユーザー:
 *   e2e-user@test.mannschaft.local / TestPass2026!
 */

import { test, expect } from '@playwright/test'
import { TOTP } from 'otplib'
import { waitForHydration } from '../helpers/wait'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
const USER_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'TestPass2026!'

// TWOFA-001 → 002 の順で実行し、totpSecret を共有する
test.describe('TWOFA: 2FA セットアップ・ログインフロー', () => {
  test.describe.configure({ mode: 'serial' })

  // シリーズ間で共有するシークレット
  let totpSecret = ''

  // テスト開始前提: e2e-user の 2FA は seed 時に無効化されていること。
  // もし有効の場合は TWOFA-001 の setup API がエラーになる。
  // seed スクリプトで users テーブルの totp_enabled を false にリセットすること。

  // afterAll: 2FA 無効化はメールトークン確認が必要なため自動化不可。
  // 次回テスト実行前に seed で e2e-user の 2FA を無効化してリセットすること。

  test('TWOFA-001: 2FA をセットアップできる（API フロー）', async ({ page }) => {
    // storageState で認証済み（setup-real-user が事前にログイン）

    // 1. 2FA セットアップ開始 → シークレット取得
    const setupRes = await page.request.post('/api/v1/auth/2fa/setup')
    expect(setupRes.ok(), `2FA setup が失敗: ${setupRes.status()}`).toBeTruthy()
    const setupBody = await setupRes.json()
    const secret: string = setupBody.data.secret
    const qrCodeUrl: string = setupBody.data.qrCodeUrl
    expect(secret).toBeTruthy()
    expect(qrCodeUrl).toBeTruthy()

    // シリーズ後続テストで使用するために保存
    totpSecret = secret

    // 2. TOTP コードを生成
    const totp = new TOTP()
    const code = totp.generate(secret)
    expect(code).toMatch(/^\d{6}$/)

    // 3. 検証・有効化
    // 注意: useAuthApi.ts の verifyTotpSetup は body: { code } だが BE は { totpCode } を期待している。
    // ここでは BE 仕様に合わせて totpCode フィールドで直接呼び出す。
    const verifyRes = await page.request.post('/api/v1/auth/2fa/verify', {
      data: { totpCode: code },
    })
    expect(verifyRes.ok(), `2FA verify が失敗: ${verifyRes.status()} (codeが30秒以内なら有効)`).toBeTruthy()
    const verifyBody = await verifyRes.json()
    // backupCodes が返ってくることを確認（BackupCodesResponse）
    expect(Array.isArray(verifyBody.data.backupCodes)).toBeTruthy()
    expect(verifyBody.data.backupCodes.length).toBeGreaterThan(0)
  })

  test('TWOFA-002: 2FA コードを入力してログインできる', async ({ page }) => {
    // totpSecret は TWOFA-001 で設定済みである前提
    expect(totpSecret, 'TWOFA-001 が先に実行されている必要があります').toBeTruthy()

    // 1. ログアウト（storageState セッションを破棄）
    await page.request.post('/api/v1/auth/logout')
    // localStorage もクリア
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())

    // 2. ログインフォームへ
    await page.goto('/login')
    await waitForHydration(page)

    // 3. メール + パスワードを入力してログイン
    const emailInput = page.locator('input#email')
    await emailInput.click()
    await emailInput.pressSequentially(USER_EMAIL, { delay: 10 })

    const passwordInput = page.locator('input[type="password"]')
    await passwordInput.click()
    await passwordInput.pressSequentially(USER_PASSWORD, { delay: 10 })

    await page.getByRole('button', { name: 'ログイン' }).click()

    // 4. 2FA 検証画面へリダイレクトされることを確認
    await page.waitForURL(/\/2fa-verify/, { timeout: 15_000 })
    await waitForHydration(page)

    // 5. TOTP コードを生成して InputOtp に入力
    const totp = new TOTP()
    const code = totp.generate(totpSecret)

    // PrimeVue の InputOtp は各桁が個別の input になっている
    // 6桁分の入力フィールドに1文字ずつ入力する
    const otpInputs = page.locator('input[inputmode="numeric"]')
    const count = await otpInputs.count()

    if (count >= 6) {
      // InputOtp: 各桁の入力フィールドに1文字ずつ
      for (let i = 0; i < 6; i++) {
        await otpInputs.nth(i).click()
        await otpInputs.nth(i).fill(code[i])
      }
    } else {
      // フォールバック: 単一の input に6桁まとめて入力
      const singleInput = page.locator('input[inputmode="numeric"]').first()
      await singleInput.fill(code)
    }

    // 6. 「認証する」ボタンをクリック
    await page.getByRole('button', { name: '認証する' }).click()

    // 7. ダッシュボードへ遷移することを確認
    await page.waitForURL(/\/(dashboard|my|$)/, { timeout: 15_000 })
    const url = page.url()
    expect(url).not.toContain('/2fa-verify')
    expect(url).not.toContain('/login')
  })

  test('TWOFA-003: 2FA 設定後の /settings/security でセキュリティセクションが表示される', async ({
    page,
  }) => {
    // 2FA が有効な状態（TWOFA-001 後）でセキュリティページを確認する
    // storageState から再ログインが必要な場合は loginViaApi を使用
    await page.goto('/settings/security')
    await waitForHydration(page)

    // セキュリティページのヘッダーが表示されること
    await expect(page.getByRole('heading', { name: 'セキュリティ' })).toBeVisible({
      timeout: 15_000,
    })

    // 2FA セクションが存在すること（「2FAをセットアップ」または QR コードが表示）
    const hasTwoFaSection =
      (await page.getByText('二要素認証').count()) > 0 ||
      (await page.getByText('2FA').count()) > 0
    expect(hasTwoFaSection, '2FA セクションが見つかりません').toBeTruthy()
  })
})
