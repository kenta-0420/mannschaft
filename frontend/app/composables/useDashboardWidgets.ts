import type { MinRole, ViewerRole, WidgetVisibilitySetting } from '~/types/dashboard'

const MIN_ROLE_LEVEL: Record<MinRole, number> = { PUBLIC: 0, SUPPORTER: 1, MEMBER: 2 }

function viewerRoleLevel(viewerRole: ViewerRole | undefined): number {
  if (!viewerRole || viewerRole === 'PUBLIC') return 0
  if (viewerRole === 'SUPPORTER') return 1
  return 2 // MEMBER / DEPUTY_ADMIN / ADMIN / SYSTEM_ADMIN
}

function effectiveMinRole(
  widget: WidgetDefinition,
  scopeType: 'personal' | 'team' | 'organization',
  visibilityMap: WidgetVisibilitySetting[],
): MinRole {
  // バックエンドキーがある場合は visibilityMap（admin設定）を優先する
  if (scopeType !== 'personal') {
    const backendKey = backendKeyForWidget(widget.key, scopeType as 'team' | 'organization')
    if (backendKey) {
      const setting = visibilityMap.find((s) => s.widget_key === backendKey)
      if (setting) return setting.min_role
    }
  }
  // バックエンドキーなし or visibilityMap未取得時はウィジェット定義のデフォルトを使用
  return widget.defaultMinRole ?? 'PUBLIC'
}

export interface WidgetDefinition {
  key: string
  label: string
  icon: string
  description: string
  scope: Array<'personal' | 'team' | 'organization'>
  defaultMinRole?: MinRole
}

export const WidgetKeyMap: Record<string, { team?: string; organization?: string }> = {
  bulletin: { team: 'TEAM_NOTICES', organization: 'ORG_NOTICES' },
  'upcoming-events': { team: 'TEAM_UPCOMING_EVENTS' },
  todos: { team: 'TEAM_TODO', organization: 'ORG_TODO' },
  timeline: { team: 'TEAM_LATEST_POSTS' },
  chat: { team: 'TEAM_UNREAD_THREADS' },
  schedule: { team: 'TEAM_UPCOMING_EVENTS' },
  members: { team: 'TEAM_MEMBERS', organization: 'ORG_MEMBERS' },
  activities: { team: 'TEAM_ACTIVITY' },
  gallery: { team: 'TEAM_GALLERY' },
  circulation: { team: 'TEAM_CIRCULATION' },
  surveys: { team: 'TEAM_SURVEYS' },
  'survey-results': { team: 'TEAM_SURVEY_RESULTS' },
  'attendance-results': { team: 'TEAM_MEMBER_ATTENDANCE' },
  blog: { team: 'TEAM_BLOG' },
  // F08.7.1 成績ウィジェット 3 種
  'team-standings-record': { team: 'TEAM_TOURNAMENT_RECORD' },
  'team-division-standings': { team: 'TEAM_DIVISION_STANDINGS' },
  'org-tournament-summary': { organization: 'ORG_TOURNAMENT_SUMMARY' },
  // F08.10 チーム試合サマリ
  'team-match-summary': { team: 'TEAM_MATCH_SUMMARY' },
}

export const WidgetDefaultMinRoleMap: Record<string, MinRole> = {
  TEAM_NOTICES: 'PUBLIC',
  TEAM_UPCOMING_EVENTS: 'PUBLIC',
  TEAM_TODO: 'MEMBER',
  TEAM_PROJECT_PROGRESS: 'MEMBER',
  TEAM_ACTIVITY: 'SUPPORTER',
  TEAM_LATEST_POSTS: 'SUPPORTER',
  TEAM_UNREAD_THREADS: 'MEMBER',
  TEAM_MEMBER_ATTENDANCE: 'MEMBER',
  ORG_TEAM_LIST: 'PUBLIC',
  ORG_NOTICES: 'PUBLIC',
  ORG_TODO: 'MEMBER',
  ORG_PROJECT_PROGRESS: 'MEMBER',
  ORG_STATS: 'SUPPORTER',
  // F08.7.1 成績ウィジェット 3 種（BE WidgetDefaultMinRoleMap と同期）
  TEAM_TOURNAMENT_RECORD: 'SUPPORTER',
  TEAM_DIVISION_STANDINGS: 'SUPPORTER',
  ORG_TOURNAMENT_SUMMARY: 'MEMBER',
  // F08.10 チーム試合サマリ（BE WidgetDefaultMinRoleMap と同期）
  TEAM_MATCH_SUMMARY: 'MEMBER',
}

export function backendKeyForWidget(
  frontendKey: string,
  scopeType: 'team' | 'organization',
): string | undefined {
  return WidgetKeyMap[frontendKey]?.[scopeType]
}

