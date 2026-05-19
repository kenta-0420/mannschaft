<script setup lang="ts">
/**
 * F09.17 Phase 11-c-4 — SYSTEM_ADMIN 広告審査ダッシュボード（概況）。
 *
 * <p>審査キュー件数・自動フラグ件数・通報件数・直近 BLOCK 件数を KPI として
 * 表示し、各キーセクションへの導線を提供する。実装の重点は導線・概況把握。</p>
 */
import type { AdReviewQueueItem, AdUserReport } from '~/types/adModeration'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const systemAdminAdApi = useSystemAdminAdCampaignApi()
const notification = useNotification()
const router = useRouter()

const loading = ref(true)
const reviewQueue = ref<AdReviewQueueItem[]>([])
const reviewQueueTotal = ref(0)
const autoFlaggedCount = ref(0)
const userReports = ref<AdUserReport[]>([])
const userReportsTotal = ref(0)
const autoSuspendCandidateCount = ref(0)

/**
 * 概況用に審査キュー + 通報を並列取得する。
 *
 * <p>「直近 7 日 BLOCK 件数」 KPI は backend が現状サポートしていないため、
 * 取得失敗時は 0 を表示しても運用上の混乱を招かないようにしておく。
 * （根治: backend に専用集計 endpoint を追加する PR で対応予定）</p>
 */
async function load() {
  loading.value = true
  try {
    const [queueRes, reportsRes] = await Promise.all([
      systemAdminAdApi.listReviewQueue({ page: 0, size: 5 }),
      systemAdminAdApi.listUserReports({ page: 0, size: 5 }),
    ])
    reviewQueue.value = queueRes.data
    reviewQueueTotal.value = queueRes.meta.totalElements
    autoFlaggedCount.value = queueRes.data.filter(
      (item) => item.moderationStatus === 'AUTO_FLAGGED',
    ).length
    userReports.value = reportsRes.data
    userReportsTotal.value = reportsRes.meta.totalElements
    autoSuspendCandidateCount.value = reportsRes.data.filter(
      (r) => r.autoSuspendCandidate,
    ).length
  } catch {
    notification.error(t('advertising.pages.system_admin_dashboard.load_failed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

function goReviewQueue() {
  router.push('/system-admin/advertising/moderation-queue')
}

function goUserReports() {
  router.push('/system-admin/advertising/user-reports')
}
</script>

<template>
  <div class="mx-auto max-w-screen-xl">
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
          {{ t('advertising.pages.system_admin_dashboard.title') }}
        </h1>
        <p class="mt-0.5 text-sm text-surface-500">
          {{ t('advertising.pages.system_admin_dashboard.description') }}
        </p>
      </div>
      <Button
        v-tooltip.left="t('advertising.actions.reload')"
        icon="pi pi-refresh"
        text
        rounded
        :loading="loading"
        @click="load"
      />
    </div>

    <PageLoading v-if="loading" />

    <template v-else>
      <!-- 通報集中 警告バナー -->
      <div
        v-if="autoSuspendCandidateCount > 0"
        class="mb-6 rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-800 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200"
        role="alert"
        data-testid="auto-suspend-warning"
      >
        <i class="pi pi-exclamation-triangle mr-2" />
        {{
          t('advertising.pages.system_admin_dashboard.auto_suspend_warning', {
            count: autoSuspendCandidateCount,
          })
        }}
      </div>

      <!-- KPI 4 枚カード -->
      <div class="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-review-pending"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.system_admin_dashboard.kpi_review_pending') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-primary-700 dark:text-primary-300">
            {{ reviewQueueTotal }}
          </p>
        </div>
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-auto-flagged"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.system_admin_dashboard.kpi_auto_flagged') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-amber-600 dark:text-amber-400">
            {{ autoFlaggedCount }}
          </p>
        </div>
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-user-reports"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.system_admin_dashboard.kpi_user_reports') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-red-600 dark:text-red-400">
            {{ userReportsTotal }}
          </p>
        </div>
        <div
          class="rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-700 dark:bg-surface-800"
          data-testid="kpi-recent-blocks"
        >
          <p class="text-xs text-surface-500">
            {{ t('advertising.pages.system_admin_dashboard.kpi_recent_blocks') }}
          </p>
          <p class="mt-2 text-3xl font-bold text-surface-500 dark:text-surface-300">
            —
          </p>
        </div>
      </div>

      <!-- ショートカット -->
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
        <Button
          :label="t('advertising.pages.system_admin_dashboard.shortcut_review_queue')"
          icon="pi pi-list-check"
          severity="primary"
          @click="goReviewQueue"
        />
        <Button
          :label="t('advertising.pages.system_admin_dashboard.shortcut_user_reports')"
          icon="pi pi-flag"
          severity="warn"
          @click="goUserReports"
        />
      </div>
    </template>
  </div>
</template>
