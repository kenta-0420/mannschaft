// @vitest-environment happy-dom
import { PasskeyLoginError } from '~/composables/useWebAuthnLogin'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
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

const { mockPasskeyLogin, mockSetTokens, mockSetUser, mockSyncSettings, mockServerLogout, mockApplyLocale, mockNavigate, mockArmProactiveRefresh } = vi.hoisted(() => ({
  mockPasskeyLogin: vi.fn(),
  mockSetTokens: vi.fn(),
  mockSetUser: vi.fn(),
  mockSyncSettings: vi.fn(),
  mockServerLogout: vi.fn(),
  mockApplyLocale: vi.fn(),
  mockNavigate: vi.fn(),
  mockArmProactiveRefresh: vi.fn(),
}))
const authState = vi.hoisted(() => ({ isSystemAdmin: false }))
mockNuxtImport('useWebAuthnLogin', () => () => ({ loginWithPasskey: mockPasskeyLogin }))
mockNuxtImport('navigateTo', () => mockNavigate)
mockNuxtImport('useServerHead', () => () => {})
mockNuxtImport('useRuntimeConfig', () => () => ({ public: {} }))
mockNuxtImport('readPrefilledInputValue', () => () => '')

const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
  armProactiveRefresh: mockArmProactiveRefresh,
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
    applyAccountLocale: mockApplyLocale,
  }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  // app/plugins/auth.client.ts が mount 毎に loadFromStorage() を呼ぶため必須（#2609 是正）。
  useAuthStore: () => ({
    setTokens: mockSetTokens,
    setUser: mockSetUser,
    syncAccountSettings: mockSyncSettings,
    serverLogout: mockServerLogout,
    get isSystemAdmin() { return authState.isSystemAdmin },
    isAuthenticated: false,
    loadFromStorage: vi.fn(),
  }),
}))

// i18n は実インスタンスを使うと環境ロケール（happy-dom の navigator.language = en）に
// 解決され、検証対象が訳文になってしまう。キーをそのまま返すスタブへ差し替えて
// 「どのキーで通知したか」を検証できるようにする。
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const routeState = { path: '/login', params: {} as Record<string, string>, query: {} as Record<string, string> }
mockNuxtImport('useRoute', () => () => routeState)


const LoginPage = (await import('~/pages/login.vue')).default

async function flush(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) await nextTick()
}

const mountLogin = (attachTo?: HTMLElement, page = LoginPage) => mount(page, {
  attachTo,
  global: {
    plugins: [PrimeVue],
    mocks: { $t: (key: string) => key },
    stubs: { NuxtLink: { template: '<a><slot /></a>' } },
  },
})

