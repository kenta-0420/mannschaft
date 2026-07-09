<script setup lang="ts">
import type { SidebarCategory } from '~/types/sidebar'

const props = defineProps<{
  teamId: string
}>()

const categories: SidebarCategory[] = [
  {
    key: 'home',
    labelKey: 'teamSidebar.category.home',
    icon: 'pi pi-home',
    items: [
      { labelKey: 'teamSidebar.item.dashboard', icon: 'pi pi-th-large', path: '', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.announcements', icon: 'pi pi-bullhorn', path: 'announcements', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.chat', icon: 'pi pi-comments', path: 'chat', moduleSlug: 'chat', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.bulletin', icon: 'pi pi-clipboard', path: 'bulletin', moduleSlug: 'bulletin', requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'schedule',
    labelKey: 'teamSidebar.category.schedule',
    icon: 'pi pi-calendar',
    items: [
      { labelKey: 'teamSidebar.item.schedule', icon: 'pi pi-calendar', path: 'schedule', moduleSlug: 'schedule', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.shifts', icon: 'pi pi-clock', path: 'shifts', moduleSlug: 'shift', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.duties', icon: 'pi pi-list', path: 'duties', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.annual_plan', icon: 'pi pi-calendar-plus', path: 'annual-plan', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
  {
    // F08.10: 試合記録・分析（試合一覧＝記録入口、チーム分析＝統計可視化）
    key: 'match',
    labelKey: 'teamSidebar.category.match',
    icon: 'pi pi-flag',
    items: [
      { labelKey: 'teamSidebar.item.matches', icon: 'pi pi-flag', path: 'matches', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.matchAnalytics', icon: 'pi pi-chart-bar', path: 'match-analytics', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'member',
    labelKey: 'teamSidebar.category.member',
    icon: 'pi pi-users',
    items: [
      { labelKey: 'teamSidebar.item.circulation', icon: 'pi pi-envelope', path: 'circulation', moduleSlug: 'circulation', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.safety_check', icon: 'pi pi-shield', path: 'safety', moduleSlug: 'safety_check', requiredRole: 'MEMBER' },
    ],
  },
  {
    // タスク・進行系（MEMBER 以上で使う日常実務）
    key: 'ops_work',
    labelKey: 'teamSidebar.category.ops_work',
    icon: 'pi pi-briefcase',
    items: [
      { labelKey: 'teamSidebar.item.todos', icon: 'pi pi-check-square', path: 'todos', moduleSlug: 'todo', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.workflows', icon: 'pi pi-sitemap', path: 'workflows', moduleSlug: null, requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.forms', icon: 'pi pi-file-edit', path: 'forms', moduleSlug: 'survey', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.projects', icon: 'pi pi-folder', path: 'projects', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
  {
    // 運営・予算系（DEPUTY_ADMIN/ADMIN 以上の管理・カネ業務）
    key: 'ops_admin',
    labelKey: 'teamSidebar.category.ops_admin',
    icon: 'pi pi-wallet',
    items: [
      { labelKey: 'teamSidebar.item.budget', icon: 'pi pi-wallet', path: 'budget', moduleSlug: null, requiredRole: 'DEPUTY_ADMIN' },
      // F09.17 Phase 11-d-4: チーム広告主機能（チーム ADMIN のみ表示。
      // moduleSlug は組織版と同じ 'ad_display' を流用し、有効化判定を統一する）。
      { labelKey: 'teamSidebar.item.advertiser', icon: 'pi pi-megaphone', path: 'advertiser', moduleSlug: 'ad_display', requiredRole: 'ADMIN' },
    ],
  },
  {
    key: 'facility',
    labelKey: 'teamSidebar.category.facility',
    icon: 'pi pi-building',
    items: [
      { labelKey: 'teamSidebar.item.repair_plan', icon: 'pi pi-wrench', path: 'repair-plan', moduleSlug: 'repair_longterm_plan', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.equipment', icon: 'pi pi-cog', path: 'equipment', moduleSlug: 'equipment', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.parking', icon: 'pi pi-car', path: 'parking', moduleSlug: 'parking', requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'data',
    labelKey: 'teamSidebar.category.data',
    icon: 'pi pi-database',
    items: [
      { labelKey: 'teamSidebar.item.files', icon: 'pi pi-folder-open', path: 'files', moduleSlug: 'file_sharing', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.analytics', icon: 'pi pi-chart-bar', path: 'analytics', moduleSlug: 'analytics', requiredRole: 'MEMBER' },
      { labelKey: 'teamSidebar.item.audit_logs', icon: 'pi pi-history', path: 'audit-logs', moduleSlug: 'audit_log', requiredRole: 'ADMIN' },
      { labelKey: 'teamSidebar.item.leagueTransfers', icon: 'pi pi-arrow-right-arrow-left', path: 'league-transfers', moduleSlug: null, requiredRole: 'MEMBER' },
    ],
  },
  {
    key: 'settings',
    labelKey: 'teamSidebar.category.settings',
    icon: 'pi pi-cog',
    items: [
      // F10.1.1 P2a: 管理コンソール（L2 ハブ）への入口。DEPUTY_ADMIN 以上に表示。
      { labelKey: 'teamSidebar.item.adminConsole', icon: 'pi pi-shield', path: 'admin', moduleSlug: null, requiredRole: 'DEPUTY_ADMIN' },
      { labelKey: 'teamSidebar.item.settings', icon: 'pi pi-sliders-h', path: 'settings/shift', moduleSlug: null, requiredRole: 'ADMIN' },
      { labelKey: 'teamSidebar.item.faqSettings', icon: 'pi pi-question-circle', path: 'settings/faq-settings', moduleSlug: null, requiredRole: 'ADMIN' },
    ],
  },
]
</script>

<template>
  <BaseSidebar
    scope-type="team"
    :scope-id="props.teamId"
    :categories="categories"
  />
</template>
