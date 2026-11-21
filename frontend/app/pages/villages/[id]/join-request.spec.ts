// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, ref } from 'vue'
import Page from './join-request.vue'
import DashboardErrorState from '~/components/DashboardErrorState.vue'

// ページ・取得失敗表示は実物。申請 API と村コンテキストの境界だけ差し替える。
const mocks = vi.hoisted(() => ({
  listMyJoinRequests: vi.fn(),
  listJoinRequests: vi.fn(),
  createJoinRequest: vi.fn(),
  showError: vi.fn(),
}))
mockNuxtImport('definePageMeta', () => () => undefined)
mockNuxtImport('useRoute', () => () => ({ params: { id: '7' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useVillageMembershipApi', () => () => ({
  createJoinRequest: mocks.createJoinRequest,
  listMyJoinRequests: mocks.listMyJoinRequests,
  listJoinRequests: mocks.listJoinRequests,
  approveJoinRequest: vi.fn(),
  rejectJoinRequest: vi.fn(),
  withdrawJoinRequest: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({
  showSuccess: vi.fn(),
  showError: mocks.showError,
  showWarn: vi.fn(),
}))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (v: string) => v }))
vi.mock('~/composables/useVillageContext', () => ({
  useVillageContext: () => ({
    village: ref({ id: 7, joinPolicy: 'APPROVAL' }),
    perms: computed(() => ({ isMember: false, isAdmin: false, isHeadman: false, myRole: null })),
    currentUserId: ref(100),
    refresh: vi.fn(),
  }),
}))

function httpError(status: number): Error & { statusCode: number } {
  return Object.assign(new Error(`HTTP ${status}`), { statusCode: status })
}

let wrapper: VueWrapper | undefined
function renderPage(): VueWrapper {
  wrapper = mount(Page, {
    global: {
      components: { DashboardErrorState },
      stubs: {
        PageHeader: true,
        VillageJoinRequestGuideModal: true,
        Message: true,
        Dialog: true,
        Tabs: true,
        TabList: true,
        Tab: true,
        DataTable: true,
        Column: true,
        Tag: true,
        Textarea: true,
        SectionCard: { template: '<section><slot /></section>' },
        Button: {
          props: ['label'],
          emits: ['click'],
          template: '<button v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>',
        },
      },
    },
  })
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.listMyJoinRequests.mockResolvedValue([])
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('村参加申請: 自分の申請の取得失敗', () => {
  it('取得に失敗したら申請フォームを出さず、取得失敗の表示と再試行を出す（二重申請の防止）', async () => {
    mocks.listMyJoinRequests.mockRejectedValueOnce(httpError(500))
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="join-request-submit"]').exists()).toBe(false)
    expect(wrapper!.find('[data-testid="join-request-mine-error-state"]').exists()).toBe(true)
  })

  it('再試行で成功し申請が無ければ、申請フォームを出す', async () => {
    mocks.listMyJoinRequests.mockRejectedValueOnce(httpError(500))
    renderPage()
    await flushPromises()

    await wrapper!.get('[data-testid="join-request-mine-error-state-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper!.find('[data-testid="join-request-mine-error-state"]').exists()).toBe(false)
    expect(wrapper!.find('[data-testid="join-request-submit"]').exists()).toBe(true)
  })

  it('取得に成功し申請が無ければ、これまでどおり申請フォームを出す', async () => {
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="join-request-submit"]').exists()).toBe(true)
    expect(wrapper!.find('[data-testid="join-request-mine-error-state"]').exists()).toBe(false)
  })
})
