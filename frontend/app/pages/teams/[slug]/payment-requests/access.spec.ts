// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive, ref } from 'vue'
import RequestList from './index.vue'
import RequestDetail from './[id].vue'
import Advances from '../payments/advances.vue'

const mocks = vi.hoisted(() => ({ api: vi.fn(), report: vi.fn() }))
const params = reactive({ slug: 'team-a', id: 'request-a' })
mockNuxtImport('useRoute', () => () => ({ params }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, locale: ref('ja') }))
mockNuxtImport('useTeamShellContext', () => () => ({ isAdminOrDeputy: ref(false) }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.report }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn() }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => { resolve = res })
  return { promise, resolve }
}

let role = 'ADMIN'
let permissionError = false
let dataError = false
let empty = false
const privateTitle = 'private invoice'
const privatePayer = 'private payer'
const detail = { id: 'request-a', title: privateTitle, faceAmount: 12345, currency: 'JPY', status: 'SENT' }
function respond(url: string) {
  if (url.endsWith('/me/permissions')) {
    if (permissionError) return Promise.reject(new Error('permissions unavailable'))
    return Promise.resolve({ data: { roleName: role, permissions: [] } })
  }
  if (url === '/api/v1/me/teams') return Promise.resolve({ data: [{ slug: 'team-a', id: 1 }, { slug: 'team-b', id: 2 }] })
  if (dataError) return Promise.reject(new Error('data unavailable'))
  if (url.endsWith('/payment-advances')) return Promise.resolve({ data: empty ? null : [{ id: 'advance-a', payerUserId: privatePayer, advancedAmount: 12345, settlementStatus: 'PENDING' }] })
  if (url.endsWith('/payment-requests')) return Promise.resolve({ data: empty ? null : [detail] })
  return Promise.resolve({ data: detail })
}

const options = { global: { stubs: {
  PageHeader: { props: ['title'], template: '<h1>{{ title }}</h1>' },
  SectionCard: { props: ['title'], template: '<section>{{ title }}<slot /></section>' },
  PageLoading: { template: '<div data-testid="loading" />' },
  DashboardErrorState: { emits: ['retry'], template: '<button data-testid="error" @click="$emit(\'retry\')">retry</button>' },
  DashboardEmptyState: { template: '<div data-testid="empty" />' },
  Message: { template: '<div><slot /></div>' },
  Button: true, Tag: true, ConfirmDialog: true, PaymentRequestPaymentDialog: true,
  NuxtLink: { template: '<a><slot /></a>' },
} } }

