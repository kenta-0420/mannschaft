import type { Ref } from 'vue'
import type { MinRole, ViewerRole, WidgetVisibilitySetting } from '~/types/dashboard'
import type { components } from '~/types/generated'

type WidgetSettingResponse = components['schemas']['WidgetSettingResponse']
type WidgetSettingItem = components['schemas']['WidgetSettingItem']

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
  // team/organization の場合は visibilityMap（admin設定）を優先する
  // personal は admin 可視性設定を持たないため除外
  if (scopeType === 'team' || scopeType === 'organization') {
    const bk = backendKeyForWidget(widget.key, scopeType)
    if (bk) {
      const setting = visibilityMap.find((s) => s.widget_key === bk)
      if (setting) return setting.min_role
    }
  }
  // バックエンドキーなし or visibilityMap未取得時はウィジェット定義のデフォルトを使用
  return widget.defaultMinRole ?? 'PUBLIC'
}

export interface WidgetDefinition {
  key: string
  label: string        // 後方互換のため残す（fallback表示用）
  labelKey: string     // i18n キー (例: 'dashboard.widget_labels.weather')
  icon: string
  description: string  // 後方互換のため残す
  descriptionKey: string  // i18n キー (例: 'dashboard.widget_descriptions.weather')
  scope: Array<'personal' | 'team' | 'organization'>
  defaultMinRole?: MinRole
}

/**
 * FE ケバブキー → BE WidgetKey enum（UPPER_SNAKE）の対応表。
 *
 * 対象2 の根治: team / organization スコープの「全」ウィジェットを BE enum と 1:1（一意）で対応させる。
 * 対象3-B の実装: personal スコープも DB 永続化（#1849 で確定した BE PERSONAL_* キーと対応）。
 * これにより並び順・表示設定が DB（dashboard_widget_settings）に永続化され、再描画で並びが戻る／
 * リロードで並びが消える問題を解消する。
 * 注意: schedule（カレンダー）は upcoming-events と区別するため専用キー TEAM_SCHEDULE_CALENDAR /
 * ORG_SCHEDULE_CALENDAR を使う（旧実装は両者が TEAM_UPCOMING_EVENTS に衝突していた）。
 */
