<script setup lang="ts">
import type { EquipmentTrendingResponse } from '~/types/equipment-ranking'

const props = defineProps<{
  teamId: number
}>()

const { getTrending } = useEquipmentTrending(props.teamId)

const data = ref<EquipmentTrendingResponse | null>(null)
const loading = ref(false)
const visible = ref(true)  // エラー時に false にして非表示
const is503 = ref(false)
const showOptOutDialog = ref(false)
const selectedCategory = ref<string | null>(null)

// カテゴリ一覧を ranking から抽出（重複除去）
const categories = computed(() => {
  if (!data.value) return []
  const cats = data.value.ranking
    .map((r) => r.category)
    .filter((c): c is string => c !== null)
  return [...new Set(cats)]
})

// 選択中カテゴリでフィルタ（null = 全カテゴリ）
const filteredItems = computed(() => {
  if (!data.value) return []
  if (selectedCategory.value === null) return data.value.ranking
  return data.value.ranking.filter((r) => r.category === selectedCategory.value)
})

async function load() {
  loading.value = true
  try {
    const res = await getTrending({ limit: 10 })
    data.value = res.data
  } catch (err: unknown) {
    // 503 = ランキング準備中
    const status = (err as { statusCode?: number })?.statusCode
    if (status === 503) {
      is503.value = true
      // 503 でも空状態として表示（非表示にはしない）
    } else {
      // その他のエラーはコンポーネントごと非表示
      console.warn('[EquipmentTrending] API error, hiding component', err)
      visible.value = false
    }
  } finally {
    loading.value = false
  }
}

function onOptOutConfirmed() {
  // opt-out 変更後に再取得
  load()
}

onMounted(() => load())
</script>

<template>
  <div v-if="visible" class="rounded-xl border border-surface-200 bg-surface-50 p-4">
    <EquipmentTrendingHeader
      v-if="data"
      :total-teams="data.totalTemplatesTeams"
      :calculated-at="data.calculatedAt"
      :opt-out="data.optOut"
      @open-opt-out="showOptOutDialog = true"
    />

    <EquipmentTrendingCategoryTabs
      v-if="categories.length > 0"
      v-model="selectedCategory"
      :categories="categories"
    />

    <EquipmentTrendingSkeleton v-if="loading" />

    <template v-else-if="data && filteredItems.length > 0">
      <EquipmentTrendingItem
        v-for="item in filteredItems"
        :key="item.rank"
        :item="item"
        class="mb-2 last:mb-0"
      />
    </template>

    <EquipmentTrendingEmpty v-else-if="!loading" :is503="is503" />

    <!-- Amazon アソシエイト表記 -->
    <p v-if="data && filteredItems.some((r) => r.replenishUrl)" class="mt-3 text-xs text-surface-300">
      {{ $t('equipment.trending.amazon_associate_footer') }}
    </p>

    <EquipmentTrendingOptOutDialog
      v-if="data"
      v-model:visible="showOptOutDialog"
      :is-opt-out="data.optOut"
      :team-id="teamId"
      @confirmed="onOptOutConfirmed"
    />
  </div>
</template>
