// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, defineComponent, h, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import SkillsPage from './skills.vue'

/**
 * CMP-261012-0313 F-AC3 / F-AC4 / F-AC6: 資格画面の閲覧範囲・非メンバー案内・カテゴリ反映。
 *
 * 子コンポーネントはスタブにし、「何を描画するか（= どの API を呼ぶ子を出すか）」を検証する。
 * スタブの子は API を呼ばないので、search / matrix を呼ぶ子（SkillList scope=all / SkillMatrix）が
 * 描画されないことをもって「API を呼ばない」ことを担保する。
 */

const api = vi.hoisted(() => ({ getSkillCategories: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
const perm = vi.hoisted(() => ({
  roleName: null as { value: string | null } | null,
  permissions: null as { value: string[] } | null,
  loadPermissions: vi.fn(),
}))
const spies = vi.hoisted(() => ({
  listRefresh: vi.fn(),
  listRefreshCategories: vi.fn(),
  matrixRefresh: vi.fn(),
}))

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'e2e' } }))
mockNuxtImport('useSkillApi', () => () => api)
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useRoleAccess', () => () => {
  const roleName = perm.roleName!
  const permissions = perm.permissions!
  return {
    roleName,
    permissions,
    loadPermissions: perm.loadPermissions,
    can: (p: string) => permissions.value.includes(p),
    isAdmin: computed(() => roleName.value === 'ADMIN' || roleName.value === 'SYSTEM_ADMIN'),
    isAdminOrDeputy: computed(() => ['ADMIN', 'SYSTEM_ADMIN', 'DEPUTY_ADMIN'].includes(roleName.value ?? '')),
    isMember: computed(() => ['ADMIN', 'SYSTEM_ADMIN', 'DEPUTY_ADMIN', 'MEMBER'].includes(roleName.value ?? '')),
  }
})

const passthrough = (name: string) =>
  defineComponent({ name, setup: (_, { slots }) => () => h('div', { 'data-stub': name }, slots.default?.()) })

const SkillList = defineComponent({
  name: 'SkillList',
  props: { teamId: String, canManage: Boolean, scope: { type: String, default: 'all' } },
  emits: ['create', 'edit', 'verify'],
  setup(p, { expose }) {
    expose({ refresh: spies.listRefresh, refreshCategories: spies.listRefreshCategories })
    return () => h('div', { 'data-test': 'skill-list', 'data-scope': p.scope })
  },
})
const SkillMatrix = defineComponent({
  name: 'SkillMatrix',
  props: { teamId: String },
  setup(_, { expose }) {
    expose({ refresh: spies.matrixRefresh })
    return () => h('div', { 'data-test': 'skill-matrix' })
  },
})
const SkillCategoryManager = defineComponent({
  name: 'SkillCategoryManager',
  props: { teamId: String },
  emits: ['changed'],
  setup(_, { emit }) {
    return () => h('button', { 'data-test': 'category-manager', onClick: () => emit('changed') })
  },
})
const DashboardEmptyState = defineComponent({
  name: 'DashboardEmptyState',
  props: { icon: String, message: String },
  setup: (p) => () => h('div', { 'data-test': 'empty', 'data-icon': p.icon }, p.message),
})
const stubs = {
  SkillList,
  SkillMatrix,
  SkillCategoryManager,
  DashboardEmptyState,
  SkillForm: true,
  PageHeader: true,
  PageLoading: true,
  Tabs: passthrough('Tabs'),
  TabList: passthrough('TabList'),
  Tab: passthrough('Tab'),
  TabPanels: passthrough('TabPanels'),
  TabPanel: passthrough('TabPanel'),
}

function setRole(roleName: string | null, permissions: string[] = []) {
  perm.roleName = ref(roleName)
  perm.permissions = ref(permissions)
  perm.loadPermissions.mockResolvedValue({ ok: true })
}

let wrapper: VueWrapper | undefined
async function render() {
  wrapper = mount(SkillsPage, { global: { stubs } })
  await flushPromises()
  return wrapper
}
const has = (w: VueWrapper, test: string) => w.find(`[data-test="${test}"]`).exists()

