<script setup lang="ts">
import { attendanceStatusKey, formatAttendanceDate } from '~/utils/attendanceResults'
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  scheduleId: string
}>()
const { t, locale } = useI18n()
const { userTimezone } = useDatetime()
const { schedule, attendances, members, summary, loading, error, load } = useAttendanceResultDetail(
  () => props.scopeType,
  () => props.scopeId,
  () => props.scheduleId,
)
const names = computed(
  () =>
    new Map(
      [...members.value, ...(schedule.value?.targets ?? [])].map((member) => [
        member.userId,
        member.displayName,
      ]),
    ),
)
const base = computed(
  () =>
    `/${props.scopeType === 'team' ? 'teams' : 'organizations'}/${props.scopeId}/schedule-attendance`,
)
</script>

<template>
  <div class="min-w-0 space-y-4">
    <PageHeader :title="t('dashboard.attendance_results.detailTitle')" />
    <NuxtLink :to="base" class="inline-flex min-h-11 items-center gap-2 text-primary"
      ><i class="pi pi-arrow-left" />{{ t('dashboard.attendance_results.listTitle') }}</NuxtLink
    >
    <PageLoading v-if="loading" />
    <DashboardErrorState v-else-if="error" :error="error" @retry="load" />
    <SectionCard v-else-if="schedule">
      <h2 class="[overflow-wrap:anywhere] text-xl font-semibold">
        {{ schedule.content?.title || t('dashboard.attendance_results.untitled') }}
      </h2>
      <p class="my-3 text-sm text-surface-500">
        {{
          formatAttendanceDate(schedule.time?.startAt, userTimezone, locale) ||
          t('dashboard.attendance_results.noDate')
        }}
      </p>
      <AttendanceResultCounts v-if="summary" :summary="summary" />
      <p class="my-3 text-sm text-surface-500">{{ t('dashboard.attendance_results.countHint') }}</p>
      <ul v-if="attendances.length" class="divide-y divide-surface-200 dark:divide-surface-700">
        <li
          v-for="(response, index) in attendances"
          :key="response.id ?? index"
          class="min-w-0 py-3"
        >
          <div class="flex min-w-0 flex-wrap items-center justify-between gap-2">
            <span class="min-w-0 [overflow-wrap:anywhere] font-medium">{{
              names.get(response.userId ?? -1) ||
              t('dashboard.attendance_results.unknownMember', { number: index + 1 })
            }}</span>
            <span class="text-sm">{{
              t(`dashboard.attendance_results.status.${attendanceStatusKey(response)}`)
            }}</span>
          </div>
          <p
            v-if="response.comment?.trim()"
            class="mt-2 whitespace-pre-wrap [overflow-wrap:anywhere] text-sm text-surface-600 dark:text-surface-300"
            data-testid="attendance-reason"
          >
            {{ response.comment }}
          </p>
        </li>
      </ul>
      <DashboardEmptyState v-else :message="t('dashboard.attendance_results.noData')" />
    </SectionCard>
  </div>
</template>
