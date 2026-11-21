import { defineComponent, h, reactive, type Component } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#app'
import LineSettings from '~/pages/admin/line-settings.vue'
import SnsSettings from '~/pages/admin/sns-settings.vue'

const { getConfig, updateConfig, createConfig, deleteConfig, listFeeds } = vi.hoisted(() => ({ getConfig: vi.fn(), updateConfig: vi.fn(), createConfig: vi.fn(), deleteConfig: vi.fn(), listFeeds: vi.fn() }))
const scope = reactive({ current: { type: 'team', id: 't' } })
mockNuxtImport('useScopeStore', () => () => scope)

mockNuxtImport('useAdminScopeGuard', () => vi.fn())
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useLineConfigApi', () => () => ({ getConfig, updateConfig, createConfig, deleteConfig }))
mockNuxtImport('useSnsFeedApi', () => () => ({ listFeeds, createFeed: vi.fn(), updateFeed: vi.fn(), deleteFeed: vi.fn(), previewFeed: vi.fn() }))
const HeaderStub = defineComponent({ props: { title: String, subtitle: String }, setup(props, { slots }) { return () => h('header', { 'data-testid': 'settings-header' }, [h('h1', props.title), props.subtitle ? h('p', { 'data-testid': 'page-header-subtitle' }, props.subtitle) : slots.subtitle?.(), slots.actions?.()]) } })
const ButtonStub = defineComponent({ props: { label: String, loading: Boolean, disabled: Boolean }, emits: ['click'], setup(props, { emit }) { return () => h('button', { disabled: props.loading || props.disabled, 'data-loading': String(props.loading), onClick: () => emit('click') }, props.label) } })
const DialogStub = defineComponent({ props: { visible: Boolean }, setup(props, { slots }) { return () => props.visible ? h('div', { 'data-testid': 'sns-create-dialog' }, slots.footer?.()) : null } })
const cleanups: Array<() => void> = []
let restoreLocale: (() => Promise<void>) | undefined
async function mountSettings(component: Component) {
  const wrapper = await mountSuspended(component, { global: { stubs: { PageHeader: HeaderStub, Button: ButtonStub, Dialog: DialogStub, DataTable: true, Column: true, SectionCard: { template: '<section><slot /></section>' } } } })
  cleanups.push(() => wrapper.unmount())
  await flushPromises()
  return wrapper
}
const config = { id: 1, channelId: 'test-channel', webhookSecret: null, botUserId: null, isActive: true, notificationEnabled: true }
describe('CMP-261014-2035 P11/P12 設定ヘッダ操作', () => {
  beforeEach(async () => {
    const i18n = useNuxtApp().$i18n
    const initialLocale = i18n.locale.value
    restoreLocale = async () => { await i18n.setLocale(initialLocale) }
    await i18n.loadLocaleMessages('ja')
    await i18n.setLocale('ja')
    scope.current = { type: 'team', id: 't' }
    getConfig.mockReset().mockResolvedValue({ data: config })
    listFeeds.mockReset().mockResolvedValue({ data: [] })
    updateConfig.mockReset().mockResolvedValue(undefined)
    createConfig.mockReset().mockResolvedValue(undefined)
    deleteConfig.mockReset().mockResolvedValue(undefined)
    vi.stubGlobal('confirm', vi.fn(() => true))
  })
  afterEach(async () => { cleanups.splice(0).forEach(cleanup => cleanup()); vi.unstubAllGlobals(); await restoreLocale?.(); restoreLocale = undefined })
  it('P11 config有りでは削除・保存をactionsへ置き、クリックで既存APIを呼ぶ', async () => {
    const wrapper = await mountSettings(LineSettings)
    const header = wrapper.get('[data-testid="settings-header"]')
    expect(header.get('[data-testid="page-header-subtitle"]').text()).toBe('LINE Messaging API との連携設定')
    expect(header.get('h1').text()).toBe('LINE設定')
    const buttons = header.findAll('button')
    expect(buttons.map(button => button.text())).toEqual(['削除', '保存'])
    await buttons[1]!.trigger('click')
    await flushPromises()
    expect(updateConfig).toHaveBeenCalledOnce()
    expect(updateConfig.mock.calls[0]?.slice(0, 2)).toEqual(['team', 't'])
    await buttons[0]!.trigger('click')
    await flushPromises()
    expect(deleteConfig).toHaveBeenCalledExactlyOnceWith('team', 't')
  })
  it('P11 config無しでは削除を表示せず保存だけを表示する', async () => {
    getConfig.mockResolvedValue({ data: null })
    const wrapper = await mountSettings(LineSettings)
    expect(wrapper.get('[data-testid="settings-header"]').findAll('button').map(button => button.text())).toEqual(['保存'])
  })
  it('P11 saving中は移行後の保存ボタンにもloadingを渡す', async () => {
    let finish!: () => void
    updateConfig.mockReturnValue(new Promise<void>(resolve => { finish = resolve }))
    const wrapper = await mountSettings(LineSettings)
    const buttons = wrapper.get('[data-testid="settings-header"]').findAll('button')
    expect(buttons, '削除と保存をactions内に維持する').toHaveLength(2)
    const button = buttons[1]!
    await button.trigger('click')
    expect(button.attributes('data-loading')).toBe('true')
    expect((button.element as HTMLButtonElement).disabled).toBe(true)
    finish()
    await flushPromises()
    expect(button.attributes('data-loading')).toBe('false')
  })
  it.each([['P11 LINE', LineSettings], ['P12 SNS', SnsSettings]] as const)('%sは個人スコープでヘッダ操作を表示しない', async (_name, component) => {
    scope.current = { type: 'personal', id: '' }
    const wrapper = await mountSettings(component)
    expect(wrapper.get('[data-testid="settings-header"]').findAll('button')).toHaveLength(0)
  })
  it('P12 SNS追加をactionsへ置きクリックで作成ダイアログを開く', async () => {
    const wrapper = await mountSettings(SnsSettings)
    const header = wrapper.get('[data-testid="settings-header"]')
    expect(header.get('[data-testid="page-header-subtitle"]').text()).toBe('Instagram・X のフィード連携を管理します')
    expect(header.get('h1').text()).toBe('SNS設定')
    expect(header.get('button').text()).toBe('アカウントを追加')
    await header.get('button').trigger('click')
    expect(wrapper.find('[data-testid="sns-create-dialog"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="sns-create-dialog"]').findAll('button').map(button => button.text())).toEqual(['キャンセル', '保存'])
  })
})
