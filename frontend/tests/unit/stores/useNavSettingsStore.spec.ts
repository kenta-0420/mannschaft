/**
 * F20.1 useNavSettingsStore のユニットテスト（個人ナビ並び替え）。
 *
 * - AC1-2: visibleFeatures は BE が返した features 配列順を尊重し、visible のみ絞り込む
 * - AC1-1: reorderNav の楽観更新 → updateNavSettings に並び順が渡る
 * - AC1-6: reorderNav 失敗時は元の順序にロールバックしトースト表示
 * - 根治(2026-07-15): loadFromServer が応答の features を無検証で代入していたため、
 *   features が配列でない不正応答（スキーマドリフト等）を受け取ると
 *   visibleFeatures/visibleMobileFeatures getter が `state.features.filter is not a function`
 *   でクラッシュし、サイドバー全体が死ぬ（全ページ影響）バグがあった。
 *   features が配列でない場合は既存の localStorage フォールバック値を温存する。
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

  describe('loadFromServer（根治: features 不正応答での visibleFeatures クラッシュ防止）', () => {
    it('features が配列の正常応答では従来通り上書きされ localStorage にも永続化される', async () => {
      const store = useNavSettingsStore()
      store.features = [makeFeature({ key: 'old' })]
      getNavSettingsMock.mockResolvedValueOnce({
        features: [makeFeature({ key: 'new' })],
      })

      await store.loadFromServer()

      expect(store.features.map(f => f.key)).toEqual(['new'])
      expect(store.loaded).toBe(true)
    })

    it('features が undefined の不正応答では localStorage フォールバック値を維持しクラッシュしない', async () => {
      const store = useNavSettingsStore()
      const fallback = [makeFeature({ key: 'fallback-a' }), makeFeature({ key: 'fallback-b' })]
      store.features = fallback
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
      getNavSettingsMock.mockResolvedValueOnce({ features: undefined })

      await expect(store.loadFromServer()).resolves.toBeUndefined()

      // フォールバック値を温存（上書きしない）
      expect(store.features).toEqual(fallback)
      expect(store.loaded).toBe(true)
      // クラッシュせず空配列/フォールバック値を返す
      expect(() => store.visibleFeatures).not.toThrow()
      expect(store.visibleFeatures.map(f => f.key)).toEqual(['fallback-a', 'fallback-b'])
      // エラーは握りつぶさず記録する
      expect(warnSpy).toHaveBeenCalledTimes(1)
      warnSpy.mockRestore()
    })

    it('features が null / 非配列（オブジェクト）の不正応答でも同様にフォールバックする', async () => {
      const store = useNavSettingsStore()
      const fallback = [makeFeature({ key: 'fallback-a' })]
      store.features = fallback
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
      // BE スキーマドリフトを想定した非配列応答（object）
      getNavSettingsMock.mockResolvedValueOnce({ features: { unexpected: 'shape' } })

      await store.loadFromServer()

      expect(store.features).toEqual(fallback)
      expect(() => store.visibleFeatures).not.toThrow()
      warnSpy.mockRestore()
    })

    it('features が空配列の応答時は fallback を維持せず素直に空配列を採用する', async () => {
      const store = useNavSettingsStore()
      store.features = [makeFeature({ key: 'old' })]
      getNavSettingsMock.mockResolvedValueOnce({ features: [] })

      await store.loadFromServer()

      // 空配列は Array.isArray なので正常応答として採用される（機能が0件という正当な状態）
      expect(store.features).toEqual([])
      expect(() => store.visibleFeatures).not.toThrow()
    })
  })
})
