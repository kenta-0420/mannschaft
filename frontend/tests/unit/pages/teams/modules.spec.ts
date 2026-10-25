import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { nextTick, ref } from 'vue'
import TeamModulesPage from '~/pages/teams/[slug]/modules.vue'

enableAutoUnmount(afterEach)

const { navigate } = vi.hoisted(() => ({ navigate: vi.fn() }))
const shell = {
  roleName: ref<string | null>(null),
  roleResolved: ref(false),
  roleLoadFailed: ref(false),
  isAdmin: ref(false),
}
vi.mock('~/composables/useTeamShellContext', () => ({ useTeamShellContext: () => shell }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' } }))
mockNuxtImport('navigateTo', () => navigate)

function mountPage() {
  return mount(TeamModulesPage, {
    global: { stubs: { ModuleSettingsPanel: { template: '<div data-testid="module-settings" />' } } },
  })
}

describe('チーム機能設定の直URLガード', () => {
  beforeEach(() => {
    navigate.mockReset()
    shell.roleName.value = null
    shell.roleResolved.value = false
    shell.roleLoadFailed.value = false
    shell.isAdmin.value = false
  })

  it('正常解決した非所属nullは管理画面を出さずhomeへ戻す', () => {
    shell.roleResolved.value = true
    const wrapper = mountPage()
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/teams/team-1')
    expect(wrapper.find('[data-testid="module-settings"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('未解決ADMINを先に戻さず、解決後に管理画面を出す', async () => {
    const wrapper = mountPage()
    expect(navigate).not.toHaveBeenCalled()
    shell.roleName.value = 'ADMIN'
    shell.isAdmin.value = true
    shell.roleResolved.value = true
    await nextTick()
    expect(navigate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="module-settings"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('解決済みADMINは管理画面を保持する', () => {
    shell.roleName.value = 'ADMIN'
    shell.isAdmin.value = true
    shell.roleResolved.value = true
    const wrapper = mountPage()
    expect(navigate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="module-settings"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('取得失敗を正常な非所属と混同して戻さない', () => {
    shell.roleResolved.value = true
    shell.roleLoadFailed.value = true
    const wrapper = mountPage()
    expect(navigate).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="module-settings"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('正常解決したMEMBERもhomeへ戻す', () => {
    shell.roleName.value = 'MEMBER'
    shell.roleResolved.value = true
    const wrapper = mountPage()
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/teams/team-1')
    wrapper.unmount()
  })
})
