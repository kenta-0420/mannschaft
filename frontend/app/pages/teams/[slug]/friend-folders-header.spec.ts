import { computed, defineComponent, h, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import FriendFoldersPage from '~/pages/teams/[slug]/friend-folders.vue'

const state = vi.hoisted(() => ({ role: 'ADMIN', permitted: false, count: 19 }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 't' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string, params?: { remaining?: number }) => key + (params ? ':' + params.remaining : '') }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdmin: computed(() => state.role === 'ADMIN'), isAdminOrDeputy: computed(() => ['ADMIN', 'DEPUTY_ADMIN'].includes(state.role)), can: () => state.permitted, loadPermissions: vi.fn() }))
mockNuxtImport('useFriendFoldersApi', () => () => ({ deleteFolder: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useConfirmDialog', () => () => ({ confirmAction: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ warn: vi.fn(), success: vi.fn() }))
const header = defineComponent({ props: { title: String, subtitle: String }, setup(props, { slots }) { return () => h('header', { 'data-testid': 'folder-header' }, [h('h1', props.title), props.subtitle ? h('p', { 'data-testid': 'page-header-subtitle' }, props.subtitle) : null, slots.actions?.()]) } })
const list = defineComponent({ setup(_props, { expose }) { const folders = ref(Array.from({ length: state.count }, (_, id) => ({ id }))); expose({ folders, refresh: vi.fn() }); return () => h('section') } })
const dialog = defineComponent({ props: { modelValue: Boolean }, setup(props) { return () => h('div', { 'data-testid': 'folder-dialog', 'data-open': String(props.modelValue) }) } })
let wrapper: Awaited<ReturnType<typeof mountPage>> | undefined
async function mountPage() {
  return mountSuspended(FriendFoldersPage, { global: { stubs: { PageHeader: header, FriendFolderList: list, FriendFolderFormDialog: dialog, FriendFolderMembersPanel: true } } })
}
describe('CMP-261014-2035 P10 friend-foldersヘッダ', () => {
  beforeEach(() => { state.role = 'ADMIN'; state.permitted = false; state.count = 19 })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined })
  it.each([['ADMIN', false], ['DEPUTY_ADMIN', true]])('%sは残数副題と作成actionsを表示する', async (role, permitted) => {
    state.role = role as string; state.permitted = permitted as boolean
    wrapper = await mountPage()
    const pageHeader = wrapper.get('[data-testid="folder-header"]')
    expect(pageHeader.get('[data-testid="page-header-subtitle"]').text()).toBe('folders.list.remaining:1')
    const create = pageHeader.get('button')
    expect(create.text()).toContain('folders.create')
    expect(create.attributes('disabled')).toBeUndefined()
    await create.trigger('click')
    expect(wrapper.get('[data-testid="folder-dialog"]').attributes('data-open')).toBe('true')
  })
  it.each(['DEPUTY_ADMIN', 'MEMBER'])('%sの権限なしでは副題と作成を表示しない', async (role) => {
    state.role = role
    wrapper = await mountPage()
    expect(wrapper.find('[data-testid="page-header-subtitle"]').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
  })
  it('20件では作成actionsをdisabledにし残数0を表示する', async () => {
    state.count = 20
    wrapper = await mountPage()
    const pageHeader = wrapper.get('[data-testid="folder-header"]')
    expect(pageHeader.get('[data-testid="page-header-subtitle"]').text()).toBe('folders.list.remaining:0')
    expect(pageHeader.get<HTMLButtonElement>('button').element.disabled).toBe(true)
  })
})