describe('pages/login.vue 送信中の公開ダッシュボード資産準備', () => {
  const modulePaths = [
    '~/pages/dashboard.vue',
    '~/components/dashboard/DashboardPersonalPanel.vue',
    '~/components/schedule/CalendarGrid.vue',
  ]
  let assetStarts: ReturnType<typeof vi.fn>[]
  let finishAssets: Array<() => void>
  let rejectAssets: Array<() => void>
  let warn: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.resetModules()
    assetStarts = modulePaths.map(() => vi.fn())
    finishAssets = []
    rejectAssets = []
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    modulePaths.forEach((path, index) => {
      vi.doMock(path, () => {
        assetStarts[index]!()
        return new Promise<{ default: { render: () => null } }>((resolve, reject) => {
          finishAssets[index] = () => resolve({ default: { render: () => null } })
          rejectAssets[index] = () => reject(new Error('asset unavailable'))
        })
      })
    })
  })

  afterEach(async () => {
    finishAssets.forEach(finish => finish())
    await flush(8)
    modulePaths.forEach(path => vi.doUnmock(path))
    warn.mockRestore()
  })

  async function mountAssetLogin(): Promise<Wrapper> {
    const page = (await import('~/pages/login.vue')).default
    const wrapper = mountLogin(undefined, page)
    mountedPages.push(wrapper)
    return wrapper
  }

  it('認証応答待ちに3資産を準備し、二重送信せず、資産未完了でも本人復元と遷移を進める', async () => {
    let finishLogin: (value: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise(resolve => { finishLogin = resolve }))
      .mockResolvedValueOnce({ data: profile })
    routeState.query = { redirect: '/my/profile' }
    const wrapper = await mountAssetLogin()
    expect(assetStarts.every(start => start.mock.calls.length === 0)).toBe(true)

    await submit(wrapper)
    await vi.waitFor(() => assetStarts.forEach(start => expect(start).toHaveBeenCalledOnce()))
    expect(mockApi).toHaveBeenCalledOnce()
    expect(mockApi.mock.calls[0]?.[0]).toBe('/api/v1/auth/login')
    expect(mockSetTokens).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()

    await submit(wrapper)
    expect(mockApi).toHaveBeenCalledOnce()
    assetStarts.forEach(start => expect(start).toHaveBeenCalledOnce())
    finishLogin({ data: { ...tokenResponse, userId: 42, fullName: 'Test Member', email: profile.email } })
    await vi.waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/my/profile'))
    expect(mockApi).toHaveBeenCalledTimes(2)
    expect(mockApi.mock.calls[1]?.[0]).toBe('/api/v1/users/me')
    expect(mockSyncSettings).toHaveBeenCalledOnce()
    expect(mockNotifyError).not.toHaveBeenCalled()
  })

  it.each(['failure', 'mfa'] as const)('%sでは資産だけを準備し本人・privateデータを取得しない', async (outcome) => {
    if (outcome === 'failure') mockApi.mockRejectedValueOnce({ data: { error: { code: 'AUTH_001' } } })
    else mockApi.mockResolvedValueOnce({ data: { mfaRequired: true, mfaSessionToken: 'mfa-session' } })
    const wrapper = await mountAssetLogin()

    await submit(wrapper)
    await vi.waitFor(() => assetStarts.forEach(start => expect(start).toHaveBeenCalledOnce()))
    expect(mockApi).toHaveBeenCalledOnce()
    expect(mockSetTokens).not.toHaveBeenCalled()
    expect(mockSetUser).not.toHaveBeenCalled()
    expect(mockSyncSettings).not.toHaveBeenCalled()
    if (outcome === 'failure') {
      expect(mockNavigate).not.toHaveBeenCalled()
      expect(mockNotifyError).toHaveBeenCalledWith('auth.login.failed', 'auth.login.invalid_credentials')
    } else expect(mockNavigate).toHaveBeenCalledWith('/2fa-verify?session=mfa-session')
  })

  it('資産準備失敗は固定文言で診断し、成功した認証を失敗扱いにしない', async () => {
    mockApi.mockResolvedValueOnce({ data: { ...tokenResponse, userId: 42, fullName: 'Test Member', email: profile.email } })
      .mockResolvedValueOnce({ data: profile })
    const wrapper = await mountAssetLogin()

    await submit(wrapper)
    await vi.waitFor(() => assetStarts.forEach(start => expect(start).toHaveBeenCalledOnce()))
    rejectAssets[0]!()
    finishAssets.slice(1).forEach(finish => finish())

    await vi.waitFor(() => expect(warn).toHaveBeenCalledWith('ダッシュボード資産の先読みを完了できませんでした。通常の画面遷移を続行します。'))
    expect(mockNavigate).toHaveBeenCalledWith('/dashboard')
    expect(mockApi).toHaveBeenCalledTimes(2)
    expect(mockNotifyError).not.toHaveBeenCalled()
  })
})
type Wrapper = ReturnType<typeof mountLogin>

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
  authState.isSystemAdmin = false
  mockPasskeyLogin.mockReset()
  mockSetTokens.mockReset()
  mockSetUser.mockReset()
  mockSyncSettings.mockReset()
  mockServerLogout.mockReset()
  mockApplyLocale.mockReset()
  mockNavigate.mockReset()
  mockArmProactiveRefresh.mockReset()
})

