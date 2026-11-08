// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamPage from './public-settings.vue'
import OrganizationPage from '~/pages/organizations/[slug]/settings/public-settings.vue'
import common from '~/locales/en/common.json'
import publicMessages from '~/locales/en/public.json'

const state = vi.hoisted(() => ({
  allowed: true,
  roleName: 'ADMIN',
  permissions: vi.fn(),
  fetchTeamPublicSettings: vi.fn(),
  fetchOrgPublicSettings: vi.fn(),
  fetchPublicTeam: vi.fn(),
  fetchPublicOrganization: vi.fn(),
  updateTeamPublicSettings: vi.fn(),
  updateOrgPublicSettings: vi.fn(),
  handleApiError: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'private-scope' } }))
mockNuxtImport('usePublicApi', () => () => state)
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: ref(state.allowed),
  roleName: ref(state.roleName),
  loadPermissions: state.permissions,
}))
mockNuxtImport('useToast', () => () => ({ add: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: state.handleApiError }))

const wrappers: ReturnType<typeof mount>[] = []
async function mountPage(scope: 'team' | 'organization') {
  const page = scope === 'team' ? TeamPage : OrganizationPage
  const wrapper = mount(
    defineComponent({
      setup: () => ({ page }),
      template: '<Suspense><component :is="page" /></Suspense>',
    }),
    {
      global: {
        plugins: [
          createI18n({
            legacy: false,
            locale: 'en',
            messages: {
              ja: {},
              en: { ...common, ...publicMessages },
              zh: {},
              ko: {},
              es: {},
              de: {},
            },
          }),
        ],
        stubs: {
          SlugRenameField: true,
          PageLoading: true,
          Button: {
            props: ['label', 'type'],
            emits: ['click'],
            template:
              '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
          },
          ToggleSwitch: {
            props: ['modelValue'],
            emits: ['update:modelValue'],
            template:
              '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
          },
        },
      },
    },
  )
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  state.allowed = true
  state.roleName = 'ADMIN'
  vi.clearAllMocks()
  state.permissions.mockReset().mockResolvedValue({ ok: true })
  for (const fetch of [
    state.fetchTeamPublicSettings,
    state.fetchOrgPublicSettings,
    state.fetchPublicTeam,
    state.fetchPublicOrganization,
  ]) {
    fetch
      .mockReset()
      .mockResolvedValue({ numericId: 1217, timelinePostsPublic: true, publicEventsEnabled: true })
  }
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})

for (const scope of ['team', 'organization'] as const) {
  const read = () =>
    scope === 'team' ? state.fetchTeamPublicSettings : state.fetchOrgPublicSettings
  const write = () =>
    scope === 'team' ? state.updateTeamPublicSettings : state.updateOrgPublicSettings
  describe(`${scope}の管理公開設定`, () => {
    it('非公開スコープでも公開閲覧APIを使わず管理GETの現在値で初期化する', async () => {
      const wrapper = await mountPage(scope)
      expect(read()).toHaveBeenCalledWith('private-scope')
      expect(state.fetchPublicTeam).not.toHaveBeenCalled()
      expect(state.fetchPublicOrganization).not.toHaveBeenCalled()
      expect(
        (wrapper.get('[data-testid="toggle-timeline-posts-public"]').element as HTMLInputElement)
          .checked,
      ).toBe(true)
    })
    it('SYSTEM_ADMIN単独も既存isAdminOrDeputy許可で管理GETと設定フォームを利用できる', async () => {
      state.roleName = 'SYSTEM_ADMIN'
      state.allowed = true
      const wrapper = await mountPage(scope)
      expect(read()).toHaveBeenCalledWith('private-scope')
      expect(wrapper.find('form').exists()).toBe(true)
    })
    it('数値IDと変更後timeline値・取得済みevent値を必ず送る', async () => {
      const wrapper = await mountPage(scope)
      await wrapper.get('[data-testid="toggle-timeline-posts-public"]').setValue(false)
      await wrapper.get('form').trigger('submit')
      await flushPromises()
      expect(write()).toHaveBeenCalledWith(1217, {
        timelinePostsPublic: false,
        publicEventsEnabled: true,
      })
    })
    it('取得失敗では初期falseのフォームを出さず同じ管理GETで再試行する', async () => {
      read().mockRejectedValueOnce({ statusCode: 503 })
      const wrapper = await mountPage(scope)
      expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
      expect(wrapper.find('form').exists()).toBe(false)
      expect(write()).not.toHaveBeenCalled()
      await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
      await flushPromises()
      expect(read()).toHaveBeenCalledTimes(2)
      expect(wrapper.find('form').exists()).toBe(true)
    })
    it('権限取得失敗を設定値なしと偽らず再試行する', async () => {
      state.permissions.mockResolvedValueOnce({ ok: false, error: { statusCode: 503 } })
      const wrapper = await mountPage(scope)
      expect(wrapper.find('[data-testid="load-error-state"]').exists()).toBe(true)
      expect(wrapper.find('form').exists()).toBe(false)
      await wrapper.get('[data-testid="load-error-state-retry"]').trigger('click')
      await flushPromises()
      expect(state.permissions).toHaveBeenCalledTimes(2)
      expect(wrapper.find('form').exists()).toBe(true)
    })
    it('管理権限がない利用者には設定フォームを出さない', async () => {
      state.allowed = false
      const wrapper = await mountPage(scope)
      expect(wrapper.find('form').exists()).toBe(false)
      expect(write()).not.toHaveBeenCalled()
    })
  })
}
