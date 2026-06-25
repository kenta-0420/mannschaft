/**
 * WebAuthn（パスキー）実機テスト。
 *
 * バックエンド (http://localhost:8080) とフロントエンド (http://localhost:8081) が
 * 起動中であることを前提とし、CDPSession の仮想認証器で WebAuthn フローを検証する。
 *
 * テストユーザー:
 *   e2e-user@test.mannschaft.local / TestPass2026!
 *
 * 前提:
 *   - BE に MANNSCHAFT_WEBAUTHN_RP_ID=localhost を設定すること（application-local.yml 参照）
 *   - WEBAUTHN-001/002: API 直接呼び出し + CDP 仮想認証器でフローを検証
 *   - WEBAUTHN-003: /settings/security の UI ボタンからパスキーを登録するフルフロー
 */

import { test, expect } from '@playwright/test'
import type { Page, CDPSession } from '@playwright/test'
import { waitForHydration } from '../helpers/wait'

const USER_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e-user@test.mannschaft.local'
// FE dev server が /api/v1/** をプロキシしていない場合 (NUXT_API_PROXY 未設定) は
// BE に直接 API リクエストを送る。page.goto はそのまま FE (baseURL) を使う。
const API_BASE = process.env.API_BASE_URL ?? 'http://localhost:8080'

// ----------------------------------------------------------------------------------
// 仮想 WebAuthn 認証器セットアップ
// ----------------------------------------------------------------------------------

interface VirtualAuthenticator {
  client: CDPSession
  authenticatorId: string
}

async function setupVirtualAuthenticator(page: Page): Promise<VirtualAuthenticator> {
  const client = await page.context().newCDPSession(page)
  await client.send('WebAuthn.enable', { enableUI: false })
  const { authenticatorId } = await client.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
    },
  })
  return { client, authenticatorId }
}

/**
 * FE ページが /api/v1/** を BE にプロキシしていない場合（NUXT_API_PROXY 未設定）でも
 * UI テストが動作するよう、page.route() で API 呼び出しを BE に中継する。
 * origin ヘッダーを FE オリジンに差し替えて CORS を通過させる。
 */
async function setupApiRoute(page: Page): Promise<void> {
  const feOrigin = new URL(page.url() || 'http://localhost').origin
  await page.route('**/api/v1/auth/webauthn/**', async (route) => {
    const req = route.request()
    const originalUrl = req.url()
    // FE オリジン部分を BE に差し替え（相対パス→絶対パス）
    const targetUrl = originalUrl.startsWith(API_BASE)
      ? originalUrl
      : originalUrl.replace(/^https?:\/\/[^/]+/, API_BASE)
    const response = await route.fetch({
      url: targetUrl,
      headers: {
        ...req.headers(),
        origin: feOrigin,
        'access-control-allow-origin': feOrigin,
      },
    })
    const resHeaders = { ...response.headers() }
    resHeaders['access-control-allow-origin'] = feOrigin
    resHeaders['access-control-allow-credentials'] = 'true'
    await route.fulfill({
      status: response.status(),
      headers: resHeaders,
      body: await response.body(),
    })
  })
}

// ----------------------------------------------------------------------------------
// WEBAUTHN: パスキー登録・ログインフロー
// ----------------------------------------------------------------------------------

