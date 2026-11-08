// @vitest-environment happy-dom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'

import { useWebAuthnLogin } from './useWebAuthnLogin'

const mocks = vi.hoisted(() => ({ begin: vi.fn(), complete: vi.fn(), get: vi.fn() }))
vi.mock('./useAuthApi', () => ({ useAuthApi: () => ({ beginWebAuthnLogin: mocks.begin, completeWebAuthnLogin: mocks.complete }) }))

class TestPublicKeyCredential {
  type = 'public-key'
  rawId = new Uint8Array([255, 1]).buffer
  response = {
    authenticatorData: new Uint8Array(37).buffer,
    clientDataJSON: new Uint8Array([1, 2]).buffer,
    signature: new Uint8Array([3, 4]).buffer,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('PublicKeyCredential', TestPublicKeyCredential)
  Object.defineProperty(navigator, 'credentials', { configurable: true, value: { get: mocks.get } })
  mocks.begin.mockResolvedValue({ data: { challenge: '_wE', rpId: 'localhost', timeout: 60000, allowCredentials: ['_wE'] } })
  mocks.complete.mockResolvedValue({ data: { accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900 } })
  mocks.get.mockResolvedValue(new TestPublicKeyCredential())
})
afterEach(() => vi.unstubAllGlobals())

describe('useWebAuthnLogin', () => {
  it('メールを開始APIへ渡しUV必須で認証器に署名を要求する', async () => {
    const { loginWithPasskey } = useWebAuthnLogin()

    const tokens = await loginWithPasskey('member@example.com')

    expect(mocks.begin).toHaveBeenCalledWith('member@example.com')
    expect(mocks.get).toHaveBeenCalledWith({ publicKey: {
      challenge: new Uint8Array([255, 1]).buffer,
      rpId: 'localhost', timeout: 60000, userVerification: 'required',
      allowCredentials: [{ type: 'public-key', id: new Uint8Array([255, 1]).buffer }],
    } })
    expect(tokens?.accessToken).toBe('access')
  })

  it('署名と認証器のsignCountをbase64urlで完了APIに渡す', async () => {
    const credential = new TestPublicKeyCredential()
    new DataView(credential.response.authenticatorData).setUint32(33, 12345, false)
    mocks.get.mockResolvedValue(credential)

    await useWebAuthnLogin().loginWithPasskey('member@example.com')

    expect(mocks.complete).toHaveBeenCalledWith(expect.objectContaining({
      credentialId: '_wE', clientDataJson: 'AQI', signature: 'AwQ', signCount: 12345,
    }))
  })

  it('キャンセルはnullで復帰し完了APIを呼ばない', async () => {
    mocks.get.mockRejectedValue(new DOMException('cancelled', 'NotAllowedError'))

    expect(await useWebAuthnLogin().loginWithPasskey('member@example.com')).toBeNull()
    expect(mocks.complete).not.toHaveBeenCalled()
  })

  it('非対応ブラウザではAPIを呼ばず理由を返す', async () => {
    vi.stubGlobal('PublicKeyCredential', undefined)

    await expect(useWebAuthnLogin().loginWithPasskey('member@example.com')).rejects.toThrow('unsupported')
    expect(mocks.begin).not.toHaveBeenCalled()
  })

  it('安全でないコンテキストではAPIを呼ばず理由を返す', async () => {
    vi.stubGlobal('isSecureContext', false)

    await expect(useWebAuthnLogin().loginWithPasskey('member@example.com')).rejects.toThrow('unsupported')
    expect(mocks.begin).not.toHaveBeenCalled()
  })

  it.each(['AUTH_015', 'AUTH_024'])('開始APIの%sは未登録として返す', async (code) => {
    mocks.begin.mockRejectedValue({ data: { error: { code } } })

    await expect(useWebAuthnLogin().loginWithPasskey('member@example.com')).rejects.toThrow('unregistered')
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('許可資格情報が空なら認証器を呼ばず未登録を返す', async () => {
    mocks.begin.mockResolvedValue({ data: { challenge: 'AQI', rpId: 'localhost', allowCredentials: [], timeout: 60000 } })

    await expect(useWebAuthnLogin().loginWithPasskey('member@example.com')).rejects.toThrow('unregistered')
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('通信と署名検証失敗を呼び出し元へ伝える', async () => {
    const failure = { data: { error: { code: 'AUTH_024' } } }
    mocks.complete.mockRejectedValue(failure)

    await expect(useWebAuthnLogin().loginWithPasskey('member@example.com')).rejects.toBe(failure)
  })

  it('認証器が無応答ならキャンセルとして復帰する', async () => {
    mocks.get.mockResolvedValue(null)

    expect(await useWebAuthnLogin().loginWithPasskey('member@example.com')).toBeNull()
    expect(mocks.complete).not.toHaveBeenCalled()
  })
})
