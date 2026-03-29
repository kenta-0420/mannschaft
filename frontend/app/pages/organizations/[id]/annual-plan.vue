<script setup lang="ts">
import type { AnnualViewMonth, EventCategory } from '~/types/annual-plan'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const annualPlanApi = useAnnualPlanApi()
const notification = useNotification()

const months = ref<AnnualViewMonth[]>([])
const categories = ref<EventCategory[]>([])
const loading = ref(true)
const selectedYear = ref(new Date().getFullYear())
const selectedCategory = ref<number | undefined>()

async function loadData() {
  loading.value = true
  try {
    const [monthsRes, catsRes] = await Promise.all([
      annualPlanApi.getAnnualView('organization', orgId.value, {
        year: selectedYear.value,
        categoryId: selectedCategory.value,
      }),
      annualPlanApi.listCategories('organization', orgId.value),
    ])
    months.value = monthsRes
    categories.value = catsRes
  } catch {
    notification.error('年間行事計画の取得に失敗しました')
  } finally {
    loading.value = false
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
      </div>
    </div>

    <div v-if="loading" class="flex justify-center py-12"><ProgressSpinner /></div>

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
            class="flex items-center gap-3 rounded-lg p-2"
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
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