describe('pages/login.vue ログイン失敗時のエラー出し分け', () => {
  it('LOGIN-001: AUTH_003 → アカウントロック専用文言で notification.error', async () => {
    mockApi.mockRejectedValue({ data: { error: { code: 'AUTH_003', message: 'locked' } } })
    const wrapper = mountLogin()
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.account_locked',
      'auth.login.account_locked_detail',
    )
  })

  it('LOGIN-002: AUTH_002 → メール未確認文言で notification.error', async () => {
    mockApi.mockRejectedValue({ data: { error: { code: 'AUTH_002' } } })
    const wrapper = mountLogin()
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.email_not_verified',
      'auth.login.email_not_verified_detail',
    )
  })

  it('LOGIN-003: AUTH_001（既定）→ 汎用「メール/パスワード不一致」文言で notification.error', async () => {
    mockApi.mockRejectedValue({ data: { error: { code: 'AUTH_001' } } })
    const wrapper = mountLogin()
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.failed',
      'auth.login.invalid_credentials',
    )
  })

  it('LOGIN-004: code 無し（ネットワーク断等）→ 既定の汎用文言にフォールバック', async () => {
    mockApi.mockRejectedValue(new Error('Network error'))
    const wrapper = mountLogin()
    await submit(wrapper)

    expect(mockNotifyError).toHaveBeenCalledWith(
      'auth.login.failed',
      'auth.login.invalid_credentials',
    )
  })
})

const mountedPages: Wrapper[] = []
afterEach(() => {
  for (const wrapper of mountedPages) wrapper.unmount()
  mountedPages.length = 0
})

async function mountPasskeyPage(): Promise<Wrapper> {
  const wrapper = mountLogin(document.body)
  mountedPages.push(wrapper)
  await wrapper.find('#email').setValue('member@example.com')
  return wrapper
}

const tokenResponse = { accessToken: 'access', refreshToken: 'refresh', tokenType: 'Bearer', expiresIn: 900 }
const profile = {
  id: 42, email: 'member@example.com', lastName: 'Test', firstName: 'Member',
  avatarUrl: null, systemRole: null, locale: 'ja', timezone: 'Asia/Tokyo', status: 'ACTIVE',
}

