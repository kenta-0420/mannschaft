<script setup lang="ts">
import type { AnnualViewMonth, EventCategory } from '~/types/annual-plan'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const annualPlanApi = useAnnualPlanApi()
const notification = useNotification()

const months = ref<AnnualViewMonth[]>([])
const categories = ref<EventCategory[]>([])
const loading = ref(true)
const selectedYear = ref(new Date().getFullYear())
const selectedCategory = ref<number | undefined>()
const showCopyDialog = ref(false)

async function loadData() {
  loading.value = true
  try {
    const [monthsRes, catsRes] = await Promise.all([
      annualPlanApi.getAnnualView('team', teamId.value, {
        year: selectedYear.value,
        categoryId: selectedCategory.value,
      }),
      annualPlanApi.listCategories('team', teamId.value),
    ])
    months.value = monthsRes
    categories.value = catsRes
  } catch {
    notification.error('年間行事計画の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function handleCopy() {
  try {
    await annualPlanApi.executeCopy('team', teamId.value, selectedYear.value - 1)
    notification.success('前年度の行事をコピーしました')
    showCopyDialog.value = false
    await loadData()
  } catch {
    notification.error('コピーに失敗しました')
  }
}

watch([selectedYear, selectedCategory], () => loadData())
onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">年間行事計画</h1>
      <div class="flex items-center gap-3">
        <Select
          v-model="selectedYear"
          :options="[selectedYear - 1, selectedYear, selectedYear + 1]"
          class="w-28"
        />
        <Select
          v-model="selectedCategory"
          :options="[{ id: undefined, name: 'すべて' }, ...categories]"
          option-label="name"
          option-value="id"
          placeholder="カテゴリ"
          class="w-40"
        />
        <Button
          label="前年度コピー"
          icon="pi pi-copy"
          severity="secondary"
          size="small"
          @click="showCopyDialog = true"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="space-y-6">
      <div
        v-for="month in months"
        :key="month.month"
        class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-800"
      >
        <h2 class="mb-3 text-lg font-semibold">{{ month.month }}</h2>
        <div v-if="month.events.length === 0" class="text-sm text-surface-400">行事なし</div>
        <div v-else class="space-y-2">
          <div
            v-for="event in month.events"
            :key="event.id"
            class="flex items-center gap-3 rounded-lg p-2 hover:bg-surface-50 dark:hover:bg-surface-700"
          >
            <div
              v-if="event.categoryColor"
              class="h-3 w-3 rounded-full"
              :style="{ backgroundColor: event.categoryColor }"
            />
            <div class="flex-1">
              <p class="text-sm font-medium">{{ event.title }}</p>
              <p class="text-xs text-surface-400">
                {{ event.startDate }}{{ event.endDate ? ` - ${event.endDate}` : '' }}
              </p>
            </div>
            <Badge
              v-if="event.categoryName"
              :value="event.categoryName"
              severity="secondary"
              class="text-xs"
            />
            <Badge :value="event.eventType" severity="info" class="text-xs" />
          </div>
        </div>
      </div>
      <div v-if="months.length === 0" class="py-12 text-center text-surface-500">
        <i class="pi pi-calendar mb-2 text-4xl" />
        <p>行事データがありません</p>
      </div>
    </div>

    <Dialog
      v-model:visible="showCopyDialog"
      header="前年度トレース"
      :modal="true"
      class="w-full max-w-md"
    >
      <p class="mb-4">
        {{ selectedYear - 1 }}年度の行事を{{
          selectedYear
        }}年度にコピーしますか？日程は自動調整されます。
      </p>
      <template #footer>
        <Button label="キャンセル" severity="secondary" @click="showCopyDialog = false" />
        <Button label="コピー実行" icon="pi pi-copy" @click="handleCopy" />
      </template>
    </Dialog>
  </div>
</template>
