<script setup lang="ts">
interface QuickLink {
  label: string
  icon: string
  to: string
  color: string
  bg: string
}

const quickLinks: QuickLink[] = [
  {
    label: 'お知らせ管理',
    icon: 'pi pi-megaphone',
    to: '/admin/announcements',
    color: 'text-blue-500',
    bg: 'bg-blue-50 dark:bg-blue-900/20',
  },
  {
    label: 'フィーチャーフラグ',
    icon: 'pi pi-flag',
    to: '/admin/feature-flags',
    color: 'text-purple-500',
    bg: 'bg-purple-50 dark:bg-purple-900/20',
  },
  {
    label: 'メンテナンス',
    icon: 'pi pi-wrench',
    to: '/admin/maintenance',
    color: 'text-orange-500',
    bg: 'bg-orange-50 dark:bg-orange-900/20',
  },
  {
    label: 'モジュール管理',
    icon: 'pi pi-th-large',
    to: '/admin/modules',
    color: 'text-teal-500',
    bg: 'bg-teal-50 dark:bg-teal-900/20',
  },
  {
    label: 'テンプレート',
    icon: 'pi pi-file',
    to: '/admin/templates',
    color: 'text-cyan-500',
    bg: 'bg-cyan-50 dark:bg-cyan-900/20',
  },
  {
    label: 'モデレーション',
    icon: 'pi pi-shield',
    to: '/admin/moderation',
    color: 'text-red-500',
    bg: 'bg-red-50 dark:bg-red-900/20',
  },
  {
    label: 'ユーザー管理',
    icon: 'pi pi-users',
    to: '/admin/users',
    color: 'text-indigo-500',
    bg: 'bg-indigo-50 dark:bg-indigo-900/20',
  },
  {
    label: '組織管理',
    icon: 'pi pi-building',
    to: '/admin/organizations',
    color: 'text-green-500',
    bg: 'bg-green-50 dark:bg-green-900/20',
  },
  {
    label: 'フィードバック',
    icon: 'pi pi-comments',
    to: '/admin/feedbacks',
    color: 'text-yellow-500',
    bg: 'bg-yellow-50 dark:bg-yellow-900/20',
  },
  {
    label: 'レポート',
    icon: 'pi pi-chart-bar',
    to: '/admin/reports',
    color: 'text-pink-500',
    bg: 'bg-pink-50 dark:bg-pink-900/20',
  },
  {
    label: '広告主管理',
    icon: 'pi pi-tag',
    to: '/admin/advertiser-accounts',
    color: 'text-amber-500',
    bg: 'bg-amber-50 dark:bg-amber-900/20',
  },
  {
    label: '監査ログ',
    icon: 'pi pi-list',
    to: '/admin/audit-logs',
    color: 'text-slate-500',
    bg: 'bg-slate-50 dark:bg-slate-900/20',
  },
  // F08.7 Phase 10-γ: シフト予算管理（BUDGET_VIEW/ADMIN 保有者向け、Service 層で 403）
  {
    label: 'シフト予算',
    icon: 'pi pi-wallet',
    to: '/admin/shift-budget/dashboard',
    color: 'text-emerald-500',
    bg: 'bg-emerald-50 dark:bg-emerald-900/20',
  },
]
</script>

<template>
  <section class="mb-6">
    <h2
      class="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-surface-400"
    >
      <i class="pi pi-th-large" />管理メニュー
    </h2>
    <div class="grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-6">
      <NuxtLink
        v-for="link in quickLinks"
        :key="link.to"
        :to="link.to"
        class="flex flex-col items-center gap-2 rounded-xl border border-surface-300 bg-surface-0 p-4 text-center transition-all hover:shadow-md dark:border-surface-600 dark:bg-surface-800"
      >
        <div class="flex h-10 w-10 items-center justify-center rounded-lg" :class="link.bg">
          <i :class="[link.icon, link.color, 'text-lg']" />
        </div>
        <span class="text-xs font-medium text-surface-600 dark:text-surface-300">{{
          link.label
        }}</span>
      </NuxtLink>
    </div>
  </section>
</template>
