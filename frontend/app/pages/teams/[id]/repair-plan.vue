<script setup lang="ts">
import type { RepairPlanTimelineResponse } from '~/types/repairPlanTimeline'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const teamId = computed(() => Number(route.params.id))
const { getTimeline } = useRepairPlanTimelineApi()
const notification = useNotification()

const currentYear = new Date().getFullYear()
const yearFrom = ref(currentYear - 20)
const yearTo = ref(currentYear + 10)

const timelineData = ref<RepairPlanTimelineResponse | null>(null)
const loading = ref(true)

async function loadData() {
  loading.value = true
  try {
    timelineData.value = await getTimeline('teams', teamId.value, {
      yearFrom: yearFrom.value,
      yearTo: yearTo.value,
    })
  } catch {
    notification.error(t('repair_plan.timeline.no_data'))
  } finally {
    loading.value = false
  }
}

async function handleRefresh() {
  await loadData()
}

onMounted(loadData)
</script>

<template>
  <div class="mx-auto max-w-7xl">
    <div class="mb-6 flex flex-wrap items-center justify-between gap-4">
      <div class="flex items-center gap-3">
        <BackButton />
        <div>
          <PageHeader :title="$t('repair_plan.dashboard.title')" />
          <p class="text-sm text-surface-500 dark:text-surface-400">
            {{ $t('repair_plan.timeline.title') }}
          </p>
        </div>
      </div>

      <!-- 年度範囲フォーム -->
      <div class="flex flex-wrap items-center gap-3">
        <div class="flex items-center gap-2">
          <label class="text-sm text-surface-600 dark:text-surface-300">
            {{ $t('repair_plan.timeline.year_from') }}
          </label>
          <InputNumber
            v-model="yearFrom"
            :min="1900"
            :max="yearTo - 1"
            :use-grouping="false"
            class="w-24"
            input-class="text-center"
          />
        </div>
        <span class="text-surface-400">〜</span>
        <div class="flex items-center gap-2">
          <label class="text-sm text-surface-600 dark:text-surface-300">
            {{ $t('repair_plan.timeline.year_to') }}
          </label>
          <InputNumber
            v-model="yearTo"
            :min="yearFrom + 1"
            :max="2100"
            :use-grouping="false"
            class="w-24"
            input-class="text-center"
          />
        </div>
        <Button
          :label="$t('button.update')"
          icon="pi pi-refresh"
          size="small"
          @click="handleRefresh"
        />
      </div>
    </div>

    <!-- ローディング -->
    <PageLoading v-if="loading" />

    <!-- データなし -->
    <DashboardEmptyState
      v-else-if="!timelineData || timelineData.labels.length === 0"
      icon="pi pi-chart-bar"
      :message="$t('repair_plan.timeline.no_data')"
    />

    <!-- チャート -->
    <SectionCard v-else :title="$t('repair_plan.timeline.title')">
      <StratifiedTimeline :data="timelineData" />
    </SectionCard>
  </div>
</template>