const ALL_WIDGETS: WidgetDefinition[] = [
  {
    key: 'announcements',
    label: 'お知らせ',
    icon: 'pi pi-megaphone',
    description: '運営からのお知らせ',
    scope: ['personal'],
  },
  {
    key: 'team-announcements',
    label: 'チームのお知らせ',
    icon: 'pi pi-users',
    description: '所属チームからの掲示板・お知らせ',
    scope: ['personal'],
  },
  {
    key: 'org-announcements',
    label: '組織のお知らせ',
    icon: 'pi pi-building',
    description: '所属組織からの掲示板・お知らせ',
    scope: ['personal'],
  },
  {
    key: 'upcoming-events',
    label: '今後の予定',
    icon: 'pi pi-calendar',
    description: '直近のスケジュール・イベント',
    scope: ['personal', 'team', 'organization'],
  },
  {
    key: 'todos',
    label: 'TODO',
    icon: 'pi pi-check-square',
    description: '未完了のTODO',
    scope: ['personal', 'team'],
  },
  {
    key: 'timeline',
    label: 'タイムライン',
    icon: 'pi pi-comments',
    description: '最新の投稿',
    scope: ['personal', 'team', 'organization'],
  },
  {
    key: 'bulletin',
    label: '掲示板',
    icon: 'pi pi-clipboard',
    description: '最新のスレッド',
    scope: ['team', 'organization'],
  },
  {
    key: 'blog',
    label: 'ブログ',
    icon: 'pi pi-book',
    description: '最新の記事・記事作成',
    scope: ['personal', 'team', 'organization'],
  },
  {
    key: 'chat',
    label: 'チャット',
    icon: 'pi pi-inbox',
    description: '未読メッセージ',
    scope: ['personal', 'team', 'organization'],
  },
  {
    key: 'schedule',
    label: 'カレンダー',
    icon: 'pi pi-calendar',
    description: '月のスケジュールをカレンダーで表示',
    scope: ['team', 'organization'],
  },
  {
    key: 'members',
    label: 'メンバー',
    icon: 'pi pi-users',
    description: 'メンバー一覧',
    scope: ['team', 'organization'],
  },
  {
    key: 'activities',
    label: '活動記録',
    icon: 'pi pi-file-edit',
    description: '最近の活動',
    scope: ['team', 'organization'],
  },
  {
    key: 'gallery',
    label: 'ギャラリー',
    icon: 'pi pi-images',
    description: '最新の写真',
    scope: ['team', 'organization'],
  },
  {
    key: 'family-hub',
    label: '家族',
    icon: 'pi pi-home',
    description: '家族チームのお知らせ・TODO',
    scope: ['personal'],
  },
  {
    key: 'notifications',
    label: '通知',
    icon: 'pi pi-bell',
    description: '未読の通知',
    scope: ['personal'],
  },
  {
    key: 'circulation',
    label: '回覧板',
    icon: 'pi pi-send',
    description: '未読の回覧',
    scope: ['team', 'organization'],
  },
  {
    key: 'surveys',
    label: 'アンケート',
    icon: 'pi pi-chart-bar',
    description: '回答待ちのアンケート',
    scope: ['team', 'organization'],
  },
  {
    key: 'survey-results',
    label: 'アンケート結果',
    icon: 'pi pi-chart-pie',
    description: 'アンケートの集計結果をグラフで表示',
    scope: ['team', 'organization'],
  },
  {
    key: 'attendance-results',
    label: '出席確認状況',
    icon: 'pi pi-calendar-check',
    description: 'イベントごとの出欠状況と個人別回答',
    scope: ['team', 'organization'],
  },
  // Phase 2: F03.11 募集型予約ウィジェット
  {
    key: 'recruitment-feed',
    label: '新着募集',
    icon: 'pi pi-megaphone',
    description: 'フォロー先・サポーター先の新着募集',
    scope: ['personal'],
  },
  {
    key: 'my-recruitments',
    label: '参加予定',
    icon: 'pi pi-ticket',
    description: '自分の確定・キャンセル待ち参加予定',
    scope: ['personal'],
  },
  // F09.8.1 Phase 4: マイコルクボード
  {
    key: 'my-corkboard',
    label: 'マイコルクボード',
    icon: 'pi pi-bookmark-fill',
    description: 'ピン止めしたカードの横断一覧',
    scope: ['personal'],
  },
  // F14.2: チームメンバー定期更新フォーム
  {
    key: 'member-info',
    label: 'メンバー情報',
    icon: 'pi pi-id-card',
    description: '連絡先・緊急連絡先等の定期更新フォーム',
    scope: ['team'],
    defaultMinRole: 'MEMBER' as MinRole,
  },
  // F02.10: 天気ウィジェット
  {
    key: 'weather',
    label: '天気',
    icon: 'pi pi-cloud',
    description: '登録郵便番号から導出した居住地点の今日・明日の予報',
    scope: ['personal'],
  },
  // F17.1 §3.12.5: 井戸端ダイジェストウィジェット
  {
    key: 'village-lobby-digest',
    label: '井戸端ダイジェスト',
    icon: 'pi pi-comments',
    description: 'ピン留め村の本日の井戸端在席状況',
    scope: ['personal'],
  },
  // F04.11: 統合通知インボックスウィジェット
  {
    key: 'inbox',
    label: 'インボックス',
    icon: 'pi pi-inbox',
    description: '通知を一箇所で仕分け',
    scope: ['personal'],
  },
  // F08.7.1: 成績ウィジェット 3 種（F02.2 系の詳細ダッシュボード）
  {
    key: 'team-standings-record',
    label: '大会成績',
    icon: 'pi pi-trophy',
    description: '自チームの大会通算成績と順位履歴',
    scope: ['team'],
    defaultMinRole: 'SUPPORTER' as MinRole,
  },
  {
    key: 'team-division-standings',
    label: '順位表',
    icon: 'pi pi-list',
    description: '現在参加中のディビジョンの順位表',
    scope: ['team'],
    defaultMinRole: 'SUPPORTER' as MinRole,
  },
  {
    key: 'org-tournament-summary',
    label: '主催大会サマリ',
    icon: 'pi pi-sitemap',
    description: '主催する各大会×各部の首位・参加数・状態',
    scope: ['organization'],
    defaultMinRole: 'MEMBER' as MinRole,
  },
  // F08.10: チーム試合サマリ（直近成績＋ミニチャート＋進行中試合の記録再開導線）
  {
    key: 'team-match-summary',
    label: '試合サマリ',
    icon: 'pi pi-flag',
    description: '直近の試合成績と進行中試合の記録再開',
    scope: ['team'],
    defaultMinRole: 'MEMBER' as MinRole,
  },
]

