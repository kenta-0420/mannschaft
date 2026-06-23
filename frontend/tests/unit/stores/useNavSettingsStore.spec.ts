/**
 * F20.1 useNavSettingsStore のユニットテスト（個人ナビ並び替え）。
 *
 * - AC1-2: visibleFeatures は BE が返した features 配列順を尊重し、visible のみ絞り込む
 * - AC1-1: reorderNav の楽観更新 → updateNavSettings に並び順が渡る
 * - AC1-6: reorderNav 失敗時は元の順序にロールバックしトースト表示
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useNavSettingsStore } from '~/stores/useNavSettingsStore'
import type { NavFeatureItem } from '~/types/nav'

// === モック: useNavSettingsApi ===
const updateNavSettingsMock = vi.fn()
const getNavSettingsMock = vi.fn()
vi.mock('~/composables/useNavSettingsApi', () => ({
  useNavSettingsApi: () => ({
    getNavSettings: getNavSettingsMock,
    updateNavSettings: updateNavSettingsMock,
  }),
}))

// === モック: useNotification ===
const showErrorMock = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ showError: showErrorMock }),
}))

function makeFeature(overrides: Partial<NavFeatureItem> = {}): NavFeatureItem {
  return {
    key: 'todo',
    labelKey: 'nav.todo',
    icon: 'pi pi-check',
    path: '/todos',
    fixed: false,
    sortOrder: 10,
    mobileVisible: true,
    visible: true,
    ...overrides,
  }
}

describe('useNavSettingsStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    updateNavSettingsMock.mockReset()
    getNavSettingsMock.mockReset()
    showErrorMock.mockReset()
    if (typeof window !== 'undefined') window.localStorage?.clear()
  })

  describe('visibleFeatures (AC1-2: BE 配列順を尊重)', () => {
    it('features 配列順をそのまま採用し、再ソートしない', () => {
      const store = useNavSettingsStore()
      // sortOrder 昇順では a,b,c だが、配列順は c,a,b
      store.features = [
        makeFeature({ key: 'c', sortOrder: 30 }),
        makeFeature({ key: 'a', sortOrder: 10 }),
        makeFeature({ key: 'b', sortOrder: 20 }),
      ]
      expect(store.visibleFeatures.map(f => f.key)).toEqual(['c', 'a', 'b'])
    })

    it('visible=false は除外される', () => {
      const store = useNavSettingsStore()
      store.features = [
        makeFeature({ key: 'a', visible: true }),
        makeFeature({ key: 'b', visible: false }),
        makeFeature({ key: 'c', visible: true }),
      ]
      expect(store.visibleFeatures.map(f => f.key)).toEqual(['a', 'c'])
    })

    it('visibleMobileFeatures は mobileVisible=false を除外', () => {
      const store = useNavSettingsStore()
      store.features = [
        makeFeature({ key: 'a', mobileVisible: true }),
        makeFeature({ key: 'b', mobileVisible: false }),
      ]
      expect(store.visibleMobileFeatures.map(f => f.key)).toEqual(['a'])
    })
  })

  describe('reorderNav (AC1-1 / AC1-6)', () => {
    it('AC1-1: 並び替えで features 順が更新され、updateNavSettings に順序が渡る', async () => {
      const store = useNavSettingsStore()
      store.features = [
        makeFeature({ key: 'a' }),
        makeFeature({ key: 'b' }),
        makeFeature({ key: 'c' }),
      ]
      updateNavSettingsMock.mockResolvedValueOnce(undefined)

      await store.reorderNav(['c', 'a', 'b'])

      expect(store.features.map(f => f.key)).toEqual(['c', 'a', 'b'])
      expect(updateNavSettingsMock).toHaveBeenCalledWith(
        expect.any(Array),
        ['c', 'a', 'b'],
      )
    })

    it('欠落 key は末尾に維持される', async () => {
      const store = useNavSettingsStore()
      store.features = [
        makeFeature({ key: 'a' }),
        makeFeature({ key: 'b' }),
        makeFeature({ key: 'c' }),
      ]
      updateNavSettingsMock.mockResolvedValueOnce(undefined)

      await store.reorderNav(['b']) // a, c は省略

      expect(store.features.map(f => f.key)).toEqual(['b', 'a', 'c'])
    })

    it('AC1-6: 失敗時は元の順序にロールバックしトースト表示', async () => {
      const store = useNavSettingsStore()
      store.features = [
        makeFeature({ key: 'a' }),
        makeFeature({ key: 'b' }),
        makeFeature({ key: 'c' }),
      ]
      updateNavSettingsMock.mockRejectedValueOnce(new Error('boom'))

      await store.reorderNav(['c', 'b', 'a'])

      // ロールバックで元の順序に戻る
      expect(store.features.map(f => f.key)).toEqual(['a', 'b', 'c'])
      expect(showErrorMock).toHaveBeenCalledTimes(1)
    })
  })
})
