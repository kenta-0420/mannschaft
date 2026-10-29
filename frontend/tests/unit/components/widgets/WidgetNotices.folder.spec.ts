// @vitest-environment happy-dom
/**
 * F15.3 Phase 2-C: WidgetNotices フォルダタブ追加機能のユニットテスト。
 *
 * mountSuspended は Nuxt の API クライアント・Pinia ストア・PrimeVue を
 * 一括で必要とし、テスト設定が広範化するため、本テストでは
 * 既存のタブ規則はロジックを reproduction して検証する。
 * 取得待機の回帰試験は末尾で実コンポーネントを明示stub付きでmountする。
 *
 * 検証範囲:
 *  - タブ定義生成（「すべて」+ TEAM フォルダ群 + ORGANIZATION フォルダ群）
 *  - アクティブタブ判定（all / folder のキー比較）
 *  - 未読バッジ値の伝播（store からの集計値マッピング）
 *  - 「もっと見る」リンク生成（タブごとの URL）
 *  - 未読 0 件タブのバッジ非表示判定
 */
import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import WidgetNotices from '~/components/widgets/WidgetNotices.vue'
import type { ScopeFolder, ScopeType } from '~/types/scopeFolder'

/** WidgetNotices 内のタブキー型を再現。 */
type TabKey =
  | { kind: 'all' }
  | { kind: 'folder', scopeType: ScopeType, folderId: number }

interface FolderTabDef {
  key: string
  label: string
  tab: TabKey
  unreadBadge: number
}

/** アクティブタブ判定ロジックの再現（コンポーネント内 `isActiveTab`）。 */
function isActiveTab(current: TabKey, target: TabKey): boolean {
  if (current.kind === 'all' && target.kind === 'all') return true
  if (current.kind === 'folder' && target.kind === 'folder') {
    return (
      current.scopeType === target.scopeType
      && current.folderId === target.folderId
    )
  }
  return false
}

/** タブ定義生成ロジックの再現（コンポーネント内 `tabs` computed）。 */
function buildTabs(
  teamFolders: ScopeFolder[],
  orgFolders: ScopeFolder[],
  unreadByFolder: Record<number, number>,
  allLabel: string,
  untaggedLabel: string,
): FolderTabDef[] {
  const result: FolderTabDef[] = [
    {
      key: 'all',
      label: allLabel,
      tab: { kind: 'all' },
      unreadBadge: 0,
    },
  ]
  const appendFor = (scopeType: ScopeType, list: ScopeFolder[]) => {
    for (const folder of list) {
      result.push({
        key: `${scopeType}-${folder.id}`,
        label: folder.isDefault ? untaggedLabel : folder.name,
        tab: { kind: 'folder', scopeType, folderId: folder.id },
        unreadBadge: unreadByFolder[folder.id] ?? 0,
      })
    }
  }
  appendFor('TEAM', teamFolders)
  appendFor('ORGANIZATION', orgFolders)
  return result
}

/** 「もっと見る」リンク生成ロジックの再現。 */
function buildMoreLink(current: TabKey): string {
  if (current.kind === 'folder') {
    return `/notifications?folderId=${current.folderId}&scopeType=${current.scopeType}`
  }
  return '/notifications'
}

const teamFolderA: ScopeFolder = {
  id: 11,
  name: '部活',
  color: '#3B82F6',
  isDefault: false,
  sortOrder: 0,
  itemScopeIds: [],
}

const teamFolderDefault: ScopeFolder = {
  id: 12,
  name: '未分類',
  color: null,
  isDefault: true,
  sortOrder: 9999,
  itemScopeIds: [],
}

const orgFolderX: ScopeFolder = {
  id: 21,
  name: '保護者会',
  color: '#22C55E',
  isDefault: false,
  sortOrder: 0,
  itemScopeIds: [],
}

