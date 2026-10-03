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
const notification = useNotification()

const moderationStats = ref<ModerationDashboardResponse | null>(null)
const errorStats = ref<ErrorReportStatsResponse | null>(null)
const batchLogs = ref<BatchJobLogResponse[]>([])
const pendingReReviews = ref<WarningReReviewResponse[]>([])
const pendingUnflagRequests = ref<YabaiUnflagResponse[]>([])
const loading = ref(true)

// 各取得の失敗を「0件（空/ゼロ）」と区別するためのエラー状態。
// 安全系（モデレーション/再審査/フラグ解除）は失敗を成功(0件)に見せてはならない。
const moderationError = ref(false)
const errorStatsError = ref(false)
const batchError = ref(false)
const reReviewError = ref(false)
const unflagError = ref(false)

async function load() {
  loading.value = true
  moderationError.value = false
  errorStatsError.value = false
  batchError.value = false
  reReviewError.value = false
  unflagError.value = false
  try {
    // 1 API の不通で全体を白紙化しない部分描画パターン。ただし失敗は null(=0件) と区別し、
    // 該当エラー状態 ref を立ててセクションに「取得失敗」を表示する（症状を隠さない・根治原則）。
    const [mod, err, batch, rereviews, unflag] = await Promise.all([
      systemAdminApi.getModerationDashboard().catch((e) => {
        console.error('[system-admin] モデレーションダッシュボードの取得に失敗', e)
        moderationError.value = true
        return null
      }),
      systemAdminApi.getErrorReportStats().catch((e) => {
        console.error('[system-admin] エラーレポート統計の取得に失敗', e)
        errorStatsError.value = true
        return null
      }),
      systemAdminApi.getBatchLogs({ size: 5 }).catch((e) => {
        console.error('[system-admin] バッチログの取得に失敗', e)
        batchError.value = true
        return null
      }),
      systemAdminApi.getWarningReReviews({ size: 5 }).catch((e) => {
        console.error('[system-admin] 再審査待ちの取得に失敗', e)
        reReviewError.value = true
        return null
      }),
      systemAdminApi.getUnflagRequests({ size: 5 }).catch((e) => {
        console.error('[system-admin] フラグ解除申請の取得に失敗', e)
        unflagError.value = true
        return null
      }),
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
      <SystemAdminSecurityScanCard />
      <p
        v-if="moderationError"
        class="mb-3 rounded border border-red-300 bg-red-50 p-2 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
        role="alert"
      >
        {{ $t('error.section_load_failed') }}
      </p>
      <SystemAdminModerationKpi v-else :stats="moderationStats" />
      <p
        v-if="errorStatsError"
        class="mb-3 rounded border border-red-300 bg-red-50 p-2 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
        role="alert"
      >
        {{ $t('error.section_load_failed') }}
      </p>
      <SystemAdminErrorKpi v-else :stats="errorStats" />
      <SystemAdminBatchSummary />
      <SystemAdminQuickLinks />

      <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div v-if="reReviewError" class="rounded border border-red-300 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300" role="alert">
          {{ $t('error.section_load_failed') }}
        </div>
        <SystemAdminPendingPanel
          v-else
          title="再審査待ち"
          :items="pendingReReviews"
          empty-message="対応待ちの再審査はありません"
          link-to="/admin/moderation"
        />
        <div v-if="unflagError" class="rounded border border-red-300 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300" role="alert">
          {{ $t('error.section_load_failed') }}
        </div>
        <SystemAdminPendingPanel
          v-else
          title="フラグ解除申請"
          :items="pendingUnflagRequests"
          empty-message="対応待ちの申請はありません"
          link-to="/admin/moderation"
        />
        <div v-if="batchError" class="rounded border border-red-300 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300" role="alert">
          {{ $t('error.section_load_failed') }}
        </div>
        <SystemAdminBatchLogs v-else :logs="batchLogs" />
      </div>
    </template>
  </div>
</template>
