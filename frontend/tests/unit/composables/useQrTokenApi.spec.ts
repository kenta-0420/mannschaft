import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F13.1 Phase 13.1.2 {@code useQrTokenApi} のユニットテスト。
 *
 * <p>対象関数:</p>
 * <ul>
 *   <li>{@code issueToken} — 正しいエンドポイントに正しい body で POST されること</li>
 *   <li>{@code getCurrentToken} — {@code type} クエリを付けて GET、204 時は null を返すこと</li>
 *   <li>{@code startAutoRotation} — 初回 issue が走り、expiresAt - 5s で再 issue が走ること</li>
 *   <li>{@code startAutoRotation} の cleanup — タイマーが停止すること</li>
 * </ul>
 */

// ============================================================
// Nuxt auto-import のモック
// ============================================================

const mockApiFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApiFetch,
}))

// useQrTokenApi 内で ref / readonly / onBeforeUnmount / getCurrentInstance / setTimeout を
// 使用しているため、Nuxt auto-import 環境（vitest の environment: 'nuxt'）で
// 動的 import することを前提とする。

const { useQrTokenApi } = await import('~/composables/jobs/useQrTokenApi')

// ============================================================
// ヘルパー
// ============================================================

type IssueBody = { type: 'IN' | 'OUT'; ttlSeconds?: number }

function makeTokenResponse(overrides: Partial<{ token: string | null; shortCode: string; type: 'IN' | 'OUT'; issuedAt: string; expiresAt: string; kid: string }> = {}) {
  const now = Date.now()
  return {
    data: {
      token: overrides.token !== undefined ? overrides.token : 'jwt-token-abc',
      shortCode: overrides.shortCode ?? 'A1B2C3',
      type: overrides.type ?? 'IN',
      issuedAt: overrides.issuedAt ?? new Date(now).toISOString(),
      expiresAt: overrides.expiresAt ?? new Date(now + 60_000).toISOString(),
      kid: overrides.kid ?? 'kid-1',
    },
  }
}

// ============================================================
// テスト本体
// ============================================================

