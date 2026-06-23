<script setup lang="ts">
/**
 * F17.1 村機能 — 村詳細 / 村史タブ（永続シェル子）
 *
 * 設計書: docs/features/F17.1_village_community.md §2.2 / §13.2（Phase 3）
 *
 * 永続シェル方式（SPA）: 村データ・権限・VillageHeader・アクションは親
 * `pages/villages/[id].vue` に集約。本ファイルは村史パネル本体（読み取り専用）のみ。
 *
 * 構成:
 *   - 月別アーカイブ表示（年セレクタ）
 *   - 各村史: 投稿数 / 新規参加 / TOP3 タグカード表示
 */
import type { VillageChronicleResponse } from '~/types/village'
import { useVillageContext } from '~/composables/useVillageContext'

// auth は各タブで明示宣言（本コードベースの規約。親シェルも auth を持つ）。
definePageMeta({ middleware: 'auth' })

const route = useRoute()
const villageId = computed(() => String(route.params.id))
const { t } = useI18n()
const villageApi = useVillageApi()
const { handleApiError } = useErrorHandler()

// 村史は権限不問の読み取り専用パネル。コンテキストは provide 存在の保証として参照する。
useVillageContext()

const chronicles = ref<VillageChronicleResponse[]>([])
const chroniclesLoading = ref(false)
const totalChronicles = ref(0)
const page = ref(0)
const PAGE_SIZE = 12

// 年セレクタ — 全件取得後にフロントで絞り込み（件数が少ない前提）
const selectedYear = ref<number | 'ALL'>('ALL')

const availableYears = computed<number[]>(() => {
  const years = new Set<number>()
  for (const c of chronicles.value) {
    const y = Number.parseInt(c.yearMonth.slice(0, 4), 10)
    if (!Number.isNaN(y)) years.add(y)
  }
  return Array.from(years).sort((a, b) => b - a)
})

const filteredChronicles = computed<VillageChronicleResponse[]>(() => {
  if (selectedYear.value === 'ALL') return chronicles.value
  const y = String(selectedYear.value)
  return chronicles.value.filter(c => c.yearMonth.startsWith(y))
})

const yearOptions = computed(() => [
  { value: 'ALL' as const, label: t('village.chronicle.title') },
  ...availableYears.value.map(y => ({ value: y, label: `${y}` })),
])

async function loadChronicles() {
  chroniclesLoading.value = true
  try {
    const res = await villageApi.listChronicles(villageId.value, page.value, PAGE_SIZE)
    chronicles.value = res.items
    totalChronicles.value = res.total
  }
  catch (error) {
    chronicles.value = []
    totalChronicles.value = 0
    handleApiError(error, t('village.chronicle.loadFailed'))
  }
  finally {
    chroniclesLoading.value = false
  }
}

function formatYearMonth(yearMonth: string): string {
  // YYYY-MM 形式
  const [y, m] = yearMonth.split('-')
  if (!y || !m) return yearMonth
  return `${y}年${Number.parseInt(m, 10)}月`
}

function topTags(chronicle: VillageChronicleResponse): string[] {
  return chronicle.topicTags.slice(0, 3)
}

onMounted(() => {
  void loadChronicles()
})
</script>

<template>
  <div class="mx-auto max-w-4xl p-4 sm:p-6">
    <div class="flex items-center justify-between flex-wrap gap-3 mb-4">
      <div>
        <h2 class="text-xl font-bold">
          {{ t('village.chronicle.title') }}
        </h2>
        <p class="text-sm text-surface-500">
          {{ t('village.chronicle.subtitle') }}
        </p>
      </div>
      <Select
        v-model="selectedYear"
        :options="yearOptions"
        option-value="value"
        option-label="label"
        class="w-44"
      />
    </div>

    <div v-if="chroniclesLoading" class="text-center py-12 text-surface-500">
      <i class="pi pi-spin pi-spinner text-2xl" />
    </div>
    <DashboardEmptyState
      v-else-if="filteredChronicles.length === 0"
      icon="pi pi-book"
      :message="t('village.chronicle.empty')"
    />
    <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-3">
      <div
        v-for="c in filteredChronicles"
        :key="c.id"
        class="rounded-lg border border-surface-200 p-4 dark:border-surface-700"
      >
        <div class="flex items-center justify-between gap-2 mb-2">
          <h3 class="font-semibold text-lg">
            {{ formatYearMonth(c.yearMonth) }}
          </h3>
          <span class="text-xs text-surface-400">
            {{ c.generatedAt.slice(0, 10) }}
          </span>
        </div>
        <div class="grid grid-cols-2 gap-2 mb-3">
          <div class="rounded bg-surface-50 p-2 dark:bg-surface-800 text-center">
            <div class="text-xs text-surface-500">
              {{ t('village.chronicle.postCount') }}
            </div>
            <div class="text-xl font-bold">
              {{ c.postCount }}
            </div>
          </div>
          <div class="rounded bg-surface-50 p-2 dark:bg-surface-800 text-center">
            <div class="text-xs text-surface-500">
              {{ t('village.chronicle.newMemberCount') }}
            </div>
            <div class="text-xl font-bold">
              {{ c.newMemberCount }}
            </div>
          </div>
        </div>
        <div v-if="topTags(c).length > 0">
          <div class="text-xs text-surface-500 mb-1">
            {{ t('village.chronicle.topicTags') }}
          </div>
          <div class="flex flex-wrap gap-1">
            <Tag
              v-for="tag in topTags(c)"
              :key="tag"
              :value="tag"
              severity="secondary"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
