<script setup lang="ts">
import { useMyCalendarData, FILTER_OVERFLOW } from '~/composables/useMyCalendarData'

const {
  currentYear, currentMonth, loading, loadEvents,
  onPrevMonth, onNextMonth,
  allScopeOptions, selectedScopes, filteredEvents,
  toggleScope, multiSelectScopes, initStorage,
} = useMyCalendarData({ storageKey: 'mannschaft:widget:calendar:scopeFilter' })

function onDateClick(date: string) {
  navigateTo(`/calendar?date=${date}`)
}

function onEventClick(eventId: number, _isPersonal: boolean) {
  if (eventId < 0) {
    // TODO: todo 詳細へ
    navigateTo(`/todos/${Math.abs(eventId) - 1}`)
  }
  else {
    navigateTo('/calendar')
  }
}

onMounted(() => {
  initStorage()
  loadEvents()
})
</script>

<template>
  <div>
    <div class="mb-2 flex items-center justify-between">
      <h3 class="font-semibold text-sm text-surface-700 dark:text-surface-300">
        <i class="pi pi-calendar mr-1.5 text-primary" />マイカレンダー
      </h3>
      <Button label="全画面で開く" icon="pi pi-external-link" text size="small" @click="navigateTo('/calendar')" />
    </div>

    <div v-if="loading" class="space-y-2">
      <Skeleton height="1.5rem" />
      <Skeleton height="12rem" />
    </div>
    <template v-else>
      <CalendarGrid
        :year="currentYear"
        :month="currentMonth"
        :events="filteredEvents"
        @date-click="onDateClick"
        @event-click="onEventClick"
        @prev-month="onPrevMonth"
        @next-month="onNextMonth"
      />

      <!-- スコープフィルター -->
      <div v-if="allScopeOptions.length > 1" class="mt-3 flex flex-wrap items-center gap-2 text-xs text-surface-500">
        <span class="text-surface-400">表示:</span>
        <template v-if="allScopeOptions.length <= FILTER_OVERFLOW">
          <button
            v-for="sc in allScopeOptions"
            :key="sc.value"
            type="button"
            class="px-2 py-0.5 rounded-full border transition-colors text-xs"
            :class="selectedScopes.includes(sc.value)
              ? 'border-primary text-primary bg-primary/10'
              : 'border-surface-300 text-surface-400'"
            @click="toggleScope(sc.value)"
          >
            {{ sc.label }}
          </button>
        </template>
        <MultiSelect
          v-else
          v-model="multiSelectScopes"
          :options="allScopeOptions"
          option-label="label"
          option-value="value"
          placeholder="表示するスコープを選択"
          :max-selected-labels="2"
          selected-items-label="{0}件選択中"
          class="text-xs"
          style="min-width: 160px"
        />
      </div>

      <!-- 凡例 -->
      <div class="mt-2 flex flex-wrap gap-3 text-xs text-surface-400">
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-green-500" />個人</span>
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-orange-500" />TODO</span>
        <span><span class="mr-1 inline-block h-2 w-2 rounded-full bg-indigo-500" />チーム/組織</span>
      </div>
    </template>
  </div>
</template>
