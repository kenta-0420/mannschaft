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

  describe('Phase2 AC-13/AC-14: isRail — scopeExpanded ＞ forceRail ＞ userCollapsed の優先順位（全8象限）', () => {
    it.each([
      // [userCollapsed, forceRail, scopeExpanded, expected isRail, 説明]
      [false, false, false, false, '個人ページ・展開のまま'],
      [true, false, false, true, '個人ページの手動記憶のみでレール'],
      [false, true, false, true, 'スコープページの自動レール（既定）'],
      [true, true, false, true, '自動レールが手動記憶と一致してレール'],
      [false, true, true, false, 'スコープページの一時展開で自動レールを上書き'],
      [true, true, true, false, '一時展開は userCollapsed=true でも forceRail より優先して展開'],
      [false, false, true, false, 'forceRail=false 時は scopeExpanded を無視（personalCollapsed=falseのまま展開）'],
      [true, false, true, true, 'forceRail=false 時は scopeExpanded を無視し userCollapsed の記憶が勝つ'],
    ])(
      'userCollapsed=%s, forceRail=%s, scopeExpanded=%s → isRail=%s（%s）',
      (userCollapsed, forceRail, scopeExpanded, expected) => {
        const store = useAppShellStore()
        store.userCollapsed = userCollapsed
        store.forceRail = forceRail
        store.scopeExpanded = scopeExpanded
        expect(store.isRail).toBe(expected)
      },
    )
  })

  describe('Phase2: setForceRail はスコープ出入りのたび scopeExpanded をリセットする', () => {
    it('true をセットすると scopeExpanded は false に戻る（自動収縮が既定で再開される）', () => {
      const store = useAppShellStore()
      store.scopeExpanded = true
      store.setForceRail(true)
      expect(store.forceRail).toBe(true)
      expect(store.scopeExpanded).toBe(false)
    })

    it('false をセットしても scopeExpanded は false に戻る（スコープ退出時の取り残し防止）', () => {
      const store = useAppShellStore()
      store.forceRail = true
      store.scopeExpanded = true
      store.setForceRail(false)
      expect(store.forceRail).toBe(false)
      expect(store.scopeExpanded).toBe(false)
    })

    it('setForceRail は永続化しない', () => {
      const store = useAppShellStore()
      store.setForceRail(true)
      expect(localStorageMock.setItem).not.toHaveBeenCalled()
    })
  })

  describe('Phase2: toggleScopeExpanded / setScopeExpanded — 一時展開は永続化しない', () => {
    it('toggleScopeExpanded は scopeExpanded を反転し永続化しない', () => {
      const store = useAppShellStore()
      expect(store.scopeExpanded).toBe(false)
      store.toggleScopeExpanded()
      expect(store.scopeExpanded).toBe(true)
      store.toggleScopeExpanded()
      expect(store.scopeExpanded).toBe(false)
      expect(localStorageMock.setItem).not.toHaveBeenCalled()
    })

    it('setScopeExpanded は指定値をそのまま反映する', () => {
      const store = useAppShellStore()
      store.setScopeExpanded(true)
      expect(store.scopeExpanded).toBe(true)
      expect(localStorageMock.setItem).not.toHaveBeenCalled()
    })
  })

  describe('Phase2 AC-14: togglePanel — ヘッダーのパネルボタンから呼ぶ統一エントリ', () => {
    it('forceRail=false（個人ページ）のときは userCollapsed をトグルし永続化する', () => {
      const store = useAppShellStore()
      store.togglePanel()
      expect(store.userCollapsed).toBe(true)
      expect(store.scopeExpanded).toBe(false)
      expect(localStorageMock.setItem).toHaveBeenCalledWith('app-shell', JSON.stringify({ userCollapsed: true }))
    })

    it('forceRail=true（スコープページ）のときは scopeExpanded をトグルし永続化しない', () => {
      const store = useAppShellStore()
      store.forceRail = true
      store.togglePanel()
      expect(store.scopeExpanded).toBe(true)
      expect(store.userCollapsed).toBe(false)
      expect(localStorageMock.setItem).not.toHaveBeenCalled()
      // もう一度押すと一時展開が閉じてレールに戻る
      store.togglePanel()
      expect(store.scopeExpanded).toBe(false)
      expect(store.isRail).toBe(true)
    })
  })
})
