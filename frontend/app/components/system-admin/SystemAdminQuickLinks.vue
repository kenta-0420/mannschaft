<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

interface QuickLink {
  label: string
  icon: string
  to: string
  color: string
  bg: string
}

const quickLinks: QuickLink[] = [
  {
    label: t('admin.quickLinks.announcements'),
    icon: 'pi pi-megaphone',
    to: '/admin/announcements',
    color: 'text-blue-500',
    bg: 'bg-blue-50 dark:bg-blue-900/20',
  },
  {
    label: t('admin.quickLinks.featureFlags'),
    icon: 'pi pi-flag',
    to: '/admin/feature-flags',
    color: 'text-purple-500',
    bg: 'bg-purple-50 dark:bg-purple-900/20',
  },
  {
    label: t('admin.quickLinks.maintenance'),
    icon: 'pi pi-wrench',
    to: '/admin/maintenance',
    color: 'text-orange-500',
    bg: 'bg-orange-50 dark:bg-orange-900/20',
  },
  {
    label: t('admin.quickLinks.modules'),
    icon: 'pi pi-th-large',
    to: '/admin/modules',
    color: 'text-teal-500',
    bg: 'bg-teal-50 dark:bg-teal-900/20',
  },
  {
    label: t('admin.quickLinks.templates'),
    icon: 'pi pi-file',
    to: '/admin/templates',
    color: 'text-cyan-500',
    bg: 'bg-cyan-50 dark:bg-cyan-900/20',
  },
  {
    label: t('admin.quickLinks.moderation'),
    icon: 'pi pi-shield',
    to: '/admin/moderation',
    color: 'text-red-500',
    bg: 'bg-red-50 dark:bg-red-900/20',
  },
  {
    label: t('admin.quickLinks.users'),
    icon: 'pi pi-users',
    to: '/admin/users',
    color: 'text-indigo-500',
    bg: 'bg-indigo-50 dark:bg-indigo-900/20',
  },
  {
    label: t('admin.quickLinks.organizations'),
    icon: 'pi pi-building',
    to: '/admin/organizations',
    color: 'text-green-500',
    bg: 'bg-green-50 dark:bg-green-900/20',
  },
  {
    label: t('admin.quickLinks.feedback'),
    icon: 'pi pi-comments',
    to: '/admin/feedbacks',
    color: 'text-yellow-500',
    bg: 'bg-yellow-50 dark:bg-yellow-900/20',
  },
  {
    label: t('admin.quickLinks.reports'),
    icon: 'pi pi-chart-bar',
    to: '/admin/reports',
    color: 'text-pink-500',
    bg: 'bg-pink-50 dark:bg-pink-900/20',
  },
  {
    label: t('admin.quickLinks.advertisers'),
    icon: 'pi pi-tag',
    to: '/admin/advertiser-accounts',
    color: 'text-amber-500',
    bg: 'bg-amber-50 dark:bg-amber-900/20',
  },
  // F09.19.7 §10.1: 広告 admin 孤立ページのナビ接続
  {
    label: t('admin.quickLinks.adRateCards'),
    icon: 'pi pi-money-bill',
    to: '/admin/ad-rate-cards',
    color: 'text-amber-500',
    bg: 'bg-amber-50 dark:bg-amber-900/20',
  },
  {
    label: t('admin.quickLinks.adCreditLimitRequests'),
    icon: 'pi pi-credit-card',
    to: '/admin/ad-credit-limit-requests',
    color: 'text-amber-500',
    bg: 'bg-amber-50 dark:bg-amber-900/20',
  },
  {
    label: t('admin.quickLinks.affiliateSettings'),
    icon: 'pi pi-link',
    to: '/admin/affiliate-settings',
    color: 'text-amber-500',
    bg: 'bg-amber-50 dark:bg-amber-900/20',
  },
  {
    label: t('admin.quickLinks.auditLogs'),
    icon: 'pi pi-list',
    to: '/admin/audit-logs',
    color: 'text-slate-500',
    bg: 'bg-slate-50 dark:bg-slate-900/20',
  },
  // F08.7 Phase 10-γ: シフト予算管理（BUDGET_VIEW/ADMIN 保有者向け、Service 層で 403）
  {
    label: t('admin.quickLinks.shiftBudget'),
    icon: 'pi pi-wallet',
    to: '/admin/shift-budget/dashboard',
    color: 'text-emerald-500',
    bg: 'bg-emerald-50 dark:bg-emerald-900/20',
  },
  // F00.6: ベータ登録制限
  {
    label: t('admin.quickLinks.betaRestriction'),
    icon: 'pi pi-lock',
    to: '/system-admin/beta-restriction',
    color: 'text-rose-500',
    bg: 'bg-rose-50 dark:bg-rose-900/20',
  },
  // F12.5 Phase 2: エラーレポート（システム管理者向け）
  {
    label: t('admin.quickLinks.errorReports'),
    icon: 'pi pi-exclamation-triangle',
    to: '/system-admin/error-reports',
    color: 'text-red-500',
    bg: 'bg-red-50 dark:bg-red-900/20',
  },
  // F10.6 Phase 10-γ-③-b: システムログ（MySQLスロークエリ・NuxtSSRエラー）
  {
    label: t('systemAdmin.logs.quickLink'),
    icon: 'pi pi-file-export',
    to: '/system-admin/logs',
    color: 'text-gray-500',
    bg: 'bg-gray-50 dark:bg-gray-900/20',
  },
  // F09.17 Phase 11-c-4: 広告キャンペーン審査ダッシュボード
  {
    label: t('advertising.pages.system_admin_dashboard.quick_link_label'),
    icon: 'pi pi-shield',
    to: '/system-admin/advertising',
    color: 'text-amber-500',
    bg: 'bg-amber-50 dark:bg-amber-900/20',
  },
  // Phase E: GDPR パージ状況管理
  {
    label: t('admin.quickLinks.gdprPurge'),
    icon: 'pi pi-trash',
    to: '/system-admin/gdpr',
    color: 'text-violet-500',
    bg: 'bg-violet-50 dark:bg-violet-900/20',
  },
  // F12.5: 障害告知バナー（シスアド手動オーサリング）
  {
    label: t('incident_banner.quick_link_label'),
    icon: 'pi pi-exclamation-circle',
    to: '/system-admin/incident-banners',
    color: 'text-orange-500',
    bg: 'bg-orange-50 dark:bg-orange-900/20',
  },
  // F10.1: 目安箱 全体受信トレイ
  {
    label: t('admin.quickLinks.feedbackInbox'),
    icon: 'pi pi-inbox',
    to: '/system-admin/feedbacks',
    color: 'text-lime-500',
    bg: 'bg-lime-50 dark:bg-lime-900/20',
  },
  // F20.1: 課金マスタ管理（プラン/機能カタログ/人数バンド/手動付与/契約横断検索）
  {
    label: t('admin.quickLinks.billing'),
    icon: 'pi pi-credit-card',
    to: '/system-admin/billing',
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