test.describe('WEBAUTHN: パスキー登録・ログインフロー', () => {
  // WEBAUTHN-001 → WEBAUTHN-002 → WEBAUTHN-003 の順で実行（資格情報登録が前提）
  test.describe.configure({ mode: 'serial' })

  // 各テスト前に登録済み資格情報をすべてクリーンアップ（テスト干渉防止）
  test.beforeEach(async ({ page }) => {
    // storageState で認証済みのため、page.request でそのまま API 呼び出し可能
    const credsRes = await page.request.get(`${API_BASE}/api/v1/auth/webauthn/credentials`)
    if (credsRes.ok()) {
      const body = await credsRes.json()
      const creds: { id: number }[] = body.data ?? []
      for (const cred of creds) {
        await page.request.delete(`${API_BASE}/api/v1/auth/webauthn/credentials/${cred.id}`)
      }
    }
  })

  test('WEBAUTHN-001: パスキーを登録できる（API フロー）', async ({ page }) => {
    // 仮想 WebAuthn 認証器をセットアップ
    const { client } = await setupVirtualAuthenticator(page)

    try {
      // 1. 登録開始: BEGIN → チャレンジ取得
      const beginRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/begin`)
      expect(beginRes.ok(), `register/begin が失敗: ${beginRes.status()}`).toBeTruthy()
      const beginBody = await beginRes.json()
      const options = beginBody.data

      // 2. FE ページに移動して clientDataJSON.origin を FE オリジンに確立する
      //    （BE の ServerProperty の origin と一致させるために必要）
      await page.goto('/')

      // 3. ブラウザ側で navigator.credentials.create() を実行（仮想認証器が自動応答）
      //    page.evaluate でブラウザコンテキストに入り WebAuthn 操作を実行する
      const credential = await page.evaluate(async (rawOptions: unknown) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const creationOptions = rawOptions as any

        // ArrayBuffer のフィールドを base64url 文字列から Uint8Array に変換するヘルパー
        function base64urlToBuffer(b64url: string): ArrayBuffer {
          const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
          const binary = atob(b64)
          const bytes = new Uint8Array(binary.length)
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
          return bytes.buffer
        }

        function bufferToBase64url(buffer: ArrayBuffer): string {
          const bytes = new Uint8Array(buffer)
          let binary = ''
          for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
          const b64 = btoa(binary)
          return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
        }

        // BE レスポンスの challenge / user.id は base64url 文字列で来る
        const publicKey: PublicKeyCredentialCreationOptions = {
          ...creationOptions,
          challenge: base64urlToBuffer(creationOptions.challenge as string),
          user: {
            ...creationOptions.user,
            id: base64urlToBuffer(creationOptions.user.id as string),
          },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          excludeCredentials: ((creationOptions.excludeCredentials as any[]) ?? []).map((c: any) => ({
            ...c,
            id: base64urlToBuffer(c.id as string),
          })),
        }

        const cred = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential
        const response = cred.response as AuthenticatorAttestationResponse

        return {
          id: cred.id,
          rawId: bufferToBase64url(cred.rawId),
          type: cred.type,
          response: {
            clientDataJSON: bufferToBase64url(response.clientDataJSON),
            attestationObject: bufferToBase64url(response.attestationObject),
          },
        }
      }, options)

      // 3. 登録完了: COMPLETE → 201
      const completeRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/complete`, {
        data: credential,
      })
      expect(completeRes.ok(), `register/complete が失敗: ${completeRes.status()}`).toBeTruthy()

      // 4. 資格情報一覧に登録されたことを確認
      const credsRes = await page.request.get(`${API_BASE}/api/v1/auth/webauthn/credentials`)
      expect(credsRes.ok()).toBeTruthy()
      const credsBody = await credsRes.json()
      expect(credsBody.data.length).toBeGreaterThanOrEqual(1)
    } finally {
      await client.detach()
    }
  })

  test('WEBAUTHN-002: パスキーでログインできる（API フロー）', async ({ page }) => {
    // 事前: 資格情報を登録する（WEBAUTHN-001 と同様の手順）
    const { client } = await setupVirtualAuthenticator(page)

    try {
      // === 資格情報登録 ===
      // FE オリジンを確立してから evaluate を実行する（clientDataJSON.origin = FE オリジン）
      await page.goto('/')
      const beginRegRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/begin`)
      expect(beginRegRes.ok()).toBeTruthy()
      const regOptions = (await beginRegRes.json()).data

      const credential = await page.evaluate(async (rawOpts: unknown) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const opts = rawOpts as any
        function base64urlToBuffer(b64url: string): ArrayBuffer {
          const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
          const binary = atob(b64)
          const bytes = new Uint8Array(binary.length)
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
          return bytes.buffer
        }
        function bufferToBase64url(buffer: ArrayBuffer): string {
          const bytes = new Uint8Array(buffer)
          let binary = ''
          for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
          const b64 = btoa(binary)
          return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
        }
        const publicKey: PublicKeyCredentialCreationOptions = {
          ...opts,
          challenge: base64urlToBuffer(opts.challenge as string),
          user: { ...opts.user, id: base64urlToBuffer(opts.user.id as string) },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          excludeCredentials: ((opts.excludeCredentials as any[]) ?? []).map((c: any) => ({
            ...c,
            id: base64urlToBuffer(c.id as string),
          })),
        }
        const cred = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential
        const response = cred.response as AuthenticatorAttestationResponse
        return {
          id: cred.id,
          rawId: bufferToBase64url(cred.rawId),
          type: cred.type,
          response: {
            clientDataJSON: bufferToBase64url(response.clientDataJSON),
            attestationObject: bufferToBase64url(response.attestationObject),
          },
        }
      }, regOptions)

      const completeRegRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/register/complete`, {
        data: credential,
      })
      expect(completeRegRes.ok(), `register/complete が失敗: ${completeRegRes.status()}`).toBeTruthy()

      // === ログアウト ===
      await page.request.post(`${API_BASE}/api/v1/auth/logout`)

      // === パスキーでログイン ===
      // 1. ログイン開始: email を渡してチャレンジ取得
      const beginLoginRes = await page.request.post(
        `${API_BASE}/api/v1/auth/webauthn/login/begin?email=${encodeURIComponent(USER_EMAIL)}`,
      )
      expect(beginLoginRes.ok(), `login/begin が失敗: ${beginLoginRes.status()}`).toBeTruthy()
      const loginOptions = (await beginLoginRes.json()).data

      // 2. navigator.credentials.get() を実行（仮想認証器が自動応答）
      // page に先にアクセスしてドメインを確立してから evaluate を実行
      await page.goto('/')

      const assertion = await page.evaluate(async (rawLoginOpts: unknown) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const opts = rawLoginOpts as any
        function base64urlToBuffer(b64url: string): ArrayBuffer {
          const b64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
          const binary = atob(b64)
          const bytes = new Uint8Array(binary.length)
          for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
          return bytes.buffer
        }
        function bufferToBase64url(buffer: ArrayBuffer): string {
          const bytes = new Uint8Array(buffer)
          let binary = ''
          for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
          const b64 = btoa(binary)
          return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
        }
        const publicKey: PublicKeyCredentialRequestOptions = {
          ...opts,
          challenge: base64urlToBuffer(opts.challenge as string),
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          allowCredentials: ((opts.allowCredentials as any[]) ?? []).map((c: any) => ({
            ...c,
            id: base64urlToBuffer(c.id as string),
          })),
        }
        const assertion = (await navigator.credentials.get({ publicKey })) as PublicKeyCredential
        const response = assertion.response as AuthenticatorAssertionResponse
        return {
          id: assertion.id,
          rawId: bufferToBase64url(assertion.rawId),
          type: assertion.type,
          response: {
            clientDataJSON: bufferToBase64url(response.clientDataJSON),
            authenticatorData: bufferToBase64url(response.authenticatorData),
            signature: bufferToBase64url(response.signature),
            userHandle: response.userHandle ? bufferToBase64url(response.userHandle) : null,
          },
        }
      }, loginOptions)

      // 3. ログイン完了: COMPLETE → 200 + トークン取得
      const completeLoginRes = await page.request.post(`${API_BASE}/api/v1/auth/webauthn/login/complete`, {
        data: assertion,
      })
      expect(completeLoginRes.ok(), `login/complete が失敗: ${completeLoginRes.status()}`).toBeTruthy()
      const loginBody = await completeLoginRes.json()
      expect(loginBody.data).toHaveProperty('accessToken')
    } finally {
      await client.detach()
    }
  })

  test('WEBAUTHN-003: /settings/security の UI ボタンからパスキーを登録できる（UI フロー）', async ({
    page,
  }) => {
    // 1. ページ遷移前に仮想認証器をセットアップ
    //    （CDP 仮想認証器はページコンテキストに紐づくため先にセットアップする）
    const { client } = await setupVirtualAuthenticator(page)

    try {
      // 2. /settings/security ページに移動してハイドレーション待ち
      await page.goto('/settings/security')
      await waitForHydration(page)

      // 3. API ブリッジ設定（NUXT_API_PROXY が未設定の場合の保険）
      //    FE の /api/v1/** 呼び出しを BE (API_BASE) に中継する
      await setupApiRoute(page)

      // 4. 「パスキーを登録」ボタンが表示されるまで待つ
      //    WebAuthn サポート検出は onMounted で行われるため、少し待つ必要がある
      const registerBtn = page.getByRole('button', { name: 'パスキーを登録' })
      await expect(registerBtn, 'パスキーを登録ボタンが表示されること').toBeVisible({ timeout: 10_000 })

      // 5. ボタンをクリックして登録ダイアログを開く
      await registerBtn.click()

      // 6. ダイアログが開いたことを確認
      const dialog = page.getByRole('dialog', { name: 'パスキーを登録' })
      await expect(dialog, 'パスキー登録ダイアログが開くこと').toBeVisible({ timeout: 5_000 })

      // 7. 「登録する」ボタンをクリック
      //    CDP 仮想認証器が navigator.credentials.create() を自動インターセプトする
      const confirmBtn = dialog.getByRole('button', { name: '登録する' })
      await confirmBtn.click()

      // 8. 成功トーストが表示されること
      const successToast = page.locator('[role="alert"][data-p="success"]').first()
      await expect(successToast, '成功トーストが表示されること').toBeVisible({ timeout: 15_000 })

      // 9. 資格情報一覧に新しいエントリが追加されたことを確認
      //    pi pi-key アイコンを持つ要素が1件以上表示される
      const credentialEntry = page.locator('.pi.pi-key').first()
      await expect(credentialEntry, '資格情報エントリが表示されること').toBeVisible({ timeout: 5_000 })

      // 10. API でも確認（BE に永続化されていること）
      const credsRes = await page.request.get(`${API_BASE}/api/v1/auth/webauthn/credentials`)
      expect(credsRes.ok()).toBeTruthy()
      const credsBody = await credsRes.json()
      expect(credsBody.data.length, '資格情報が BE に登録されていること').toBeGreaterThanOrEqual(1)
    } finally {
      await client.detach()
    }
  })
})
