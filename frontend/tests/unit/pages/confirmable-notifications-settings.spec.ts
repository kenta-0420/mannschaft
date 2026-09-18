import { computed, defineComponent, h, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TeamSidebar from '~/components/TeamSidebar.vue'
import OrganizationSidebar from '~/components/OrganizationSidebar.vue'
import TeamConfirmableNotificationsPage from '~/pages/teams/[slug]/settings/confirmable-notifications.vue'
import OrgConfirmableNotificationsPage from '~/pages/organizations/[slug]/settings/confirmable-notifications.vue'

/**
 * CMP-260909-1141: 確認通知（F04.9）を /admin/reservation-settings.vue から
 * teams/[slug]/settings/confirmable-notifications.vue・organizations/[slug]/settings/confirmable-notifications.vue
 * へ移設したことの回帰テスト。
 *
 * 検証観点:
 *  CN-SIDEBAR-001/002: 両サイドバーの settings カテゴリから新ページへ到達できること
 *    （DEPUTY_ADMIN 以上に表示。BE の checkAdminOrAbove に合わせた権限）
 *  CN-SIDEBAR-003/004: MEMBER には表示されないこと（金銭・通知送信操作のため）
 *  CN-PAGE-001/002: 新ページが3コンポーネントへ正しい scope-type/scope-id を渡すこと
 *    （TEAM/ORGANIZATION の双方で、useScopeStore().current.id をそのまま使う）
 */

const roleName = ref<string | null>('ADMIN')
vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    roleName,
    isAdmin: computed(() => roleName.value === 'ADMIN'),
    isAdminOrDeputy: computed(
      () => roleName.value === 'ADMIN' || roleName.value === 'DEPUTY_ADMIN',
    ),
    loadPermissions: vi.fn().mockResolvedValue(undefined),
  }),
}))

const enabledModules = ref<{ moduleSlug: string, isEnabled: boolean }[]>([])
vi.mock('~/composables/useOrganizationModuleApi', () => ({
  useOrganizationModuleApi: () => ({
    getOrganizationModules: () => Promise.resolve(enabledModules.value),
  }),
}))
vi.mock('~/composables/useModuleApi', () => ({
  useModuleApi: () => ({
    getTeamModules: () => Promise.resolve({ data: enabledModules.value }),
  }),
}))

/**
 * useScopeStore を直接モックする（実 Pinia を介した状態受け渡しは、mountSuspended が
 * 構築する Nuxt アプリコンテキストと、テストが setActivePinia したコンテキストが
 * 一致しない実測により scope-id が空文字になる事故があったため採用しない）。
 */
const currentScope = ref<{ type: 'personal' | 'team' | 'organization', id: string | null, name: string }>({
  type: 'personal',
  id: null,
  name: '個人',
})
vi.mock('~/stores/useScopeStore', () => ({
  useScopeStore: () => ({
    current: currentScope.value,
  }),
}))

