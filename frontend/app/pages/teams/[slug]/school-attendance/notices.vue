<script setup lang="ts">
import dayjs from 'dayjs'
import { computed, ref, onMounted, watch } from 'vue'

definePageMeta({
  layout: 'team',
  middleware: 'auth',
  key: route => String(route.params.slug),
})

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const schoolTeam = useSchoolTeamContext(teamSlug)
const schoolTeamId = schoolTeam.teamId
const { ready, canView, canRecordDaily, forbidden, loadFailed: permissionsFailed, loadPermissions } = useAttendancePermissions(schoolTeamId)
const canQuery = computed(() => !!schoolTeamId.value && ready.value && canView.value)


const { noticeList, loading, processing, loadTeamNotices, acknowledge, apply } =
  useFamilyAttendanceNotice(schoolTeamId)
const { userTimezone } = useDatetime()

const today = dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
const selectedDate = ref(today)

async function onDateChange(): Promise<void> {
  if (canQuery.value) await loadTeamNotices(selectedDate.value)
}

async function onAcknowledge(noticeId: number): Promise<void> {
  if (canQuery.value && canRecordDaily.value) await acknowledge(noticeId)
}

async function onApply(noticeId: number): Promise<void> {
  if (canQuery.value && canRecordDaily.value) await apply(noticeId)
}

async function loadPage(): Promise<void> {
  const requestedSlug = teamSlug.value
  if (!await schoolTeam.load() || teamSlug.value !== requestedSlug) return
  await loadPermissions()
  if (teamSlug.value === requestedSlug && canQuery.value) await loadTeamNotices(selectedDate.value)
}
onMounted(loadPage)
watch(teamSlug, loadPage)
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamSlug}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0" data-testid="notices-page-title">
        {{ $t('school.familyNotice.title') }}
      </h1>
    </header>

    <DashboardErrorState v-if="schoolTeam.error.value || permissionsFailed" :title="$t('school.attendance.permissionError.title')" testid="school-attendance-permission-error" @retry="loadPage" />
    <SchoolAttendanceForbidden v-else-if="forbidden || (ready && !canView)" />
    <PageLoading v-else-if="schoolTeam.loading.value || !ready" />
    <main v-else class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <!-- 日付選択 -->
      <div class="mb-4">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.familyNotice.attendanceDate') }}
        </label>
        <InputText
          v-model="selectedDate"
          type="date"
          class="w-full"
          data-testid="notices-date"
          @change="onDateChange"
        />
      </div>

      <!-- 未確認件数バッジ -->
      <div
        v-if="noticeList && noticeList.unacknowledgedCount > 0"
        class="mb-4 rounded-lg bg-yellow-50 dark:bg-yellow-900/20 border border-yellow-200 dark:border-yellow-700 px-4 py-2 text-sm text-yellow-800 dark:text-yellow-200 font-medium"
      >
        {{ $t('school.familyNotice.unacknowledged', { count: noticeList.unacknowledgedCount }) }}
      </div>

      <PageLoading v-if="loading" />

      <template v-else>
        <TeacherInboxNoticeList
          :records="noticeList?.records ?? []"
          :processing="processing"
          @acknowledge="onAcknowledge"
          @apply="onApply"
        />
      </template>
    </main>
  </div>
</template>
