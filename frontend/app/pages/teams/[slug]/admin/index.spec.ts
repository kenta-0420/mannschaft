// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import en from '~/locales/en/admin_console.json'
import { mockComponent, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamAdmin from './index.vue'
import OrganizationAdmin from '~/pages/organizations/[slug]/admin/index.vue'

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'sample' } }))
mockComponent('NuxtLink', async () => (await import('vue-router')).RouterLink)

mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(true),
  isAdminOrDeputy: ref(true),
  loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))
mockNuxtImport('useScopeTabApi', () => () => ({
  getAdminActionRequired: vi.fn().mockResolvedValue({ totalPending: 0, domains: [] }),
}))

describe('管理コンソール設定カード', () => {
  it.each([
    ['teams', TeamAdmin],
    ['organizations', OrganizationAdmin],
  ] as const)('%sの設定カードは個別設定ではなく集約ハブを指す', async (scope, component) => {
    const router = createRouter({ history: createMemoryHistory(), routes: [] })
    const wrapper = mount(component, {
      global: { plugins: [router, createI18n({ legacy: false, locale: 'en', messages: { en } })] },
    })
    expect(wrapper.find(`a[href="/${scope}/sample/admin/settings"]`).exists()).toBe(true)
  })
})
