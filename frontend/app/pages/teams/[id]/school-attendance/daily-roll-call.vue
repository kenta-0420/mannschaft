<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import type { DailyRollCallEntry, DailyRollCallSummary } from '~/types/school'

definePageMeta({
  middleware: 'auth',
})

interface StudentEntry extends DailyRollCallEntry {
  displayName: string
}

const route = useRoute()
const teamId = computed(() => Number(route.params.id))

const { records, loading, submitting, lastSummary, loadRecords, submitRollCall } =
  useDailyRollCall(teamId)

const { t } = useI18n()

const today = new Date().toISOString().slice(0, 10)
const selectedDate = ref(today)

const entries = ref<StudentEntry[]>([])
const showSummary = ref(false)

function initEntries(): void {
  if (records.value.length > 0) {
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
  }
}

async function onDateChange(): Promise<void> {
  await loadRecords(selectedDate.value)
  initEntries()
  showSummary.value = false
}

async function onSubmit(): Promise<void> {
  const result: DailyRollCallSummary | null = await submitRollCall(
    selectedDate.value,
    entries.value,
  )
  if (result) {
    showSummary.value = true
    await loadRecords(selectedDate.value)
    initEntries()
  }
}

onMounted(async () => {
  await loadRecords(selectedDate.value)
  initEntries()
})
</script>

<template>
  <div class="flex flex-col min-h-screen">
    <header class="flex items-center gap-3 px-4 py-3 border-b border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900">
      <BackButton :to="`/teams/${teamId}`" :label="$t('common.back')" />
      <h1 class="text-lg font-bold m-0">
        {{ $t('school.attendance.dailyRollCall.title') }}
      </h1>
    </header>

    <main class="flex-1 p-4 max-w-2xl mx-auto w-full">
      <div class="mb-4">
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

      <PageLoading v-if="loading" />

      <template v-else>
        <DailyRollCallSheet
          :entries="entries"
          :date="selectedDate"
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
            :label="$t('school.attendance.dailyRollCall.submit')"
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