export const WidgetKeyMap: Record<string, { team?: string; organization?: string; personal?: string }> = {
  // --- team / organization スコープ ---
  bulletin: { team: 'TEAM_NOTICES', organization: 'ORG_NOTICES' },
  'upcoming-events': { team: 'TEAM_UPCOMING_EVENTS', organization: 'ORG_UPCOMING_EVENTS', personal: 'UPCOMING_EVENTS' },
  todos: { team: 'TEAM_TODO', organization: 'ORG_TODO' },
  timeline: { team: 'TEAM_LATEST_POSTS', organization: 'ORG_LATEST_POSTS' },
  chat: { team: 'TEAM_UNREAD_THREADS', organization: 'ORG_UNREAD_THREADS' },
  schedule: { team: 'TEAM_SCHEDULE_CALENDAR', organization: 'ORG_SCHEDULE_CALENDAR' },
  members: { team: 'TEAM_MEMBERS', organization: 'ORG_MEMBERS' },
  activities: { team: 'TEAM_ACTIVITY', organization: 'ORG_ACTIVITY' },
  gallery: { team: 'TEAM_GALLERY', organization: 'ORG_GALLERY' },
  circulation: { team: 'TEAM_CIRCULATION', organization: 'ORG_CIRCULATION' },
  surveys: { team: 'TEAM_SURVEYS', organization: 'ORG_SURVEYS' },
  'survey-results': { team: 'TEAM_SURVEY_RESULTS', organization: 'ORG_SURVEY_RESULTS' },
  'attendance-results': { team: 'TEAM_MEMBER_ATTENDANCE', organization: 'ORG_MEMBER_ATTENDANCE' },
  blog: { team: 'TEAM_BLOG', organization: 'ORG_BLOG' },
  // F14.2 メンバー情報定期更新フォーム（team スコープのみ）
  'member-info': { team: 'TEAM_MEMBER_INFO' },
  // F08.7.1 成績ウィジェット 3 種
  'team-standings-record': { team: 'TEAM_TOURNAMENT_RECORD' },
  'team-division-standings': { team: 'TEAM_DIVISION_STANDINGS' },
  'org-tournament-summary': { organization: 'ORG_TOURNAMENT_SUMMARY' },
  // F08.10 チーム試合サマリ
  'team-match-summary': { team: 'TEAM_MATCH_SUMMARY' },
  // F02.3 プロジェクト進捗
  projects: { team: 'TEAM_PROJECT_PROGRESS', organization: 'ORG_PROJECT_PROGRESS' },
  // --- personal スコープ（対象3-B / #1849 で確定した BE WidgetKey） ---
  'event-dismissal-reminder': { personal: 'PERSONAL_EVENT_DISMISSAL_REMINDER' },
  notices: { personal: 'NOTICES' },
  'my-calendar': { personal: 'PERSONAL_CALENDAR' },
  weather: { personal: 'PERSONAL_WEATHER' },
  'todo-countdown': { personal: 'PERSONAL_TODO_COUNTDOWN' },
  'timetable-today': { personal: 'TIMETABLE_TODAY' },
  'quick-memo': { personal: 'TIMETABLE_NOTES' },
  'reflection-today': { personal: 'PERSONAL_REFLECTION_TODAY' },
  'unread-threads': { personal: 'UNREAD_THREADS' },
  'team-announcements': { personal: 'PERSONAL_TEAM_ANNOUNCEMENTS' },
  'org-announcements': { personal: 'PERSONAL_ORG_ANNOUNCEMENTS' },
  'my-blog': { personal: 'PERSONAL_BLOG' },
  'my-teams': { personal: 'PERSONAL_MY_TEAMS' },
  'my-organizations': { personal: 'PERSONAL_MY_ORGANIZATIONS' },
  favorites: { personal: 'PERSONAL_FAVORITES' },
  'my-timeline': { personal: 'PERSONAL_MY_TIMELINE' },
  'recent-activity': { personal: 'RECENT_ACTIVITY' },
  'personal-todo': { personal: 'PERSONAL_TODO' },
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
  // 対象2 追加分（BE WidgetDefaultMinRoleMap と同期）
  TEAM_MEMBERS: 'SUPPORTER',
  TEAM_GALLERY: 'SUPPORTER',
  TEAM_CIRCULATION: 'MEMBER',
  TEAM_SURVEYS: 'MEMBER',
  TEAM_SURVEY_RESULTS: 'MEMBER',
  TEAM_BLOG: 'PUBLIC',
  TEAM_SCHEDULE_CALENDAR: 'SUPPORTER',
  TEAM_MEMBER_INFO: 'MEMBER',
  ORG_UPCOMING_EVENTS: 'PUBLIC',
  ORG_LATEST_POSTS: 'SUPPORTER',
  ORG_BLOG: 'PUBLIC',
  ORG_UNREAD_THREADS: 'MEMBER',
  ORG_SCHEDULE_CALENDAR: 'SUPPORTER',
  ORG_MEMBERS: 'SUPPORTER',
  ORG_ACTIVITY: 'SUPPORTER',
  ORG_GALLERY: 'SUPPORTER',
  ORG_CIRCULATION: 'MEMBER',
  ORG_SURVEYS: 'MEMBER',
  ORG_SURVEY_RESULTS: 'MEMBER',
  ORG_MEMBER_ATTENDANCE: 'MEMBER',
}

export function backendKeyForWidget(
  frontendKey: string,
  scopeType: 'personal' | 'team' | 'organization',
): string | undefined {
  return WidgetKeyMap[frontendKey]?.[scopeType]
}

