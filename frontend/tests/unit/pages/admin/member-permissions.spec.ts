import { defineComponent, h, nextTick, reactive } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemberPermissionsPage from '~/pages/admin/member-permissions.vue'

const apiMock = vi.fn()
const notificationMock = { success: vi.fn(), error: vi.fn() }
const scopeStoreStub = reactive({ current: { type: 'team', id: '12', name: 'Team 12' } })
const teamStoreStub = reactive({
  myTeams: [{ id: 12, role: 'ADMIN' }],
  fetchMyTeams: vi.fn(async () => {}),
})
const organizationStoreStub = reactive({
  myOrganizations: [] as Array<{ id: number; role: string }>,
  fetchMyOrganizations: vi.fn(async () => {}),
})

mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useScopeStore', () => () => scopeStoreStub)
mockNuxtImport('useTeamStore', () => () => teamStoreStub)
mockNuxtImport('useOrganizationStore', () => () => organizationStoreStub)
mockNuxtImport('useNotification', () => () => notificationMock)

const DashboardErrorStateStub = defineComponent({
  props: {
    message: String,
    testid: String,
    showRetry: { type: Boolean, default: true },
  },
  emits: ['retry'],
  setup(props, { emit }) {
    return () =>
      h('div', { 'data-testid': props.testid }, [
        h('span', props.message),
        props.showRetry === false
          ? null
          : h(
              'button',
              { 'data-testid': `${props.testid}-retry`, onClick: () => emit('retry') },
              'retry',
            ),
      ])
  },
})

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: { label: String, disabled: Boolean },
  emits: ['click'],
  setup(props, { attrs, emit }) {
    return () =>
      h(
        'button',
        {
          ...attrs,
          disabled: props.disabled,
          'data-testid': 'save-member-permissions',
          onClick: () => emit('click'),
        },
        props.label,
      )
  },
})

const ToggleSwitchStub = defineComponent({
  inheritAttrs: false,
  props: { modelValue: Boolean, disabled: Boolean },
  emits: ['update:modelValue'],
  setup(props, { attrs, emit }) {
    return () =>
      h('input', {
        ...attrs,
        type: 'checkbox',
        checked: props.modelValue,
        disabled: props.disabled,
        onChange: (event: Event) =>
          emit('update:modelValue', (event.target as HTMLInputElement).checked),
      })
  },
})

const permissionResponse = {
  data: {
    scopeType: 'TEAM',
    scopeId: 12,
    roleName: 'MEMBER',
    permissions: [
      { name: 'MANAGE_SCHEDULES', enabled: true, inherited: true },
      { name: 'MANAGE_FILES', enabled: false, inherited: false },
      { name: 'MANAGE_POSTS', enabled: true, inherited: false },
    ],
  },
}

async function mountPage() {
  const wrapper = await mountSuspended(MemberPermissionsPage, {
    global: {
      stubs: {
        DashboardErrorState: DashboardErrorStateStub,
        Button: ButtonStub,
        ToggleSwitch: ToggleSwitchStub,
        PageHeader: { template: '<header><slot /></header>' },
        PageLoading: { template: '<div data-testid="loading" />' },
        SectionCard: { template: '<section><slot /></section>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('/admin/member-permissions', () => {
  beforeEach(() => {
    apiMock.mockReset()
    notificationMock.success.mockReset()
    notificationMock.error.mockReset()
    teamStoreStub.myTeams = [{ id: 12, role: 'ADMIN' }]
    organizationStoreStub.myOrganizations = []
    scopeStoreStub.current = { type: 'team', id: '12', name: 'Team 12' }
  })

  it('取得失敗をエラー表示し、再試行後にAPIの3権限を表示する', async () => {
    apiMock.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(permissionResponse)
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="member-permissions-load-error"]').exists()).toBe(true)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(0)

    await wrapper.get('[data-testid="member-permissions-load-error-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="member-permissions-load-error"]').exists()).toBe(false)
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(3)
    expect(apiMock).toHaveBeenNthCalledWith(2, '/api/v1/admin/member-permissions', {
      query: { scopeType: 'TEAM', scopeId: 12 },
    })
  })

  it('GETした3権限を完全指定のPUT bodyで保存する', async () => {
    apiMock.mockResolvedValueOnce(permissionResponse).mockResolvedValueOnce(permissionResponse)
    const wrapper = await mountPage()

    await wrapper.get('[data-testid="save-member-permissions"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenNthCalledWith(2, '/api/v1/admin/member-permissions', {
      method: 'PUT',
      query: { scopeType: 'TEAM', scopeId: 12 },
      body: {
        permissions: [
          { name: 'MANAGE_SCHEDULES', enabled: true },
          { name: 'MANAGE_FILES', enabled: false },
          { name: 'MANAGE_POSTS', enabled: true },
        ],
      },
    })
    expect(notificationMock.success).toHaveBeenCalledOnce()
  })

  it('非ADMINでは設定APIを呼ばずfail-closedのエラーを表示する', async () => {
    teamStoreStub.myTeams = [{ id: 12, role: 'MEMBER' }]
    const wrapper = await mountPage()

    expect(apiMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="member-permissions-access-denied"]').exists()).toBe(true)
  })

  it('旧スコープの遅延応答を破棄し、現在スコープの表示値だけを現在スコープへ保存する', async () => {
    let resolveTeam!: (value: typeof permissionResponse) => void
    const delayedTeam = new Promise<typeof permissionResponse>((resolve) => {
      resolveTeam = resolve
    })
    const organizationResponse = {
      ...permissionResponse,
      data: {
        ...permissionResponse.data,
        scopeType: 'ORGANIZATION',
        scopeId: 33,
        permissions: [
          { name: 'MANAGE_SCHEDULES', enabled: false, inherited: false },
          { name: 'MANAGE_FILES', enabled: true, inherited: false },
          { name: 'MANAGE_POSTS', enabled: false, inherited: false },
        ],
      },
    }
    apiMock.mockImplementation(
      (_url: string, options: { method?: string; query: { scopeId: number } }) => {
        if (options.method === 'PUT') return Promise.resolve(organizationResponse)
        return options.query.scopeId === 12 ? delayedTeam : Promise.resolve(organizationResponse)
      },
    )
    organizationStoreStub.myOrganizations = [{ id: 33, role: 'ADMIN' }]
    const wrapper = await mountPage()

    scopeStoreStub.current = { type: 'organization', id: '33', name: 'Organization 33' }
    await nextTick()
    await flushPromises()
    resolveTeam(permissionResponse)
    await flushPromises()

    const toggles = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    expect(toggles.map((toggle) => toggle.element.checked)).toEqual([false, true, false])

    await wrapper.get('[data-testid="save-member-permissions"]').trigger('click')
    await flushPromises()
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/admin/member-permissions', {
      method: 'PUT',
      query: { scopeType: 'ORGANIZATION', scopeId: 33 },
      body: {
        permissions: [
          { name: 'MANAGE_SCHEDULES', enabled: false },
          { name: 'MANAGE_FILES', enabled: true },
          { name: 'MANAGE_POSTS', enabled: false },
        ],
      },
    })
  })
})