describe('WidgetNotices フォルダタブ生成（F15.3 §7.5）', () => {
  it('「すべて」が必ず先頭に含まれる', () => {
    const tabs = buildTabs([], [], {}, 'すべて', '未分類')
    expect(tabs[0]).toMatchObject({ key: 'all', tab: { kind: 'all' } })
  })

  it('TEAM フォルダが ORGANIZATION より先に並ぶ', () => {
    const tabs = buildTabs(
      [teamFolderA],
      [orgFolderX],
      {},
      'All',
      'Uncategorized',
    )
    const keys = tabs.map(t => t.key)
    expect(keys).toEqual(['all', 'TEAM-11', 'ORGANIZATION-21'])
  })

  it('未分類フォルダは isDefault フラグでラベルが切り替わる', () => {
    const tabs = buildTabs(
      [teamFolderA, teamFolderDefault],
      [],
      {},
      'All',
      'Uncategorized',
    )
    const defaultTab = tabs.find(t => t.key === 'TEAM-12')
    expect(defaultTab?.label).toBe('Uncategorized')
    const normalTab = tabs.find(t => t.key === 'TEAM-11')
    expect(normalTab?.label).toBe('部活')
  })

  it('未読件数バッジが正しくマッピングされる', () => {
    const tabs = buildTabs(
      [teamFolderA, teamFolderDefault],
      [orgFolderX],
      { 11: 5, 12: 0, 21: 3 },
      'All',
      'Uncategorized',
    )
    expect(tabs.find(t => t.key === 'TEAM-11')?.unreadBadge).toBe(5)
    expect(tabs.find(t => t.key === 'TEAM-12')?.unreadBadge).toBe(0)
    expect(tabs.find(t => t.key === 'ORGANIZATION-21')?.unreadBadge).toBe(3)
  })

  it('集計データに無いフォルダは unreadBadge=0 になる', () => {
    const tabs = buildTabs([teamFolderA], [], {}, 'All', 'Uncategorized')
    expect(tabs.find(t => t.key === 'TEAM-11')?.unreadBadge).toBe(0)
  })

  it('「すべて」タブの unreadBadge は常に 0（集計 API 未対応）', () => {
    const tabs = buildTabs(
      [teamFolderA],
      [],
      { 11: 10 },
      'All',
      'Uncategorized',
    )
    expect(tabs[0]?.unreadBadge).toBe(0)
  })
})

describe('WidgetNotices アクティブタブ判定', () => {
  it('all 同士は一致', () => {
    expect(isActiveTab({ kind: 'all' }, { kind: 'all' })).toBe(true)
  })

  it('all と folder は不一致', () => {
    expect(
      isActiveTab(
        { kind: 'all' },
        { kind: 'folder', scopeType: 'TEAM', folderId: 11 },
      ),
    ).toBe(false)
  })

  it('同じ folder は一致', () => {
    expect(
      isActiveTab(
        { kind: 'folder', scopeType: 'TEAM', folderId: 11 },
        { kind: 'folder', scopeType: 'TEAM', folderId: 11 },
      ),
    ).toBe(true)
  })

  it('別 scopeType の同 ID は不一致（TEAM と ORG の混同防止）', () => {
    expect(
      isActiveTab(
        { kind: 'folder', scopeType: 'TEAM', folderId: 11 },
        { kind: 'folder', scopeType: 'ORGANIZATION', folderId: 11 },
      ),
    ).toBe(false)
  })

  it('別 folderId は不一致', () => {
    expect(
      isActiveTab(
        { kind: 'folder', scopeType: 'TEAM', folderId: 11 },
        { kind: 'folder', scopeType: 'TEAM', folderId: 12 },
      ),
    ).toBe(false)
  })
})

describe('WidgetNotices もっと見るリンク生成', () => {
  it('all タブは /notifications', () => {
    expect(buildMoreLink({ kind: 'all' })).toBe('/notifications')
  })

  it('TEAM フォルダタブは folderId と scopeType の両方を含む', () => {
    expect(
      buildMoreLink({ kind: 'folder', scopeType: 'TEAM', folderId: 11 }),
    ).toBe('/notifications?folderId=11&scopeType=TEAM')
  })

  it('ORGANIZATION フォルダタブは folderId と scopeType の両方を含む', () => {
    expect(
      buildMoreLink({
        kind: 'folder',
        scopeType: 'ORGANIZATION',
        folderId: 21,
      }),
    ).toBe('/notifications?folderId=21&scopeType=ORGANIZATION')
  })
})

