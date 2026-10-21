// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createFetch } from 'ofetch'
import { useUserSettingsApi } from './useUserSettingsApi'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('./useApi', () => ({ useApi: () => mocks.api }))
vi.stubGlobal('useApi', () => mocks.api)

beforeEach(() => mocks.api.mockReset())

describe('本人パスワードのHTTP送信契約 AC-11', () => {
  it('OAuth初期設定はURLに含めずフォーム本文として既存POSTへ送る', async () => {
    const transport = vi.fn(async () => new Response('{"data":{"message":"ok"}}', {
      headers: { 'content-type': 'application/json' },
    }))
    const client = createFetch({ fetch: transport, defaults: { baseURL: 'https://api.example.test' } })
    mocks.api.mockImplementation(client)
    const input = 'Fixture+A&b=1 #日本語'
    await useUserSettingsApi().setupPassword(input)
    expect(transport).toHaveBeenCalledTimes(1)
    const [url, options] = transport.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('https://api.example.test/api/v1/users/me/password/setup')
    expect(options.method).toBe('POST')
    expect(new Headers(options.headers).get('content-type')).toBe('application/x-www-form-urlencoded')
    expect(new URLSearchParams(String(options.body)).get('password')).toBe(input)
  })

  it('本人の変更は既存PATCHへ新旧パスワードを本文で渡す', async () => {
    const body = { currentPassword: 'FixtureOld1', newPassword: 'FixtureNew2' }
    await useUserSettingsApi().changePassword(body)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/users/me/password', { method: 'PATCH', body })
  })
})