describe('pages/login.vue パスキーでログイン', () => {
  it('空フォームでもGoogle URL取得を開始しパスワード認証を送信しない', async () => {
    mockApi.mockResolvedValue({ data: { authUrl: '#google-start' } })
    const wrapper = await mountPasskeyPage()
    await wrapper.find('#email').setValue('')
    const google = wrapper.findAll('button').find(button => button.find('.pi-google').exists())!

    await google.trigger('click')
    await flush()

    expect(mockApi).toHaveBeenCalledOnce()
    expect(mockApi).toHaveBeenCalledWith('/api/v1/auth/oauth/google/auth-url')
    expect(mockPasskeyLogin).not.toHaveBeenCalled()
    expect(mockSetTokens).not.toHaveBeenCalled()
  })

  it('メールとパスワード、パスキー、Googleの順に表示する', async () => {
    const wrapper = await mountPasskeyPage()
    const elements = Array.from(wrapper.element.querySelectorAll('input, button'))
    const index = (selector: string) => elements.indexOf(wrapper.element.querySelector(selector)!)

    expect(index('#email')).toBeLessThan(index('#password'))
    expect(index('[data-login-submit]')).toBeLessThan(index('[data-passkey-login]'))
    const google = wrapper.findAll('button').find(button => button.find('.pi-google').exists())!
    expect(index('[data-passkey-login]')).toBeLessThan(elements.indexOf(google.element))
    expect(wrapper.find('[data-passkey-login] .pi-key').exists()).toBe(true)
  })

  it('パスワード未入力で署名を完了しプロフィールと設定を同期してredirectへ遷移する', async () => {
    mockPasskeyLogin.mockResolvedValue(tokenResponse)
    mockApi.mockResolvedValue({ data: profile })
    routeState.query = { redirect: '/my/profile' }
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await flush(8)

    expect(mockPasskeyLogin).toHaveBeenCalledWith('member@example.com')
    expect(mockSetTokens).toHaveBeenCalledWith('access', 'refresh')
    expect(mockSetUser).toHaveBeenCalledWith(expect.objectContaining({ id: 42, locale: 'ja' }))
    expect(mockApplyLocale).toHaveBeenCalledWith('ja')
    expect(mockSyncSettings).toHaveBeenCalledOnce()
    expect(mockNavigate).toHaveBeenCalledWith('/my/profile')
    expect(mockApi).not.toHaveBeenCalledWith('/api/v1/auth/login', expect.anything())
  })

  it('プロフィールの管理者ロールをストアへ反映して管理画面へ遷移する', async () => {
    mockPasskeyLogin.mockResolvedValue(tokenResponse)
    mockApi.mockResolvedValue({ data: { ...profile, systemRole: 'SYSTEM_ADMIN' } })
    mockSetUser.mockImplementation((user: { systemRole?: string }) => {
      authState.isSystemAdmin = user.systemRole === 'SYSTEM_ADMIN'
    })
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await flush(8)

    expect(mockSetUser).toHaveBeenCalledWith(expect.objectContaining({ systemRole: 'SYSTEM_ADMIN' }))
    expect(mockNavigate).toHaveBeenCalledWith('/system-admin')
  })

  it('メール未入力では開始しない', async () => {
    const wrapper = await mountPasskeyPage()
    await wrapper.find('#email').setValue('')

    await wrapper.find('[data-passkey-login]').trigger('click')

    expect(mockPasskeyLogin).not.toHaveBeenCalled()
  })

  it('キャンセル後は通知せず操作できる状態へ戻る', async () => {
    mockPasskeyLogin.mockResolvedValue(null)
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await flush()

    expect(mockNotifyError).not.toHaveBeenCalled()
    expect(mockSetTokens).not.toHaveBeenCalled()
    expect(wrapper.find('[data-passkey-login]').attributes('disabled')).toBeUndefined()
  })

  it('未登録ではパスキー専用案内を表示する', async () => {
    mockPasskeyLogin.mockRejectedValue(new PasskeyLoginError('unregistered'))
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await flush()

    expect(mockNotifyError).toHaveBeenCalledWith('auth.login.failed', 'auth.login.passkey_unregistered')
    expect(mockSetTokens).not.toHaveBeenCalled()
  })

  it('通信・検証失敗をパスキー専用案内で表示する', async () => {
    mockPasskeyLogin.mockRejectedValue(new Error('network'))
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await flush()

    expect(mockNotifyError).toHaveBeenCalledWith('auth.login.failed', 'auth.login.passkey_failed')
  })

  it('認証中はパスキー二重実行とパスワードログインを開始しない', async () => {
    let finish: (value: null) => void = () => {}
    mockPasskeyLogin.mockReturnValue(new Promise<null>(resolve => { finish = resolve }))
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await wrapper.find('[data-passkey-login]').trigger('click')
    await wrapper.find('form').trigger('submit')

    expect(mockPasskeyLogin).toHaveBeenCalledOnce()
    expect(mockApi).not.toHaveBeenCalled()
    expect(wrapper.find('[data-login-submit]').attributes('disabled')).toBeDefined()
    finish(null)
    await flush()
  })

  it('保護者同意待ちではプロフィール保存と設定同期をせず専用ページへ遷移する', async () => {
    mockPasskeyLogin.mockResolvedValue(tokenResponse)
    mockApi.mockResolvedValue({ data: { ...profile, status: 'PENDING_PARENTAL_CONSENT' } })
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await flush(8)

    expect(mockSetUser).not.toHaveBeenCalled()
    expect(mockSyncSettings).not.toHaveBeenCalled()
    expect(mockNavigate).toHaveBeenCalledWith('/parental-consent/pending')
  })

  it('プロフィール取得失敗ではセッションを解除し成功遷移しない', async () => {
    mockPasskeyLogin.mockResolvedValue(tokenResponse)
    mockApi.mockRejectedValue(new Error('profile unavailable'))
    routeState.query = { redirect: '/my/profile' }
    const wrapper = await mountPasskeyPage()

    await wrapper.find('[data-passkey-login]').trigger('click')
    await flush(8)

    expect(mockServerLogout).toHaveBeenCalledOnce()
    expect(mockArmProactiveRefresh).not.toHaveBeenCalled()
    expect(mockNavigate).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/my/profile' } }, { replace: true })
    expect(mockSyncSettings).not.toHaveBeenCalled()
    expect(mockNotifyError).toHaveBeenCalledWith('auth.login.failed', 'auth.login.passkey_profile_failed')
  })
})

