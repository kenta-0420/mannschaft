import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick } from 'vue'

/**
 * pages/provisioning/accept.vue の UT（柱②-2 販促プロビジョニング）。
 *
 * 検証観点:
 *   ACCEPT-001: URLフラグメント（#token=...）からトークンを読み取り preview を呼ぶ
 *   ACCEPT-002: フラグメントが無ければ notFound エラー表示（preview を呼ばない）
 *   ACCEPT-003: preview 成功後、承諾ボタン押下で accept を呼び成功表示へ遷移する
 *   ACCEPT-004~006/008: preview は存在秘匿のため一律 PROV_001 を返すため、実際の理由
 *               （期限切れ/取消済み/メール不一致/真に存在しない）は accept() へのフォールバック
 *               呼び出しで判定して表示する
 *   ACCEPT-007/009: accept が PROV_010（既に承諾済み・本人再訪）を返した場合、エラーではなく
 *               冪等成功として表示する（明示クリック経由・preview失敗フォールバック経由の両方）
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

  it('ACCEPT-002: フラグメントが無ければ preview を呼ばずnotFoundエラー表示', async () => {
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
    expect(wrapper.text()).toContain('provisioning.accept.alreadyAcceptedMessage')
  })

  it('ACCEPT-004: preview失敗→フォールバックaccept()がPROV_002（期限切れ）→ expiredエラー表示', async () => {
    setHash('#token=abc-123')
    // preview() は存在秘匿のため一律 PROV_001 を返す。実際の理由は accept() が区別する。
    mockPreview.mockRejectedValue({ data: { error: { code: 'PROV_001' } } })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_002' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(mockAccept).toHaveBeenCalledWith('abc-123')
    expect(wrapper.text()).toContain('provisioning.accept.errors.expiredTitle')
  })

  it('ACCEPT-005: preview失敗→フォールバックaccept()がPROV_003（取消済み）→ cancelledエラー表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockRejectedValue({ data: { error: { code: 'PROV_001' } } })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_003' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.errors.cancelledTitle')
  })

  it('ACCEPT-006: preview失敗→フォールバックaccept()がPROV_006（メール不一致）→ emailMismatchエラー表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockRejectedValue({ data: { error: { code: 'PROV_001' } } })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_006' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.errors.emailMismatchTitle')
  })

  it('ACCEPT-008: preview失敗→フォールバックaccept()も真に見つからない（PROV_001）→ notFoundエラー表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockRejectedValue({ data: { error: { code: 'PROV_001' } } })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_001' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.errors.notFoundTitle')
  })

  it('ACCEPT-009: preview失敗→フォールバックaccept()がPROV_010（本人の再訪）→ 冪等成功表示', async () => {
    setHash('#token=abc-123')
    mockPreview.mockRejectedValue({ data: { error: { code: 'PROV_001' } } })
    mockAccept.mockRejectedValue({ data: { error: { code: 'PROV_010' } } })

    const wrapper = await mountSuspended(AcceptPage)
    await flush()

    expect(wrapper.text()).toContain('provisioning.accept.alreadyAcceptedMessage')
    expect(mockNotifyError).not.toHaveBeenCalled()
  })

  it('ACCEPT-007: acceptがPROV_010（本人による再承諾）を返した場合は冪等成功として表示する', async () => {
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

    expect(wrapper.text()).not.toContain('provisioning.accept.errors')
    expect(wrapper.text()).toContain('provisioning.accept.alreadyAcceptedMessage')
    // PROV_010 は「既に承諾済み」であり、エラー扱いしないため notification.error は呼ばれない
    expect(mockNotifyError).not.toHaveBeenCalled()
  })
})
