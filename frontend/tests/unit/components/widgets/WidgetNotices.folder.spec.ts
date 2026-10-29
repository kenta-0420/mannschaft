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
const requests = vi.hoisted(() => ({
  fetchAll: vi.fn<(scope: string) => Promise<void>>(),
  summary: vi.fn<(scope: string) => Promise<void>>(),
  notices: vi.fn<() => Promise<{ data: { items: [] } }>>(),
  actions: vi.fn<() => Promise<{ items: [] }>>(),
  platform: vi.fn<() => Promise<{ data: [] }>>(),
  folders: vi.fn<() => Promise<{ data: [] }>>(),
  report: vi.fn(),
  team: [] as ScopeFolder[],
}))
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
  foldersFor: (scope: string) => scope === 'TEAM' ? requests.team : [],
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
function mountNotices() {
  const wrapper = shallowMount(WidgetNotices, {
    global: { stubs: {
      DashboardWidgetCard: { props: ['loading'], template: '<section :data-loading="loading"><slot /></section>' },
      DashboardEmptyState: { props: ['message'], template: '<p>{{ message }}</p>' },
    } },
  })
  noticeWrappers.push(wrapper)
  return wrapper
}

describe('WidgetNotices 実コンポーネントの取得待機境界', () => {
  beforeEach(() => {
    requests.team = []
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

  it('フォルダ取得中も集計を開始し、両者が終わるまで本文取得を待つ', async () => {
    const folders = deferred<undefined>()
    const summaries = deferred<undefined>()
    requests.fetchAll.mockReturnValueOnce(folders.promise)
    requests.summary.mockReturnValue(summaries.promise)
    mountNotices()
    await flushPromises()
    expect(requests.fetchAll).toHaveBeenCalledWith('TEAM')
    expect(requests.summary).toHaveBeenCalledTimes(2)
    expect(requests.notices).not.toHaveBeenCalled()
    summaries.resolve(undefined)
    await flushPromises()
    expect(requests.notices).not.toHaveBeenCalled()
    folders.resolve(undefined)
    await flushPromises()
    expect(requests.fetchAll.mock.calls.map(([scope]) => scope)).toEqual(['TEAM', 'ORGANIZATION'])
    expect(requests.notices).toHaveBeenCalledOnce()
  })

  it('通常タブが未完了でも要対応と運営取得を開始し、全群完了までloadingを保つ', async () => {
    const notices = deferred<{ data: { items: [] } }>()
    const folders = deferred<{ data: [] }>()
    const actions = deferred<{ items: [] }>()
    const platform = deferred<{ data: [] }>()
    requests.team = [teamFolderA]
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
    expect(wrapper.find('section').attributes('data-loading')).toBe('true')
    folders.resolve({ data: [] })
    await flushPromises()
    expect(wrapper.find('section').attributes('data-loading')).toBe('false')
  })

  it('独立群の途中失敗でも残りを待ち、既存catchと空表示を保つ', async () => {
    const platform = deferred<{ data: [] }>()
    requests.notices.mockRejectedValue(new Error('試験用の通知失敗'))
    requests.actions.mockRejectedValue(new Error('試験用の要対応失敗'))
    requests.platform.mockReturnValue(platform.promise)
    const wrapper = mountNotices()
    await flushPromises()
    expect(requests.report).toHaveBeenCalledTimes(2)
    expect(wrapper.find('section').attributes('data-loading')).toBe('true')
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
})
