// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const fixture = vi.hoisted(() => ({
  auth: { accessToken: null as string | null, isAuthenticated: true },
  desk: { isPinned: false, pinnedSubjectUserId: 90245, pinnedConsentId: 2,
    inputSource: 'PAPER_FORM', originalStorageLocation: '' },
  guardian: { isActingAs: false, activeChild: { childUserId: 17 } },
  impersonation: { isImpersonating: false, targetUserId: 19 },
  onRequest: undefined as undefined | ((context: { options: { headers?: Headers } }) => void),
}))
vi.mock('ofetch', () => ({ ofetch: { create: vi.fn((options) => {
  fixture.onRequest = options.onRequest
  return vi.fn()
}) } }))
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://localhost:8081' }))
// 標準Nuxt変換のauto-importも、同じ境界fixtureへ束縛する。無関係なChat/DOM依存は読み込まない。
vi.mock('~/stores/useAuthStore', () => ({ useAuthStore: () => fixture.auth }))
vi.mock('~/stores/useProxyDeskStore', () => ({ useProxyDeskStore: () => fixture.desk }))
vi.mock('~/stores/useGuardianshipSwitchStore', () => ({ useGuardianshipSwitchStore: () => fixture.guardian }))
vi.mock('~/stores/useAdminImpersonationStore', () => ({ useAdminImpersonationStore: () => fixture.impersonation }))
vi.mock('~/stores/usePaywallStore', () => ({ usePaywallStore: () => ({ open: vi.fn() }) }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({}) }))
vi.mock('#app/nuxt', () => ({
  useRuntimeConfig: () => ({ public: {} }),
  useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }),
}))
const { useApi } = await import('./useApi')