// 以下は再現ロジックではなく実コンポーネントをmountし、取得開始順と表示完了を検証する。
interface AllNotice {
  id: number; type: string; title: string; body: null; is_read: boolean; created_at: string; action_url: string | null
}
interface FolderNotice {
  id: number; notificationType: string; title: string; body: null; isRead: boolean; createdAt: string; actionUrl: string | null
}
const requests = vi.hoisted(() => ({
  fetchAll: vi.fn<(scope: string) => Promise<void>>(),
  summary: vi.fn<(scope: string) => Promise<void>>(),
  notices: vi.fn<() => Promise<{ data: { items: AllNotice[] } }>>(),
  actions: vi.fn<() => Promise<{ items: [] }>>(),
  platform: vi.fn<() => Promise<{ data: [] }>>(),
  folders: vi.fn<() => Promise<{ data: FolderNotice[] }>>(),
  report: vi.fn(),
}))
const folderLists = reactive<Record<ScopeType, ScopeFolder[]>>({ TEAM: [], ORGANIZATION: [] })
mockNuxtImport('useDashboardApi', () => () => ({
  getNotices: requests.notices,
  getPlatformAnnouncements: requests.platform,
  markNoticeRead: vi.fn(), markAllNoticesRead: vi.fn(),
}))
mockNuxtImport('useApi', () => () => requests.folders)
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet: requests.report }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('usePersonalActionRequired', () => () => ({ fetchActionRequired: requests.actions }))
mockNuxtImport('useScopeFoldersStore', () => () => ({
  foldersFor: (scope: ScopeType) => folderLists[scope],
  unreadByFolder: {},
  fetchAll: requests.fetchAll,
  refreshNotificationSummary: requests.summary,
  unreadCountOf: () => 0,
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

const noticeWrappers: Array<{ unmount: () => void }> = []
function allNotice(title: string): AllNotice {
  return { id: 1, type: 'INFO', title, body: null, is_read: false, created_at: '2026-01-01T00:00:00Z', action_url: '/notifications' }
}
function folderNotice(title: string): FolderNotice {
  return { id: 2, notificationType: 'INFO', title, body: null, isRead: false, createdAt: '2026-01-01T00:00:00Z', actionUrl: '/notifications' }
}
function mountNotices() {
  const wrapper = shallowMount(WidgetNotices, {
    global: { stubs: {
      DashboardWidgetCard: { name: 'DashboardWidgetCard', props: ['loading'], template: '<section :data-loading="loading"><div v-if="loading" data-testid="notice-skeleton" /><slot v-else /></section>' },
      DashboardEmptyState: { props: ['message'], template: '<p>{{ message }}</p>' },
      NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
    } },
  })
  noticeWrappers.push(wrapper)
  return wrapper
}

describe('WidgetNotices 実コンポーネントの取得待機境界', () => {
  beforeEach(() => {
    folderLists.TEAM = []
    folderLists.ORGANIZATION = []
    requests.fetchAll.mockReset().mockResolvedValue(undefined)
    requests.summary.mockReset().mockResolvedValue(undefined)
    requests.notices.mockReset().mockResolvedValue({ data: { items: [] } })
    requests.actions.mockReset().mockResolvedValue({ items: [] })
    requests.platform.mockReset().mockResolvedValue({ data: [] })
    requests.folders.mockReset().mockResolvedValue({ data: [] })
    requests.report.mockClear()
  })
  afterEach(() => {
    noticeWrappers.forEach(wrapper => wrapper.unmount())
    noticeWrappers.length = 0
  })

  it('フォルダ・集計・独立タブが未完了でも、すべての本文を先に表示する', async () => {
    const folders = deferred<undefined>()
    const summaries = deferred<undefined>()
    requests.fetchAll.mockReturnValueOnce(folders.promise)
    requests.summary.mockReturnValue(summaries.promise)
    requests.actions.mockReturnValue(deferred<{ items: [] }>().promise)
    requests.platform.mockReturnValue(deferred<{ data: [] }>().promise)
    requests.notices.mockResolvedValue({ data: { items: [allNotice('all-ready')] } })
    const wrapper = mountNotices()
    await flushPromises()
    expect(requests.fetchAll).toHaveBeenCalledWith('TEAM')
    expect(requests.summary).toHaveBeenCalledTimes(2)
    expect(requests.notices).toHaveBeenCalledOnce()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('all-ready')
    summaries.resolve(undefined)
    await flushPromises()
    expect(requests.notices).toHaveBeenCalledOnce()
    folders.resolve(undefined)
    await flushPromises()
    expect(requests.fetchAll.mock.calls.map(([scope]) => scope)).toEqual(['TEAM', 'ORGANIZATION'])
    expect(requests.notices).toHaveBeenCalledOnce()
  })

  it('他のフォルダが未完了でも、選択中のすべてが完了すればloadingを解除する', async () => {
    const notices = deferred<{ data: { items: [] } }>()
    const folders = deferred<{ data: [] }>()
    const actions = deferred<{ items: [] }>()
    const platform = deferred<{ data: [] }>()
    folderLists.TEAM = [teamFolderA]
    requests.notices.mockReturnValue(notices.promise)
    requests.folders.mockReturnValue(folders.promise)
    requests.actions.mockReturnValue(actions.promise)
    requests.platform.mockReturnValue(platform.promise)
    const wrapper = mountNotices()
    await flushPromises()
    expect(requests.notices).toHaveBeenCalledOnce()
    expect(requests.folders).toHaveBeenCalledOnce()
    expect(requests.actions).toHaveBeenCalledOnce()
    expect(requests.platform).toHaveBeenCalledOnce()
    actions.resolve({ items: [] })
    platform.resolve({ data: [] })
    notices.resolve({ data: { items: [] } })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    folders.resolve({ data: [] })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
  })

  it('選択中の取得失敗は空表示へ戻り、独立群の未完了を待たない', async () => {
    const platform = deferred<{ data: [] }>()
    requests.notices.mockRejectedValue(new Error('試験用の通知失敗'))
    requests.actions.mockRejectedValue(new Error('試験用の要対応失敗'))
    requests.platform.mockReturnValue(platform.promise)
    const wrapper = mountNotices()
    await flushPromises()
    expect(requests.report).toHaveBeenCalledTimes(2)
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    platform.reject(new Error('試験用の運営失敗'))
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('dashboard.widgets.notices.empty')
  })

  it('フォルダ取得と集計が失敗しても空folderで通常取得と空表示を完了する', async () => {
    requests.fetchAll.mockRejectedValue(new Error('試験用のフォルダ失敗'))
    requests.summary.mockRejectedValue(new Error('試験用の集計失敗'))
    const wrapper = mountNotices()
    await flushPromises()
    expect(requests.report).toHaveBeenCalledTimes(2)
    expect(requests.notices).toHaveBeenCalledOnce()
    expect(requests.actions).toHaveBeenCalledOnce()
    expect(requests.platform).toHaveBeenCalledOnce()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('dashboard.widgets.notices.empty')
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(3)
  })

  it('未完了フォルダへの切替は空表示にせず、そのフォルダ完了で表示する', async () => {
    const folder = deferred<{ data: FolderNotice[] }>()
    folderLists.TEAM = [teamFolderA]
    requests.notices.mockResolvedValue({ data: { items: [allNotice('all-content')] } })
    requests.folders.mockReturnValue(folder.promise)
    const wrapper = mountNotices()
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    await wrapper.get(`[data-testid="widget-notices-tab-TEAM-${teamFolderA.id}"]`).trigger('click')
    expect(wrapper.find('[data-testid="notice-skeleton"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('dashboard.widgets.notices.empty')
    folder.resolve({ data: [folderNotice('folder-ready')] })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('folder-ready')
  })

  it('非選択フォルダの後着は現在の本文を上書きしない', async () => {
    const folder = deferred<{ data: FolderNotice[] }>()
    folderLists.TEAM = [teamFolderA]
    requests.notices.mockResolvedValue({ data: { items: [allNotice('all-content')] } })
    requests.folders.mockReturnValue(folder.promise)
    const wrapper = mountNotices()
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('all-content')
    folder.resolve({ data: [folderNotice('inactive-folder')] })
    await flushPromises()
    expect(wrapper.text()).toContain('all-content')
    expect(wrapper.text()).not.toContain('inactive-folder')
  })

  it.each(['success', 'failure'] as const)('refresh前の%s応答とfinallyは新しい待機状態を解除しない', async (outcome) => {
    const old = deferred<{ data: { items: AllNotice[] } }>()
    const fresh = deferred<{ data: { items: AllNotice[] } }>()
    requests.notices.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const wrapper = mountNotices()
    await flushPromises()
    wrapper.getComponent({ name: 'DashboardWidgetCard' }).vm.$emit('refresh')
    await flushPromises()
    if (outcome === 'success') old.resolve({ data: { items: [allNotice('old-content')] } })
    else old.reject(new Error('試験用の古い失敗'))
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('true')
    expect(wrapper.text()).not.toContain('old-content')
    fresh.resolve({ data: { items: [allNotice('fresh-content')] } })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('fresh-content')
  })

  it('一覧取得中のrefreshは一覧を重複取得せず、新しい本文とフォルダ先読みを完了する', async () => {
    const bootstrap = deferred<undefined>()
    requests.fetchAll.mockImplementationOnce(async () => {
      await bootstrap.promise
      folderLists.TEAM = [teamFolderA]
    })
    requests.notices.mockResolvedValueOnce({ data: { items: [allNotice('old-content')] } })
      .mockResolvedValueOnce({ data: { items: [allNotice('fresh-content')] } })
    const wrapper = mountNotices()
    await flushPromises()
    wrapper.getComponent({ name: 'DashboardWidgetCard' }).vm.$emit('refresh')
    await flushPromises()
    expect(requests.fetchAll).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('fresh-content')
    bootstrap.resolve(undefined)
    await flushPromises()
    expect(requests.fetchAll.mock.calls.map(([scope]) => scope)).toEqual(['TEAM', 'ORGANIZATION'])
    expect(requests.folders).toHaveBeenCalledOnce()
    expect(requests.summary).toHaveBeenCalledTimes(2)
    await wrapper.get(`[data-testid="widget-notices-tab-TEAM-${teamFolderA.id}"]`).trigger('click')
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
  })

  it('一覧取得中のunmount後は後続の一覧・本文取得を開始しない', async () => {
    const bootstrap = deferred<undefined>()
    requests.fetchAll.mockReturnValueOnce(bootstrap.promise)
    const wrapper = mountNotices()
    await flushPromises()
    wrapper.unmount()
    noticeWrappers.splice(noticeWrappers.indexOf(wrapper), 1)
    bootstrap.resolve(undefined)
    await flushPromises()
    expect(requests.fetchAll).toHaveBeenCalledTimes(1)
    expect(requests.notices).toHaveBeenCalledOnce()
    expect(requests.folders).not.toHaveBeenCalled()
  })

  it('古い要対応応答のfinallyでrefresh後の選択タブのloadingを落とさない', async () => {
    const old = deferred<{ items: [] }>()
    const fresh = deferred<{ items: [] }>()
    requests.actions.mockResolvedValueOnce({ items: [] }).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const wrapper = mountNotices()
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    await wrapper.get('[data-testid="widget-notices-tab-action-required"]').trigger('click')
    wrapper.getComponent({ name: 'DashboardWidgetCard' }).vm.$emit('refresh')
    await flushPromises()
    wrapper.getComponent({ name: 'DashboardWidgetCard' }).vm.$emit('refresh')
    await flushPromises()
    old.resolve({ items: [] })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('true')
    fresh.resolve({ items: [] })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
  })

  it('選択フォルダが共有一覧から消えた後のrefreshはすべてへ戻る', async () => {
    folderLists.TEAM = [teamFolderA]
    requests.notices.mockResolvedValue({ data: { items: [allNotice('all-content')] } })
    const wrapper = mountNotices()
    await flushPromises()
    await wrapper.get(`[data-testid="widget-notices-tab-TEAM-${teamFolderA.id}"]`).trigger('click')
    folderLists.TEAM = []
    await flushPromises()
    wrapper.getComponent({ name: 'DashboardWidgetCard' }).vm.$emit('refresh')
    await flushPromises()
    expect(wrapper.get('[data-testid="widget-notices-tab-all"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('all-content')
  })

  it('mount後に一覧へ追加されたフォルダは選択時に取得して表示する', async () => {
    const added = deferred<{ data: FolderNotice[] }>()
    const wrapper = mountNotices()
    await flushPromises()
    folderLists.TEAM = [teamFolderA]
    requests.folders.mockReturnValue(added.promise)
    await flushPromises()
    await wrapper.get(`[data-testid="widget-notices-tab-TEAM-${teamFolderA.id}"]`).trigger('click')
    expect(wrapper.find('section').attributes('data-loading')).toBe('true')
    expect(requests.folders).toHaveBeenCalledOnce()
    added.resolve({ data: [folderNotice('added-folder')] })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
    expect(wrapper.text()).toContain('added-folder')
  })
})
