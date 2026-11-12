<script setup lang="ts">
import dayjs from 'dayjs'
import { computed, ref, onMounted, watch } from 'vue'
import type { DailyRollCallEntry, DailyRollCallSummary } from '~/types/school'

definePageMeta({
  layout: 'team',
  middleware: 'auth',
  key: route => String(route.params.slug),
})

interface StudentEntry extends DailyRollCallEntry {
  displayName: string
}

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))
const schoolTeam = useSchoolTeamContext(teamSlug)
const schoolTeamId = schoolTeam.teamId

const { records, loading, submitting, lastSummary, forbidden, loadFailed: recordsFailed, loaded: recordsLoaded, recordKey, loadRecords, submitRollCall } =
  useDailyRollCall(schoolTeamId)
const { userTimezone } = useDatetime()
const {
  forbidden: permissionsForbidden,
  loadFailed: permissionsFailed,
  ready: permissionsReady,
  canView,
  canRecordDaily,
  loadPermissions,
} = useAttendancePermissions(schoolTeamId)

// 判定は BE の権限判定 API のみ。403 は握りつぶさず「権限がありません」を明示する（AC-18）
const denied = computed(
  () => permissionsForbidden.value || forbidden.value || (permissionsReady.value && !canView.value),
)

// 一覧取得・操作は「照会完了かつ閲覧権限 true」のときだけ許可する（fail-closed）
const canQuery = computed(() => !!schoolTeamId.value && permissionsReady.value && !denied.value && canView.value)

const today = dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
const selectedDate = ref(today)

const entries = ref<StudentEntry[]>([])
const showSummary = ref(false)

// 空の成功・失敗によるリセットも必ず反映する。前日の行を次の日へ持ち越さない。
watch(records, () => {
  entries.value = records.value.map((r) => ({
    studentUserId: r.studentUserId,
    displayName: String(r.studentUserId),
    status: r.status,
    absenceReason: r.absenceReason,
    arrivalTime: r.arrivalTime,
    leaveTime: r.leaveTime,
    comment: r.comment,
    familyNoticeId: r.familyNoticeId,
  }))
}, { flush: 'sync' })
const canSubmit = computed(() => canQuery.value && canRecordDaily.value && recordsLoaded.value
  && !recordsFailed.value && !loading.value && recordKey.value === `${schoolTeamId.value}:${selectedDate.value}`)
watch([selectedDate, schoolTeamId], () => {
  entries.value = []
  showSummary.value = false
}, { flush: 'sync' })

async function onDateChange(): Promise<void> {
  if (!canQuery.value) return
  await loadRecords(selectedDate.value)
  showSummary.value = false
}

async function onSubmit(): Promise<void> {
  if (!canSubmit.value || entries.value.length === 0 || submitting.value) return
  const requestedKey = recordKey.value
  const result: DailyRollCallSummary | null = await submitRollCall(
    selectedDate.value,
    entries.value,
  )
  if (result && canSubmit.value && recordKey.value === requestedKey) {
    showSummary.value = true
    await loadRecords(selectedDate.value)
  }
}

async function loadPage(): Promise<void> {
  const requestedSlug = teamSlug.value
  if (!await schoolTeam.load() || teamSlug.value !== requestedSlug) return
  await loadPermissions()
  if (teamSlug.value !== requestedSlug) return
  if (!canQuery.value) return
  await loadRecords(selectedDate.value)
}

onMounted(loadPage)
watch(teamSlug, loadPage)
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamSlug}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.attendance.dailyRollCall.title') }}
      </h1>
    </header>

    <DashboardErrorState
      v-if="schoolTeam.error.value || permissionsFailed"
      :title="$t('school.attendance.permissionError.title')"
      :message="$t('school.attendance.permissionError.message')"
      testid="school-attendance-permission-error"
      @retry="loadPage"
    />

    <SchoolAttendanceForbidden v-else-if="denied" />

    <PageLoading v-else-if="schoolTeam.loading.value || !permissionsReady" />

    <main v-else class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <div class="mb-4">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.attendance.dailyRollCall.date') }}
        </label>
        <InputText
          v-model="selectedDate"
          type="date"
          class="w-full"
          :disabled="!canQuery"
          data-testid="daily-roll-call-date"
          @change="onDateChange"
        />
      </div>

      <PageLoading v-if="loading" />

      <DashboardErrorState
        v-else-if="recordsFailed"
        :title="$t('school.attendance.dailyRollCall.loadError')"
        testid="daily-roll-call-load-error"
        @retry="onDateChange"
      />

      <template v-else-if="recordsLoaded && recordKey === `${schoolTeamId}:${selectedDate}`">
        <DailyRollCallSheet
          :entries="entries"
          :date="selectedDate"
          @change="(e) => (entries = e)"
        />

        <div v-if="showSummary && lastSummary" class="mt-6 rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-800 p-4" data-testid="daily-roll-call-summary">
          <h2 class="text-base font-semibold mb-3">
            {{ $t('school.attendance.summary.title') }}
          </h2>
          <div class="grid grid-cols-5 gap-2 text-center text-sm">
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.total') }}</div>
              <div class="font-bold text-lg" data-testid="daily-roll-call-summary-total">{{ lastSummary.total }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.attending') }}</div>
              <div class="font-bold text-lg text-green-600" data-testid="daily-roll-call-summary-attending">{{ lastSummary.attending }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.partial') }}</div>
              <div class="font-bold text-lg text-yellow-600">{{ lastSummary.partial }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.absent') }}</div>
              <div class="font-bold text-lg text-red-600" data-testid="daily-roll-call-summary-absent">{{ lastSummary.absent }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.undecided') }}</div>
              <div class="font-bold text-lg text-surface-400">{{ lastSummary.undecided }}</div>
            </div>
          </div>
        </div>

        <div v-if="canSubmit" class="mt-6">
          <Button
            :label="$t('school.attendance.dailyRollCall.submit')"
            :loading="submitting"
            :disabled="entries.length === 0 || submitting"
            class="w-full"
            data-testid="daily-roll-call-submit"
            @click="onSubmit"
          />
        </div>
      </template>
    </main>
  </div>
</template>
