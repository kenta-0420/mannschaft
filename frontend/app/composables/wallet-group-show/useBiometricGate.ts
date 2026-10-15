/**
 * F18 個人ポイントカードウォレット — 提示モードの生体認証ゲート。
 *
 * <p>設定 {@code requireBiometricOnShow=true} のときに呼ぶ。サーバー側 5 分 TTL
 * 検証を含む 3 段フロー。</p>
 *
 * <ol>
 *   <li>{@code POST /reauthenticate-begin} でチャレンジ取得</li>
 *   <li>{@code navigator.credentials.get} で署名取得（OS 生体認証 / PIN ダイアログ）</li>
 *   <li>{@code POST /reauthenticate-complete} でサーバー検証 → 「再認証済みフラグ」が
 *       Valkey に 5 分 TTL で記録される</li>
 * </ol>
 *
 * <p>completeReauthenticate は AT/RT を再発行しない。フラグは提示モード起動 API が
 * 1 回限りで消費する（POINT_CARD_009 再生攻撃防止）。</p>
 *
 * <p>分割元: {@code frontend/app/pages/wallet/groups/[id]/show.vue}（リファクタリング第11弾）。
 * ロジック・呼び出し順序は元実装と完全同一であり、振る舞いの変更は含まれない。</p>
 */

export interface UseBiometricGate {
  /**
   * @returns 成功で true / ユーザーキャンセル or 失敗で false
   */
  verifyBiometric: () => Promise<boolean>
}

// base64url ⇄ ArrayBuffer 変換ユーティリティ（依存追加を避けて手書き）
function b64urlToBuf(s: string): ArrayBuffer {
  const pad = '='.repeat((4 - (s.length % 4)) % 4)
  const b64 = (s + pad).replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64)
  const buf = new ArrayBuffer(bin.length)
  const view = new Uint8Array(buf)
  for (let i = 0; i < bin.length; i++) view[i] = bin.charCodeAt(i)
  return buf
}

function bufToB64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let bin = ''
  for (let i = 0; i < bytes.byteLength; i++) bin += String.fromCharCode(bytes[i]!)
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/**
 * authenticatorData (CBOR デコード不要、固定オフセット) から signCount を抽出する。
 *
 * <p>WebAuthn 仕様 §6.1: authenticatorData の構造
 * <pre>
 *   rpIdHash (32) | flags (1) | signCount (4, big-endian) | ...
 * </pre>
 * signCount は バイト 33-36 (0-indexed) の 32bit big-endian unsigned。
 */
function extractSignCount(authenticatorData: ArrayBuffer): number {
  const view = new DataView(authenticatorData)
  // 32 (rpIdHash) + 1 (flags) = 33 から 4 バイトが signCount
  return view.getUint32(33, false)
}

export function useBiometricGate(): UseBiometricGate {
  const authApi = useAuthApi()

  async function verifyBiometric(): Promise<boolean> {
    // WebAuthn API 自体が無い環境（古いブラウザ・SSR）ではスキップして警告のみとする
    if (typeof window === 'undefined' || !window.PublicKeyCredential || !navigator.credentials) {
      return false
    }
    try {
      // 1. begin: 再認証用チャレンジ取得（既存ログインフローとは別エンドポイント）
      const beginRes = await authApi.beginWebAuthnReauthenticate()
      const begin = beginRes.data

      const publicKey: PublicKeyCredentialRequestOptions = {
        challenge: b64urlToBuf(begin.challenge),
        rpId: begin.rpId,
        timeout: begin.timeout,
        userVerification: 'preferred',
        allowCredentials: begin.allowCredentials.map((id) => ({
          type: 'public-key' as const,
          id: b64urlToBuf(id),
        })),
      }

      // 2. navigator.credentials.get で署名取得
      const cred = await navigator.credentials.get({ publicKey })
      if (!cred) return false

      // PublicKeyCredential を取り出してサーバー検証用に Base64URL 化
      const pkCred = cred as PublicKeyCredential
      const assertion = pkCred.response as AuthenticatorAssertionResponse

      const credentialId = bufToB64url(pkCred.rawId)
      const authenticatorDataB64 = bufToB64url(assertion.authenticatorData)
      const clientDataJsonB64 = bufToB64url(assertion.clientDataJSON)
      const signatureB64 = bufToB64url(assertion.signature)
      const signCount = extractSignCount(assertion.authenticatorData)

      // 3. complete: サーバー検証 → 再認証済みフラグが Valkey に書き込まれる
      await authApi.completeWebAuthnReauthenticate({
        credentialId,
        authenticatorData: authenticatorDataB64,
        clientDataJson: clientDataJsonB64,
        signature: signatureB64,
        signCount,
      })
      return true
    }
    catch (e) {
      // ユーザーキャンセル / タイムアウト / authenticator なし / サーバー検証失敗
      if (import.meta.dev) console.warn('[presentation] biometric verification failed', e)
      return false
    }
  }

  return { verifyBiometric }
}
