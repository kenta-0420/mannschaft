<script setup lang="ts">
/**
 * F06.5 個人ダッシュボード「今日の振り返り」ウィジェット（follow-up A③）。
 *
 * 常設導線が無かった reflection 機能への入口を個人ダッシュボードに設置する
 * （マスター御裁可「個人ダッシュボード内」）。タイトルクリックで `/reflections`
 * （今日ビュー）へ遷移。今日のコマ／テーマの件数サマリ（未記入・想起待ち）を出す。
 */
import type { ReflectionTodayItem } from '~/types/reflection'

const { t } = useI18n()
const reflectionApi = useReflectionApi()
const { captureQuiet } = useErrorReport()

const loading = ref(true)
const items = ref<ReflectionTodayItem[]>([])

// テーマ／コマが割り当たっている item のみを対象に件数を数える（空きコマは除外）。
const themedItems = computed(() => items.value.filter(i => i.themeId))
// 想起待ち（マスク中）件数。
const pendingRecallCount = computed(() => themedItems.value.filter(i => i.isMasked).length)
// 当日まだ記入していない件数。
const unwrittenCount = computed(() => themedItems.value.filter(i => !i.hasEntryToday).length)

async function load() {
  loading.value = true
  try {
    const res = await reflectionApi.getToday()
    items.value = res.data.items ?? []
  } catch (error) {
    captureQuiet(error, { context: 'WidgetReflectionToday: 今日の振り返り取得' })
    items.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="t('reflection.nav.today')"
    icon="pi pi-book"
    to="/reflections"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <template #default>
      <div v-if="themedItems.length > 0" class="space-y-3">
        <div class="grid grid-cols-2 gap-2">
          <NuxtLink
            to="/reflections"
            class="flex flex-col items-center rounded-lg border border-surface-200 px-3 py-3 transition-colors hover:border-primary hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-700/50"
          >
            <span class="text-2xl font-bold text-amber-600">{{ pendingRecallCount }}</span>
            <span class="mt-1 text-xs text-surface-500">{{ t('reflection.widget.pending_recall') }}</span>
          </NuxtLink>
          <NuxtLink
            to="/reflections"
            class="flex flex-col items-center rounded-lg border border-surface-200 px-3 py-3 transition-colors hover:border-primary hover:bg-surface-50 dark:border-surface-700 dark:hover:bg-surface-700/50"
          >
            <span class="text-2xl font-bold text-primary">{{ unwrittenCount }}</span>
            <span class="mt-1 text-xs text-surface-500">{{ t('reflection.widget.unwritten') }}</span>
          </NuxtLink>
        </div>
        <Button
          :label="t('reflection.widget.open_today')"
          icon="pi pi-arrow-right"
          icon-pos="right"
          size="small"
          outlined
          class="w-full"
          @click="navigateTo('/reflections')"
        />
      </div>

      <div v-else class="text-center">
        <DashboardEmptyState icon="pi pi-book" :message="t('reflection.widget.empty')" />
        <Button
          :label="t('reflection.widget.open_today')"
          icon="pi pi-arrow-right"
          icon-pos="right"
          size="small"
          text
          @click="navigateTo('/reflections')"
        />
      </div>
    </template>
  </DashboardWidgetCard>
</template>
