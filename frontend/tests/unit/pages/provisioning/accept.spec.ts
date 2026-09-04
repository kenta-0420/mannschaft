import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick } from 'vue'

/**
 * pages/provisioning/accept.vue の UT（柱②-2 販促プロビジョニング）。
 *
 * 検証観点:
 *   ACCEPT-001: URLフラグメント（#token=...）からトークンを読み取り preview を呼ぶ
 *   ACCEPT-002: フラグメントが無く sessionStorage にも無ければ notFound エラー表示（preview を呼ばない）
 *   ACCEPT-003: preview 成功後、承諾ボタン押下で accept を呼び成功表示へ遷移する
 *   ACCEPT-004: preview が 500 等で失敗した場合、再試行可能な previewError 表示になり、
 *               accept() は一切呼ばれない（検分 P0: resolveViaAccept フォールバック全廃の再発防止）
 *   ACCEPT-005: previewError 状態で再読み込みボタンを押すと preview を再試行する
 *   ACCEPT-006: 承諾ボタン押下で accept() が実際のエラーコード（PROV_002 期限切れ）を返した場合、
 *               対応するエラー表示に遷移する
 *   ACCEPT-007: 承諾ボタン押下で accept() が PROV_003（取消済み）を返した場合の表示
 *   ACCEPT-008: 承諾ボタン押下で accept() が PROV_006（メール不一致）を返した場合の表示
 *   ACCEPT-009: 承諾ボタン押下で accept() が PROV_010（承諾者本人以外による再承諾・存在秘匿）
 *               を返した場合、notFound 表示に畳む
 *   ACCEPT-010/011: ログイン往復のトークン退避 — sessionStorage に保存されたトークンから復元して
 *               preview を呼び、使用後は sessionStorage から即座に削除する（P1 検分対応）
 *   ACCEPT-012: フラグメントにトークンがある場合は sessionStorage より優先する
 */

const mockPreview = vi.fn()
const mockAccept = vi.fn()
vi.mock('~/composables/useProvisioningInvitationApi', () => ({
  useProvisioningInvitationApi: () => ({
    preview: mockPreview,
    accept: mockAccept,
  }),
}))

const mockNotifyError = vi.fn()
const mockNotifySuccess = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    error: mockNotifyError,
    success: mockNotifySuccess,
    info: vi.fn(),
    warn: vi.fn(),
  }),
}))

vi.mock('~/composables/useDatetime', () => ({
  useDatetime: () => ({
    formatDateTime: (v: string) => v,
    userTimezone: { value: 'Asia/Tokyo' },
  }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    isAuthenticated: true,
    isSystemAdmin: false,
    loadFromStorage: vi.fn(),
  }),
}))

mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const routeState = { path: '/provisioning/accept', params: {}, query: {} }
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('useRouter', () => () => ({
  replace: vi.fn().mockResolvedValue(undefined),
  push: vi.fn().mockResolvedValue(undefined),
  go: vi.fn(),
  back: vi.fn(),
  forward: vi.fn(),
  beforeEach: vi.fn().mockReturnValue(vi.fn()),
  afterEach: vi.fn().mockReturnValue(vi.fn()),
  beforeResolve: vi.fn().mockReturnValue(vi.fn()),
  onError: vi.fn().mockReturnValue(vi.fn()),
  addRoute: vi.fn(),
  removeRoute: vi.fn(),
  hasRoute: vi.fn().mockReturnValue(false),
  getRoutes: vi.fn().mockReturnValue([]),
  resolve: vi.fn().mockReturnValue({ href: '/', fullPath: '/', matched: [], params: {}, query: {}, hash: '', meta: {}, name: undefined, redirectedFrom: undefined }),
  currentRoute: { value: routeState },
  isReady: vi.fn().mockResolvedValue(undefined),
}))

const AcceptPage = (await import('~/pages/provisioning/accept.vue')).default

const TOKEN_STORAGE_KEY = 'provisioning_accept_token'

async function flush(times = 4): Promise<void> {
  for (let i = 0; i < times; i++) await nextTick()
}

function setHash(hash: string): void {
  window.location.hash = hash
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockPreview.mockReset()
  mockAccept.mockReset()
  mockNotifyError.mockReset()
  mockNotifySuccess.mockReset()
  setHash('')
  window.sessionStorage.clear()
})

