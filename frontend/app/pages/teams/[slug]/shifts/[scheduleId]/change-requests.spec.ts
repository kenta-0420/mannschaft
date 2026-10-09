import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ChangeRequestsPage from './change-requests.vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  error: vi.fn(),
  fetchRequests: vi.fn(),
  route: null as unknown as { params: { slug: string; scheduleId: string } },
  requests: null as unknown,
  isLoading: null as unknown,
}))

mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useAuthStore', () => () => ({ currentUser: { id: 4, systemRole: null } }))
mockNuxtImport('useNotification', () => () => ({ error: mocks.error }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useChangeRequest', () => () => ({
  requests: mocks.requests,
  isLoading: mocks.isLoading,
  fetchRequests: mocks.fetchRequests,
  review: vi.fn(),
  withdraw: vi.fn(),
}))

const RequestList = defineComponent({
  props: { isAdmin: Boolean },
  template: '<button v-if="isAdmin" data-review>review</button>',
})

async function mountPage() {
  const wrapper = await mountSuspended(ChangeRequestsPage, {
    global: {
      stubs: {
        ShiftChangeRequestList: RequestList,
        ShiftChangeRequestForm: true,
        PageHeader: true,
        SectionCard: { template: '<div><slot /></div>' },
      },
      mocks: { $t: (key: string) => key },
    },
  })
  await flushPromises()
  return wrapper
}

function permissions(roleName: string) {
  return { data: { roleName, permissions: ['MANAGE_SHIFTS'] } }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.route = reactive({ params: { slug: 'team-a', scheduleId: '1' } })
  mocks.requests = ref([])
  mocks.isLoading = ref(false)
  mocks.api.mockResolvedValue(permissions('MEMBER'))
})

describe('変更依頼のチーム審査権限', () => {
  it.each(['ADMIN', 'DEPUTY_ADMIN', 'SYSTEM_ADMIN'])('%s は審査操作を表示する', async (role) => {
    mocks.api.mockResolvedValue(permissions(role))
    const wrapper = await mountPage()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/teams/team-a/me/permissions')
    expect(wrapper.find('[data-review]').exists()).toBe(true)
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it('MEMBER は MANAGE_SHIFTS があっても審査操作を表示しない', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-review]').exists()).toBe(false)
  })

  it('外国チームの権限取得が拒否されたら操作を隠し、取得失敗を通知する', async () => {
    mocks.api.mockRejectedValue({ statusCode: 403 })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-review]').exists()).toBe(false)
    expect(mocks.error).toHaveBeenCalledWith('shift.hourlyRate.permissionLoadFailed')
  })

  it('権限取得のサーバー障害を権限不足として隠さない', async () => {
    mocks.api.mockRejectedValue({ statusCode: 500 })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-review]').exists()).toBe(false)
    expect(mocks.error).toHaveBeenCalledWith('shift.hourlyRate.permissionLoadFailed')
  })

  it('スコープ変更中は旧チームの審査操作を隠し、新チームの権限を反映する', async () => {
    mocks.api.mockResolvedValueOnce(permissions('ADMIN'))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-review]').exists()).toBe(true)
    let resolve!: (value: ReturnType<typeof permissions>) => void
    mocks.api.mockImplementationOnce(() => new Promise((done) => { resolve = done }))
    mocks.route.params.slug = 'team-b'
    await wrapper.vm.$nextTick()
    expect(mocks.api).toHaveBeenLastCalledWith('/api/v1/teams/team-b/me/permissions')
    expect(wrapper.find('[data-review]').exists()).toBe(false)
    resolve(permissions('MEMBER'))
    await flushPromises()
    expect(wrapper.find('[data-review]').exists()).toBe(false)
  })
})