const ALL_WIDGETS: WidgetDefinition[] = [
  // =====================================================================
  // personal スコープ（対象3-B: DB永続化対象18ウィジェット）
  // 並び順はDashboardPersonalPanel.vue の初期描画順と一致させる
  // =====================================================================
  // F03.12 §16: 解散通知未送信リマインダー（主催者向け）
  {
    key: 'event-dismissal-reminder',
    label: '解散通知リマインダー',
    labelKey: 'dashboard.widget_labels.event-dismissal-reminder',
    icon: 'pi pi-exclamation-circle',
    description: '解散通知が未送信のイベントへのリマインダー',
    descriptionKey: 'dashboard.widget_descriptions.event-dismissal-reminder',
    scope: ['personal'],
  },
  // プラットフォームからのお知らせ（WidgetPlatformAnnouncements）
  {
    key: 'notices',
    label: 'お知らせ',
    labelKey: 'dashboard.widget_labels.notices',
    icon: 'pi pi-megaphone',
    description: 'プラットフォームからのお知らせ',
    descriptionKey: 'dashboard.widget_descriptions.notices',
    scope: ['personal'],
  },
  // マイカレンダー（データウィジェット: lg:col-span-2）
  {
    key: 'my-calendar',
    label: 'マイカレンダー',
    labelKey: 'dashboard.widget_labels.my-calendar',
    icon: 'pi pi-calendar',
    description: '自分のスケジュールをカレンダーで表示',
    descriptionKey: 'dashboard.widget_descriptions.my-calendar',
    scope: ['personal'],
  },
  // 今後の予定（WidgetUpcomingEvents）
  {
    key: 'upcoming-events',
    label: '今後の予定',
    labelKey: 'dashboard.widget_labels.upcoming-events',
    icon: 'pi pi-calendar',
    description: '直近のスケジュール・イベント',
    descriptionKey: 'dashboard.widget_descriptions.upcoming-events',
    scope: ['personal', 'team', 'organization'],
  },
  // 個人TODO（WidgetPersonalTodo）
  {
    key: 'personal-todo',
    label: '個人TODO',
    labelKey: 'dashboard.widget_labels.personal-todo',
    icon: 'pi pi-check-square',
    description: '個人の未完了TODO',
    descriptionKey: 'dashboard.widget_descriptions.personal-todo',
    scope: ['personal'],
  },
  // F02.10: 天気ウィジェット
  {
    key: 'weather',
    label: '天気',
    labelKey: 'dashboard.widget_labels.weather',
    icon: 'pi pi-cloud',
    description: '登録郵便番号から導出した居住地点の今日・明日の予報',
    descriptionKey: 'dashboard.widget_descriptions.weather',
    scope: ['personal'],
  },
  // TODOカウントダウン（WidgetTodoCountdown）
  {
    key: 'todo-countdown',
    label: 'TODOカウントダウン',
    labelKey: 'dashboard.widget_labels.todo-countdown',
    icon: 'pi pi-clock',
    description: '期限が近いTODOのカウントダウン',
    descriptionKey: 'dashboard.widget_descriptions.todo-countdown',
    scope: ['personal'],
  },
  // F03.15 Phase 3: 個人時間割「今日の時間割」（データウィジェット）
  {
    key: 'timetable-today',
    label: '今日の時間割',
    labelKey: 'dashboard.widget_labels.timetable-today',
    icon: 'pi pi-table',
    description: '本日の時間割とメモ',
    descriptionKey: 'dashboard.widget_descriptions.timetable-today',
    scope: ['personal'],
  },
  // F02.5: ポイっとメモ（データウィジェット）
  {
    key: 'quick-memo',
    label: 'ポイっとメモ',
    labelKey: 'dashboard.widget_labels.quick-memo',
    icon: 'pi pi-pencil',
    description: '未整理メモ最新5件',
    descriptionKey: 'dashboard.widget_descriptions.quick-memo',
    scope: ['personal'],
  },
  // F06.5 follow-up A: 今日の振り返り導線
  {
    key: 'reflection-today',
    label: '今日の振り返り',
    labelKey: 'dashboard.widget_labels.reflection-today',
    icon: 'pi pi-star',
    description: '今日の振り返り入力導線',
    descriptionKey: 'dashboard.widget_descriptions.reflection-today',
    scope: ['personal'],
  },
  // 未読チャット（WidgetUnreadThreads）
  {
    key: 'unread-threads',
    label: '未読チャット',
    labelKey: 'dashboard.widget_labels.unread-threads',
    icon: 'pi pi-inbox',
    description: '未読のチャットスレッド',
    descriptionKey: 'dashboard.widget_descriptions.unread-threads',
    scope: ['personal'],
  },
  // チームのお知らせ（WidgetTeamAnnouncements）
  {
    key: 'team-announcements',
    label: 'チームのお知らせ',
    labelKey: 'dashboard.widget_labels.team-announcements',
    icon: 'pi pi-users',
    description: '所属チームからの掲示板・お知らせ',
    descriptionKey: 'dashboard.widget_descriptions.team-announcements',
    scope: ['personal'],
  },
  // 組織のお知らせ（WidgetOrgAnnouncements）
  {
    key: 'org-announcements',
    label: '組織のお知らせ',
    labelKey: 'dashboard.widget_labels.org-announcements',
    icon: 'pi pi-building',
    description: '所属組織からの掲示板・お知らせ',
    descriptionKey: 'dashboard.widget_descriptions.org-announcements',
    scope: ['personal'],
  },
  // マイブログ（WidgetMyBlog）
  {
    key: 'my-blog',
    label: 'マイブログ',
    labelKey: 'dashboard.widget_labels.my-blog',
    icon: 'pi pi-book',
    description: '自分のブログ記事',
    descriptionKey: 'dashboard.widget_descriptions.my-blog',
    scope: ['personal'],
  },
  // 所属チーム（WidgetMyTeams）
  {
    key: 'my-teams',
    label: '所属チーム',
    labelKey: 'dashboard.widget_labels.my-teams',
    icon: 'pi pi-users',
    description: '参加中のチーム一覧',
    descriptionKey: 'dashboard.widget_descriptions.my-teams',
    scope: ['personal'],
  },
  // 所属組織（WidgetMyOrganizations）
  {
    key: 'my-organizations',
    label: '所属組織',
    labelKey: 'dashboard.widget_labels.my-organizations',
    icon: 'pi pi-building',
    description: '参加中の組織一覧',
    descriptionKey: 'dashboard.widget_descriptions.my-organizations',
    scope: ['personal'],
  },
  // F02.9 Phase 2: お気に入り（WidgetFavorites）
  {
    key: 'favorites',
    label: 'お気に入り',
    labelKey: 'dashboard.widget_labels.favorites',
    icon: 'pi pi-heart',
    description: 'お気に入りに登録したコンテンツ',
    descriptionKey: 'dashboard.widget_descriptions.favorites',
    scope: ['personal'],
  },
  // 個人集約タイムライン（WidgetMyTimeline・所属 team/org 横断）
  {
    key: 'my-timeline',
    label: '集約タイムライン',
    labelKey: 'dashboard.widget_labels.my-timeline',
    icon: 'pi pi-comments',
    description: '所属チーム・組織の投稿を横断表示',
    descriptionKey: 'dashboard.widget_descriptions.my-timeline',
    scope: ['personal'],
  },
  // 最近のアクティビティ（WidgetRecentActivity）
  {
    key: 'recent-activity',
    label: '最近のアクティビティ',
    labelKey: 'dashboard.widget_labels.recent-activity',
    icon: 'pi pi-history',
    description: '最近の活動履歴',
    descriptionKey: 'dashboard.widget_descriptions.recent-activity',
    scope: ['personal'],
  },
  // =====================================================================
  // personal のみ（固定パネル・DB永続化対象外）
  // FamilyHub と AdminBusinessAlert は DashboardPersonalPanel で v-if 固定描画
  // 以下はその他の personal 専用ウィジェット
  // =====================================================================
  // Phase 2: F03.11 募集型予約ウィジェット
  {
    key: 'recruitment-feed',
    label: '新着募集',
    labelKey: 'dashboard.widget_labels.recruitment-feed',
    icon: 'pi pi-megaphone',
    description: 'フォロー先・サポーター先の新着募集',
    descriptionKey: 'dashboard.widget_descriptions.recruitment-feed',
    scope: ['personal'],
  },
  {
    key: 'my-recruitments',
    label: '参加予定',
    labelKey: 'dashboard.widget_labels.my-recruitments',
    icon: 'pi pi-ticket',
    description: '自分の確定・キャンセル待ち参加予定',
    descriptionKey: 'dashboard.widget_descriptions.my-recruitments',
    scope: ['personal'],
  },
  // F09.8.1 Phase 4: マイコルクボード
  {
    key: 'my-corkboard',
    label: 'マイコルクボード',
    labelKey: 'dashboard.widget_labels.my-corkboard',
    icon: 'pi pi-bookmark-fill',
    description: 'ピン止めしたカードの横断一覧',
    descriptionKey: 'dashboard.widget_descriptions.my-corkboard',
    scope: ['personal'],
  },
  // F17.1 §3.12.5: 井戸端ダイジェストウィジェット
  {
    key: 'village-lobby-digest',
    label: '井戸端ダイジェスト',
    labelKey: 'dashboard.widget_labels.village-lobby-digest',
    icon: 'pi pi-comments',
    description: 'ピン留め村の本日の井戸端在席状況',
    descriptionKey: 'dashboard.widget_descriptions.village-lobby-digest',
    scope: ['personal'],
  },
  // =====================================================================
  // team / organization 共通スコープ
  // =====================================================================
  {
    key: 'todos',
    label: 'TODO',
    labelKey: 'dashboard.widget_labels.todos',
    icon: 'pi pi-check-square',
    description: '未完了のTODO',
    descriptionKey: 'dashboard.widget_descriptions.todos',
    scope: ['team'],
  },
  {
    key: 'timeline',
    label: 'タイムライン',
    labelKey: 'dashboard.widget_labels.timeline',
    icon: 'pi pi-comments',
    description: '最新の投稿',
    descriptionKey: 'dashboard.widget_descriptions.timeline',
    scope: ['team', 'organization'],
  },
  {
    key: 'bulletin',
    label: '掲示板',
    labelKey: 'dashboard.widget_labels.bulletin',
    icon: 'pi pi-clipboard',
    description: '最新のスレッド',
    descriptionKey: 'dashboard.widget_descriptions.bulletin',
    scope: ['team', 'organization'],
  },
  {
    key: 'blog',
    label: 'ブログ',
    labelKey: 'dashboard.widget_labels.blog',
    icon: 'pi pi-book',
    description: '最新の記事・記事作成',
    descriptionKey: 'dashboard.widget_descriptions.blog',
    scope: ['team', 'organization'],
  },
  {
    key: 'chat',
    label: 'チャット',
    labelKey: 'dashboard.widget_labels.chat',
    icon: 'pi pi-inbox',
    description: '未読メッセージ',
    descriptionKey: 'dashboard.widget_descriptions.chat',
    scope: ['team', 'organization'],
  },
  {
    key: 'schedule',
    label: 'カレンダー',
    labelKey: 'dashboard.widget_labels.schedule',
    icon: 'pi pi-calendar',
    description: '月のスケジュールをカレンダーで表示',
    descriptionKey: 'dashboard.widget_descriptions.schedule',
    scope: ['team', 'organization'],
  },
  {
    key: 'members',
    label: 'メンバー',
    labelKey: 'dashboard.widget_labels.members',
    icon: 'pi pi-users',
    description: 'メンバー一覧',
    descriptionKey: 'dashboard.widget_descriptions.members',
    scope: ['team', 'organization'],
  },
  {
    key: 'activities',
    label: '活動記録',
    labelKey: 'dashboard.widget_labels.activities',
    icon: 'pi pi-file-edit',
    description: '最近の活動',
    descriptionKey: 'dashboard.widget_descriptions.activities',
    scope: ['team', 'organization'],
  },
  {
    key: 'gallery',
    label: 'ギャラリー',
    labelKey: 'dashboard.widget_labels.gallery',
    icon: 'pi pi-images',
    description: '最新の写真',
    descriptionKey: 'dashboard.widget_descriptions.gallery',
    scope: ['team', 'organization'],
  },
  {
    key: 'circulation',
    label: '回覧板',
    labelKey: 'dashboard.widget_labels.circulation',
    icon: 'pi pi-send',
    description: '未読の回覧',
    descriptionKey: 'dashboard.widget_descriptions.circulation',
    scope: ['team', 'organization'],
  },
  {
    key: 'surveys',
    label: 'アンケート',
    labelKey: 'dashboard.widget_labels.surveys',
    icon: 'pi pi-chart-bar',
    description: '回答待ちのアンケート',
    descriptionKey: 'dashboard.widget_descriptions.surveys',
    scope: ['team', 'organization'],
  },
  {
    key: 'survey-results',
    label: 'アンケート結果',
    labelKey: 'dashboard.widget_labels.survey-results',
    icon: 'pi pi-chart-pie',
    description: 'アンケートの集計結果をグラフで表示',
    descriptionKey: 'dashboard.widget_descriptions.survey-results',
    scope: ['team', 'organization'],
  },
  {
    key: 'attendance-results',
    label: '出席確認状況',
    labelKey: 'dashboard.widget_labels.attendance-results',
    icon: 'pi pi-calendar-check',
    description: 'イベントごとの出欠状況と個人別回答',
    descriptionKey: 'dashboard.widget_descriptions.attendance-results',
    scope: ['team', 'organization'],
  },
  // F14.2: チームメンバー定期更新フォーム
  {
    key: 'member-info',
    label: 'メンバー情報',
    labelKey: 'dashboard.widget_labels.member-info',
    icon: 'pi pi-id-card',
    description: '連絡先・緊急連絡先等の定期更新フォーム',
    descriptionKey: 'dashboard.widget_descriptions.member-info',
    scope: ['team'],
    defaultMinRole: 'MEMBER' as MinRole,
  },
  // F08.7.1: 成績ウィジェット 3 種（F02.2 系の詳細ダッシュボード）
  {
    key: 'team-standings-record',
    label: '大会成績',
    labelKey: 'dashboard.widget_labels.team-standings-record',
    icon: 'pi pi-trophy',
    description: '自チームの大会通算成績と順位履歴',
    descriptionKey: 'dashboard.widget_descriptions.team-standings-record',
    scope: ['team'],
    defaultMinRole: 'SUPPORTER' as MinRole,
  },
  {
    key: 'team-division-standings',
    label: '順位表',
    labelKey: 'dashboard.widget_labels.team-division-standings',
    icon: 'pi pi-list',
    description: '現在参加中のディビジョンの順位表',
    descriptionKey: 'dashboard.widget_descriptions.team-division-standings',
    scope: ['team'],
    defaultMinRole: 'SUPPORTER' as MinRole,
  },
  {
    key: 'org-tournament-summary',
    label: '主催大会サマリ',
    labelKey: 'dashboard.widget_labels.org-tournament-summary',
    icon: 'pi pi-sitemap',
    description: '主催する各大会×各部の首位・参加数・状態',
    descriptionKey: 'dashboard.widget_descriptions.org-tournament-summary',
    scope: ['organization'],
    defaultMinRole: 'MEMBER' as MinRole,
  },
  // F08.10: チーム試合サマリ（直近成績＋ミニチャート＋進行中試合の記録再開導線）
  {
    key: 'team-match-summary',
    label: '試合サマリ',
    labelKey: 'dashboard.widget_labels.team-match-summary',
    icon: 'pi pi-flag',
    description: '直近の試合成績と進行中試合の記録再開',
    descriptionKey: 'dashboard.widget_descriptions.team-match-summary',
    scope: ['team'],
    defaultMinRole: 'MEMBER' as MinRole,
  },
  // F02.3: プロジェクト進捗ウィジェット（チーム・組織）
  {
    key: 'projects',
    label: 'プロジェクト',
    labelKey: 'dashboard.widget_labels.projects',
    icon: 'pi pi-briefcase',
    description: 'プロジェクトの進捗状況と一覧',
    descriptionKey: 'dashboard.widget_descriptions.projects',
    scope: ['team', 'organization'],
    defaultMinRole: 'MEMBER' as MinRole,
  },
]

