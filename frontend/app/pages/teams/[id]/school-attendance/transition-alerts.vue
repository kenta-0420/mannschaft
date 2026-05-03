<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'

definePageMeta({
  middleware: 'auth',
})

const route = useRoute()
const teamId = computed(() => Number(route.params.id))

const { alerts, loading, unresolvedCount, totalCount, loadAlerts } =
  useTransitionAlert(teamId)

const today = new Date().toISOString().slice(0, 10)
const selectedDate = ref(today)
const unresolvedOnly = ref(false)

async function onDateChange(): Promise<void> {
  await loadAlerts(selectedDate.value, unresolvedOnly.value)
}

async function onFilterChange(): Promise<void> {
  await loadAlerts(selectedDate.value, unresolvedOnly.value)
}

async function onResolved(_alertId: number): Promise<void> {
  // BannerがResolveModal経由でAPI呼び出し済み。ページ側は最新状態を再読み込みする。
  await loadAlerts(selectedDate.value, unresolvedOnly.value)
}

onMounted(async () => {
  await loadAlerts(selectedDate.value, unresolvedOnly.value)
})
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.transitionAlert.title') }}
      </h1>
      <!-- 未解決件数バッジ -->
      <span
        v-if="unresolvedCount > 0"
        data-testid="transition-alert-unresolved-count"
        class="ml-auto inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-100"
      >
        {{ $t('school.transitionAlert.unresolvedCount', { count: unresolvedCount }) }}
      </span>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <!-- フィルター -->
      <div class="flex flex-col sm:flex-row gap-3 mb-6">
        <div class="flex-1">
          <label class="text-sm text-surface-500 mb-1 block">
            {{ $t('school.attendance.dailyRollCall.date') }}
          </label>
          <InputText
            v-model="selectedDate"
            type="date"
            class="w-full"
            @change="onDateChange"
          />
        </div>
        <div class="flex items-end">
          <div class="flex items-center gap-2 pb-2">
            <Checkbox
              v-model="unresolvedOnly"
              input-id="unresolvedOnly"
              :binary="true"
              data-testid="transition-alert-unresolved-filter"
              @change="onFilterChange"
            />
            <label for="unresolvedOnly" class="text-sm text-surface-700 dark:text-surface-200 cursor-pointer">
              {{ $t('school.transitionAlert.unresolvedOnly') }}
            </label>
          </div>
        </div>
      </div>

      <PageLoading v-if="loading" />

      <template v-else>
        <!-- 件数サマリ -->
        <div
          v-if="totalCount > 0"
          class="flex items-center gap-4 mb-4 p-3 rounded-lg bg-surface-50 dark:bg-surface-800 border border-surface-200 dark:border-surface-700 text-sm"
        >
          <span class="text-surface-600 dark:text-surface-300">
            {{ $t('school.attendance.summary.total') }}: <strong>{{ totalCount }}</strong>
          </span>
          <span class="text-red-600 dark:text-red-400">
            {{ $t('school.transitionAlert.unresolvedCount', { count: unresolvedCount }) }}
          </span>
        </div>

        <!-- アラートバナー一覧 -->
        <div v-if="alerts.length === 0" data-testid="transition-alert-empty" class="text-center text-surface-400 py-12">
          {{ $t('school.transitionAlert.noAlerts') }}
        </div>
        <div v-else data-testid="transition-alert-list">
          <TransitionAlertBanner
            :alerts="alerts"
            :team-id="teamId"
            @resolved="onResolved"
          />
        </div>
      </template>
    </main>
  </div>
</template>