describe.each([
  ['請求一覧', RequestList], ['請求詳細', RequestDetail], ['立替一覧', Advances],
] as const)('%sの権限と取得状態', (_name, Page) => {
  beforeEach(() => {
    vi.clearAllMocks()
    params.slug = 'team-a'
    params.id = 'request-a'
    role = 'ADMIN'
    permissionError = false
    dataError = false
    empty = false
    mocks.api.mockImplementation(respond)
  })

  it.each(['ADMIN', 'DEPUTY_ADMIN'])('親シェルが未初期化でも%sは直アクセスできる', async (currentRole) => {
    role = currentRole
    const wrapper = mount(Page, options)
    await flushPromises()
    expect(wrapper.text()).not.toContain('payment.admin.permissionDenied')
    expect(wrapper.text()).toContain(Page === Advances ? privatePayer : privateTitle)
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/team-a/me/permissions')
  })

  it.each(['MEMBER', 'SUPPORTER', 'GUEST'])('%sは金融情報を取得・表示しない', async (currentRole) => {
    role = currentRole
    const wrapper = mount(Page, options)
    await flushPromises()
    expect(wrapper.text()).toContain('payment.admin.permissionDenied')
    expect(wrapper.text()).not.toContain('private')
    expect(mocks.api.mock.calls.some(([url]) => /\/payment-(requests|advances)/.test(url))).toBe(false)
  })

  it('権限取得中は拒否せずローディングを表示する', async () => {
    const pending = deferred<unknown>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? pending.promise : respond(url))
    const wrapper = mount(Page, options)
    await flushPromises()
    expect(wrapper.find('[data-testid="loading"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('payment.admin.permissionDenied')
    pending.resolve({ data: { roleName: 'ADMIN', permissions: [] } })
    await flushPromises()
  })

  it.each(['permissions', 'data'])('%s取得失敗はエラーを表示し、再試行で権限から一連取得する', async (failure) => {
    permissionError = failure === 'permissions'
    dataError = failure === 'data'
    const wrapper = mount(Page, options)
    await flushPromises()
    expect(wrapper.find('[data-testid="error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="empty"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('payment.admin.permissionDenied')
    permissionError = false
    dataError = false
    const calls = mocks.api.mock.calls.length
    await wrapper.get('[data-testid="error"]').trigger('click')
    await flushPromises()
    expect(mocks.api.mock.calls.slice(calls).some(([url]) => url.endsWith('/me/permissions'))).toBe(true)
    expect(wrapper.text()).toContain(Page === Advances ? privatePayer : privateTitle)
  })

  it('チーム変更時に旧金融情報を消し、現在のチーム権限で拒否する', async () => {
    const wrapper = mount(Page, options)
    await flushPromises()
    expect(wrapper.text()).toContain('private')
    role = 'MEMBER'
    params.slug = 'team-b'
    await flushPromises()
    expect(wrapper.text()).not.toContain('private')
    expect(wrapper.text()).toContain('payment.admin.permissionDenied')
  })

  it('旧チームの遅い金融情報応答で現在の拒否表示を上書きしない', async () => {
    const pending = deferred<unknown>()
    mocks.api.mockImplementation((url: string) => /\/teams\/1\/payment-/.test(url) ? pending.promise : respond(url))
    const wrapper = mount(Page, options)
    await flushPromises()
    role = 'MEMBER'
    params.slug = 'team-b'
    await flushPromises()
    pending.resolve({ data: Page === RequestDetail ? detail : [detail] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('private')
    expect(wrapper.text()).toContain('payment.admin.permissionDenied')
  })

  it('旧チームの遅い管理権限応答で現在のチームを許可しない', async () => {
    const pending = deferred<unknown>()
    mocks.api.mockImplementation((url: string) => url === '/api/v1/teams/team-a/me/permissions' ? pending.promise : respond(url))
    const wrapper = mount(Page, options)
    await flushPromises()
    role = 'MEMBER'
    params.slug = 'team-b'
    await flushPromises()
    pending.resolve({ data: { roleName: 'ADMIN', permissions: [] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('private')
    expect(wrapper.text()).toContain('payment.admin.permissionDenied')
  })

  it('別チームで金融APIが403を返した場合も旧金融情報を残さない', async () => {
    const wrapper = mount(Page, options)
    await flushPromises()
    mocks.api.mockImplementation((url: string) => /\/teams\/2\/payment-/.test(url)
      ? Promise.reject({ statusCode: 403 }) : respond(url))
    params.slug = 'team-b'
    await flushPromises()
    expect(wrapper.text()).not.toContain('private')
    expect(wrapper.find('[data-testid="error"]').exists()).toBe(true)
  })

  if (Page === RequestDetail) it('チーム変更後の支払い開始応答で旧client secretを表示しない', async () => {
    const pending = deferred<unknown>()
    mocks.api.mockImplementation((url: string) => url.endsWith('/pay') ? pending.promise : respond(url))
    const wrapper = mount(Page, options)
    await flushPromises()
    await wrapper.get('[data-testid="payment-request-pay"]').trigger('click')
    role = 'MEMBER'
    params.slug = 'team-b'
    await flushPromises()
    pending.resolve({ data: { clientSecret: 'old-secret' } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('private')
    expect(wrapper.find('payment-request-payment-dialog-stub').exists()).toBe(false)
  })

  if (Page !== RequestDetail) it('正常応答data:nullは空状態を表示する', async () => {
    empty = true
    const wrapper = mount(Page, options)
    await flushPromises()
    expect(wrapper.find('[data-testid="empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="error"]').exists()).toBe(false)
  })
})
