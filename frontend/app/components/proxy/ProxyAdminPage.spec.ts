import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import ProxyAdminPage from './ProxyAdminPage.vue'
import type { ProxyInputConsent } from '~/types/proxy-input'

const mocks = vi.hoisted(() => ({
  require: vi.fn(),
  approve: vi.fn(),
  revoke: vi.fn(),
  loadPage: vi.fn(),
  success: vi.fn(),
  handleApiError: vi.fn(),
  useState: vi.fn(),
  auth: { user: { id: 3 } },
}))
vi.mock('primevue/useconfirm', () => ({ useConfirm: () => ({ require: mocks.require }) }))
mockNuxtImport('useProxyAdmin', () => mocks.useState)
mockNuxtImport('useAuthStore', () => () => mocks.auth)
mockNuxtImport('useProxyInputApi', () => () => ({
  approveConsent: mocks.approve,
  revokeConsent: mocks.revoke,
}))
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (value: string) => value,
  formatDateTime: (value: string) => value,
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const consent: ProxyInputConsent = {
  id: 7,
  organizationId: 10,
  subjectUserId: 1,
  proxyUserId: 2,
  consentMethod: 'PAPER_SIGNED',
  effectiveFrom: '2026-01-01',
  effectiveUntil: '2026-12-31',
  status: 'PENDING_APPROVAL',
  approvedAt: null,
  approvedByUserId: null,
  revokedAt: null,
  revokeMethod: null,
  revokeReason: null,
  scopes: ['SURVEY'],
}

const ButtonStub = defineComponent({
  props: ['label', 'disabled'],
  emits: ['click'],
  setup(props, { emit, attrs }) {
    return () =>
      h('button', { ...attrs, disabled: props.disabled, onClick: () => emit('click') }, props.label)
  },
})
const ColumnStub = defineComponent({
  setup(_props, { slots }) {
    return () => h('div', slots.body?.({ data: consent }))
  },
})
const ContainerStub = defineComponent({
  setup(_props, { slots }) {
    return () => h('div', slots.default?.())
  },
})

function render() {
  return mount(ProxyAdminPage, {
    props: { mode: 'consents' },
    global: {
      stubs: {
        PageHeader: true,
        SectionCard: ContainerStub,
        NuxtLink: true,
        Select: true,
        PageLoading: true,
        DashboardErrorState: true,
        DashboardEmptyState: true,
        DataTable: ContainerStub,
        Column: ColumnStub,
        Tag: true,
        Button: ButtonStub,
        Paginator: true,
        ConfirmDialog: true,
      },
    },
  })
}

describe('ProxyAdminPage', () => {
  beforeEach(() => {
    dayjs.extend(utc)
    dayjs.extend(timezone)
    vi.clearAllMocks()
    mocks.auth.user.id = 3
    mocks.approve.mockResolvedValue({ ...consent, status: 'APPROVED' })
    mocks.revoke.mockResolvedValue(undefined)
    mocks.loadPage.mockResolvedValue(undefined)
    mocks.useState.mockReturnValue({
      organizations: ref([]),
      organizationSlug: ref('my-org'),
      loading: ref(false),
      error: ref(undefined),
      consents: ref([consent]),
      records: ref([]),
      pagination: { page: ref(0), rows: ref(20), totalRecords: ref(1) },
      loadPage: mocks.loadPage,
      changeOrganization: vi.fn(),
      changePage: vi.fn(),
      mayApprove: () => consent.proxyUserId !== mocks.auth.user.id,
      mayRevoke: () => true,
    })
  })

  it('承認は確認を受諾するまで送信せず受諾後に一覧を更新する', async () => {
    const wrapper = render()
    await wrapper.get('[data-testid="proxy-approve-7"]').trigger('click')
    expect(mocks.approve).not.toHaveBeenCalled()

    const dialog = mocks.require.mock.calls[0]?.[0] as { accept: () => void }
    dialog.accept()
    await flushPromises()

    expect(mocks.approve).toHaveBeenCalledOnce()
    expect(mocks.approve).toHaveBeenCalledWith(7)
    expect(mocks.success).toHaveBeenCalledWith('proxy.admin.approved')
    expect(mocks.loadPage).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('管理者の撤回は紙面申請として送り立会人は認可主体から確定する', async () => {
    const wrapper = render()
    await wrapper.get('[data-testid="proxy-revoke-7"]').trigger('click')
    const dialog = mocks.require.mock.calls[0]?.[0] as { accept: () => void }
    dialog.accept()
    await flushPromises()

    expect(mocks.revoke).toHaveBeenCalledWith(7, {
      revokeMethod: 'PAPER_BY_SUBJECT',
    })
    wrapper.unmount()
  })

  it('対象者本人の撤回には本人申請enumを使う', async () => {
    mocks.auth.user.id = 1
    const wrapper = render()
    await wrapper.get('[data-testid="proxy-revoke-7"]').trigger('click')
    const dialog = mocks.require.mock.calls[0]?.[0] as { accept: () => void }
    dialog.accept()
    await flushPromises()

    expect(mocks.revoke).toHaveBeenCalledWith(7, { revokeMethod: 'API_BY_SUBJECT' })
    wrapper.unmount()
  })

  it('409失敗時は成功通知を出さず再取得する', async () => {
    mocks.approve.mockRejectedValueOnce({ statusCode: 409 })
    const wrapper = render()
    await wrapper.get('[data-testid="proxy-approve-7"]').trigger('click')
    const dialog = mocks.require.mock.calls[0]?.[0] as { accept: () => void }
    dialog.accept()
    await flushPromises()

    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.handleApiError).toHaveBeenCalledOnce()
    expect(mocks.loadPage).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
})
