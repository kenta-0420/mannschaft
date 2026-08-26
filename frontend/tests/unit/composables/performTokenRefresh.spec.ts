import { describe, it, expect, beforeEach, vi } from 'vitest'
import type { FetchError, FetchResponse } from 'ofetch'

/**
 * performTokenRefresh（useApi.ts）のユニットテスト。
 *
 * auth の要となるトークン更新ロジックの回帰防止。白画面（/organizations の loading 固着）
 * 根治のため、返り値を boolean → 3 状態の文字列ユニオン（'refreshed' | 'auth_failed' | 'transient'）
 * に変更した。一時的エラー（timeout / network / 5xx）でユーザーを誤ってログアウトさせないことが要。
 *
 * テストケース:
 * 1. 成功時 'refreshed' を返し setTokens が呼ばれる
 * 2. refresh が 401/403（refresh_token 無効＝本物の認証失敗）→ 'auth_failed'、setTokens 未呼出
 * 3. timeout / ネットワークエラー / 5xx（一時的）→ 'transient'、setTokens も logout も未呼出
 * 4. いずれの場合も終了後 refreshPromise がクリアされ、再呼び出しで新しい refresh が走る
 * 5. 同時に 2 回呼ぶと 1 本の Promise を共有する（dedup）
 */

const mockOfetch = vi.fn()
vi.mock('ofetch', () => ({
  ofetch: (...args: unknown[]) => mockOfetch(...args),
}))

vi.mock('~/composables/useApiBaseUrl', () => ({
  resolveApiBaseUrl: () => 'http://localhost:8080',
}))

const { performTokenRefresh } = await import('~/composables/useApi')

// performTokenRefresh は実引数として config / authStore を要求するが、
// 本テストでは ofetch のモックと setTokens/logout のスパイのみ検証するため、
// 必要最小のスタブを unknown 経由で渡す（Nuxt 実体は不要）。
type RefreshConfig = Parameters<typeof performTokenRefresh>[0]
type RefreshAuthStore = Parameters<typeof performTokenRefresh>[1]

function makeConfig(): RefreshConfig {
  return { public: { apiBase: '' } } as unknown as RefreshConfig
}

function makeAuthStore() {
  return {
    setTokens: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
  }
}

function asAuthStore(store: ReturnType<typeof makeAuthStore>): RefreshAuthStore {
  return store as unknown as RefreshAuthStore
}

function makeFetchError(status: number): FetchError {
  const response = {
    status,
    headers: new Headers(),
  } as unknown as FetchResponse<unknown>
  const err = new Error(`HTTP ${status}`) as FetchError
  ;(err as { response?: FetchResponse<unknown> }).response = response
  return err
}

beforeEach(() => {
  mockOfetch.mockReset()
})

describe('performTokenRefresh', () => {
  it('成功時は refreshed を返し setTokens が呼ばれる', async () => {
    mockOfetch.mockResolvedValueOnce({
      data: { accessToken: 'new-access', refreshToken: 'new-refresh' },
    })
    const config = makeConfig()
    const authStore = makeAuthStore()

    const result = await performTokenRefresh(
      config, asAuthStore(authStore),
    )

    expect(result).toBe('refreshed')
    expect(authStore.setTokens).toHaveBeenCalledWith('new-access', 'new-refresh')
    expect(authStore.logout).not.toHaveBeenCalled()
  })

  it('refresh が 401 を返すと auth_failed（setTokens 未呼出）', async () => {
    mockOfetch.mockRejectedValueOnce(makeFetchError(401))
    const authStore = makeAuthStore()

    const result = await performTokenRefresh(
      makeConfig(), asAuthStore(authStore),
    )

    expect(result).toBe('auth_failed')
    expect(authStore.setTokens).not.toHaveBeenCalled()
    expect(authStore.logout).not.toHaveBeenCalled()
  })

  it('refresh が 403 を返すと auth_failed', async () => {
    mockOfetch.mockRejectedValueOnce(makeFetchError(403))
    const authStore = makeAuthStore()

    const result = await performTokenRefresh(
      makeConfig(), asAuthStore(authStore),
    )

    expect(result).toBe('auth_failed')
    expect(authStore.setTokens).not.toHaveBeenCalled()
  })

  it('5xx は transient を返し setTokens も logout も呼ばれない', async () => {
    mockOfetch.mockRejectedValueOnce(makeFetchError(503))
    const authStore = makeAuthStore()

    const result = await performTokenRefresh(
      makeConfig(), asAuthStore(authStore),
    )

    expect(result).toBe('transient')
    expect(authStore.setTokens).not.toHaveBeenCalled()
    expect(authStore.logout).not.toHaveBeenCalled()
  })

  it('ネットワークエラー（response 無し）は transient', async () => {
    mockOfetch.mockRejectedValueOnce(new Error('network error'))
    const authStore = makeAuthStore()

    const result = await performTokenRefresh(
      makeConfig(), asAuthStore(authStore),
    )

    expect(result).toBe('transient')
    expect(authStore.setTokens).not.toHaveBeenCalled()
  })

  it('timeout（abort）は transient を返す', async () => {
    const abortErr = new Error('The operation was aborted')
    abortErr.name = 'AbortError'
    mockOfetch.mockRejectedValueOnce(abortErr)
    const authStore = makeAuthStore()

    const result = await performTokenRefresh(
      makeConfig(), asAuthStore(authStore),
    )

    expect(result).toBe('transient')
    expect(authStore.setTokens).not.toHaveBeenCalled()
    expect(authStore.logout).not.toHaveBeenCalled()
  })

  it('終了後 refreshPromise はクリアされ再呼び出しで新しい refresh が走る', async () => {
    mockOfetch.mockResolvedValueOnce({
      data: { accessToken: 'a1', refreshToken: 'r1' },
    })
    const authStore = makeAuthStore()
    await performTokenRefresh(
      makeConfig(), asAuthStore(authStore),
    )
    expect(mockOfetch).toHaveBeenCalledTimes(1)

    // 2 回目: 解放されているので再度 ofetch が呼ばれる
    mockOfetch.mockResolvedValueOnce({
      data: { accessToken: 'a2', refreshToken: 'r2' },
    })
    await performTokenRefresh(
      makeConfig(), asAuthStore(authStore),
    )
    expect(mockOfetch).toHaveBeenCalledTimes(2)
  })

  it('同時に 2 回呼ぶと 1 本の Promise を共有する（dedup）', async () => {
    let resolveFetch: (value: unknown) => void = () => {}
    mockOfetch.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveFetch = resolve
      }),
    )
    const authStore = makeAuthStore()
    const config = makeConfig()

    const p1 = performTokenRefresh(
      config, asAuthStore(authStore),
    )
    const p2 = performTokenRefresh(
      config, asAuthStore(authStore),
    )

    expect(p1).toBe(p2)
    expect(mockOfetch).toHaveBeenCalledTimes(1)

    resolveFetch({ data: { accessToken: 'a', refreshToken: 'r' } })
    const [r1, r2] = await Promise.all([p1, p2])
    expect(r1).toBe('refreshed')
    expect(r2).toBe('refreshed')
  })
})
