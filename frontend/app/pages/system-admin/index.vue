<script setup lang="ts">
import type {
  ModerationDashboardResponse,
  ErrorReportStatsResponse,
  BatchJobLogResponse,
  WarningReReviewResponse,
  YabaiUnflagResponse,
} from '~/types/system-admin'

definePageMeta({ middleware: 'auth' })

const systemAdminApi = useSystemAdminApi()
const { relativeTime } = useRelativeTime()
const notification = useNotification()

// ===== State =====
const moderationStats = ref<ModerationDashboardResponse | null>(null)
const errorStats = ref<ErrorReportStatsResponse | null>(null)
const batchLogs = ref<BatchJobLogResponse[]>([])
const pendingReReviews = ref<WarningReReviewResponse[]>([])
const pendingUnflagRequests = ref<YabaiUnflagResponse[]>([])
const loading = ref(true)

// ===== Load =====
async function load() {
  loading.value = true
  try {
    const [mod, err, batch, rereviews, unflag] = await Promise.all([
      systemAdminApi.getModerationDashboard().catch(() => null),
      systemAdminApi.getErrorReportStats().catch(() => null),
      systemAdminApi.getBatchLogs({ size: 5 }).catch(() => null),
      systemAdminApi.getWarningReReviews({ size: 5 }).catch(() => null),
      systemAdminApi.getUnflagRequests({ size: 5 }).catch(() => null),
    ])
    moderationStats.value = mod?.data ?? null
    errorStats.value = err?.data ?? null
    batchLogs.value = batch?.data ?? []
    pendingReReviews.value = (rereviews?.data ?? []).filter((r) => r.status === 'PENDING')
    pendingUnflagRequests.value = (unflag?.data ?? []).filter((r) => r.status === 'PENDING')
  } catch {
    notification.error('データの取得に失敗しました')
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ===== Quick Links =====
const quickLinks = [
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
]

// ===== Helpers =====
function batchStatusClass(status: string) {
  switch (status) {
    case 'SUCCESS':
      return 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
    case 'FAILED':
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
    case 'RUNNING':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
    default:
      return 'bg-surface-100 text-surface-500'
  }
}

function batchStatusLabel(status: string) {
  switch (status) {
    case 'SUCCESS':
      return '成功'
    case 'FAILED':
      return '失敗'
    case 'RUNNING':
      return '実行中'
    default:
      return status
  }
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl">
    <!-- ヘッダー -->
    <div class="mb-6 flex items-center justify-between">
      <div>
        <div class="mb-1 flex items-center gap-2">
          <span
            class="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
          >
            SYSTEM ADMIN
          </span>
        </div>
        <h1 class="text-2xl font-bold text-surface-800 dark:text-surface-100">
          システム管理ダッシュボード
        </h1>
        <p class="mt-0.5 text-sm text-surface-500">プラットフォーム全体の状態を管理します</p>
      </div>
      <Button
        v-tooltip.left="'再読み込み'"
        icon="pi pi-refresh"
        text
        rounded
        :loading="loading"
        @click="load"
      />
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- ===== KPI: モデレーション ===== -->
      <section class="mb-6">
        <h2
          class="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-surface-400"
        >
          <i class="pi pi-shield" />モデレーション状況
        </h2>
        <div class="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (moderationStats?.pendingReportsCount ?? 0) > 0
                ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">未対応通報</span>
            <span
              class="text-2xl font-bold"
              :class="
                (moderationStats?.pendingReportsCount ?? 0) > 0
                  ? 'text-red-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ moderationStats?.pendingReportsCount ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (moderationStats?.pendingAppealsCount ?? 0) > 0
                ? 'border-orange-200 bg-orange-50 dark:border-orange-800 dark:bg-orange-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">異議申立</span>
            <span
              class="text-2xl font-bold"
              :class="
                (moderationStats?.pendingAppealsCount ?? 0) > 0
                  ? 'text-orange-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ moderationStats?.pendingAppealsCount ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (moderationStats?.pendingReReviewsCount ?? 0) > 0
                ? 'border-yellow-200 bg-yellow-50 dark:border-yellow-800 dark:bg-yellow-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">再審査待ち</span>
            <span
              class="text-2xl font-bold"
              :class="
                (moderationStats?.pendingReReviewsCount ?? 0) > 0
                  ? 'text-yellow-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ moderationStats?.pendingReReviewsCount ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (moderationStats?.escalatedReReviewsCount ?? 0) > 0
                ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">エスカレーション</span>
            <span
              class="text-2xl font-bold"
              :class="
                (moderationStats?.escalatedReReviewsCount ?? 0) > 0
                  ? 'text-red-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ moderationStats?.escalatedReReviewsCount ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (moderationStats?.pendingUnflagRequestsCount ?? 0) > 0
                ? 'border-purple-200 bg-purple-50 dark:border-purple-800 dark:bg-purple-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">フラグ解除申請</span>
            <span
              class="text-2xl font-bold"
              :class="
                (moderationStats?.pendingUnflagRequestsCount ?? 0) > 0
                  ? 'text-purple-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ moderationStats?.pendingUnflagRequestsCount ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
          >
            <span class="mb-1 text-xs text-surface-500">有効違反数</span>
            <span class="text-2xl font-bold text-surface-700 dark:text-surface-200">
              {{ moderationStats?.activeViolationsCount ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (moderationStats?.yabaiUsersCount ?? 0) > 0
                ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">ヤバイユーザー</span>
            <span
              class="text-2xl font-bold"
              :class="
                (moderationStats?.yabaiUsersCount ?? 0) > 0
                  ? 'text-red-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ moderationStats?.yabaiUsersCount ?? '-' }}
            </span>
          </div>
        </div>
      </section>

      <!-- ===== KPI: エラーレポート ===== -->
      <section class="mb-6">
        <h2
          class="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-surface-400"
        >
          <i class="pi pi-exclamation-triangle" />エラーレポート
        </h2>
        <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (errorStats?.totalNew ?? 0) > 0
                ? 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">新規</span>
            <span
              class="text-2xl font-bold"
              :class="
                (errorStats?.totalNew ?? 0) > 0
                  ? 'text-red-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ errorStats?.totalNew ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
          >
            <span class="mb-1 text-xs text-surface-500">調査中</span>
            <span class="text-2xl font-bold text-yellow-600">{{
              errorStats?.totalInvestigating ?? '-'
            }}</span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
            :class="
              (errorStats?.totalReopened ?? 0) > 0
                ? 'border-orange-200 bg-orange-50 dark:border-orange-800 dark:bg-orange-900/20'
                : ''
            "
          >
            <span class="mb-1 text-xs text-surface-500">再オープン</span>
            <span
              class="text-2xl font-bold"
              :class="
                (errorStats?.totalReopened ?? 0) > 0
                  ? 'text-orange-600'
                  : 'text-surface-700 dark:text-surface-200'
              "
            >
              {{ errorStats?.totalReopened ?? '-' }}
            </span>
          </div>
          <div
            class="flex flex-col rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
          >
            <span class="mb-1 text-xs text-surface-500">今日の件数</span>
            <span class="text-2xl font-bold text-surface-700 dark:text-surface-200">{{
              errorStats?.totalToday ?? '-'
            }}</span>
          </div>
        </div>

        <!-- 頻出エラー -->
        <div
          v-if="errorStats?.topErrors && errorStats.topErrors.length > 0"
          class="mt-3 rounded-xl border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-800"
        >
          <div class="border-b border-surface-100 px-4 py-2.5 dark:border-surface-700">
            <span class="text-xs font-semibold text-surface-500"
              >頻出エラー TOP {{ errorStats.topErrors.length }}</span
            >
          </div>
          <div class="divide-y divide-surface-100 dark:divide-surface-700">
            <NuxtLink
              v-for="err in errorStats.topErrors"
              :key="err.errorHash"
              to="/admin/reports"
              class="flex items-center justify-between px-4 py-2.5 text-sm hover:bg-surface-50 dark:hover:bg-surface-700"
            >
              <span class="min-w-0 flex-1 truncate text-surface-600 dark:text-surface-300">{{
                err.errorMessage
              }}</span>
              <span
                class="ml-3 shrink-0 rounded-full bg-red-100 px-2 py-0.5 text-xs font-semibold text-red-600 dark:bg-red-900/30 dark:text-red-400"
              >
                {{ err.count }}件
              </span>
            </NuxtLink>
          </div>
        </div>
      </section>

      <!-- ===== クイックアクセス ===== -->
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
            class="flex flex-col items-center gap-2 rounded-xl border border-surface-200 bg-surface-0 p-4 text-center transition-all hover:shadow-md dark:border-surface-700 dark:bg-surface-800"
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

      <!-- ===== 下段: 要対応 + バッチログ ===== -->
      <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <!-- 再審査待ち -->
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-800"
        >
          <div
            class="flex items-center justify-between border-b border-surface-100 px-4 py-3 dark:border-surface-700"
          >
            <span class="text-sm font-semibold">再審査待ち</span>
            <NuxtLink to="/admin/moderation" class="text-xs text-primary hover:underline"
              >すべて表示</NuxtLink
            >
          </div>
          <div
            v-if="pendingReReviews.length > 0"
            class="divide-y divide-surface-100 dark:divide-surface-700"
          >
            <div v-for="r in pendingReReviews" :key="r.id" class="px-4 py-3">
              <div class="flex items-start justify-between gap-2">
                <p class="min-w-0 flex-1 truncate text-sm text-surface-700 dark:text-surface-200">
                  ユーザー #{{ r.userId }}
                </p>
                <span class="shrink-0 text-[11px] text-surface-400">{{
                  relativeTime(r.createdAt)
                }}</span>
              </div>
              <p class="mt-0.5 line-clamp-1 text-xs text-surface-500">{{ r.reason }}</p>
            </div>
          </div>
          <div v-else class="px-4 py-8 text-center text-sm text-surface-400">
            <i class="pi pi-check-circle mb-2 text-2xl text-green-400" />
            <p>対応待ちの再審査はありません</p>
          </div>
        </div>

        <!-- フラグ解除申請 -->
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-800"
        >
          <div
            class="flex items-center justify-between border-b border-surface-100 px-4 py-3 dark:border-surface-700"
          >
            <span class="text-sm font-semibold">フラグ解除申請</span>
            <NuxtLink to="/admin/moderation" class="text-xs text-primary hover:underline"
              >すべて表示</NuxtLink
            >
          </div>
          <div
            v-if="pendingUnflagRequests.length > 0"
            class="divide-y divide-surface-100 dark:divide-surface-700"
          >
            <div v-for="r in pendingUnflagRequests" :key="r.id" class="px-4 py-3">
              <div class="flex items-start justify-between gap-2">
                <p class="min-w-0 flex-1 truncate text-sm text-surface-700 dark:text-surface-200">
                  ユーザー #{{ r.userId }}
                </p>
                <span class="shrink-0 text-[11px] text-surface-400">{{
                  relativeTime(r.createdAt)
                }}</span>
              </div>
              <p class="mt-0.5 line-clamp-1 text-xs text-surface-500">{{ r.reason }}</p>
            </div>
          </div>
          <div v-else class="px-4 py-8 text-center text-sm text-surface-400">
            <i class="pi pi-check-circle mb-2 text-2xl text-green-400" />
            <p>対応待ちの申請はありません</p>
          </div>
        </div>

        <!-- バッチジョブ -->
        <div
          class="rounded-xl border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-800"
        >
          <div
            class="flex items-center justify-between border-b border-surface-100 px-4 py-3 dark:border-surface-700"
          >
            <span class="text-sm font-semibold">バッチジョブ（直近）</span>
          </div>
          <div
            v-if="batchLogs.length > 0"
            class="divide-y divide-surface-100 dark:divide-surface-700"
          >
            <div v-for="log in batchLogs" :key="log.id" class="px-4 py-3">
              <div class="flex items-center justify-between gap-2">
                <p
                  class="min-w-0 flex-1 truncate text-sm font-medium text-surface-700 dark:text-surface-200"
                >
                  {{ log.jobName }}
                </p>
                <span
                  :class="batchStatusClass(log.status)"
                  class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium"
                >
                  {{ batchStatusLabel(log.status) }}
                </span>
              </div>
              <div class="mt-0.5 flex items-center gap-2 text-[11px] text-surface-400">
                <span>{{ relativeTime(log.startedAt) }}</span>
                <span v-if="log.processedCount > 0">・{{ log.processedCount }}件処理</span>
              </div>
              <p v-if="log.errorMessage" class="mt-1 line-clamp-1 text-xs text-red-500">
                {{ log.errorMessage }}
              </p>
            </div>
          </div>
          <div v-else class="px-4 py-8 text-center text-sm text-surface-400">
            <i class="pi pi-inbox mb-2 text-2xl" />
            <p>バッチログがありません</p>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