describe('pages/login.vue パスワード成功経路の回帰', () => {
  const result = { ...tokenResponse, userId: 42, fullName: 'Test Member', email: profile.email }

  it('共通成功処理でプロフィールと設定を同期し復活案内へ遷移する', async () => {
    mockApi.mockResolvedValueOnce({ data: { ...result, reactivated: true } }).mockResolvedValueOnce({ data: profile })
    const wrapper = await mountPasskeyPage()

    await submit(wrapper)
    await flush(8)

    expect(mockSetTokens).toHaveBeenCalledWith('access', 'refresh')
    expect(mockApplyLocale).toHaveBeenCalledWith('ja')
    expect(mockSyncSettings).toHaveBeenCalledOnce()
    expect(mockNavigate).toHaveBeenCalledWith('/account-restore')
  })

  it('パスワード成功後のプロフィール失敗では既存の基本情報フォールバックを維持する', async () => {
    mockApi.mockResolvedValueOnce({ data: result }).mockRejectedValueOnce(new Error('profile'))
    const wrapper = await mountPasskeyPage()

    await submit(wrapper)
    await flush(8)

    expect(mockSetUser).toHaveBeenCalledWith({ id: 42, email: profile.email, fullName: 'Test Member', profileImageUrl: null })
    expect(mockServerLogout).not.toHaveBeenCalled()
    expect(mockNavigate).toHaveBeenCalledWith('/dashboard')
  })

  it('MFA要求時はトークンを保存せず検証画面へ遷移する', async () => {
    mockApi.mockResolvedValue({ data: { mfaRequired: true, mfaSessionToken: 'mfa-session' } })
    const wrapper = await mountPasskeyPage()

    await submit(wrapper)
    await flush()

    expect(mockSetTokens).not.toHaveBeenCalled()
    expect(mockNavigate).toHaveBeenCalledWith('/2fa-verify?session=mfa-session')
  })
})

describe('pages/login.vue 本人復元後の先回り更新', () => {
  const result = { ...tokenResponse, userId: 42, fullName: 'Test Member', email: profile.email }

  it.each(['password', 'passkey'] as const)('%s成功は本人とlocaleの復元後だけタイマーを武装する', async (method) => {
    let finishUser: () => void = () => {}
    let finishLocale: () => void = () => {}
    mockSetUser.mockReturnValue(new Promise<void>(resolve => { finishUser = resolve }))
    mockApplyLocale.mockReturnValue(new Promise<void>(resolve => { finishLocale = resolve }))
    if (method === 'password')
      mockApi.mockResolvedValueOnce({ data: result }).mockResolvedValueOnce({ data: profile })
    else {
      mockPasskeyLogin.mockResolvedValue(tokenResponse)
      mockApi.mockResolvedValue({ data: profile })
    }
    const wrapper = await mountPasskeyPage()
    try {
      if (method === 'password') await submit(wrapper)
      else await wrapper.find('[data-passkey-login]').trigger('click')
      await flush(8)
      expect(mockSetUser).toHaveBeenCalledOnce()
      expect(mockArmProactiveRefresh).not.toHaveBeenCalled()
      finishUser()
      await flush(8)
      expect(mockApplyLocale).toHaveBeenCalledWith('ja')
      expect(mockArmProactiveRefresh).not.toHaveBeenCalled()
      finishLocale()
      await flush(8)
      expect(mockArmProactiveRefresh).toHaveBeenCalledOnce()
      expect(mockNavigate).toHaveBeenCalledWith('/dashboard')
    } finally {
      finishUser()
      finishLocale()
      await flush(8)
    }
  })

  it('passwordのプロフィール失敗でも基本本人の復元完了までタイマーを武装しない', async () => {
    let finishUser: () => void = () => {}
    mockSetUser.mockReturnValue(new Promise<void>(resolve => { finishUser = resolve }))
    mockApi.mockResolvedValueOnce({ data: result }).mockRejectedValueOnce(new Error('profile'))
    const wrapper = await mountPasskeyPage()
    try {
      await submit(wrapper)
      await flush(8)
      expect(mockSetUser).toHaveBeenCalledWith({ id: 42, email: profile.email, fullName: 'Test Member', profileImageUrl: null })
      expect(mockArmProactiveRefresh).not.toHaveBeenCalled()
      finishUser()
      await flush(8)
      expect(mockArmProactiveRefresh).toHaveBeenCalledOnce()
      expect(mockApplyLocale).not.toHaveBeenCalled()
      expect(mockServerLogout).not.toHaveBeenCalled()
      expect(mockNavigate).toHaveBeenCalledWith('/dashboard')
    } finally {
      finishUser()
      await flush(8)
    }
  })
})
