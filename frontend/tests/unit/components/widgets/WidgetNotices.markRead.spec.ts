// @vitest-environment happy-dom
/**
 * CMP-261030-0652: WidgetNotices の既読化（実 mount）。
 * 既読の通知で POST /read を送らない・失敗しても未処理の reject を出さない・
 * 応答前にタブを切り替えても押した通知が既読になる、をコンポーネントの実装そのもので検証する。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, onMounted, ref, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, RouterLink } from 'vue-router'
import WidgetNotices from '~/components/widgets/WidgetNotices.vue'

const mocks = vi.hoisted(() => {
  const m = {
  getNotices: vi.fn(),
  markNoticeRead: vi.fn(),
  markAllNoticesRead: vi.fn(),
  getPlatformAnnouncements: vi.fn(),
  api: vi.fn(),
  captureQuiet: vi.fn(),
  success: vi.fn(),
  fetchActionRequired: vi.fn(),
  store: {
    foldersFor: vi.fn(),
    unreadCountOf: vi.fn(),
    refreshNotificationSummary: vi.fn(),
    fetchAll: vi.fn(),
  },
  }
  return { ...m, dashboardApi: () => ({
    getNotices: m.getNotices,
    markNoticeRead: m.markNoticeRead,
    markAllNoticesRead: m.markAllNoticesRead,
    getPlatformAnnouncements: m.getPlatformAnnouncements,
  }) }
})


vi.mock('~/composables/useDashboardApi', () => ({ useDashboardApi: mocks.dashboardApi }))
vi.mock('~/composables/useApi', () => ({ useApi: () => mocks.api }))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: mocks.success }),
}))
vi.mock('~/composables/usePersonalActionRequired', () => ({
  usePersonalActionRequired: () => ({ fetchActionRequired: mocks.fetchActionRequired }),
}))
vi.mock('~/composables/useScopeFoldersStore', () => ({ useScopeFoldersStore: () => mocks.store }))

vi.mock('#imports', () => ({
  ref,
  computed,
  watch,
  onMounted,
  useDashboardApi: mocks.dashboardApi,
  useApi: () => mocks.api,
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
  useNotification: () => ({ success: mocks.success }),
  usePersonalActionRequired: () => ({ fetchActionRequired: mocks.fetchActionRequired }),
  useScopeFoldersStore: () => mocks.store,
  useI18n: () => ({ t: (key: string) => key }),
}))

const i18n = createI18n({ legacy: false, locale: 'ja', missingWarn: false, fallbackWarn: false, messages: { ja: {}, en: {}, zh: {}, ko: {}, es: {}, de: {} } })

const stubs = {
  DashboardWidgetCard: { template: '<div><slot /></div>' },
  DashboardEmptyState: { template: '<div />' },
  CirculationConfirmModal: { template: '<div />' },
  SurveyAnswerModal: { template: '<div />' },
  AttendanceQuickModal: { template: '<div />' },
  Badge: { template: '<span />' },
  Button: { template: '<button type="button" />' },
  NuxtLink: RouterLink,
}

const apiNotice = (id: number, isRead: boolean, actionUrl: string | null) => ({
  id,
  type: 'SYSTEM',
  title: `通知${id}`,
  body: null,
  is_read: isRead,
  created_at: '2026-11-10T09:00:00',
  action_url: actionUrl,
})

const page = { template: '<div />' }
const makeRouter = () => createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: page },
    { path: '/schedules/1', component: page },
    { path: '/notifications', component: page },
  ],
})

async function mountWidget(items: ReturnType<typeof apiNotice>[]) {
  mocks.getNotices.mockResolvedValue({ data: { items } })
  const router = makeRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(WidgetNotices, { global: { stubs, plugins: [i18n, router], mocks: { $t: (k: string) => k } } })
  await flushPromises()
  return Object.assign(wrapper, { router })
}

const dotOf = (wrapper: ReturnType<typeof mount>, id: number) => {
  const row = wrapper.findAll('.divide-y > div').find(r => r.text().includes(`通知${id}`))
  return row!.get('div.h-2.w-2')
}

describe('WidgetNotices 既読化（CMP-261030-0652）', () => {
  const unhandled: unknown[] = []
  const onUnhandled = (reason: unknown) => {
    unhandled.push(reason)
  }

  beforeEach(() => {
    vi.clearAllMocks()
    unhandled.length = 0
    process.on('unhandledRejection', onUnhandled)
    mocks.markNoticeRead.mockResolvedValue(undefined)
    mocks.getPlatformAnnouncements.mockResolvedValue({ data: [] })
    mocks.fetchActionRequired.mockResolvedValue({ items: [] })
    mocks.store.foldersFor.mockImplementation((scope: string) =>
      scope === 'TEAM' ? [{ id: 11, name: '部活', isDefault: false }] : [])
    mocks.store.unreadCountOf.mockReturnValue(0)
    mocks.store.refreshNotificationSummary.mockResolvedValue(undefined)
    mocks.store.fetchAll.mockResolvedValue(undefined)
    mocks.api.mockResolvedValue({
      data: [{
        id: 2, notificationType: 'SYSTEM', title: '通知2', body: null, isRead: false,
        actionUrl: null, createdAt: '2026-11-10T09:00:00',
      }],
    })
  })

  afterEach(() => {
    process.off('unhandledRejection', onUnhandled)
  })

  it('AC-7 既読の通知を押しても markNoticeRead は呼ばれない（リンク・テキストの両方）', async () => {
    const wrapper = await mountWidget([apiNotice(1, true, '/schedules/1'), apiNotice(3, true, null)])

    await wrapper.get('a[href="/schedules/1"]').trigger('click')
    await wrapper.findAll('p').find(p => p.text() === '通知3')!.trigger('click')
    await flushPromises()

    expect(mocks.markNoticeRead).not.toHaveBeenCalled()
    // NuxtLink の既読通知は POST せずに遷移先へ移る
    expect(wrapper.router.currentRoute.value.fullPath).toBe('/schedules/1')
    wrapper.unmount()
  })

  it('AC-8 未読の通知を押すと markNoticeRead が1回だけ呼ばれ、表示が既読になる', async () => {
    const wrapper = await mountWidget([apiNotice(1, false, null)])
    expect(dotOf(wrapper, 1).classes()).toContain('bg-primary')

    await wrapper.findAll('p').find(p => p.text() === '通知1')!.trigger('click')
    await flushPromises()

    expect(mocks.markNoticeRead).toHaveBeenCalledTimes(1)
    expect(mocks.markNoticeRead).toHaveBeenCalledWith(1)
    expect(dotOf(wrapper, 1).classes()).toContain('bg-surface-300')
    wrapper.unmount()
  })

  it('AC-9 markNoticeRead が reject しても未処理の reject を出さず、captureQuiet に記録して未読のまま', async () => {
    mocks.markNoticeRead.mockRejectedValue(new Error('409'))
    const wrapper = await mountWidget([apiNotice(1, false, null)])

    await wrapper.findAll('p').find(p => p.text() === '通知1')!.trigger('click')
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(unhandled).toHaveLength(0)
    expect(mocks.captureQuiet).toHaveBeenCalled()
    expect(dotOf(wrapper, 1).classes()).toContain('bg-primary')
    wrapper.unmount()
  })

  it('AC-14 応答前に別タブへ切り替えても、成功後に元のタブへ戻ると押した通知は既読になっている', async () => {
    let resolveRead: () => void = () => {}
    mocks.markNoticeRead.mockImplementation(() => new Promise<void>((resolve) => {
      resolveRead = resolve
    }))
    const wrapper = await mountWidget([apiNotice(1, false, null)])

    await wrapper.findAll('p').find(p => p.text() === '通知1')!.trigger('click')
    await wrapper.get('[data-testid="widget-notices-tab-TEAM-11"]').trigger('click')
    await flushPromises()
    resolveRead()
    await flushPromises()
    await wrapper.get('[data-testid="widget-notices-tab-all"]').trigger('click')
    await flushPromises()

    expect(dotOf(wrapper, 1).classes()).toContain('bg-surface-300')
    wrapper.unmount()
  })
})
