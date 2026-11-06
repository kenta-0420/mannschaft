<script setup lang="ts">
import { formatAttendanceDate } from '~/utils/attendanceResults'
const props = defineProps<{ scopeType: 'team' | 'organization'; scopeId: string }>()
const { t, locale } = useI18n()
const { userTimezone } = useDatetime()
const { results, loading, error, load } = useAttendanceResults(
  () => props.scopeType,
  () => props.scopeId,
  10,
)
const base = computed(
  () =>
    `/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/schedule-attendance`,
)
</script>

<template>
  <div @click.stop>
    <div class="mb-3 flex items-center justify-between gap-2">
      <span class="text-xs text-surface-500">{{
        t('dashboard.attendance_results.recentRange')
      }}</span>
      <Button
        icon="pi pi-refresh"
        text
        class="min-h-11 min-w-11"
        :aria-label="t('dashboard.attendance_results.refresh')"
        :loading="loading"
        @click="load"
      />
    </div>
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <div v-else-if="results.length" class="space-y-3">
      <div v-for="row in results" :key="row.schedule.id" class="min-w-0">
        <NuxtLink
          :to="`${base}/${row.schedule.id}`"
          class="flex min-h-11 min-w-0 flex-col gap-1 rounded-lg p-2 hover:bg-surface-100 dark:hover:bg-surface-700"
          @click.stop
        >
          <span class="text-xs text-primary">{{
            formatAttendanceDate(row.schedule.time?.startAt, userTimezone, locale) ||
            t('dashboard.attendance_results.noDate')
          }}</span>
          <span class="[overflow-wrap:anywhere] font-medium">{{
            row.schedule.content?.title || t('dashboard.attendance_results.untitled')
          }}</span>
          <AttendanceResultCounts v-if="row.summary" :summary="row.summary" />
        </NuxtLink>
        <DashboardErrorState v-if="row.error" :error="row.error" @retry="load" />
      </div>
      <p class="text-xs text-surface-500">{{ t('dashboard.attendance_results.countHint') }}</p>
    </div>
    <DashboardEmptyState v-else :message="t('dashboard.attendance_results.empty')" />
  </div>
</template>
