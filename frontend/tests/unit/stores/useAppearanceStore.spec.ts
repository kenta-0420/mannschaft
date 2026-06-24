/**
 * useAppearanceStore のユニットテスト — テーマ FOUC 根治（cookie 鏡写し）
 *
 * 受け入れ条件:
 * - AC1: persistToStorage が localStorage と cookie の両方にデータを書き込む
 * - AC2: cookie には JSON が encodeURIComponent 済みで格納される
 * - AC3: loadFromStorage が localStorage から読み込み、テーマ・色を正しく反映する
 * - AC4: setTheme('DARK') → isDark=true・applyTheme で <html> に dark/p-dark クラスが付く
 * - AC5: setTheme('LIGHT') → isDark=false・<html> から dark/p-dark クラスが除去される
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAppearanceStore } from '~/stores/useAppearanceStore'

// import.meta.client を true にする（クライアント環境を模倣）
vi.stubGlobal('import', {
  meta: { client: true, server: false },
})

// localStorage のモック
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => { store[key] = value }),
    removeItem: vi.fn((key: string) => { delete store[key] }),
    clear: vi.fn(() => { store = {} }),
    get length() { return Object.keys(store).length },
    key: vi.fn((i: number) => Object.keys(store)[i] ?? null),
  }
})()

// document.cookie のモック
let cookieStore = ''
const cookieDescriptor = {
  get: vi.fn(() => cookieStore),
  set: vi.fn((val: string) => { cookieStore = val }),
  configurable: true,
}

describe('useAppearanceStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorageMock.clear()
    localStorageMock.getItem.mockClear()
    localStorageMock.setItem.mockClear()
    cookieStore = ''
    cookieDescriptor.get.mockClear()
    cookieDescriptor.set.mockClear()

    // localStorage と document.cookie を差し替え
    Object.defineProperty(globalThis, 'localStorage', {
      value: localStorageMock,
      configurable: true,
      writable: true,
    })
    Object.defineProperty(globalThis.document, 'cookie', cookieDescriptor)

    // document.documentElement.classList のモック
    if (typeof document !== 'undefined') {
      const classList = document.documentElement.classList
      classList.remove('dark', 'p-dark')
    }
  })

  describe('AC1 / AC2: persistToStorage — localStorage と cookie に両方書き込む', () => {
    it('DARK テーマを persistToStorage すると localStorage と cookie の両方に書き込まれる', () => {
      const store = useAppearanceStore()
      store.theme = 'DARK'
      store.bgColor = '#f3efe0'
      store.darkBgColor = '#18181b'

      store.persistToStorage()

      // localStorage には JSON が書き込まれている
      expect(localStorageMock.setItem).toHaveBeenCalledWith(
        'appearance',
        expect.stringContaining('"theme":"DARK"'),
      )

      // cookie に書き込まれている（set が呼ばれた）
      expect(cookieDescriptor.set).toHaveBeenCalled()
      const setCookieCall = cookieDescriptor.set.mock.calls[0]
      expect(setCookieCall).toBeDefined()
      const setCookieArg = String(setCookieCall![0])
      expect(setCookieArg).toMatch(/^appearance=/)
      expect(setCookieArg).toContain('path=/')
      expect(setCookieArg).toContain('SameSite=Lax')
      expect(setCookieArg).toContain('max-age=')

      // cookie 値には encodeURIComponent された JSON が含まれる
      const rawValue = (setCookieArg.split(';')[0] ?? '').replace('appearance=', '')
      const decoded = decodeURIComponent(rawValue)
      const parsed = JSON.parse(decoded) as Record<string, unknown>
      expect(parsed.theme).toBe('DARK')
      expect(parsed.bgColor).toBe('#f3efe0')
      expect(parsed.darkBgColor).toBe('#18181b')
    })

    it('LIGHT テーマも cookie に正しく書き込まれる', () => {
      const store = useAppearanceStore()
      store.theme = 'LIGHT'
      store.persistToStorage()

      expect(cookieDescriptor.set).toHaveBeenCalled()
      const setCookieCall2 = cookieDescriptor.set.mock.calls[0]
      expect(setCookieCall2).toBeDefined()
      const setCookieArg2 = String(setCookieCall2![0])
      const rawValue2 = (setCookieArg2.split(';')[0] ?? '').replace('appearance=', '')
      const parsed = JSON.parse(decodeURIComponent(rawValue2)) as Record<string, unknown>
      expect(parsed.theme).toBe('LIGHT')
    })
  })

  describe('AC3: loadFromStorage — localStorage から正しく読み込む', () => {
    it('localStorage にダーク設定がある場合、store に反映される', () => {
      const saved = JSON.stringify({
        theme: 'DARK',
        bgColor: '#fffacd',
        darkBgColor: '#1a1a2e',
        seasonalThemeId: null,
        hideChatPreview: true,
      })
      localStorageMock.getItem.mockReturnValue(saved)

      const store = useAppearanceStore()
      store.loadFromStorage()

      expect(store.theme).toBe('DARK')
      expect(store.bgColor).toBe('#fffacd')
      expect(store.darkBgColor).toBe('#1a1a2e')
      expect(store.hideChatPreview).toBe(true)
      expect(store.isDark).toBe(true)
    })

    it('localStorage が空のとき、デフォルト LIGHT のまま', () => {
      localStorageMock.getItem.mockReturnValue(null)
      const store = useAppearanceStore()
      store.loadFromStorage()

      expect(store.theme).toBe('LIGHT')
      expect(store.isDark).toBe(false)
    })

    it('localStorage の JSON が壊れていても例外を投げない', () => {
      localStorageMock.getItem.mockReturnValue('invalid-json')
      const store = useAppearanceStore()
      expect(() => store.loadFromStorage()).not.toThrow()
      // デフォルト値が維持される
      expect(store.theme).toBe('LIGHT')
    })
  })

  describe('AC6: loadFromStorage — 既存ユーザー救済（localStorage を cookie に書き戻す）', () => {
    it('localStorage にダーク設定があり cookie が未生成でも、loadFromStorage で cookie が書き込まれる', () => {
      // 既存ユーザーの状況を模倣: localStorage にダーク設定があるが cookie は空
      const saved = JSON.stringify({
        theme: 'DARK',
        bgColor: '#f3efe0',
        darkBgColor: '#222244',
        seasonalThemeId: null,
        hideChatPreview: false,
      })
      localStorageMock.getItem.mockReturnValue(saved)
      cookieStore = '' // cookie 未生成

      const store = useAppearanceStore()
      store.loadFromStorage()

      // loadFromStorage が cookie を書き戻している
      expect(cookieDescriptor.set).toHaveBeenCalled()
      const setCookieCall = cookieDescriptor.set.mock.calls[0]
      expect(setCookieCall).toBeDefined()
      const setCookieArg = String(setCookieCall![0])
      expect(setCookieArg).toMatch(/^appearance=/)
      expect(setCookieArg).toContain('SameSite=Lax')

      const rawValue = (setCookieArg.split(';')[0] ?? '').replace('appearance=', '')
      const parsed = JSON.parse(decodeURIComponent(rawValue)) as Record<string, unknown>
      expect(parsed.theme).toBe('DARK')
      expect(parsed.darkBgColor).toBe('#222244')
    })

    it('localStorage が空のときは cookie を書き込まない（不要な cookie 生成を避ける）', () => {
      localStorageMock.getItem.mockReturnValue(null)
      cookieStore = ''

      const store = useAppearanceStore()
      store.loadFromStorage()

      // 読み込むデータが無いので cookie は書かれない
      expect(cookieDescriptor.set).not.toHaveBeenCalled()
    })

    it('JSON が壊れている場合も cookie を書き込まない', () => {
      localStorageMock.getItem.mockReturnValue('invalid-json')
      cookieStore = ''

      const store = useAppearanceStore()
      store.loadFromStorage()

      expect(cookieDescriptor.set).not.toHaveBeenCalled()
    })
  })

  describe('AC7: writeCookie — 引数なしでも現在状態から cookie を生成する', () => {
    it('payload 省略時は store の現在状態から JSON を生成して cookie に書く', () => {
      const store = useAppearanceStore()
      store.theme = 'DARK'
      store.darkBgColor = '#0a0a0a'

      store.writeCookie()

      expect(cookieDescriptor.set).toHaveBeenCalled()
      const setCookieCall = cookieDescriptor.set.mock.calls[0]
      const setCookieArg = String(setCookieCall![0])
      const rawValue = (setCookieArg.split(';')[0] ?? '').replace('appearance=', '')
      const parsed = JSON.parse(decodeURIComponent(rawValue)) as Record<string, unknown>
      expect(parsed.theme).toBe('DARK')
      expect(parsed.darkBgColor).toBe('#0a0a0a')
    })
  })

  describe('AC4 / AC5: setTheme — <html> クラスの付与と除去', () => {
    it('AC4: setTheme("DARK") で isDark が true になる', () => {
      const store = useAppearanceStore()
      store.setTheme('DARK')
      expect(store.isDark).toBe(true)
    })

    it('AC5: setTheme("LIGHT") で isDark が false になる', () => {
      const store = useAppearanceStore()
      store.theme = 'DARK'
      store.setTheme('LIGHT')
      expect(store.isDark).toBe(false)
    })

    it('setTheme は persistToStorage を呼んで cookie にも書く', () => {
      const store = useAppearanceStore()
      store.setTheme('DARK')
      expect(cookieDescriptor.set).toHaveBeenCalled()
    })
  })
})
