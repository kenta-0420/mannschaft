<script setup lang="ts">
/**
 * F17.1 村機能 — 巡礼推薦ウィジェット
 *
 * 設計書: docs/features/F17.1_village_community.md §13.2（Phase 3 — 巡礼）
 *
 * 機能:
 *   - 個人ダッシュボードに今日の推薦村カードを表示
 *   - クリックで村ページへ遷移し、自動で visited_at を記録
 */
import type { VillagePilgrimageRecommendationResponse } from '~/types/village'

const { t } = useI18n()
const villagePhase3Api = useVillagePhase3Api()
const { captureQuiet } = useErrorReport()

const loading = ref(true)
const error = ref<string | null>(null)
const recommendation = ref<VillagePilgrimageRecommendationResponse | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    recommendation.value = await villagePhase3Api.getTodaysPilgrimage()
  }
  catch (err) {
    captureQuiet(err, { context: 'PilgrimageRecommendationWidget: 取得失敗' })
    error.value = t('village.pilgrimage.loadFailed')
    recommendation.value = null
  }
  finally {
    loading.value = false
  }
}

async function visit() {
  if (!recommendation.value) return
  const villageId = recommendation.value.recommendedVillageId
  const recommendationId = recommendation.value.id
  try {
    await villagePhase3Api.recordVisit(recommendationId)
  }
  catch (err) {
    // 訪問記録失敗は遷移をブロックしない（捕捉のみ）
    captureQuiet(err, { context: 'PilgrimageRecommendationWidget: visit記録失敗' })
  }
  navigateTo(`/villages/${villageId}`)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-3 flex items-center justify-between">
      <h3 class="text-sm font-semibold text-surface-700 dark:text-surface-200">
        {{ t('village.pilgrimage.title') }}
      </h3>
      <span class="text-xs text-surface-500">
        {{ t('village.pilgrimage.todaysRecommendation') }}
      </span>
    </div>

    <div v-if="loading">
      <Skeleton height="6rem" />
    </div>

    <div v-else-if="error" class="flex flex-col items-center gap-2 py-6">
      <i class="pi pi-exclamation-triangle text-2xl text-orange-400" />
      <p class="text-sm text-surface-500">
        {{ error }}
      </p>
      <Button
        :label="t('village.feed.retry')"
        icon="pi pi-refresh"
        size="small"
        text
        @click="load"
      />
    </div>

    <DashboardEmptyState
      v-else-if="!recommendation"
      icon="pi pi-compass"
      :message="t('village.pilgrimage.empty')"
    />

    <button
      v-else
      type="button"
      class="w-full text-left rounded-lg border border-surface-200 p-4 transition hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-800"
      @click="visit"
    >
      <div class="flex items-start gap-3">
        <div class="shrink-0 rounded-full bg-primary-100 dark:bg-primary-900 p-3">
          <i class="pi pi-compass text-2xl text-primary" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-2 mb-1">
            <span class="font-semibold">
              {{ t('village.pilgrimage.todaysRecommendation') }}
            </span>
            <Badge
              v-if="recommendation.visited"
              :value="t('village.pilgrimage.recorded')"
              severity="success"
            />
          </div>
          <p
            v-if="recommendation.reason"
            class="text-sm text-surface-600 dark:text-surface-300 line-clamp-2"
          >
            {{ recommendation.reason }}
          </p>
          <div class="mt-2 flex items-center gap-2 text-xs text-primary">
            <i class="pi pi-arrow-right" />
            {{ t('village.pilgrimage.visit') }}
          </div>
        </div>
      </div>
    </button>
  </div>
</template>