async function mountTeamSidebar() {
  const wrapper = await mountSuspended(TeamSidebar, { props: { teamId: 'team-1' } })
  await new Promise(resolve => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

async function mountOrgSidebar() {
  const wrapper = await mountSuspended(OrganizationSidebar, { props: { orgId: 'org-1' } })
  await new Promise(resolve => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('確認通知設定ページへの導線（サイドバー）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    roleName.value = 'ADMIN'
    enabledModules.value = []
  })

  it('CN-SIDEBAR-001: TeamSidebar の settings カテゴリから到達できる（DEPUTY_ADMIN）', async () => {
    roleName.value = 'DEPUTY_ADMIN'
    const wrapper = await mountTeamSidebar()
    expect(wrapper.html()).toContain('/teams/team-1/settings/confirmable-notifications')
  })

  it('CN-SIDEBAR-002: OrganizationSidebar の settings カテゴリから到達できる（DEPUTY_ADMIN）', async () => {
    roleName.value = 'DEPUTY_ADMIN'
    const wrapper = await mountOrgSidebar()
    expect(wrapper.html()).toContain('/organizations/org-1/settings/confirmable-notifications')
  })

  it('CN-SIDEBAR-003: MEMBER には TeamSidebar から表示されない', async () => {
    roleName.value = 'MEMBER'
    const wrapper = await mountTeamSidebar()
    expect(wrapper.html()).not.toContain('settings/confirmable-notifications')
  })

  it('CN-SIDEBAR-004: MEMBER には OrganizationSidebar から表示されない', async () => {
    roleName.value = 'MEMBER'
    const wrapper = await mountOrgSidebar()
    expect(wrapper.html()).not.toContain('settings/confirmable-notifications')
  })
})

const SettingsStub = defineComponent({
  name: 'ConfirmableNotificationSettings',
  props: { scopeType: String, scopeId: String },
  setup(props) {
    return () => h('div', { 'data-testid': 'settings-stub', 'data-scope-type': props.scopeType, 'data-scope-id': props.scopeId })
  },
})
const SenderStub = defineComponent({
  name: 'ConfirmableNotificationSender',
  props: { scopeType: String, scopeId: String },
  emits: ['sent'],
  setup(props) {
    return () => h('div', { 'data-testid': 'sender-stub', 'data-scope-type': props.scopeType, 'data-scope-id': props.scopeId })
  },
})
const HistoryStub = defineComponent({
  name: 'ConfirmableNotificationHistory',
  props: { scopeType: String, scopeId: String },
  setup(props) {
    return () => h('div', { 'data-testid': 'history-stub', 'data-scope-type': props.scopeType, 'data-scope-id': props.scopeId })
  },
})

describe('確認通知設定ページ: props の受け渡し', () => {
  beforeEach(() => {
    currentScope.value = { type: 'personal', id: null, name: '個人' }
  })

  it('CN-PAGE-001: TEAM スコープでは scope-type=TEAM・scope-id=現在の team id が渡る', async () => {
    currentScope.value = { type: 'team', id: '123', name: 'テストチーム' }

    const wrapper = await mountSuspended(TeamConfirmableNotificationsPage, {
      global: {
        stubs: {
          ConfirmableNotificationSettings: SettingsStub,
          ConfirmableNotificationSender: SenderStub,
          ConfirmableNotificationHistory: HistoryStub,
        },
      },
    })
    await flushPromises()

    const settings = wrapper.get('[data-testid="settings-stub"]')
    expect(settings.attributes('data-scope-type')).toBe('TEAM')
    expect(settings.attributes('data-scope-id')).toBe('123')

    const sender = wrapper.get('[data-testid="sender-stub"]')
    expect(sender.attributes('data-scope-type')).toBe('TEAM')
    expect(sender.attributes('data-scope-id')).toBe('123')

    const history = wrapper.get('[data-testid="history-stub"]')
    expect(history.attributes('data-scope-type')).toBe('TEAM')
    expect(history.attributes('data-scope-id')).toBe('123')
  })

  it('CN-PAGE-002: ORGANIZATION スコープでは scope-type=ORGANIZATION・scope-id=現在の org id が渡る', async () => {
    currentScope.value = { type: 'organization', id: '456', name: 'テスト組織' }

    const wrapper = await mountSuspended(OrgConfirmableNotificationsPage, {
      global: {
        stubs: {
          ConfirmableNotificationSettings: SettingsStub,
          ConfirmableNotificationSender: SenderStub,
          ConfirmableNotificationHistory: HistoryStub,
        },
      },
    })
    await flushPromises()

    const settings = wrapper.get('[data-testid="settings-stub"]')
    expect(settings.attributes('data-scope-type')).toBe('ORGANIZATION')
    expect(settings.attributes('data-scope-id')).toBe('456')

    const sender = wrapper.get('[data-testid="sender-stub"]')
    expect(sender.attributes('data-scope-type')).toBe('ORGANIZATION')
    expect(sender.attributes('data-scope-id')).toBe('456')

    const history = wrapper.get('[data-testid="history-stub"]')
    expect(history.attributes('data-scope-type')).toBe('ORGANIZATION')
    expect(history.attributes('data-scope-id')).toBe('456')
  })
})