beforeEach(() => {
  vi.clearAllMocks()
  api.getSkillCategories.mockResolvedValue({ data: [] })
  setRole('MEMBER')
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('skills.vue: F-AC3 閲覧範囲', () => {
  it('ADMIN は全員一覧・マトリクス・カテゴリ管理が出る', async () => {
    setRole('ADMIN', ['MANAGE_MEMBER'])
    const w = await render()
    expect(w.find('[data-test="skill-list"]').attributes('data-scope')).toBe('all')
    expect(has(w, 'skill-matrix')).toBe(true)
    expect(has(w, 'category-manager')).toBe(true)
  })

  it('SYSTEM_ADMIN も全員一覧・マトリクスが出る', async () => {
    setRole('SYSTEM_ADMIN', [])
    const w = await render()
    expect(w.find('[data-test="skill-list"]').attributes('data-scope')).toBe('all')
    expect(has(w, 'skill-matrix')).toBe(true)
  })

  it('MANAGE_MEMBER を持つ DEPUTY_ADMIN は全員一覧・マトリクス・カテゴリ管理が出る', async () => {
    setRole('DEPUTY_ADMIN', ['MANAGE_MEMBER'])
    const w = await render()
    expect(w.find('[data-test="skill-list"]').attributes('data-scope')).toBe('all')
    expect(has(w, 'skill-matrix')).toBe(true)
    expect(has(w, 'category-manager')).toBe(true)
  })

  it('MANAGE_MEMBER を持たない DEPUTY_ADMIN は自分の資格だけ（マトリクス・カテゴリ管理なし）', async () => {
    setRole('DEPUTY_ADMIN', ['MANAGE_CONTENT'])
    const w = await render()
    expect(w.find('[data-test="skill-list"]').attributes('data-scope')).toBe('mine')
    expect(has(w, 'skill-matrix')).toBe(false)
    expect(has(w, 'category-manager')).toBe(false)
  })

  it('一般 MEMBER は自分の資格だけ（マトリクス・カテゴリ管理なし）', async () => {
    setRole('MEMBER', ['MANAGE_MEMBER'])
    const w = await render()
    expect(w.find('[data-test="skill-list"]').attributes('data-scope')).toBe('mine')
    expect(has(w, 'skill-matrix')).toBe(false)
    expect(has(w, 'category-manager')).toBe(false)
  })
})

describe('skills.vue: F-AC4 非メンバー', () => {
  it.each(['SUPPORTER', 'GUEST', null])('%s には権限なし案内（pi-lock）を1つだけ出し、資格系 API を呼ばない', async (role) => {
    setRole(role)
    const w = await render()
    expect(w.findAll('[data-test="empty"]')).toHaveLength(1)
    expect(w.find('[data-test="empty"]').attributes('data-icon')).toBe('pi pi-lock')
    expect(has(w, 'skill-list')).toBe(false)
    expect(has(w, 'skill-matrix')).toBe(false)
    expect(has(w, 'category-manager')).toBe(false)
    expect(api.getSkillCategories).not.toHaveBeenCalled()
    expect(notify.error).not.toHaveBeenCalled()
  })

  it('権限情報の取得に失敗したら握りつぶさず失敗通知し、「権限なし」とは別の表示にする', async () => {
    setRole(null)
    perm.loadPermissions.mockResolvedValue({ ok: false, error: new Error('500') })
    const w = await render()
    expect(notify.error).toHaveBeenCalled()
    expect(has(w, 'skill-list')).toBe(false)
    expect(api.getSkillCategories).not.toHaveBeenCalled()
    expect(w.find('[data-test="empty"]').attributes('data-icon')).not.toBe('pi pi-lock')
  })
})

describe('skills.vue: F-AC6 カテゴリ変更の反映', () => {
  it('changed を受けたら登録フォーム用カテゴリ・一覧のカテゴリ・マトリクスを再取得する', async () => {
    setRole('ADMIN', ['MANAGE_MEMBER'])
    const w = await render()
    expect(api.getSkillCategories).toHaveBeenCalledTimes(1)
    await w.find('[data-test="category-manager"]').trigger('click')
    await flushPromises()
    expect(api.getSkillCategories).toHaveBeenCalledTimes(2)
    expect(spies.listRefreshCategories).toHaveBeenCalledTimes(1)
    expect(spies.matrixRefresh).toHaveBeenCalledTimes(1)
  })

  it('登録フォーム用カテゴリの再取得に失敗したら握りつぶさずエラー通知する', async () => {
    setRole('ADMIN', ['MANAGE_MEMBER'])
    const w = await render()
    api.getSkillCategories.mockRejectedValue(new Error('500'))
    await w.find('[data-test="category-manager"]').trigger('click')
    await flushPromises()
    expect(notify.error).toHaveBeenCalled()
  })
})
