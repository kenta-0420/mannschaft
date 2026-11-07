import { useAuthApi } from './useAuthApi'
import type { TokenResponse } from '~/types/auth'
import { b64urlToBuf, bufToB64url } from '~/utils/webauthn'

/** 未対応と未登録をAPI/署名失敗から区別する。 */
export class PasskeyLoginError extends Error {
  constructor(public readonly reason: 'unsupported' | 'unregistered') {
    super(reason)
  }
}

export function useWebAuthnLogin() {
  const { beginWebAuthnLogin, completeWebAuthnLogin } = useAuthApi()

  async function loginWithPasskey(email: string): Promise<TokenResponse | null> {
    if (typeof window === 'undefined' || !window.isSecureContext || !window.PublicKeyCredential || !navigator.credentials?.get) {
      throw new PasskeyLoginError('unsupported')
    }
    // 未登録の案内はbeginの応答だけで判定し、署名検証失敗と混同しない。
    const { data: begin } = await beginWebAuthnLogin(email).catch((error: unknown) => {
      const code = (error as { data?: { error?: { code?: string } } })?.data?.error?.code
      if (code === 'AUTH_015' || code === 'AUTH_024') throw new PasskeyLoginError('unregistered')
      throw error
    })
    if (begin.allowCredentials.length === 0) throw new PasskeyLoginError('unregistered')
    let credential: Credential | null
    try {
      credential = await navigator.credentials.get({ publicKey: {
        challenge: b64urlToBuf(begin.challenge),
        rpId: begin.rpId,
        timeout: begin.timeout,
        userVerification: 'required',
        allowCredentials: begin.allowCredentials.map(id => ({ type: 'public-key', id: b64urlToBuf(id) })),
      } })
    } catch (error: unknown) {
      // 認証器のキャンセル・タイムアウトは再試行可能な通常の操作として扱う。
      if (error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'AbortError')) return null
      throw error
    }
    if (!credential) return null
    if (!(credential instanceof PublicKeyCredential)) throw new Error('Invalid credential response')
    const response = credential.response as AuthenticatorAssertionResponse
    const { data: tokens } = await completeWebAuthnLogin({
      credentialId: bufToB64url(credential.rawId),
      authenticatorData: bufToB64url(response.authenticatorData),
      clientDataJson: bufToB64url(response.clientDataJSON),
      signature: bufToB64url(response.signature),
      signCount: new DataView(response.authenticatorData).getUint32(33, false),
    })
    return tokens
  }

  return { loginWithPasskey }
}
