import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useFeatureFlagStore } from '~/stores/featureFlags'

/**
 * Gate基盤工事① 一般ユーザー向け公開フラグ読取API の FE store ユニットテスト（試練・red 先行）。
 *
 * AC-5: loadPublicFlags 後に isEnabled が実値を返す。
 */

const publicApiMock = {
  getPublicFlags: vi.fn(),
}

vi.mock('~/composables/useFeatureFlagsApi', () => ({
  useFeatureFlagsApi: () => publicApiMock,
}))

describe('useFeatureFlagStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    publicApiMock.getPublicFlags.mockReset()
  })

  it('(AC-5) loadPublicFlags後にisEnabledが実値を返す', async () => {
    publicApiMock.getPublicFlags.mockResolvedValue([
      { flagKey: 'FEATURE_NEW_UI', enabled: true },
      { flagKey: 'FEATURE_BETA', enabled: false },
    ])

    const store = useFeatureFlagStore()
    expect(store.isEnabled('FEATURE_NEW_UI')).toBe(false)

    await store.loadPublicFlags()

    expect(store.isEnabled('FEATURE_NEW_UI')).toBe(true)
    expect(store.isEnabled('FEATURE_BETA')).toBe(false)
    expect(store.isEnabled('FEATURE_UNKNOWN')).toBe(false)
  })

  it('公開ロードが失敗してもエラーを握りつぶさず、既定値(false)を維持する', async () => {
    publicApiMock.getPublicFlags.mockRejectedValue(new Error('network error'))
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    const store = useFeatureFlagStore()
    await expect(store.loadPublicFlags()).rejects.toThrow('network error')

    expect(store.isEnabled('FEATURE_NEW_UI')).toBe(false)
    consoleErrorSpy.mockRestore()
  })
  it('同時多重呼び出しでも公開フラグ API は1回しか叩かない（in-flight 重複排除）', async () => {
    let resolveFetch: ((v: { flagKey: string, enabled: boolean }[]) => void) | undefined
    publicApiMock.getPublicFlags.mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve
      }),
    )

    const store = useFeatureFlagStore()
    const a = store.loadPublicFlags()
    const b = store.loadPublicFlags()
    const c = store.loadPublicFlags()

    resolveFetch!([{ flagKey: 'FEATURE_MARKET_ENABLED', enabled: true }])
    await Promise.all([a, b, c])

    expect(publicApiMock.getPublicFlags).toHaveBeenCalledTimes(1)
    expect(store.isEnabled('FEATURE_MARKET_ENABLED')).toBe(true)
    expect(store.publicLoaded).toBe(true)
  })

  it('取得に失敗しても in-flight を解放し、次回の再試行を封じない', async () => {
    publicApiMock.getPublicFlags.mockRejectedValueOnce(new Error('network error'))
    const store = useFeatureFlagStore()
    await expect(store.loadPublicFlags()).rejects.toThrow('network error')

    publicApiMock.getPublicFlags.mockResolvedValueOnce([
      { flagKey: 'FEATURE_MARKET_ENABLED', enabled: true },
    ])
    await store.loadPublicFlags()

    expect(publicApiMock.getPublicFlags).toHaveBeenCalledTimes(2)
    expect(store.isEnabled('FEATURE_MARKET_ENABLED')).toBe(true)
  })
})
