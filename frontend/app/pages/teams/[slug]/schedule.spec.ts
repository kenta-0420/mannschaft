import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import SchedulePage from './schedule.vue'

const role = vi.hoisted(() => ({ admin: true, canManage: false }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {} }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: ref(role.admin),
  roleName: ref(role.admin ? 'ADMIN' : 'MEMBER'),
  can: () => role.canManage,
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useCalendarEvents', () => () => ({
  currentYear: ref(2026),
  currentMonth: ref(10),
  events: ref([]),
  loading: ref(false),
  loadEvents: vi.fn(),
  refresh: vi.fn(),
  onPrevMonth: vi.fn(),
  onNextMonth: vi.fn(),
}))
mockNuxtImport('useScheduleApi', () => () => ({}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

async function mountPage() {
  const wrapper = await mountSuspended(SchedulePage, {
    global: {
      stubs: {
        CalendarGrid: true,
        ScheduleMobileListView: true,
        EventDetailPanel: true,
        ScheduleEventForm: true,
        DashboardEmptyState: true,
      },
    },
  })
  await wrapper.vm.$i18n.setLocale('de')
  await flushPromises()
  return wrapper
}

describe('チーム予定の翻訳（CMP-261017-0854）', () => {
  beforeEach(() => {
    role.admin = true
    role.canManage = false
  })

  it('deの見出しと追加ボタンを表示し、表示中の言語変更に追従する', async () => {
    const wrapper = await mountPage()
    expect(wrapper.get('h1').text()).toBe('Terminplan')
    expect(wrapper.text()).toContain('Termin hinzufügen')

    await wrapper.vm.$i18n.setLocale('en')
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('Schedule')
    expect(wrapper.text()).toContain('Add event')
  })

  it.each([false, true])(
    'MEMBERの予定管理権限=%sに応じて翻訳済み追加ボタンを出し分ける',
    async (canManage) => {
      role.admin = false
      role.canManage = canManage
      const wrapper = await mountPage()
      expect(wrapper.text().includes('Termin hinzufügen')).toBe(canManage)
    },
  )
})
