import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import InvitePage from './[token].vue'
import type { components } from '~/types/generated'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  navigate: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  auth: { isAuthenticated: false, loadFromStorage: vi.fn() },
}))
mockNuxtImport('useRoute', () => () => ({
  params: { token: 'abc-123' },
  fullPath: '/invite/abc-123?folder=7',
}))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useAuthStore', () => () => mocks.auth)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, te: () => true }))

const options = {
  global: {
    stubs: {
      SectionCard: { template: '<section><slot /></section>' },
      Avatar: { props: ['label'], template: '<span>{{ label }}</span>' },
      Tag: { props: ['value'], template: '<span>{{ value }}</span>' },
      Button: { props: ['label'], template: '<button>{{ label }}</button>' },
      PageLoading: true,
      InviteFolderPicker: {
        name: 'InviteFolderPicker',
        props: ['scopeType', 'modelValue'],
        emits: ['update:modelValue'],
        template: '<div data-testid="picker" />',
      },
      DashboardErrorState: {
        name: 'DashboardErrorState',
        props: ['error'],
        emits: ['retry'],
        template: `<button data-testid="retry" @click="$emit('retry')">retry</button>`,
      },
    },
  },
}
const preview: components['schemas']['InvitePreviewResponse'] = {
  targetName: '契約どおりの参加先',
  targetType: 'TEAM',
  roleName: 'MEMBER',
  valid: true,
}
async function mountPage() {
  const wrapper = await mountSuspended(InvitePage, options)
  await flushPromises()
  return wrapper
}

describe('招待プレビューのAPI契約と認証境界', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.auth.isAuthenticated = true
    mocks.api.mockReset().mockResolvedValue({ data: preview })
  })
  it('未認証ではAPIを呼ばずログインと登録に元URLを保持する', async () => {
    mocks.auth.isAuthenticated = false
    const wrapper = await mountPage()
    expect(mocks.api).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="picker"]').exists()).toBe(false)
    const buttons = wrapper.findAll('button')
    await buttons[0]!.trigger('click')
    expect(mocks.navigate).toHaveBeenLastCalledWith({
      path: '/login',
      query: { redirect: '/invite/abc-123?folder=7' },
    })
    await buttons[1]!.trigger('click')
    expect(mocks.navigate).toHaveBeenLastCalledWith({
      path: '/register',
      query: { redirect: '/invite/abc-123?folder=7' },
    })
  })
  it.each(['TEAM', 'ORGANIZATION'])(
    '実APIの%sを表示して任意フォルダ付きで参加する',
    async (targetType) => {
      mocks.api.mockResolvedValue({ data: { ...preview, targetType } })
      const wrapper = await mountPage()
      expect(wrapper.text()).toContain(preview.targetName)
      const picker = wrapper.findComponent({ name: 'InviteFolderPicker' })
      expect(picker.props('scopeType')).toBe(targetType)
      picker.vm.$emit('update:modelValue', 7)
      await wrapper.find('[data-testid="invite-join-button"]').trigger('click')
      await flushPromises()
      expect(mocks.api).toHaveBeenLastCalledWith('/api/v1/invite/abc-123/join', {
        method: 'POST',
        body: { folderId: 7 },
      })
      expect(mocks.navigate).toHaveBeenCalledWith('/dashboard')
    },
  )
  it('参加先名がnullでも代替表示し未選択フォルダで参加できる', async () => {
    mocks.api.mockResolvedValue({ data: { ...preview, targetName: null, roleName: null } })
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('auth.invite.unknown_target')
    expect(wrapper.text()).toContain('auth.invite.unknown_role')
    await wrapper.find('[data-testid="invite-join-button"]').trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenLastCalledWith('/api/v1/invite/abc-123/join', {
      method: 'POST',
      body: {},
    })
  })
  it.each(['valid-false', 'ROLE_002'])('%sのみ無効招待として表示する', async (condition) => {
    if (condition === 'ROLE_002')
      mocks.api.mockRejectedValue({ data: { error: { code: condition } }, statusCode: 400 })
    else mocks.api.mockResolvedValue({ data: { ...preview, valid: false } })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="invite-invalid"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="retry"]').exists()).toBe(false)
    expect(mocks.error).not.toHaveBeenCalled()
  })
  it.each([401, 403, 500, 0])(
    '%s取得失敗を無効招待にせず同じ取得で再試行する',
    async (statusCode) => {
      const failure = { statusCode, data: { error: { code: 'OTHER_ERROR' } } }
      mocks.api.mockRejectedValueOnce(failure)
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="invite-invalid"]').exists()).toBe(false)
      expect(wrapper.findComponent({ name: 'DashboardErrorState' }).props('error')).toEqual(failure)
      await wrapper.find('[data-testid="retry"]').trigger('click')
      await flushPromises()
      expect(mocks.api.mock.calls.slice(0, 2)).toEqual([
        ['/api/v1/invite/abc-123'],
        ['/api/v1/invite/abc-123'],
      ])
      expect(wrapper.find('[data-testid="invite-join-button"]').exists()).toBe(true)
    },
  )
  it.each([{}, { valid: true }, { valid: true, targetType: 'UNKNOWN' }])(
    '不正応答%jを無効招待にせず取得エラーにする',
    async (data) => {
      mocks.api.mockResolvedValue({ data })
      const wrapper = await mountPage()
      expect(wrapper.find('[data-testid="retry"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="invite-invalid"]').exists()).toBe(false)
    },
  )
})
