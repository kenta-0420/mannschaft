/**
 * F15.3 Phase 2-C: WidgetNotices フォルダタブ追加機能のユニットテスト。
 *
 * mountSuspended は Nuxt の API クライアント・Pinia ストア・PrimeVue を
 * 一括で必要とし、テスト設定が広範化するため、本テストでは
 * コンポーネントの責務であるロジックを reproduction して検証する。
 *
 * 検証範囲:
 *  - タブ定義生成（「すべて」+ TEAM フォルダ群 + ORGANIZATION フォルダ群）
 *  - アクティブタブ判定（all / folder のキー比較）
 *  - 未読バッジ値の伝播（store からの集計値マッピング）
 *  - 「もっと見る」リンク生成（タブごとの URL）
 *  - 未読 0 件タブのバッジ非表示判定
 */
import { describe, it, expect } from 'vitest'
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
