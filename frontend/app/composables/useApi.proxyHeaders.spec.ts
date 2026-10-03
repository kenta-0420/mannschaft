// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from 'vitest'

const captured = vi.hoisted(() => ({ onRequest: undefined as undefined | ((context: { options: { headers?: Headers } }) => void) }))
vi.mock('ofetch', () => ({ ofetch: { create: vi.fn((options) => {
  captured.onRequest = options.onRequest
  return vi.fn()
}) } }))
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://localhost:8081' }))
const { useApi } = await import('./useApi')

describe('Cookie認証と代理入力ヘッダーの実生成境界', () => {
  const auth = { accessToken: null as string | null }
  const desk = { isPinned: false, pinnedSubjectUserId: 90245, pinnedConsentId: 2,
    inputSource: 'PAPER_FORM', originalStorageLocation: '' }
  const guardian = { isActingAs: false, activeChild: { childUserId: 17 } }
  const impersonation = { isImpersonating: false, targetUserId: 19 }

  beforeEach(() => {
    auth.accessToken = null
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
    captured.onRequest = undefined
  })

  function generate() {
    useApi()
    const options: { headers?: Headers } = {}
    captured.onRequest!({ options })
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
  })

  it('通常本人のCookieリクエストへ代理・変身ヘッダーを付けない', () => {
    const headers = generate()
    for (const name of ['Authorization', 'X-Proxy-For-User-Id', 'X-Proxy-Consent-Id',
      'X-Proxy-Input-Source', 'X-Proxy-Original-Storage', 'X-Admin-Impersonate-User-Id']) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it('紙ピン留めは既存の親見切替・管理者変身より優先する', () => {
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

  it('親見切替は管理者変身より優先し同意・紙入力ヘッダーを付けない', () => {
    auth.accessToken = 'unit-test-token'
    guardian.isActingAs = true
    impersonation.isImpersonating = true
    const headers = generate()
    expect(headers.get('X-Proxy-For-User-Id')).toBe('17')
    for (const name of ['X-Proxy-Consent-Id', 'X-Proxy-Input-Source', 'X-Proxy-Original-Storage', 'X-Admin-Impersonate-User-Id']) {
      expect(headers.has(name)).toBe(false)
    }
  })

  it('紙・親見のない管理者変身は既存変身ヘッダーだけを付ける', () => {
    auth.accessToken = 'unit-test-token'
    impersonation.isImpersonating = true
    const headers = generate()
    expect(headers.get('X-Admin-Impersonate-User-Id')).toBe('19')
    expect(headers.has('X-Proxy-For-User-Id')).toBe(false)
  })
})
