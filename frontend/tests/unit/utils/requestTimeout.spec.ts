// @vitest-environment node
import { describe, expect, it, vi } from 'vitest'
import { runWithRequestTimeout } from '~/utils/requestTimeout'

describe('runWithRequestTimeout', () => {
  it('正常応答を成功として返す', async () => {
    await expect(runWithRequestTimeout(async () => 'ok', 100)).resolves.toEqual({
      status: 'success',
      value: 'ok',
    })
  })

  it('通常エラーをタイムアウトと区別する', async () => {
    const error = new Error('HTTP 500')
    await expect(runWithRequestTimeout(async () => Promise.reject(error), 100)).resolves.toEqual({
      status: 'error',
      error,
    })
  })

  it('通信が保留されても有限時間で完了し、リクエストをabortする', async () => {
    vi.useFakeTimers()
    let requestSignal: AbortSignal | undefined

    try {
      const resultPromise = runWithRequestTimeout((signal) => {
        requestSignal = signal
        return new Promise<never>(() => {})
      }, 15_000)

      await vi.advanceTimersByTimeAsync(15_000)

      await expect(resultPromise).resolves.toEqual({ status: 'timeout' })
      expect(requestSignal?.aborted).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })
})
