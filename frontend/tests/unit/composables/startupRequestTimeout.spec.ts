// @vitest-environment node
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useFeatureFlagsApi } from '~/composables/useFeatureFlagsApi'
import { useNavSettingsApi } from '~/composables/useNavSettingsApi'
import { useScopeTabApi } from '~/composables/useScopeTabApi'
import { RequestTimeoutError, STARTUP_REQUEST_TIMEOUT_MS } from '~/utils/requestTimeout'

const { apiMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
}))

vi.mock('~/composables/useApi', () => ({
  useApi: () => apiMock,
}))

describe('起動用GETのtimeout', () => {
  afterEach(() => {
    vi.useRealTimers()
    apiMock.mockReset()
  })

  it.each([
    ['公開feature flags', () => useFeatureFlagsApi().getPublicFlags()],
    ['ナビ設定', () => useNavSettingsApi().getNavSettings()],
    ['scopeタブ', () => useScopeTabApi().getScopeTabs('TEAM', 0)],
  ])('%sは未応答時に通信をabortして失敗を返す', async (_name, request) => {
    vi.useFakeTimers()
    apiMock.mockReturnValue(new Promise<never>(() => {}))

    const result = request()
    const rejection = expect(result).rejects.toBeInstanceOf(RequestTimeoutError)
    await vi.advanceTimersByTimeAsync(STARTUP_REQUEST_TIMEOUT_MS)
    await rejection

    const options = apiMock.mock.calls[0]?.[1] as { signal?: AbortSignal } | undefined
    expect(options?.signal?.aborted).toBe(true)
  })
})
