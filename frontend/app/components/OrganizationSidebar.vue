<script setup lang="ts">
import type { SidebarCategory, SidebarItem } from '~/types/sidebar'

const props = defineProps<{
  orgId: string
}>()

const categories: SidebarCategory[] = [
  {
    key: 'home',
    labelKey: 'orgSidebar.category.home',
    icon: 'pi pi-home',
    items: [
      { labelKey: 'orgSidebar.timeline', icon: 'pi pi-comments', path: 'timeline', moduleSlug: 'timeline', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.chat', icon: 'pi pi-comment', path: 'chat', moduleSlug: 'chat', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.bulletin', icon: 'pi pi-megaphone', path: 'bulletin', moduleSlug: 'bulletin', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.blog', icon: 'pi pi-pen-to-square', path: 'blog', moduleSlug: 'blog_cms', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.circulation', icon: 'pi pi-sync', path: 'circulation', moduleSlug: 'circular', requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'schedule',
    labelKey: 'orgSidebar.category.schedule',
    icon: 'pi pi-calendar',
    items: [
      { labelKey: 'orgSidebar.schedule', icon: 'pi pi-calendar', path: 'schedule', moduleSlug: 'schedule', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.events', icon: 'pi pi-calendar-plus', path: 'events', moduleSlug: 'schedule', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.timetable', icon: 'pi pi-table', path: 'timetable', moduleSlug: 'timetable', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.annualPlan', icon: 'pi pi-chart-bar', path: 'annual-plan', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'member',
    labelKey: 'orgSidebar.category.member',
    icon: 'pi pi-users',
    items: [
      { labelKey: 'orgSidebar.memberList', icon: 'pi pi-users', path: '', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.memberProfiles', icon: 'pi pi-id-card', path: 'member-profiles', moduleSlug: 'member_intro', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.invite', icon: 'pi pi-user-plus', path: '', moduleSlug: null, requiredRole: 'DEPUTY_ADMIN' },
      { labelKey: 'orgSidebar.permissionGroups', icon: 'pi pi-shield', path: '', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.onboarding', icon: 'pi pi-flag', path: 'onboarding', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'ops',
    labelKey: 'orgSidebar.category.ops',
    icon: 'pi pi-briefcase',
    items: [
      { labelKey: 'orgSidebar.todos', icon: 'pi pi-check-square', path: 'todos', moduleSlug: 'todo', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.workflows', icon: 'pi pi-sitemap', path: 'workflows', moduleSlug: 'workflow', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.forms', icon: 'pi pi-file-edit', path: 'forms', moduleSlug: 'form', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.surveys', icon: 'pi pi-chart-pie', path: 'surveys', moduleSlug: 'survey', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.voting', icon: 'pi pi-check-circle', path: 'voting', moduleSlug: 'voting', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.safety', icon: 'pi pi-heart', path: 'safety', moduleSlug: 'safety_check', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.incidents', icon: 'pi pi-exclamation-triangle', path: 'incidents', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.committees', icon: 'pi pi-building', path: 'committees', moduleSlug: 'committee', requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'facility',
    labelKey: 'orgSidebar.category.facility',
    icon: 'pi pi-building',
    items: [
      { labelKey: 'orgSidebar.facilities', icon: 'pi pi-building', path: 'facilities', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.equipment', icon: 'pi pi-box', path: 'equipment', moduleSlug: 'equipment', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.parking', icon: 'pi pi-car', path: 'parking', moduleSlug: 'parking', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.signage', icon: 'pi pi-desktop', path: 'signage', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'resident',
    labelKey: 'orgSidebar.category.resident',
    icon: 'pi pi-home',
    items: [
      { labelKey: 'orgSidebar.residents', icon: 'pi pi-users', path: 'residents', moduleSlug: 'resident_register', requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'data',
    labelKey: 'orgSidebar.category.data',
    icon: 'pi pi-database',
    items: [
      { labelKey: 'orgSidebar.files', icon: 'pi pi-folder', path: 'files', moduleSlug: 'file_sharing', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.kb', icon: 'pi pi-book', path: 'kb', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.analytics', icon: 'pi pi-chart-line', path: 'analytics', moduleSlug: 'analytics', requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.budget', icon: 'pi pi-wallet', path: 'budget', moduleSlug: 'budget', requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.auditLogs', icon: 'pi pi-list', path: 'audit-logs', moduleSlug: 'audit_log', requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'other',
    labelKey: 'orgSidebar.category.other',
    icon: 'pi pi-ellipsis-h',
    items: [
      { labelKey: 'orgSidebar.gamification', icon: 'pi pi-star', path: 'gamification', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.tournaments', icon: 'pi pi-trophy', path: 'tournaments', moduleSlug: 'tournament', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.leagueTransfers', icon: 'pi pi-arrow-right-arrow-left', path: 'league-transfers', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.queue', icon: 'pi pi-sort-numeric-up', path: 'queue', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.timelineDigest', icon: 'pi pi-align-left', path: 'timeline-digest', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.translations', icon: 'pi pi-language', path: 'translations', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'revenue',
    labelKey: 'orgSidebar.category.revenue',
    icon: 'pi pi-credit-card',
    items: [
      { labelKey: 'orgSidebar.payments', icon: 'pi pi-credit-card', path: 'payments', moduleSlug: 'payment', requiredRole: 'MEMBER' },
      { labelKey: 'orgSidebar.directMail', icon: 'pi pi-envelope', path: 'direct-mail', moduleSlug: 'direct_mail', requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.advertiser', icon: 'pi pi-megaphone', path: 'advertiser', moduleSlug: 'ad_display', requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.webhooks', icon: 'pi pi-code', path: 'webhooks', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'settings',
    labelKey: 'orgSidebar.category.settings',
    icon: 'pi pi-cog',
    items: [
      // F10.1.1 P2a: 管理コンソール（L2 ハブ）への入口。DEPUTY_ADMIN 以上に表示。
      { labelKey: 'orgSidebar.adminConsole', icon: 'pi pi-shield', path: 'admin', moduleSlug: null, requiredRole: 'DEPUTY_ADMIN' },
      { labelKey: 'orgSidebar.settingsModules', icon: 'pi pi-sliders-h', path: '', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.notificationCredits', icon: 'pi pi-bell', path: 'settings/notification-credits', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.todoStatusLabels', icon: 'pi pi-tags', path: 'settings/todo-status-labels', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'orgSidebar.faqSettings', icon: 'pi pi-question-circle', path: 'settings/faq-settings', moduleSlug: null, requiredRole: 'ADMIN' },
      // F20.1: 課金・プラン管理（閲覧はメンバー可・操作はADMIN限定。ナビはメンバー以上に表示）
      { labelKey: 'orgSidebar.billing', icon: 'pi pi-credit-card', path: 'settings/billing', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
]

// タブ遷移
// index.vue のタブ番号確認済み:
//   value=2: メンバー、value=4: 招待（isAdminOrDeputy）、value=5: 権限グループ（isAdmin）、value=7: 機能設定（isAdmin）
const router = useRouter()
const tabMap: Record<string, number> = {
  'orgSidebar.memberList': 2,
  'orgSidebar.invite': 4,
  'orgSidebar.permissionGroups': 5,
  'orgSidebar.settingsModules': 7,
}

function handleTabNavigate(item: SidebarItem) {
  const tab = tabMap[item.labelKey]
  router.push(`/organizations/${props.orgId}${tab !== undefined ? `?tab=${tab}` : ''}`)
}
</script>

<template>
  <BaseSidebar
    scope-type="organization"
    :scope-id="props.orgId"
    :categories="categories"
    @tab-navigate="handleTabNavigate"
  />
</template>