describe('useQrTokenApi', () => {
  beforeEach(() => {
    mockApiFetch.mockReset()
    vi.useRealTimers()
  })

  describe('issueToken', () => {
    it('正しいエンドポイントに type のみで POST する（ttlSeconds 未指定）', async () => {
      mockApiFetch.mockResolvedValueOnce(makeTokenResponse())
      const api = useQrTokenApi()

      await api.issueToken(42, 'IN')

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url, options] = mockApiFetch.mock.calls[0] as [string, { method: string; body: IssueBody }]
      expect(url).toBe('/api/v1/contracts/42/qr-tokens')
      expect(options.method).toBe('POST')
      expect(options.body).toEqual({ type: 'IN' })
    })

    it('ttlSeconds 指定時は body に乗せる', async () => {
      mockApiFetch.mockResolvedValueOnce(makeTokenResponse({ type: 'OUT' }))
      const api = useQrTokenApi()

      await api.issueToken(99, 'OUT', 90)

      const [, options] = mockApiFetch.mock.calls[0] as [string, { method: string; body: IssueBody }]
      expect(options.body).toEqual({ type: 'OUT', ttlSeconds: 90 })
    })
  })

  describe('getCurrentToken', () => {
    it('type クエリを付けて GET する', async () => {
      mockApiFetch.mockResolvedValueOnce(makeTokenResponse({ token: null }))
      const api = useQrTokenApi()

      await api.getCurrentToken(7, 'IN')

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      const [url] = mockApiFetch.mock.calls[0] as [string]
      expect(url).toBe('/api/v1/contracts/7/qr-tokens/current?type=IN')
    })

    it('BE が 204（null 応答）を返したら null を返す', async () => {
      mockApiFetch.mockResolvedValueOnce(null)
      const api = useQrTokenApi()

      const res = await api.getCurrentToken(7, 'OUT')
      expect(res).toBeNull()
    })
  })

  describe('startAutoRotation', () => {
    // Promise 解決の microtask をフラッシュする小ヘルパー。
    // fake timer 下では await だけでは Promise は進まないので、3 tick 回す。
    async function flushMicrotasks(rounds = 3) {
      for (let i = 0; i < rounds; i++) {
        await Promise.resolve()
      }
    }

    it('初回に即 issue を発火して onToken を呼ぶ', async () => {
      vi.useFakeTimers()
      const now = Date.now()
      mockApiFetch.mockResolvedValueOnce(makeTokenResponse({
        issuedAt: new Date(now).toISOString(),
        expiresAt: new Date(now + 60_000).toISOString(),
      }))
      const api = useQrTokenApi()
      const onToken = vi.fn()

      const stop = api.startAutoRotation(1, 'IN', onToken)

      // 初回 issue の Promise 解決（setTimeout を待たずに microtask で完結）
      await flushMicrotasks()

      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      expect(onToken).toHaveBeenCalledTimes(1)
      expect(onToken.mock.calls[0][0].shortCode).toBe('A1B2C3')

      stop()
    })

    it('expiresAt - 5s で再 issue をスケジュールする', async () => {
      vi.useFakeTimers()
      const now = Date.now()
      // TTL=10s の初回トークン → 5s 後に再発行が走る想定
      mockApiFetch
        .mockResolvedValueOnce(makeTokenResponse({
          issuedAt: new Date(now).toISOString(),
          expiresAt: new Date(now + 10_000).toISOString(),
          shortCode: 'FIRST1',
        }))
        .mockResolvedValueOnce(makeTokenResponse({
          issuedAt: new Date(now + 5_000).toISOString(),
          expiresAt: new Date(now + 15_000).toISOString(),
          shortCode: 'SECND2',
        }))
      const api = useQrTokenApi()
      const onToken = vi.fn()

      const stop = api.startAutoRotation(1, 'IN', onToken)

      // 初回 issue 解決（microtask のみ）
      await flushMicrotasks()
      expect(mockApiFetch).toHaveBeenCalledTimes(1)
      expect(onToken).toHaveBeenCalledTimes(1)

      // 5 秒進めると再 issue が走り、さらに microtask をフラッシュして解決
      await vi.advanceTimersByTimeAsync(5_000)
      await flushMicrotasks()
      expect(mockApiFetch).toHaveBeenCalledTimes(2)
      expect(onToken).toHaveBeenCalledTimes(2)
      expect(onToken.mock.calls[1][0].shortCode).toBe('SECND2')

      stop()
    })

    it('cleanup 後は再発行されない', async () => {
      vi.useFakeTimers()
      const now = Date.now()
      mockApiFetch.mockResolvedValueOnce(makeTokenResponse({
        issuedAt: new Date(now).toISOString(),
        expiresAt: new Date(now + 10_000).toISOString(),
      }))
      const api = useQrTokenApi()
      const onToken = vi.fn()

      const stop = api.startAutoRotation(1, 'IN', onToken)
      await flushMicrotasks()
      expect(mockApiFetch).toHaveBeenCalledTimes(1)

      stop()

      // 5 秒以上進めても追加 issue は起きない
      await vi.advanceTimersByTimeAsync(10_000)
      await flushMicrotasks()
      expect(mockApiFetch).toHaveBeenCalledTimes(1)
    })

    it('issue 失敗時は onError を呼びつつ、5 秒後にリトライが走る', async () => {
      vi.useFakeTimers()
      mockApiFetch
        .mockRejectedValueOnce(new Error('boom'))
        .mockResolvedValueOnce(makeTokenResponse())
      const api = useQrTokenApi()
      const onToken = vi.fn()
      const onError = vi.fn()

      const stop = api.startAutoRotation(1, 'IN', onToken, onError)

      // 初回の失敗を消化
      await flushMicrotasks()
      expect(onError).toHaveBeenCalledTimes(1)
      expect(mockApiFetch).toHaveBeenCalledTimes(1)

      // 5 秒後にリトライ → 2 回目は成功
      await vi.advanceTimersByTimeAsync(5_000)
      await flushMicrotasks()
      expect(mockApiFetch).toHaveBeenCalledTimes(2)
      expect(onToken).toHaveBeenCalledTimes(1)

      stop()
    })
  })
})
