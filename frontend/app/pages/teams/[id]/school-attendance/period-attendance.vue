<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import type { CandidateItem, PeriodAttendanceEntry, PeriodAttendanceSummary } from '~/types/school'

definePageMeta({
  middleware: 'auth',
})

interface PeriodEntry extends PeriodAttendanceEntry {
  displayName: string
  dailyStatus: CandidateItem['dailyStatus']
  previousPeriodStatus?: CandidateItem['previousPeriodStatus']
}

const route = useRoute()
const teamId = computed(() => Number(route.params.id))

const { candidates, loading, submitting, lastSummary, loadCandidates, submitPeriodAttendance } =
  usePeriodAttendance(teamId)

const today = new Date().toISOString().slice(0, 10)
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
    absenceReason: undefined,
    comment: undefined,
  }))
}

async function reload(): Promise<void> {
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

onMounted(() => {
  void reload()
})
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.attendance.period.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <div class="grid grid-cols-2 gap-4 mb-4">
        <div>
          <label class="text-sm text-surface-500 mb-1 block">
            {{ $t('school.attendance.dailyRollCall.date') }}
          </label>
          <InputText
            v-model="selectedDate"
            type="date"
            class="w-full"
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

        <div v-if="showSummary && lastSummary" class="mt-6 rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-50 dark:bg-surface-800 p-4">
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
              <div class="font-bold text-lg text-green-600">{{ lastSummary.attending }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.partial') }}</div>
              <div class="font-bold text-lg text-yellow-600">{{ lastSummary.partial }}</div>
            </div>
            <div>
              <div class="text-surface-500 text-xs mb-1">{{ $t('school.attendance.summary.absent') }}</div>
              <div class="font-bold text-lg text-red-600">{{ lastSummary.absent }}</div>
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
            :disabled="entries.length === 0"
            class="w-full"
            @click="onSubmit"
          />
        </div>
      </template>
    </main>
  </div>
</template>