function hiddenStorageKey(scopeType: string, scopeId?: string): string {
  return scopeId ? `dashboard-widgets:${scopeType}:${scopeId}` : `dashboard-widgets:${scopeType}`
}

function orderStorageKey(scopeType: string, scopeId?: string): string {
  return scopeId
    ? `dashboard-widget-order:${scopeType}:${scopeId}`
    : `dashboard-widget-order:${scopeType}`
}

export function useDashboardWidgets(
  scopeType: 'personal' | 'team' | 'organization',
  scopeId?: Ref<string> | string,
  viewerRole?: ViewerRole,
  visibilityMap?: WidgetVisibilitySetting[],
) {
  const resolvedId = typeof scopeId === 'string' ? scopeId : scopeId?.value

  const availableWidgets = computed(() => ALL_WIDGETS.filter((w) => w.scope.includes(scopeType)))

  const hiddenKeys = ref<Set<string>>(new Set())
  const orderedKeys = ref<string[]>([])

  function loadPreferences() {
    if (import.meta.server) return
    // hidden
    const hKey = hiddenStorageKey(scopeType, resolvedId)
    const rawHidden = localStorage.getItem(hKey)
    if (rawHidden) {
      try {
        hiddenKeys.value = new Set(JSON.parse(rawHidden))
      } catch {
        hiddenKeys.value = new Set()
      }
    }
    // order
    const oKey = orderStorageKey(scopeType, resolvedId)
    const rawOrder = localStorage.getItem(oKey)
    if (rawOrder) {
      try {
        orderedKeys.value = JSON.parse(rawOrder)
      } catch {
        orderedKeys.value = []
      }
    }
  }

  function saveHidden() {
    if (import.meta.server) return
    const key = hiddenStorageKey(scopeType, resolvedId)
    localStorage.setItem(key, JSON.stringify([...hiddenKeys.value]))
  }

  function saveOrder() {
    if (import.meta.server) return
    const key = orderStorageKey(scopeType, resolvedId)
    localStorage.setItem(key, JSON.stringify(orderedKeys.value))
  }

  function isVisible(widgetKey: string): boolean {
    return !hiddenKeys.value.has(widgetKey)
  }

  function toggleWidget(widgetKey: string) {
    if (hiddenKeys.value.has(widgetKey)) {
      hiddenKeys.value.delete(widgetKey)
    } else {
      hiddenKeys.value.add(widgetKey)
    }
    hiddenKeys.value = new Set(hiddenKeys.value)
    saveHidden()
  }

  /** availableWidgets をユーザー定義の順序でソート */
  const sortedWidgets = computed(() => {
    const order = orderedKeys.value
    if (order.length === 0) return availableWidgets.value
    const indexed = new Map(order.map((key, i) => [key, i]))
    return [...availableWidgets.value].sort((a, b) => {
      const ia = indexed.get(a.key) ?? Infinity
      const ib = indexed.get(b.key) ?? Infinity
      return ia - ib
    })
  })

  const visibleWidgets = computed(() =>
    sortedWidgets.value.filter((w) => {
      if (!isVisible(w.key)) return false
      const minRole = effectiveMinRole(w, scopeType, visibilityMap ?? [])
      return viewerRoleLevel(viewerRole) >= MIN_ROLE_LEVEL[minRole]
    }),
  )

  function reorder(fromIndex: number, toIndex: number) {
    const list = sortedWidgets.value.map((w) => w.key)
    const removed = list.splice(fromIndex, 1)
    if (removed.length === 0) return
    list.splice(toIndex, 0, removed[0] as string)
    orderedKeys.value = list
    saveOrder()
  }

  onMounted(() => loadPreferences())

  return {
    availableWidgets,
    sortedWidgets,
    visibleWidgets,
    isVisible,
    toggleWidget,
    reorder,
    hiddenKeys,
    orderedKeys,
  }
}
