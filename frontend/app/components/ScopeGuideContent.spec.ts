import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ScopeGuideContent from './ScopeGuideContent.vue'

const catalogMock = vi.fn()
const storageMock = vi.fn()

mockNuxtImport('useAdminDashboardApi', () => () => ({ getModuleCatalog: catalogMock }))
mockNuxtImport('useStorageUsageApi', () => () => ({ getMyStorageUsage: storageMock }))

async function mountGuide(scopeType: 'team' | 'organization') {
  const wrapper = await mountSuspended(ScopeGuideContent, {
    props: { scopeType, slug: scopeType === 'team' ? 'team-a' : 'org-a' },
    global: {
      stubs: {
        PageHeader: { template: '<header><slot /></header>' },
        PageLoading: { template: '<div data-testid="loading" />' },
        SectionCard: { template: '<section><slot /></section>' },
        Tag: { template: '<span />' },
        NuxtLink: { template: '<a><slot /></a>' },
        Message: { template: '<p><slot /></p>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('ScopeGuideContent', () => {
  beforeEach(() => {
    catalogMock.mockReset()
    storageMock.mockReset()
    storageMock.mockResolvedValue([])
  })

  it('チーム固有の機能カタログとQ&Aを表示する', async () => {
    catalogMock.mockResolvedValue({
      planLimit: 10,
      enabledCount: 1,
      modules: [{ moduleId: 1, name: '試合分析', description: '試合を分析', isEnabled: true }],
    })
    const wrapper = await mountGuide('team')

    expect(catalogMock).toHaveBeenCalledWith('team', 'team-a')
    expect(wrapper.text()).toContain('試合分析')
    expect(wrapper.text()).toContain('How is a team different from an organization?')
    expect(wrapper.text()).not.toContain('Is storage shared with teams?')
  })

  it('組織固有のカタログが403なら機能情報を表示しない', async () => {
    catalogMock.mockRejectedValue(new Error('403'))
    const wrapper = await mountGuide('organization')

    expect(catalogMock).toHaveBeenCalledWith('organization', 'org-a')
    expect(wrapper.find('[data-testid="scope-guide-load-error"]').exists()).toBe(true)
    expect(storageMock).not.toHaveBeenCalled()
  })

  it('組織ではチームと異なる機能カタログとQ&Aを表示する', async () => {
    catalogMock.mockResolvedValue({
      planLimit: 10,
      enabledCount: 1,
      modules: [{ moduleId: 2, name: '組織向け機能', description: '組織で使う', isEnabled: true }],
    })
    const wrapper = await mountGuide('organization')

    expect(catalogMock).toHaveBeenCalledWith('organization', 'org-a')
    expect(wrapper.text()).toContain('組織向け機能')
    expect(wrapper.text()).toContain('Is storage shared with teams?')
    expect(wrapper.text()).not.toContain('How is a team different from an organization?')
  })
})
