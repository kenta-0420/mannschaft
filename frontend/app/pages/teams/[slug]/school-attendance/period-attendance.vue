<script setup lang="ts">
import dayjs from 'dayjs'
import { computed, ref, watch, onMounted } from 'vue'
import type { CandidateItem, PeriodAttendanceEntry, PeriodAttendanceSummary } from '~/types/school'

definePageMeta({
  layout: 'team',
  middleware: 'auth',
})

interface PeriodEntry extends PeriodAttendanceEntry {
  displayName: string
  dailyStatus: CandidateItem['dailyStatus']
  previousPeriodStatus?: CandidateItem['previousPeriodStatus']
}

const route = useRoute()
const teamSlug = computed(() => String(route.params.slug))

const {
  candidates,
  loading,
  submitting,
  lastSummary,
  forbidden,
  loadCandidates,
  submitPeriodAttendance,
} =
  usePeriodAttendance(teamSlug)
const { userTimezone } = useDatetime()
const {
  forbidden: permissionsForbidden,
  loadFailed: permissionsFailed,
  ready: permissionsReady,
  canView,
  canRecordPeriod,
  loadPermissions,
} = useAttendancePermissions(teamSlug)

// 判定は BE の権限判定 API のみ。教科担任は閲覧権限が無くても時限登録権限で入れる。
// 403 は握りつぶさず「権限がありません」を明示する（AC-18）
const denied = computed(
  () =>
    permissionsForbidden.value ||
    forbidden.value ||
    (permissionsReady.value && !canView.value && !canRecordPeriod.value),
)

// 一覧取得・操作は「照会完了かつ該当権限が true」のときだけ許可する（fail-closed）。
// 照会中・取得失敗・拒否の間は候補一覧 API を呼ばず、日付・時限の操作も封じる。
const canQuery = computed(
  () => permissionsReady.value && !denied.value && (canView.value || canRecordPeriod.value),
)

const today = dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
const selectedDate = ref(today)
const selectedPeriod = ref(1)

const PERIOD_OPTIONS = Array.from({ length: 8 }, (_, i) => ({
  value: i + 1,
  label: `${i + 1}`,
}))

const entries = ref<PeriodEntry[]>([])
const showSummary = ref(false)

function initEntries(): void {
  entries.value = candidates.value.map((c) => ({
    studentUserId: c.studentUserId,
    displayName: c.displayName,
    dailyStatus: c.dailyStatus,
    previousPeriodStatus: c.previousPeriodStatus,
    status: 'UNDECIDED' as const,
    comment: undefined,
  }))
}

async function reload(): Promise<void> {
  if (!canQuery.value) return
  await loadCandidates(selectedPeriod.value, selectedDate.value)
  initEntries()
  showSummary.value = false
}

async function onSubmit(): Promise<void> {
  const result: PeriodAttendanceSummary | null = await submitPeriodAttendance(
    selectedPeriod.value,
    selectedDate.value,
    entries.value,
  )
  if (result) {
    showSummary.value = true
  }
}

watch([selectedDate, selectedPeriod], () => {
  void reload()
})

async function loadPage(): Promise<void> {
  await loadPermissions()
  await reload()
}

onMounted(loadPage)
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamSlug}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.attendance.period.title') }}
      </h1>
    </header>

    <DashboardErrorState
      v-if="permissionsFailed"
      :title="$t('school.attendance.permissionError.title')"
      :message="$t('school.attendance.permissionError.message')"
      testid="school-attendance-permission-error"
      @retry="loadPage"
    />

    <SchoolAttendanceForbidden v-else-if="denied" />

    <main v-else class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <div class="grid grid-cols-2 gap-4 mb-4">
        <div>
          <label class="text-sm text-surface-500 mb-1 block">
            {{ $t('school.attendance.dailyRollCall.date') }}
          </label>
          <InputText
            v-model="selectedDate"
            type="date"
            class="w-full"
            :disabled="!canQuery"
            data-testid="period-attendance-date"
          />
        </div>
        <div>
          <label class="text-sm text-surface-500 mb-1 block">
            {{ $t('school.attendance.period.selectPeriod') }}
          </label>
          <Select
            v-model="selectedPeriod"
            :options="PERIOD_OPTIONS"
            option-label="label"
            option-value="value"
            class="w-full"
            :disabled="!canQuery"
            data-testid="period-attendance-period-select"
          />
        </div>
      </div>

      <PageLoading v-if="loading" />

      <template v-else>
        <PeriodAttendanceSheet
          :entries="entries"
          :period-number="selectedPeriod"
          :date="selectedDate"
          :candidates="candidates"
          @change="(e) => (entries = e)"
        />

        <div v-if="showSummary && lastSummary" data-testid="period-attendance-summary" class="mt-6 rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-800 p-4">
          <h2 class="text-base font-semibold mb-3">
            {{ $t('school.attendance.summary.title') }}
          </h2>
          <div class="grid grid-cols-5 gap-2 text-center text-sm">
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.total') }}</div>
              <div class="font-bold text-lg">{{ lastSummary.total }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.attending') }}</div>
              <div data-testid="period-attendance-summary-attending" class="font-bold text-lg text-green-600">{{ lastSummary.attending }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.partial') }}</div>
              <div class="font-bold text-lg text-yellow-600">{{ lastSummary.partial }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.absent') }}</div>
              <div data-testid="period-attendance-summary-absent" class="font-bold text-lg text-red-600">{{ lastSummary.absent }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.undecided') }}</div>
              <div class="font-bold text-lg text-surface-400">{{ lastSummary.undecided }}</div>
            </div>
          </div>
        </div>

        <div class="mt-6">
          <Button
            :label="$t('school.attendance.period.submit')"
            :loading="submitting"
            :disabled="!canRecordPeriod || entries.length === 0 || submitting"
            class="w-full"
            data-testid="period-attendance-submit"
            @click="onSubmit"
          />
        </div>
      </template>
    </main>
  </div>
</template>
