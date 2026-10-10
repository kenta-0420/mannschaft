import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import type { components } from '~/types/generated'
import InvitePage from './[token].vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  success: vi.fn(),
  handleApiError: vi.fn(),
  navigateTo: vi.fn(),
  auth: { isAuthenticated: true },
}))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useAuthStore', () => () => mocks.auth)
mockNuxtImport('useNotification', () => () => ({ success: mocks.success }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useRoute', () => () => ({ params: { token: 'wire-token' } }))
mockNuxtImport('navigateTo', () => mocks.navigateTo)

const ButtonStub = defineComponent({
  props: { label: String, loading: Boolean },
  emits: ['click'],
  setup(props, { emit }) {
    return () => h('button', { disabled: props.loading, onClick: () => emit('click') }, props.label)
  },
})
const FolderPickerStub = defineComponent({
  props: { modelValue: Number, scopeType: String },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('button', {
      'data-testid': 'folder-picker',
      'data-scope-type': props.scopeType,
      onClick: () => emit('update:modelValue', 11),
    }, 'フォルダ選択')
  },
})
const wrappers: VueWrapper[] = []
type Preview = components['schemas']['InvitePreviewResponse']
const teamPreview: Preview = { targetName: '部活チーム', targetType: 'TEAM', roleName: 'MEMBER', valid: true }

async function mountPreview(data: unknown = teamPreview) {
  mocks.api.mockResolvedValueOnce({ data })
  const wrapper = await mountSuspended(InvitePage, {
    global: {
      stubs: {
        Button: ButtonStub,
        InviteFolderPicker: FolderPickerStub,
        Avatar: true,
        Tag: true,
        PageLoading: true,
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.auth.isAuthenticated = true
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})

describe('招待プレビューの実 API 契約', () => {
  it.each([
    { targetType: 'TEAM', targetName: '部活チーム' },
    { targetType: 'ORGANIZATION', targetName: '町内会' },
  ])('valid=true の $targetType は参加導線と対応フォルダ選択を表示する', async (scope) => {
    const wrapper = await mountPreview({ ...teamPreview, ...scope })
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/invite/wire-token')
    expect(wrapper.text()).toContain(scope.targetName)
    expect(wrapper.text()).toContain('メンバー')
    expect(wrapper.find('[data-testid="invite-join-button"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="folder-picker"]').attributes('data-scope-type')).toBe(scope.targetType)
    expect(wrapper.text()).not.toContain('有効期限')
    expect(wrapper.text()).not.toContain('無期限')
  })

  it('未ログインでも実 wire の有効招待はログイン導線を表示する', async () => {
    mocks.auth.isAuthenticated = false
    const wrapper = await mountPreview()
    const login = wrapper.findAll('button').find(button => button.text() === 'ログインして参加')!
    await login.trigger('click')
    expect(mocks.navigateTo).toHaveBeenCalledWith('/login?redirect=/invite/wire-token')
    expect(wrapper.find('[data-testid="invite-join-button"]').exists()).toBe(false)
  })

  it('valid=false は無効表示となり参加導線を出さない', async () => {
    const wrapper = await mountPreview({ ...teamPreview, valid: false })
    expect(wrapper.text()).toContain('この招待リンクは無効です')
    expect(wrapper.find('[data-testid="invite-join-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="folder-picker"]').exists()).toBe(false)
  })

  it.each([null, undefined, ''])('targetName/roleName=%s でも有効招待を安全に表示する', async (value) => {
    const wrapper = await mountPreview({ ...teamPreview, targetName: value, roleName: value })
    expect(wrapper.get('h2').text()).toBe('チーム')
    expect(wrapper.text()).not.toContain('参加ロール')
    expect(wrapper.find('[data-testid="invite-join-button"]').exists()).toBe(true)
  })

  it.each([
    { name: '旧形式', type: 'TEAM', roleName: 'MEMBER', isValid: true },
    { ...teamPreview, valid: undefined },
    { ...teamPreview, valid: 'true' },
    { ...teamPreview, targetType: 'PERSONAL' },
    { ...teamPreview, targetName: 123 },
    { ...teamPreview, roleName: {} },
    null,
  ])('不正なプレビュー応答は参加導線を表示しない: %j', async (data) => {
    const wrapper = await mountPreview(data)
    expect(wrapper.text()).toContain('招待リンクが無効です')
    expect(wrapper.find('[data-testid="invite-join-button"]').exists()).toBe(false)
  })

  it.each([false, true])('フォルダ選択=%s の参加は空の 200 応答で成功する', async (selectFolder) => {
    const wrapper = await mountPreview()
    if (selectFolder) await wrapper.get('[data-testid="folder-picker"]').trigger('click')
    // ResponseEntity<Void> の 200 はレスポンス JSON を持たない。
    mocks.api.mockResolvedValueOnce(undefined)
    await wrapper.get('[data-testid="invite-join-button"]').trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenLastCalledWith('/api/v1/invite/wire-token/join', {
      method: 'POST', body: selectFolder ? { folderId: 11 } : {},
    })
    expect(mocks.success).toHaveBeenCalledWith('チームに参加しました')
    expect(mocks.navigateTo).toHaveBeenCalledWith('/dashboard')
    expect(mocks.handleApiError).not.toHaveBeenCalled()
  })

  it('参加失敗時はエラーハンドラへ渡し成功遷移しない', async () => {
    const wrapper = await mountPreview()
    const failure = new Error('join failed')
    mocks.api.mockRejectedValueOnce(failure)
    await wrapper.get('[data-testid="invite-join-button"]').trigger('click')
    await flushPromises()
    expect(mocks.handleApiError).toHaveBeenCalledWith(failure, '招待参加')
    expect(mocks.success).not.toHaveBeenCalled()
    expect(mocks.navigateTo).not.toHaveBeenCalled()
  })
})
