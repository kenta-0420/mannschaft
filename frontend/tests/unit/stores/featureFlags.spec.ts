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
})
