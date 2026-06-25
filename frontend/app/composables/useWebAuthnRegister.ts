import { ref } from 'vue'
import { useAuthApi } from './useAuthApi'

/**
 * パスキー（WebAuthn）登録フロー composable。
 *
 * 1. BE の /register/begin で challenge を取得
 * 2. navigator.credentials.create() で認証器に署名させる
 * 3. BE の /register/complete に送信して登録完了
 */
export function useWebAuthnRegister() {
  const { beginWebAuthnRegister, completeWebAuthnRegister } = useAuthApi()
  const isRegistering = ref(false)
  const error = ref<string | null>(null)

  /**
   * パスキーを登録する。
   * @returns 登録成功なら true、ユーザーキャンセルまたはエラーなら false
   */
  async function registerPasskey(deviceName?: string): Promise<boolean> {
    if (typeof window === 'undefined' || !window.PublicKeyCredential) {
      error.value = 'WebAuthn非対応ブラウザです'
      return false
    }
    isRegistering.value = true
    error.value = null
    try {
      // 1. begin → challenge 取得
      const beginRes = await beginWebAuthnRegister()
      // api<{ data: WebAuthnRegisterBeginResponse }> の戻り値は { data: ... } そのもの
      const beginData = beginRes.data

      // 2. PublicKeyCredentialCreationOptions を組み立て
      const challenge = b64urlToBuf(beginData.challenge)
      const publicKeyOptions: PublicKeyCredentialCreationOptions = {
        challenge,
        rp: { id: beginData.rpId, name: beginData.rpName },
        user: {
          // userId は number（BE の Long 相当）。WebAuthn spec では BufferSource が必要なので
          // 8 バイトリトルエンディアンの ArrayBuffer に変換する。
          id: numberToUint8Array(beginData.userId).buffer,
          name: beginData.userDisplayName,
          displayName: beginData.userDisplayName,
        },
        pubKeyCredParams: [
          { alg: -7, type: 'public-key' }, // ES256
          { alg: -257, type: 'public-key' }, // RS256
        ],
        authenticatorSelection: {
          userVerification: 'preferred',
          residentKey: 'preferred',
        },
        timeout: 60000,
        attestation: 'none',
      }

      // 3. 認証器に credential を作成させる
      const credential = await navigator.credentials.create({ publicKey: publicKeyOptions })
      if (!credential || !(credential instanceof PublicKeyCredential)) {
        throw new Error('認証器からの応答が無効です')
      }

      const response = credential.response as AuthenticatorAttestationResponse

      // 4. completeWebAuthnRegister
      await completeWebAuthnRegister({
        credentialId: bufToB64url(credential.rawId),
        attestationObject: bufToB64url(response.attestationObject),
        clientDataJson: bufToB64url(response.clientDataJSON),
        publicKey: bufToB64url(
          response.getPublicKey ? (response.getPublicKey() ?? new ArrayBuffer(0)) : new ArrayBuffer(0),
        ),
        deviceName: deviceName || undefined,
      })

      return true
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'NotAllowedError') {
        // ユーザーがキャンセルした場合はエラートーストを出さない
        return false
      }
      error.value = e instanceof Error ? e.message : '登録に失敗しました'
      return false
    } finally {
      isRegistering.value = false
    }
  }

  return { registerPasskey, isRegistering, error }
}

// ---- base64url ユーティリティ ----

/**
 * ArrayBuffer を base64url 文字列に変換する。
 */
function bufToB64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '')
}

/**
 * base64url 文字列を ArrayBuffer に変換する。
 */
function b64urlToBuf(b64url: string): ArrayBuffer {
  const base64 = b64url.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes.buffer
}

/**
 * number（userId）を 8 バイトリトルエンディアンの Uint8Array に変換する。
 * WebAuthn の user.id は ArrayBuffer 型が必要なため使用する。
 */
function numberToUint8Array(n: number): Uint8Array {
  const buf = new Uint8Array(8)
  let val = n
  for (let i = 0; i < 8; i++) {
    buf[i] = val & 0xff
    val = Math.floor(val / 256)
  }
  return buf
}
