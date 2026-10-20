// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthApi } from './useAuthApi'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('./useApi', () => ({ useApi: () => (mocks.api) }))

beforeEach(() => vi.clearAllMocks())

describe('useAuthApi パスキー開始のHTTP契約', () => {
  it('POSTのqueryへemailを渡す', async () => {
    await useAuthApi().beginWebAuthnLogin('member+passkey@example.com')

    expect(mocks.api).toHaveBeenCalledWith('/api/v1/auth/webauthn/login/begin', {
      method: 'POST', query: { email: 'member+passkey@example.com' },
    })
  })
})
