/**
 * useAppShellStore のユニットテスト — サイドバー化 Phase1
 *
 * 受け入れ条件:
 * - AC13: isRail の優先順位（userCollapsed / forceRail の4象限）
 *   スコープページの自動レール(forceRail) ＞ 個人ページの手動記憶(userCollapsed)
 * - AC14: loadFromStorage が localStorage['app-shell'] から userCollapsed を復元する
 * - AC17: 壊れた JSON・想定外の型は既定値（展開 = false）にフォールバックし例外を投げない
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAppShellStore } from '~/stores/useAppShellStore'

vi.stubGlobal('import', {
  meta: { client: true, server: false },
})

const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => { store[key] = value }),
    removeItem: vi.fn((key: string) => { Reflect.deleteProperty(store, key) }),
    clear: vi.fn(() => { store = {} }),
    get length() { return Object.keys(store).length },
    key: vi.fn((i: number) => Object.keys(store)[i] ?? null),
  }
})()

describe('useAppShellStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorageMock.clear()
    localStorageMock.getItem.mockClear()
    localStorageMock.setItem.mockClear()
    Object.defineProperty(globalThis, 'localStorage', {
      value: localStorageMock,
      configurable: true,
      writable: true,
    })
  })

  describe('AC13: isRail — userCollapsed/forceRail の4象限（優先順位: forceRail ＞ userCollapsed）', () => {
    it('両方 false のとき isRail は false（展開）', () => {
      const store = useAppShellStore()
      store.userCollapsed = false
      store.forceRail = false
      expect(store.isRail).toBe(false)
    })

    it('userCollapsed のみ true のとき isRail は true（個人ページの手動記憶）', () => {
      const store = useAppShellStore()
      store.userCollapsed = true
      store.forceRail = false
      expect(store.isRail).toBe(true)
    })

    it('forceRail のみ true のとき isRail は true（スコープページの自動レール）', () => {
      const store = useAppShellStore()
      store.userCollapsed = false
      store.forceRail = true
      expect(store.isRail).toBe(true)
    })

    it('両方 true のとき isRail は true（forceRail が userCollapsed=false でも優先して勝つ）', () => {
      const store = useAppShellStore()
      store.userCollapsed = true
      store.forceRail = true
      expect(store.isRail).toBe(true)
    })
  })

  describe('toggleUserCollapsed / setForceRail', () => {
    it('toggleUserCollapsed は userCollapsed を反転し永続化する', () => {
      const store = useAppShellStore()
      expect(store.userCollapsed).toBe(false)
      store.toggleUserCollapsed()
      expect(store.userCollapsed).toBe(true)
      expect(localStorageMock.setItem).toHaveBeenCalledWith('app-shell', JSON.stringify({ userCollapsed: true }))
    })

    it('setForceRail は forceRail のみ変更し永続化しない（一時状態）', () => {
      const store = useAppShellStore()
      store.setForceRail(true)
      expect(store.forceRail).toBe(true)
      expect(localStorageMock.setItem).not.toHaveBeenCalled()
    })
  })

  describe('AC14: loadFromStorage — localStorage から userCollapsed を復元する', () => {
    it('保存済みの userCollapsed=true を復元する', () => {
      localStorageMock.getItem.mockReturnValue(JSON.stringify({ userCollapsed: true }))
      const store = useAppShellStore()
      store.loadFromStorage()
      expect(store.userCollapsed).toBe(true)
    })

    it('保存済みの userCollapsed=false を復元する', () => {
      localStorageMock.getItem.mockReturnValue(JSON.stringify({ userCollapsed: false }))
      const store = useAppShellStore()
      store.userCollapsed = true
      store.loadFromStorage()
      expect(store.userCollapsed).toBe(false)
    })

    it('localStorage が空のときは既定値（false）のまま', () => {
      localStorageMock.getItem.mockReturnValue(null)
      const store = useAppShellStore()
      store.loadFromStorage()
      expect(store.userCollapsed).toBe(false)
    })

    it('forceRail・mobileDrawerOpen は永続化対象に含まれない', () => {
      localStorageMock.getItem.mockReturnValue(JSON.stringify({ userCollapsed: true, forceRail: true, mobileDrawerOpen: true }))
      const store = useAppShellStore()
      store.loadFromStorage()
      expect(store.userCollapsed).toBe(true)
      expect(store.forceRail).toBe(false)
      expect(store.mobileDrawerOpen).toBe(false)
    })
  })

  describe('AC17: 壊れた JSON・想定外の型は既定値（展開）にフォールバックする', () => {
    it('壊れた JSON でも例外を投げず、既定値 false のまま', () => {
      localStorageMock.getItem.mockReturnValue('not-valid-json{{{')
      const store = useAppShellStore()
      expect(() => store.loadFromStorage()).not.toThrow()
      expect(store.userCollapsed).toBe(false)
    })

    it('userCollapsed が真偽値以外（文字列）のときは既定値 false にフォールバック', () => {
      localStorageMock.getItem.mockReturnValue(JSON.stringify({ userCollapsed: 'yes' }))
      const store = useAppShellStore()
      store.loadFromStorage()
      expect(store.userCollapsed).toBe(false)
    })

    it('userCollapsed が数値のときは既定値 false にフォールバック', () => {
      localStorageMock.getItem.mockReturnValue(JSON.stringify({ userCollapsed: 1 }))
      const store = useAppShellStore()
      store.loadFromStorage()
      expect(store.userCollapsed).toBe(false)
    })

    it('userCollapsed キー自体が存在しないときは既定値 false にフォールバック', () => {
      localStorageMock.getItem.mockReturnValue(JSON.stringify({}))
      const store = useAppShellStore()
      store.loadFromStorage()
      expect(store.userCollapsed).toBe(false)
    })
  })
})
