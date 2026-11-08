<script setup lang="ts">
import dayjs from 'dayjs'
import { formatAttendanceDate } from '~/utils/attendanceResults'
const props = defineProps<{ scopeType: 'team' | 'organization'; scopeId: string }>()
const { t, locale } = useI18n()
const { userTimezone } = useDatetime()
const { from, to, schedules, loading, error, load } = useAttendanceResults(
  () => props.scopeType,
  () => props.scopeId,
  0,
)
const { page, rows, totalRecords, onPage, reset } = usePagination(20)
watch(schedules, (value) => {
  totalRecords.value = value.length
  reset()
})
const visibleSchedules = computed(() =>
  schedules.value.slice(page.value * rows.value, (page.value + 1) * rows.value),
)
const base = computed(
  () =>
    `/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/schedule-attendance`,
)
function moveRange(direction: number) {
  from.value = dayjs(from.value)
    .tz(userTimezone.value)
    .add(direction * 60, 'day')
    .tz(userTimezone.value, true)
    .format()
  to.value = dayjs(to.value)
    .tz(userTimezone.value)
    .add(direction * 60, 'day')
    .tz(userTimezone.value, true)
    .format()
  load()
}
</script>

<template>
  <div class="min-w-0 space-y-4">
    <PageHeader :title="t('dashboard.attendance_results.listTitle')" />
    <SectionCard>
      <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
        <Button
          :label="t('dashboard.attendance_results.previousRange')"
          icon="pi pi-chevron-left"
          outlined
          class="min-h-11"
          :disabled="loading"
          @click="moveRange(-1)"
        />
        <p class="text-sm">
          {{ formatAttendanceDate(from, userTimezone, locale) }} –
          {{ formatAttendanceDate(to, userTimezone, locale) }}
        </p>
        <Button
          :label="t('dashboard.attendance_results.nextRange')"
          icon="pi pi-chevron-right"
          icon-pos="right"
          outlined
          class="min-h-11"
          :disabled="loading"
          @click="moveRange(1)"
        />
      </div>
      <PageLoading v-if="loading" />
      <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
      <template v-else-if="schedules.length">
        <ul class="divide-y divide-surface-200 dark:divide-surface-700">
          <li v-for="schedule in visibleSchedules" :key="schedule.id" class="min-w-0">
            <NuxtLink
              :to="`${base}/${schedule.id}`"
              class="flex min-h-11 min-w-0 flex-col gap-1 px-2 py-3 hover:text-primary"
            >
              <span class="[overflow-wrap:anywhere] font-medium">{{
                schedule.content?.title || t('dashboard.attendance_results.untitled')
              }}</span>
              <span class="text-sm text-surface-500">{{
                formatAttendanceDate(schedule.time?.startAt, userTimezone, locale) ||
                t('dashboard.attendance_results.noDate')
              }}</span>
            </NuxtLink>
          </li>
        </ul>
        <Paginator :first="page * rows" :rows="rows" :total-records="totalRecords" @page="onPage" />
      </template>
      <DashboardEmptyState v-else :message="t('dashboard.attendance_results.empty')" />
    </SectionCard>
  </div>
</template>
