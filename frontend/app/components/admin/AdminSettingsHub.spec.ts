// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, RouterLink } from 'vue-router'
import PrimeVue from 'primevue/config'
import en from '~/locales/en/common.json'
import { flushPromises, mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AdminSettingsHub from './AdminSettingsHub.vue'

const state = vi.hoisted(() => ({
  role: 'ADMIN',
  scopeAdmin: undefined as boolean | undefined,
  shift: false,
  loaded: true,
  permissions: vi.fn(),
  loadFlags: vi.fn(),
  probe: vi.fn(),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: ref(state.role),
  isScopeAdmin: ref(state.scopeAdmin ?? state.role === 'ADMIN'),
  loadPermissions: state.permissions,
}))
mockNuxtImport('useFeatureFlagStore', () => () => ({
  get publicLoaded() {
    return state.loaded
  },
  isEnabled: () => state.shift,
  loadPublicFlags: state.loadFlags,
}))
mockNuxtImport('useScopeTabApi', () => () => ({ getAdminActionRequired: state.probe }))

async function mountHub(scopeType: 'team' | 'organization' = 'team') {
  const router = createRouter({ history: createMemoryHistory(), routes: [] })
  const wrapper = mount(AdminSettingsHub, {
    props: { scopeType, slug: 'sample' },
    global: {
      plugins: [router, PrimeVue, createI18n({ legacy: false, locale: 'en', messages: { en } })],
      stubs: { NuxtLink: RouterLink, PageHeader: true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('既存設定の集約ハブ', () => {
  beforeEach(() => {
    state.role = 'ADMIN'
    state.scopeAdmin = undefined
    state.shift = false
    state.loaded = true
    state.permissions.mockReset().mockResolvedValue({ ok: true })
    state.loadFlags.mockReset().mockImplementation(async () => {
      state.loaded = true
    })
    state.probe.mockReset().mockRejectedValue({ statusCode: 403 })
  })

  it('シフトOFFでもFAQ・公開設定・TODO設定へ到達でき、シフト直リンクは残さない', async () => {
    const wrapper = await mountHub()
    for (const path of ['faq-settings', 'public-settings', 'todo-status-labels']) {
      expect(wrapper.find(`a[href="/teams/sample/settings/${path}"]`).exists()).toBe(true)
    }
    expect(wrapper.find('a[href$="/shift"]').exists()).toBe(false)
  })

  it('公開済みシフトは副管理者にも案内し、FAQ編集は案内しない', async () => {
    state.role = 'DEPUTY_ADMIN'
    state.shift = true
    const wrapper = await mountHub()
    expect(wrapper.find('a[href$="/shift"]').exists()).toBe(true)
    expect(wrapper.find('a[href$="/faq-settings"]').exists()).toBe(false)
    expect(wrapper.find('a[href$="/todo-status-labels"]').exists()).toBe(true)
  })

  it.each(['team', 'organization'] as const)(
    'SYSTEM_ADMINでも%sのADMINを兼任していれば管理設定へ案内する',
    async (scope) => {
      state.role = 'SYSTEM_ADMIN'
      state.scopeAdmin = true
      const wrapper = await mountHub(scope)
      expect(wrapper.find('a[href$="/faq-settings"]').exists()).toBe(true)
      expect(wrapper.find('a[href$="/public-settings"]').exists()).toBe(true)
      if (scope === 'organization') {
        expect(wrapper.find('a[href$="/notification-credits"]').exists()).toBe(true)
      }
      expect(state.probe).not.toHaveBeenCalled()
    },
  )

  it('SYSTEM_ADMIN単独はscopeAdmin=falseなら設定リンクを表示しない', async () => {
    state.role = 'SYSTEM_ADMIN'
    state.scopeAdmin = false
    const wrapper = await mountHub()
    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(state.probe).toHaveBeenCalledWith('TEAM', 'sample', 0)
  })

  it.each(['team', 'organization'] as const)(
    'SYSTEM_ADMIN兼任の%s副管理者は正準API成功時だけ副管理者向けリンクを表示する',
    async (scope) => {
      state.role = 'SYSTEM_ADMIN'
      state.scopeAdmin = false
      state.shift = true
      state.probe.mockResolvedValue({})
      const wrapper = await mountHub(scope)
      expect(state.probe).toHaveBeenCalledWith(
        scope === 'team' ? 'TEAM' : 'ORGANIZATION',
        'sample',
        0,
      )
      expect(wrapper.find('a[href$="/public-settings"]').exists()).toBe(true)
      expect(wrapper.find('a[href$="/faq-settings"]').exists()).toBe(false)
      expect(wrapper.find('a[href$="/notification-credits"]').exists()).toBe(false)
      expect(wrapper.find('a[href$="/todo-status-labels"]').exists()).toBe(scope === 'team')
    },
  )

  it('SYSTEM_ADMINの正準APIが503なら拒否と混同せず、再試行で権限・正準API・フラグを再取得する', async () => {
    state.role = 'SYSTEM_ADMIN'
    state.scopeAdmin = false
    state.loaded = false
    state.probe.mockRejectedValueOnce({ statusCode: 503 }).mockResolvedValueOnce({})
    const wrapper = await mountHub()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('a[href$="/public-settings"]').exists()).toBe(true)
    expect(state.permissions).toHaveBeenCalledTimes(2)
    expect(state.probe).toHaveBeenCalledTimes(2)
    expect(state.loadFlags).toHaveBeenCalledTimes(1)
  })

  it('旧slugの正準API成功が遅れても、新slugの403拒否を上書きしない', async () => {
    state.role = 'SYSTEM_ADMIN'
    state.scopeAdmin = false
    let allowOld!: () => void
    const oldResult = new Promise<object>((resolve) => {
      allowOld = () => resolve({})
    })
    state.probe.mockImplementationOnce(() => oldResult).mockRejectedValueOnce({ statusCode: 403 })
    const wrapper = await mountHub()
    await wrapper.setProps({ slug: 'other' })
    await flushPromises()
    expect(wrapper.findAll('a')).toHaveLength(0)
    allowOld()
    await flushPromises()
    expect(wrapper.findAll('a')).toHaveLength(0)
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(false)
    expect(state.probe.mock.calls).toEqual([
      ['TEAM', 'sample', 0],
      ['TEAM', 'other', 0],
    ])
  })

  it('組織の副管理者へADMIN限定のFAQ・通知クレジット・TODO設定を案内しない', async () => {
    state.role = 'DEPUTY_ADMIN'
    const wrapper = await mountHub('organization')
    expect(wrapper.find('a[href$="/public-settings"]').exists()).toBe(true)
    expect(wrapper.findAll('a')).toHaveLength(1)
    expect(state.loadFlags).not.toHaveBeenCalled()
  })

  it.each(['MEMBER', 'GUEST', 'SYSTEM_ADMIN'])(
    'スコープ管理権限のない%sには設定リンクを表示しない',
    async (role) => {
      state.role = role
      const wrapper = await mountHub()
      expect(wrapper.findAll('a')).toHaveLength(0)
    },
  )

  it('権限取得失敗を空と偽らず、再試行で設定一覧を復旧する', async () => {
    state.permissions.mockResolvedValueOnce({ ok: false, error: new Error('offline') })
    const wrapper = await mountHub()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    expect(wrapper.findAll('a')).toHaveLength(0)
    await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('a[href$="/faq-settings"]').exists()).toBe(true)
  })

  it('フラグ取得失敗をOFFと偽らず、同じ処理の再試行で公開状態を反映する', async () => {
    state.loaded = false
    state.shift = true
    state.loadFlags.mockRejectedValueOnce(new Error('offline'))
    const wrapper = await mountHub()
    expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
    await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('a[href$="/shift"]').exists()).toBe(true)
  })
})
