import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import VerifyEmailPage from './verify-email.vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  navigate: vi.fn(),
  query: {} as Record<string, string>,
}))
mockNuxtImport('useRoute', () => () => ({ query: mocks.query }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
const options = {
  global: {
    stubs: {
      Button: { props: ['label'], template: '<button>{{ label }}</button>' },
      NuxtLink: {
        name: 'NuxtLink',
        props: ['to'],
        template: '<a><slot /></a>',
      },
      LoadingBounce: true,
    },
  },
}

describe('登録確認後の招待への復帰', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockResolvedValue({})
    mocks.query = { redirect: '/invite/abc?folder=7', email: 'invite@example.test' }
  })
  it('登録案内のログイン導線へ招待URLを渡す', async () => {
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    expect(wrapper.findComponent({ name: 'NuxtLink' }).props('to')).toEqual({
      path: '/login',
      query: { redirect: '/invite/abc?folder=7' },
    })
  })
  it('認証成功のログインボタンでもcomputedの復帰先を渡す', async () => {
    mocks.query.token = 'verification-token'
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    await wrapper.find('button').trigger('click')
    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/login',
      query: { redirect: '/invite/abc?folder=7' },
    })
  })
  it('外部復帰先はログイン導線へ渡さない', async () => {
    mocks.query.redirect = '//evil.example'
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    expect(wrapper.findComponent({ name: 'NuxtLink' }).props('to')).toEqual({
      path: '/login',
      query: {},
    })
  })
})
