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
      <SystemAdminModerationKpi :stats="moderationStats" />
      <SystemAdminErrorKpi :stats="errorStats" />
      <SystemAdminQuickLinks />

      <div class="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <SystemAdminPendingPanel
          title="再審査待ち"
          :items="pendingReReviews"
          empty-message="対応待ちの再審査はありません"
          link-to="/admin/moderation"
        />
        <SystemAdminPendingPanel
          title="フラグ解除申請"
          :items="pendingUnflagRequests"
          empty-message="対応待ちの申請はありません"
          link-to="/admin/moderation"
        />
        <SystemAdminBatchLogs :logs="batchLogs" />
      </div>
    </template>
  </div>
</template>
