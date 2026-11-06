/** F01.3.1 §3。moduleの利用可否は既存の判定を維持する。 */
export const FEATURE_DISPLAY_CATALOG = [
  {
    key: 'announcements',
    scopes: ['T', 'O'],
  },
  {
    key: 'chat',
    scopes: ['T', 'O'],
  },
  {
    key: 'bulletin',
    scopes: ['T', 'O'],
  },
  {
    key: 'timeline',
    scopes: ['T', 'O'],
  },
  {
    key: 'blog',
    scopes: ['T', 'O'],
  },
  {
    key: 'schedule',
    scopes: ['T', 'O'],
  },
  {
    key: 'shifts',
    scopes: ['T'],
  },
  {
    key: 'duties',
    scopes: ['T'],
  },
  {
    key: 'annual-plan',
    scopes: ['T', 'O'],
  },
  {
    key: 'matches',
    scopes: ['T'],
  },
  {
    key: 'match-analytics',
    scopes: ['T'],
  },
  {
    key: 'market',
    scopes: ['T', 'O'],
  },
  {
    key: 'member-profiles',
    scopes: ['T', 'O'],
  },
  {
    key: 'circulation',
    scopes: ['T', 'O'],
  },
  {
    key: 'safety',
    scopes: ['T', 'O'],
  },
  {
    key: 'todos',
    scopes: ['T', 'O'],
  },
  {
    key: 'workflows',
    scopes: ['T', 'O'],
  },
  {
    key: 'forms',
    scopes: ['T', 'O'],
  },
  {
    key: 'projects',
    scopes: ['T', 'O'],
  },
  {
    key: 'repair-plan',
    scopes: ['T'],
  },
  {
    key: 'incidents',
    scopes: ['T', 'O'],
  },
  {
    key: 'equipment',
    scopes: ['T', 'O'],
  },
  {
    key: 'parking',
    scopes: ['T', 'O'],
  },
  {
    key: 'files',
    scopes: ['T', 'O'],
  },
  {
    key: 'analytics',
    scopes: ['T'],
  },
  {
    key: 'league-transfers',
    scopes: ['T'],
  },
  {
    key: 'guide',
    scopes: ['T', 'O'],
  },
  {
    key: 'billing',
    scopes: ['T', 'O'],
  },
  {
    key: 'events',
    scopes: ['O'],
  },
  {
    key: 'timetable',
    scopes: ['O'],
  },
  {
    key: 'surveys',
    scopes: ['T', 'O'],
  },
  {
    key: 'voting',
    scopes: ['O'],
  },
  {
    key: 'committees',
    scopes: ['O'],
  },
  {
    key: 'facilities',
    scopes: ['O'],
  },
  {
    key: 'residents',
    scopes: ['O'],
  },
  {
    key: 'kb',
    scopes: ['O'],
  },
  {
    key: 'tournaments',
    scopes: ['T', 'O'],
  },
  {
    key: 'queue',
    scopes: ['O'],
  },
  {
    key: 'timeline-digest',
    scopes: ['O'],
  },
  {
    key: 'translations',
    scopes: ['O'],
  },
  {
    key: 'payments',
    scopes: ['O'],
  },
  {
    key: 'info',
    scopes: ['T', 'O'],
  },
  {
    key: 'members',
    scopes: ['T', 'O'],
  },
  {
    key: 'member-teams',
    scopes: ['O'],
  },
  {
    key: 'children',
    scopes: ['O'],
  },
  {
    key: 'reservations',
    scopes: ['T'],
  },
  {
    key: 'nav',
    scopes: ['T'],
  },
  {
    key: 'activities',
    scopes: ['T', 'O'],
  },
  {
    key: 'gallery',
    scopes: ['T', 'O'],
  },
  {
    key: 'member-info',
    scopes: ['T'],
  },
  {
    key: 'skills',
    scopes: ['T'],
  },
] as const

const WIDGET_FEATURES: Record<string, string> = {
  chat: 'chat',
  bulletin: 'bulletin',
  timeline: 'timeline',
  blog: 'blog',
  schedule: 'schedule',
  'upcoming-events': 'schedule',
  'attendance-results': 'schedule',
  members: 'member-profiles',
  circulation: 'circulation',
  todos: 'todos',
  projects: 'projects',
  surveys: 'surveys',
  'survey-results': 'surveys',
  'team-match-summary': 'match-analytics',
  'team-standings-record': 'tournaments',
  'team-division-standings': 'tournaments',
  'org-tournament-summary': 'tournaments',
  activities: 'activities',
  gallery: 'gallery',
  'member-info': 'member-info',
}
export function featureForWidget(key: string): string | undefined {
  return WIDGET_FEATURES[key]
}
export function featureForSidebar(
  path: string,
  scope: 'team' | 'organization',
): string | undefined {
  const key = path === 'settings/billing' ? 'billing' : path
  return FEATURE_DISPLAY_CATALOG.some(
    (item) =>
      item.key === key && (item.scopes as readonly string[]).includes(scope === 'team' ? 'T' : 'O'),
  )
    ? key
    : undefined
}

export const FEATURE_DISPLAY_LABELS: Record<string, string> = {
  announcements: 'teamSidebar.item.announcements',
  chat: 'teamSidebar.item.chat',
  bulletin: 'teamSidebar.item.bulletin',
  schedule: 'teamSidebar.item.schedule',
  shifts: 'teamSidebar.item.shifts',
  duties: 'teamSidebar.item.duties',
  'annual-plan': 'teamSidebar.item.annual_plan',
  matches: 'teamSidebar.item.matches',
  'match-analytics': 'teamSidebar.item.matchAnalytics',
  market: 'teamSidebar.item.market',
  'member-profiles': 'teamSidebar.item.member_profiles',
  skills: 'teamSidebar.item.skills',
  circulation: 'teamSidebar.item.circulation',
  safety: 'teamSidebar.item.safety_check',
  todos: 'teamSidebar.item.todos',
  workflows: 'teamSidebar.item.workflows',
  forms: 'teamSidebar.item.forms',
  projects: 'teamSidebar.item.projects',
  'repair-plan': 'teamSidebar.item.repair_plan',
  incidents: 'teamSidebar.item.incidents',
  equipment: 'teamSidebar.item.equipment',
  parking: 'teamSidebar.item.parking',
  files: 'teamSidebar.item.files',
  analytics: 'teamSidebar.item.analytics',
  'league-transfers': 'teamSidebar.item.leagueTransfers',
  guide: 'teamSidebar.item.guide',
  billing: 'teamSidebar.item.billing',
  timeline: 'orgSidebar.timeline',
  blog: 'orgSidebar.blog',
  events: 'orgSidebar.events',
  timetable: 'orgSidebar.timetable',
  members: 'teamShell.tab.members',
  surveys: 'orgSidebar.surveys',
  voting: 'orgSidebar.voting',
  committees: 'orgSidebar.committees',
  facilities: 'orgSidebar.facilities',
  residents: 'orgSidebar.residents',
  kb: 'orgSidebar.kb',
  tournaments: 'orgSidebar.tournaments',
  queue: 'orgSidebar.queue',
  'timeline-digest': 'orgSidebar.timelineDigest',
  translations: 'orgSidebar.translations',
  payments: 'orgSidebar.payments',
  info: 'teamShell.tab.info',
  'member-teams': 'orgShell.tab.member_teams',
  children: 'organization.children_tab',
  reservations: 'reservation.tab.team_page',
  nav: 'feature_display.nav',
  activities: 'dashboard.widget_labels.activities',
  gallery: 'dashboard.widget_labels.gallery',
  'member-info': 'dashboard.widget_labels.member-info',
}