describe('Cookie認証と代理入力ヘッダーの実生成境界', () => {
  const { auth, desk, guardian, impersonation } = fixture

  beforeEach(() => {
    auth.accessToken = null
    auth.isAuthenticated = true
    Object.assign(desk, { isPinned: false, originalStorageLocation: '' })
    guardian.isActingAs = false
    impersonation.isImpersonating = false
    vi.stubGlobal('useRuntimeConfig', () => ({ public: {} }))
    vi.stubGlobal('useAuthStore', () => auth)
    vi.stubGlobal('useProxyDeskStore', () => desk)
    vi.stubGlobal('useGuardianshipSwitchStore', () => guardian)
    vi.stubGlobal('useAdminImpersonationStore', () => impersonation)
    vi.stubGlobal('useNuxtApp', () => ({ $i18n: { t: (key: string) => key } }))
    vi.stubGlobal('useErrorReport', () => ({}))
    fixture.onRequest = undefined
  })
  afterEach(() => vi.unstubAllGlobals())

  function generate(initialHeaders?: Headers) {
    useApi()
    const options: { headers?: Headers } = { headers: initialHeaders }
    fixture.onRequest!({ options })
    return new Headers(options.headers)
  }

  it('Cookieのみで認証済みでも紙ピン留めの本人・同意・入力元・原本を付ける', () => {
    desk.isPinned = true
    desk.originalStorageLocation = 'paper/2026/copy.pdf'
    const headers = generate()
    expect(headers.get('X-Proxy-For-User-Id')).toBe('90245')
    expect(headers.get('X-Proxy-Consent-Id')).toBe('2')
    expect(headers.get('X-Proxy-Input-Source')).toBe('PAPER_FORM')
    expect(headers.get('X-Proxy-Original-Storage')).toBe('paper/2026/copy.pdf')
    expect(headers.has('Authorization')).toBe(false)
  })

  it('Bearer認証の日本語原本名でも実Headers生成を例外にしない', () => {
    auth.accessToken = 'unit-test-token'
    desk.isPinned = true
    desk.originalStorageLocation = '紙原本/2026年/控え.pdf'
    expect(() => generate()).not.toThrow()
    const headers = generate()
    expect(headers.get('X-Proxy-Original-Storage')).toBe(encodeURIComponent(desk.originalStorageLocation))
    expect(headers.get('X-Proxy-Original-Storage-Encoding')).toBe('uri-component')
  })

  it('ログアウト後は残った紙ピン留めをログインリクエストへ付けない', () => {
    auth.isAuthenticated = false
    desk.isPinned = true
    desk.originalStorageLocation = '紙原本/控え.pdf'
    const headers = generate()
    for (const name of ['Authorization', 'X-Proxy-For-User-Id', 'X-Proxy-Consent-Id',
      'X-Proxy-Input-Source', 'X-Proxy-Original-Storage', 'X-Proxy-Original-Storage-Encoding',
      'X-Admin-Impersonate-User-Id']) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it('通常本人のCookieリクエストへ代理・変身ヘッダーを付けない', () => {
    const headers = generate()
    for (const name of ['Authorization', 'X-Proxy-For-User-Id', 'X-Proxy-Consent-Id',
      'X-Proxy-Input-Source', 'X-Proxy-Original-Storage', 'X-Admin-Impersonate-User-Id']) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it('紙ピン留めは既存の後見切替・管理者変身より優先する', () => {
    auth.accessToken = 'unit-test-token'
    desk.isPinned = true
    desk.originalStorageLocation = 'paper.pdf'
    guardian.isActingAs = true
    impersonation.isImpersonating = true
    const headers = generate()
    expect(headers.get('Authorization')).toBe('Bearer unit-test-token')
    expect(headers.get('X-Proxy-For-User-Id')).toBe('90245')
    expect(headers.has('X-Admin-Impersonate-User-Id')).toBe(false)
  })

  it('後見切替は管理者変身より優先し同意・紙入力ヘッダーを付けない', () => {
    auth.accessToken = 'unit-test-token'
    guardian.isActingAs = true
    impersonation.isImpersonating = true
    const headers = generate()
    expect(headers.get('X-Proxy-For-User-Id')).toBe('17')
    for (const name of ['X-Proxy-Consent-Id', 'X-Proxy-Input-Source', 'X-Proxy-Original-Storage', 'X-Admin-Impersonate-User-Id']) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it('紙・後見のない管理者変身は既存変身ヘッダーだけを付ける', () => {
    auth.accessToken = 'unit-test-token'
    impersonation.isImpersonating = true
    const headers = generate()
    expect(headers.get('X-Admin-Impersonate-User-Id')).toBe('19')
    expect(headers.has('X-Proxy-For-User-Id')).toBe(false)
  })

  it('ASCIIの既存原本名はpercentとplusを変換せずmarkerを付けない', () => {
    auth.accessToken = 'unit-test-token'
    desk.isPinned = true
    desk.originalStorageLocation = 'paper%2F2026+copy.pdf'
    const headers = generate()
    expect(headers.get('X-Proxy-Original-Storage')).toBe('paper%2F2026+copy.pdf')
    expect(headers.has('X-Proxy-Original-Storage-Encoding')).toBe(false)
  })

  it('日本語からASCIIへ切り替えたrequestのencoding markerを残さない', () => {
    auth.accessToken = 'unit-test-token'
    desk.isPinned = true
    desk.originalStorageLocation = 'paper+copy.pdf'
    const previous = new Headers({ 'X-Proxy-Original-Storage': '%E7%B4%99',
      'X-Proxy-Original-Storage-Encoding': 'uri-component' })
    const headers = generate(previous)
    expect(headers.get('X-Proxy-Original-Storage')).toBe('paper+copy.pdf')
    expect(headers.has('X-Proxy-Original-Storage-Encoding')).toBe(false)
  })

  it('後見だけのCookie認証へ今回の紙代理対応を拡張しない', () => {
    guardian.isActingAs = true
    expect(generate().has('X-Proxy-For-User-Id')).toBe(false)
  })

  it('管理者変身だけのCookie認証へ今回の紙代理対応を拡張しない', () => {
    impersonation.isImpersonating = true
    expect(generate().has('X-Admin-Impersonate-User-Id')).toBe(false)
  })
})