/** FE ケバブキー → BE enum（スコープ別）。マッピングが無ければ undefined。 */
function backendKey(
  frontendKey: string,
  scopeType: 'personal' | 'team' | 'organization',
): string | undefined {
  return WidgetKeyMap[frontendKey]?.[scopeType]
}

/** BE enum → FE ケバブキー（スコープ別の逆引き）。 */
function buildReverseKeyMap(scopeType: 'personal' | 'team' | 'organization'): Map<string, string> {
  const rev = new Map<string, string>()
  for (const [feKey, be] of Object.entries(WidgetKeyMap)) {
    const bk = be[scopeType]
    if (bk) rev.set(bk, feKey)
  }
  return rev
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

  // team/organization/personal はすべて DB 永続化。
  // personal は対象3-B で DB 化（scope_id=0 で BE が個人を識別）。
  const isApiScope = scopeType === 'team' || scopeType === 'organization' || scopeType === 'personal'

  function isVisible(widgetKey: string): boolean {
    return !hiddenKeys.value.has(widgetKey)
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

  // ====================================================================
  // personal / team / organization: DB 永続化（SSR で順序確定 → 初回描画から保存順）
  // personal は scope_id=0 として BE が個人設定を識別する
  // ====================================================================
  if (isApiScope) {
    const apiScopeType = scopeType as 'personal' | 'team' | 'organization'
    const reverseMap = buildReverseKeyMap(apiScopeType)
    // useApi() は内部で useRuntimeConfig() / useAuthStore() を呼ぶため、setup 時に 1 度だけ捕捉して
    // イベントハンドラ（reorder/toggle）からも安全に使えるようにする（visibility composable と同方針）。
    const api = useApi()
    const nuxtApp = useNuxtApp()

    /** BE レスポンスを FE state（hiddenKeys / orderedKeys）へ反映する。 */
    function applyServerSettings(settings: WidgetSettingResponse[]) {
      const order: string[] = []
      const hidden = new Set<string>()
      // BE は sortOrder 昇順で返す（ない場合に備えソートしておく）。
      const sorted = [...settings].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
      for (const s of sorted) {
        if (!s.widgetKey) continue
        const feKey = reverseMap.get(s.widgetKey)
        if (!feKey) continue
        order.push(feKey)
        if (s.visible === false) hidden.add(feKey)
      }
      orderedKeys.value = order
      hiddenKeys.value = hidden
    }

    // useAsyncData で SSR 時にサーバ取得し、初期描画時点で保存順を確定させる。
    // これにより onMounted 後の再ソート＝再描画アニメ／hydration mismatch を排除する。
    // personal スコープの場合は scopeId=undefined（scope_id=0 相当として BE が識別）。
    const effectiveScopeId = apiScopeType === 'personal' ? undefined : resolvedId
    const dataKey = `dashboard-widgets:${apiScopeType}:${effectiveScopeId ?? '0'}`
    const { data: serverSettings, status } = useAsyncData(
      dataKey,
      async () => {
        const res = await api<{ data: WidgetSettingResponse[] }>('/api/v1/dashboard/widgets', {
          query: { scopeType: apiScopeType, scopeId: effectiveScopeId },
        })
        return res.data
      },
      { default: () => [] as WidgetSettingResponse[] },
    )

    watch(
      serverSettings,
      (val) => {
        if (val) applyServerSettings(val)
      },
      { immediate: true },
    )

    // 並び順が確定する（success/error）まではウィジェットを描画させないためのフラグ。
    // クライアント遷移時は useAsyncData が後追いで解決するため、確定前に描画すると
    // 「デフォルト順→保存順」の位置ジャンプが見える。確定後に初描画することで根絶する。
    // SSR/ペイロードキャッシュ時は描画時点で既に success のため初回から保存順で出る。
    const ready = computed(() => status.value === 'success' || status.value === 'error')

    /**
     * 現在の state（並び順・表示状態）を BE へ全量 PUT する。
     * 楽観更新済みの state を送り、失敗時は呼び出し側で渡された prev へロールバックする。
     */
    async function persist(): Promise<void> {
      const order = orderedKeys.value
      const indexed = new Map(order.map((key, i) => [key, i]))
      const widgets: WidgetSettingItem[] = []
      for (const w of availableWidgets.value) {
        const bk = backendKey(w.key, apiScopeType)
        if (!bk) continue
        widgets.push({
          widgetKey: bk,
          isVisible: !hiddenKeys.value.has(w.key),
          sortOrder: indexed.get(w.key) ?? widgets.length,
        })
      }
      if (widgets.length === 0) return
      await api('/api/v1/dashboard/widgets', {
        method: 'PUT',
        body: { scopeType: apiScopeType, scopeId: effectiveScopeId, widgets },
      })
    }

    /** 楽観更新 → PUT。失敗したら snapshot へロールバックしトーストを表示する。 */
    async function optimisticPersist(snapshot: {
      hidden: Set<string>
      order: string[]
    }): Promise<void> {
      try {
        await persist()
      } catch {
        hiddenKeys.value = new Set(snapshot.hidden)
        orderedKeys.value = [...snapshot.order]
        // setup 外でも安全に呼べるよう $i18n / $toast 経由（useI18n は使わない）。
        const t = (key: string) => nuxtApp.$i18n.t(key)
        const toast = nuxtApp.$toast as
          | { add: (opts: Record<string, unknown>) => void }
          | undefined
        toast?.add({
          severity: 'error',
          summary: t('dashboard.widget_settings.save_error_title'),
          detail: t('dashboard.widget_settings.save_error_detail'),
          life: 5000,
        })
      }
    }

    function toggleWidget(widgetKey: string) {
      const snapshot = { hidden: new Set(hiddenKeys.value), order: [...orderedKeys.value] }
      const next = new Set(hiddenKeys.value)
      if (next.has(widgetKey)) next.delete(widgetKey)
      else next.add(widgetKey)
      hiddenKeys.value = next
      void optimisticPersist(snapshot)
    }

    function reorder(fromIndex: number, toIndex: number) {
      const snapshot = { hidden: new Set(hiddenKeys.value), order: [...orderedKeys.value] }
      const list = sortedWidgets.value.map((w) => w.key)
      const removed = list.splice(fromIndex, 1)
      if (removed.length === 0) return
      list.splice(toIndex, 0, removed[0] as string)
      orderedKeys.value = list
      void optimisticPersist(snapshot)
    }

    return {
      availableWidgets,
      sortedWidgets,
      visibleWidgets,
      isVisible,
      toggleWidget,
      reorder,
      hiddenKeys,
      orderedKeys,
      ready,
    }
  }

  // isApiScope は常に true のため、このコードパスには到達しない
  // 型エラー防止のための fallback return（実際には到達しない）
  function toggleWidget(_widgetKey: string) { /* noop */ }
  function reorder(_fromIndex: number, _toIndex: number) { /* noop */ }

  return {
    availableWidgets,
    sortedWidgets,
    visibleWidgets,
    isVisible,
    toggleWidget,
    reorder,
    hiddenKeys,
    orderedKeys,
    ready: ref(true),
  }
}
