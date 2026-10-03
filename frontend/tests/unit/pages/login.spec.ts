import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick } from 'vue'

/**
 * pages/login.vue のログイン失敗時エラー出し分け UT。
 *
 * 背景:
 *   ブルートフォース対策（5回失敗で30分ロック）で backend は AuthErrorCode を
 *   { error: { code, message } } 形式で返す。FE はかつて全エラーを汎用文言に
 *   握りつぶしていたため、ロックの事実がユーザーに伝わらなかった。
 *
 * 検証観点:
 *   LOGIN-001: AUTH_003（アカウントロック）→ ロック専用文言キーで notification.error
 *   LOGIN-002: AUTH_002（メール未確認）→ メール未確認文言キーで notification.error
 *   LOGIN-003: AUTH_001（既定）→ 汎用「メール/パスワード不一致」文言キーで notification.error
 *   LOGIN-004: code 無し（ネットワーク断等）→ 既定の汎用文言にフォールバック
 *
 * 検証は「どの i18n キーで通知したか」で行う（実訳文は環境ロケールに依存するため）。
 * そのため useI18n をキーをそのまま返すスタブに差し替える。
 */

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

const mockNotifyError = vi.fn()
const mockNotifySuccess = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    error: mockNotifyError,
    success: mockNotifySuccess,
    info: vi.fn(),
    warn: vi.fn(),
    showError: mockNotifyError,
    showSuccess: mockNotifySuccess,
    showInfo: vi.fn(),
    showWarn: vi.fn(),
  }),
}))

vi.mock('~/composables/useLocale', () => ({
  useLocale: () => ({
    applyUserLocale: vi.fn(),
  }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  // app/plugins/auth.client.ts が mount 毎に loadFromStorage() を呼ぶため必須（#2609 是正）。
  useAuthStore: () => ({
    setTokens: vi.fn(),
    setUser: vi.fn(),
    isSystemAdmin: false,
    isAuthenticated: false,
    loadFromStorage: vi.fn(),
  }),
}))

// i18n は実インスタンスを使うと環境ロケール（happy-dom の navigator.language = en）に
// 解決され、検証対象が訳文になってしまう。キーをそのまま返すスタブへ差し替えて
// 「どのキーで通知したか」を検証できるようにする。
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

// useRoute / useRouter は Nuxt 内部プラグインが各メソッドを呼ぶため、
// 完全なモックオブジェクトを返す必要がある。
const routeState = { path: '/login', params: {} as Record<string, string>, query: {} as Record<string, string> }
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

const LoginPage = (await import('~/pages/login.vue')).default

async function flush(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) await nextTick()
}

type Wrapper = Awaited<ReturnType<typeof mountSuspended<typeof LoginPage>>>

async function submit(wrapper: Wrapper): Promise<void> {
  await wrapper.find('form').trigger('submit')
  await flush()
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockApi.mockReset()
  mockNotifyError.mockReset()
  mockNotifySuccess.mockReset()
  routeState.query = {}
})

describe('pages/login.vue ログイン失敗時のエラー出し分け', () => {
  it('LOGIN-001: AUTH_003 → アカウントロック専用文言で notification.error', async () => {
    mockApi.mockRejectedValue({ data: { error: { code: 'AUTH_003', message: 'locked' } } })
    const wrapper = await mountSuspended(LoginPage)
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.account_locked',
      'auth.login.account_locked_detail',
    )
  })

  it('LOGIN-002: AUTH_002 → メール未確認文言で notification.error', async () => {
    mockApi.mockRejectedValue({ data: { error: { code: 'AUTH_002' } } })
    const wrapper = await mountSuspended(LoginPage)
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.email_not_verified',
      'auth.login.email_not_verified_detail',
    )
  })

  it('LOGIN-003: AUTH_001（既定）→ 汎用「メール/パスワード不一致」文言で notification.error', async () => {
    mockApi.mockRejectedValue({ data: { error: { code: 'AUTH_001' } } })
    const wrapper = await mountSuspended(LoginPage)
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.failed',
      'auth.login.invalid_credentials',
    )
  })

  it('LOGIN-004: code 無し（ネットワーク断等）→ 既定の汎用文言にフォールバック', async () => {
    mockApi.mockRejectedValue(new Error('Network error'))
    const wrapper = await mountSuspended(LoginPage)
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.failed',
      'auth.login.invalid_credentials',
    )
  })
})
