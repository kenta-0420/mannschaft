import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import DashboardConfigDialog from './DashboardConfigDialog.vue'

const fetchSettings = vi.fn().mockResolvedValue(undefined)
mockNuxtImport('useDashboardWidgetVisibility', () => () => ({
  settings: ref([]), loading: ref(false), error: ref(null), fetch: fetchSettings,
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))

describe('DashboardConfigDialog 遅延初回表示', () => {
  it('初回から開いた管理者設定を一度取得し、再表示でも既存の再取得条件を保つ', async () => {
    fetchSettings.mockClear()
    const wrapper = await mountSuspended(DashboardConfigDialog, {
      props: { visible: true, widgets: [], isVisible: () => true, scopeType: 'team', scopeId: 'team-a', isAdminOrDeputy: true },
      global: { stubs: { Dialog: { template: '<div><slot /></div>' }, Tabs: { template: '<div><slot /></div>' }, TabList: true, TabPanels: true } },
    })
    await flushPromises()
    expect(fetchSettings).toHaveBeenCalledOnce()
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(fetchSettings).toHaveBeenCalledTimes(2)
  })
})
