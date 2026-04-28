import type { MinRole } from '~/types/dashboard'

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
]

function hiddenStorageKey(scopeType: string, scopeId?: number): string {
  return scopeId ? `dashboard-widgets:${scopeType}:${scopeId}` : `dashboard-widgets:${scopeType}`
}

function orderStorageKey(scopeType: string, scopeId?: number): string {
  return scopeId
    ? `dashboard-widget-order:${scopeType}:${scopeId}`
    : `dashboard-widget-order:${scopeType}`
}

export function useDashboardWidgets(
  scopeType: 'personal' | 'team' | 'organization',
  scopeId?: Ref<number> | number,
) {
  const resolvedId = typeof scopeId === 'number' ? scopeId : scopeId?.value

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

  const visibleWidgets = computed(() => sortedWidgets.value.filter((w) => isVisible(w.key)))

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