describe('pages/provisioning/accept.vue', () => {
  it('ACCEPT-001: フラグメントのトークンで preview を呼ぶ', async () => {
    setHash('#token=abc-123')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(mockPreview).toHaveBeenCalledWith('abc-123')
    expect(wrapper.text()).toContain('サンプル組織')
  })

  it('ACCEPT-002: フラグメントが無く sessionStorageにも無ければ preview を呼ばずnotFoundエラー表示', async () => {
    setHash('')

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(mockPreview).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('provisioning.accept.errors.notFoundTitle')
  })

  it('ACCEPT-003: 承諾ボタン押下でacceptを呼び成功表示へ遷移する', async () => {
    setHash('#token=abc-123')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })
    mockAccept.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      status: 'ACCEPTED',
    })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    await wrapper.get('[data-testid="provisioning-accept-button"]').trigger('click')
    await flush()

    expect(mockAccept).toHaveBeenCalledWith('abc-123')
    expect(mockNotifySuccess).toHaveBeenCalled()
    expect(wrapper.text()).toContain('サンプル組織')
    expect(wrapper.text()).toContain('provisioning.accept.acceptedMessage')
  })

  it('ACCEPT-004: preview が 500 で失敗した場合、previewError 表示になり accept() は呼ばれない（P0 再発防止）', async () => {
    setHash('#token=abc-123')
    mockPreview.mockRejectedValue({ statusCode: 500, data: { error: { code: 'INTERNAL_ERROR' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(mockPreview).toHaveBeenCalledWith('abc-123')
    expect(mockAccept).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('provisioning.accept.previewError.title')
    expect(wrapper.find('[data-testid="provisioning-accept-button"]').exists()).toBe(false)
  })

  it('ACCEPT-004b: preview がネットワーク断で失敗した場合も accept() は呼ばれない（P0 再発防止）', async () => {
    setHash('#token=abc-123')
    mockPreview.mockRejectedValue(new TypeError('Failed to fetch'))

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(mockAccept).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('provisioning.accept.previewError.title')
  })

  it('ACCEPT-005: previewError 状態で再読み込みボタンを押すと preview を再試行する', async () => {
    setHash('#token=abc-123')
    mockPreview.mockRejectedValueOnce({ statusCode: 500 })
    mockPreview.mockResolvedValueOnce({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()
    expect(wrapper.text()).toContain('provisioning.accept.previewError.title')

    await wrapper.get('[data-testid="provisioning-preview-retry-button"]').trigger('click')
    await flush()

    expect(mockPreview).toHaveBeenCalledTimes(2)
    expect(mockAccept).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('サンプル組織')
  })

  it('ACCEPT-006: 承諾ボタン押下でaccept()がPROV_002（期限切れ）→ expiredエラー表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_002' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()
    await wrapper.get('[data-testid="provisioning-accept-button"]').trigger('click')
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.errors.expiredTitle')
  })

  it('ACCEPT-007: 承諾ボタン押下でaccept()がPROV_003（取消済み）→ cancelledエラー表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_003' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()
    await wrapper.get('[data-testid="provisioning-accept-button"]').trigger('click')
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.errors.cancelledTitle')
  })

  it('ACCEPT-008: 承諾ボタン押下でaccept()がPROV_006（メール不一致）→ emailMismatchエラー表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_006' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()
    await wrapper.get('[data-testid="provisioning-accept-button"]').trigger('click')
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.errors.emailMismatchTitle')
  })

  it('ACCEPT-009: 承諾ボタン押下でaccept()がPROV_010（本人以外の再承諾・存在秘匿）→ notFound表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_010' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()
    await wrapper.get('[data-testid="provisioning-accept-button"]').trigger('click')
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.errors.notFoundTitle')
  })

  it('ACCEPT-010: ログイン往復で sessionStorage に退避されたトークンから復元して preview を呼ぶ', async () => {
    setHash('') // ログイン後の復帰時はフラグメントが無い
    window.sessionStorage.setItem(TOKEN_STORAGE_KEY, 'stashed-token')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(mockPreview).toHaveBeenCalledWith('stashed-token')
    expect(wrapper.text()).toContain('サンプル組織')
  })

  it('ACCEPT-011: sessionStorage から復元したトークンは使用後に即座に削除する', async () => {
    setHash('')
    window.sessionStorage.setItem(TOKEN_STORAGE_KEY, 'stashed-token')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })

    await mountSuspended(AcceptPage)
    await flush()

    expect(window.sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('ACCEPT-012: フラグメントにトークンがある場合は sessionStorage より優先する', async () => {
    setHash('#token=from-hash')
    window.sessionStorage.setItem(TOKEN_STORAGE_KEY, 'from-storage')
    mockPreview.mockResolvedValue({
      teamId: null,
      organizationId: 1,
      scopeName: 'サンプル組織',
      inviteEmail: 'admin@example.com',
      expiresAt: '2026-09-10T00:00:00Z',
    })

    await mountSuspended(AcceptPage)
    await flush()

    expect(mockPreview).toHaveBeenCalledWith('from-hash')
    // フラグメント優先時、退避 sessionStorage は不要になった過去の値として残っていても害はないが、
    // 使い捨て原則としてここでは読み取り自体を行わないため削除もされない。
    expect(window.sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('from-storage')
  })
})
