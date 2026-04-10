<script setup lang="ts">
import type { AnnualViewMonth, EventCategory } from '~/types/annual-plan'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const orgId = computed(() => Number(route.params.id))
const annualPlanApi = useAnnualPlanApi()
const notification = useNotification()

const months = ref<AnnualViewMonth[]>([])
const categories = ref<EventCategory[]>([])
const loading = ref(true)
const selectedYear = ref(
  new Date().getMonth() >= 3 ? new Date().getFullYear() : new Date().getFullYear() - 1,
)
const selectedCategoryId = ref<number | undefined>()

const yearOptions = computed(() => [selectedYear.value - 1, selectedYear.value, selectedYear.value + 1])

const categoryOptions = computed(() => [
  { id: undefined as number | undefined, name: t('annual_plan.all_categories') },
  ...categories.value,
])

async function loadData() {
  loading.value = true
  try {
    const res = await annualPlanApi.getAnnualView('organization', orgId.value, {
      academicYear: selectedYear.value,
      categoryIds: selectedCategoryId.value !== undefined ? [selectedCategoryId.value] : undefined,
    })
    months.value = res.months
    categories.value = res.categories
  } catch {
    notification.error(t('annual_plan.load_error'))
  } finally {
    loading.value = false
  }
}

function formatDate(isoString: string): string {
  return isoString.substring(0, 10)
}

watch([selectedYear, selectedCategoryId], () => loadData())
onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <div class="mb-6 flex items-center justify-between">
      <h1 class="text-2xl font-bold">{{ $t('annual_plan.title') }}</h1>
      <div class="flex items-center gap-3">
        <Select
          v-model="selectedYear"
          :options="yearOptions"
          class="w-28"
        />
        <Select
          v-model="selectedCategoryId"
          :options="categoryOptions"
          option-label="name"
          option-value="id"
          :placeholder="$t('annual_plan.category_placeholder')"
          class="w-40"
        />
      </div>
    </div>

    <PageLoading v-if="loading" />

    <div v-else class="space-y-6">
      <div
        v-for="month in months"
        :key="month.month"
        class="rounded-xl border border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
      >
        <h2 class="mb-3 text-lg font-semibold">{{ month.month }}</h2>
        <div v-if="month.events.length === 0" class="text-sm text-surface-400">
          {{ $t('annual_plan.no_events') }}
        </div>
        <div v-else class="space-y-2">
          <div
            v-for="event in month.events"
            :key="event.id"
            class="flex items-center gap-3 rounded-lg p-2"
          >
            <div
              v-if="event.eventCategory?.color"
              class="h-3 w-3 flex-shrink-0 rounded-full"
              :style="{ backgroundColor: event.eventCategory.color }"
            />
            <div class="flex-1 min-w-0">
              <p class="text-sm font-medium truncate">{{ event.title }}</p>
              <p class="text-xs text-surface-400">
                {{ formatDate(event.startAt) }}{{ event.endAt ? ` - ${formatDate(event.endAt)}` : '' }}
              </p>
            </div>
            <Badge
              v-if="event.eventCategory?.name"
              :value="event.eventCategory.name"
              severity="secondary"
              class="text-xs"
            />
          </div>
        </div>
      </div>
      <div v-if="months.length === 0" class="py-12 text-center text-surface-500">
        <i class="pi pi-calendar mb-2 text-4xl" />
        <p>{{ $t('annual_plan.no_data') }}</p>
      </div>
    </div>
  </div>
</template>
