import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { reactive } from 'vue'
import DashboardStorageSummary from '~/components/dashboard/DashboardStorageSummary.vue'
import type { StorageScopeUsage } from '~/types/storage'

const getMyStorageUsage = vi.fn()
const handleApiError = vi.fn()
const dashboardStore = reactive<{
  selectedTeamId: string | null
  selectedOrgId: string | null
  teamTabPage: number
  loadFromStorage: ReturnType<typeof vi.fn>
  loadTabs: ReturnType<typeof vi.fn>
}>({
  selectedTeamId: 'team-a',
  selectedOrgId: 'org-a',
  teamTabPage: 0,
  loadFromStorage: vi.fn(),
  loadTabs: vi.fn().mockResolvedValue(undefined),
})

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, string>) => {
    if (params?.scope) return `${key}:${params.scope}`
    if (params?.percent) return `${key}:${params.percent}`
    return key
  },
}))
mockNuxtImport('useStorageUsageApi', () => () => ({ getMyStorageUsage }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useScopeDashboardStore', () => () => dashboardStore)
mockNuxtImport('useAuthStore', () => () => ({ isAuthenticated: false, loadFromStorage: vi.fn() }))

const stubs = {
  DashboardWidgetCard: { template: '<section><header><slot name="actions" /></header><slot /></section>' },
  NuxtLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
  Button: { template: '<button @click="$attrs.onClick"><slot />{{ label }}</button>', props: ['label'] },
  Message: { template: '<div><slot /></div>' },
  Skeleton: { template: '<div class="skeleton" />' },
}

function usage(scopeType: StorageScopeUsage['scopeType'], overrides: Partial<StorageScopeUsage> = {}): StorageScopeUsage {
  return {
    scopeType, scopeId: 1, scopeName: scopeType === 'PERSONAL' ? '個人' : scopeType === 'TEAM' ? 'Team Actual' : 'Organization Actual', slug: scopeType === 'TEAM' ? 'team-a' : scopeType === 'ORGANIZATION' ? 'org-a' : null,
    usedBytes: 80, fileCount: 1, includedBytes: 100, maxBytes: 100, usagePercent: 80, ...overrides,
  }
}

async function mountSummary() {
  const wrapper = await mountSuspended(DashboardStorageSummary, { attachTo: document.body, global: { stubs } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  getMyStorageUsage.mockReset()
  handleApiError.mockReset()
  dashboardStore.selectedTeamId = 'team-a'
  dashboardStore.selectedOrgId = 'org-a'
})
afterEach(() => { document.body.innerHTML = '' })

describe('DashboardStorageSummary', () => {
  it('3スコープを1回のAPI呼出で表示し、選択slug切替に追随する', async () => {
    getMyStorageUsage.mockResolvedValue([usage('PERSONAL'), usage('TEAM'), usage('ORGANIZATION')])
    const wrapper = await mountSummary()
    expect(getMyStorageUsage).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('[data-testid^="storage-card-"]')).toHaveLength(3)
    expect(wrapper.get('[data-testid="storage-card-0"]').text()).toContain('80 B / 100 B')
    expect(wrapper.get('[data-testid="storage-card-0"] .text-amber-700')).toBeTruthy()
    expect(wrapper.get('[data-testid="storage-card-0"]').text()).toContain('scopeDashboard.storageSummary.personal')
    expect(wrapper.get('[data-testid="storage-card-0"]').text()).not.toContain('個人')
    expect(wrapper.get('[data-testid="storage-card-0"] [role="progressbar"]').attributes('aria-label')).toContain('scopeDashboard.storageSummary.personal')
    expect(wrapper.get('[data-testid="storage-card-1"] [role="progressbar"]').attributes('aria-label')).toContain('Team Actual')
    dashboardStore.selectedTeamId = 'other-team'
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="storage-card-1"]').text()).toContain('scopeDashboard.storageSummary.not_available')
    dashboardStore.selectedTeamId = '1'
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="storage-card-1"]').text()).not.toContain('scopeDashboard.storageSummary.not_available')
  })

  it('容量枠未設定・未選択・境界色・100%クランプを扱う', async () => {
    getMyStorageUsage.mockResolvedValue([
      usage('PERSONAL', { includedBytes: 0, usagePercent: 100 }),
      usage('TEAM', { usagePercent: 90 }),
      usage('ORGANIZATION', { usagePercent: 120 }),
    ])
    const wrapper = await mountSummary()
    expect(wrapper.text()).toContain('scopeDashboard.storageSummary.unconfigured')
    expect(wrapper.get('[data-testid="storage-card-0"]').find('[role="progressbar"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="storage-card-0"]').text()).not.toContain('100.0%')
    expect(wrapper.get('[data-testid="storage-card-1"] .text-red-600')).toBeTruthy()
    expect(wrapper.get('[data-testid="storage-card-2"] [role="progressbar"]').attributes('aria-valuenow')).toBe('100')
    expect(wrapper.get('[data-testid="storage-card-2"] [role="progressbar"]').attributes('aria-valuetext')).toContain('120.0')
    expect(wrapper.get('[data-testid="storage-card-2"] .text-red-600').text()).toContain('120.0%')
    dashboardStore.selectedOrgId = null
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="storage-card-2"]').text()).toContain('scopeDashboard.storageSummary.not_available')
  })

  it('API失敗時にインラインエラーと再試行を表示する', async () => {
    getMyStorageUsage.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce([usage('PERSONAL')])
    const wrapper = await mountSummary()
    expect(handleApiError).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-testid="storage-error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="storage-retry"]').classes()).toContain('min-h-11')
    expect(wrapper.get('[data-testid="storage-retry"]').classes()).toContain('min-w-11')
    await wrapper.get('[data-testid="storage-retry"]').trigger('click')
    await flushPromises()
    expect(getMyStorageUsage).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="storage-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="storage-card-0"]').exists()).toBe(true)
  })

  it('詳細リンクとレスポンシブ3列/1列構造を持つ', async () => {
    getMyStorageUsage.mockResolvedValue([])
    const wrapper = await mountSummary()
    expect(wrapper.get('a[href="/settings/storage"]')).toBeTruthy()
    expect(wrapper.html()).toContain('grid-cols-1')
    expect(wrapper.html()).toContain('md:grid-cols-3')
    expect(wrapper.get('a[href="/settings/storage"]').classes()).toContain('min-h-11')
  })
})
