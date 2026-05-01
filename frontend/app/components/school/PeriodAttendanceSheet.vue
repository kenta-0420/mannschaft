<script setup lang="ts">
import type { CandidateItem, PeriodAttendanceEntry } from '~/types/school'

interface PeriodEntry extends PeriodAttendanceEntry {
  displayName: string
  dailyStatus: CandidateItem['dailyStatus']
  previousPeriodStatus?: CandidateItem['previousPeriodStatus']
}

const props = defineProps<{
  entries: PeriodEntry[]
  periodNumber: number
  date: string
  candidates: CandidateItem[]
}>()

const emit = defineEmits<{
  change: [entries: PeriodEntry[]]
}>()

const { t } = useI18n()

const STATUS_OPTIONS = [
  { value: 'ATTENDING', label: () => t('school.attendance.status.ATTENDING') },
  { value: 'PARTIAL', label: () => t('school.attendance.status.PARTIAL') },
  { value: 'ABSENT', label: () => t('school.attendance.status.ABSENT') },
  { value: 'UNDECIDED', label: () => t('school.attendance.status.UNDECIDED') },
]

const REASON_OPTIONS = [
  { value: 'ILLNESS', label: () => t('school.attendance.absenceReason.ILLNESS') },
  { value: 'INJURY', label: () => t('school.attendance.absenceReason.INJURY') },
  { value: 'FAMILY', label: () => t('school.attendance.absenceReason.FAMILY') },
  { value: 'OTHER', label: () => t('school.attendance.absenceReason.OTHER') },
]

function updateEntry(index: number, patch: Partial<PeriodEntry>): void {
  const updated = props.entries.map((e, i) => (i === index ? { ...e, ...patch } : e))
  emit('change', updated)
}

function needsReason(entry: PeriodEntry): boolean {
  return entry.status === 'ABSENT' || entry.status === 'PARTIAL'
}

function statusLabel(status: string | undefined): string {
  if (!status) return '—'
  return t(`school.attendance.status.${status}`)
}
</script>

<template>
  <div class="period-attendance-sheet">
    <div v-if="entries.length === 0" class="text-center text-surface-400 py-8">
      {{ $t('school.attendance.dailyRollCall.noStudents') }}
    </div>

    <div v-else class="flex flex-col gap-3">
      <div
        v-for="(entry, index) in entries"
        :key="entry.studentUserId"
        class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4"
      >
        <div class="flex items-start justify-between mb-2">
          <div>
            <span class="font-semibold text-surface-800 dark:text-surface-100">
              {{ entry.displayName }}
            </span>
            <div class="flex gap-3 mt-1 text-xs text-surface-500">
              <span>{{ $t('school.attendance.label.dailyStatus') }}: {{ statusLabel(entry.dailyStatus) }}</span>
              <span v-if="entry.previousPeriodStatus">
                {{ $t('school.attendance.period.previousStatus') }}: {{ statusLabel(entry.previousPeriodStatus) }}
              </span>
            </div>
          </div>
          <div class="flex gap-1 flex-wrap justify-end">
            <button
              v-for="opt in STATUS_OPTIONS"
              :key="opt.value"
              type="button"
              class="px-2 py-1 rounded text-xs font-medium transition-colors"
              :class="
                entry.status === opt.value
                  ? 'bg-primary-500 text-white'
                  : 'bg-surface-100 dark:bg-surface-800 text-surface-600 dark:text-surface-300 hover:bg-surface-200 dark:hover:bg-surface-700'
              "
              @click="updateEntry(index, { status: opt.value as PeriodAttendanceEntry['status'] })"
            >
              {{ opt.label() }}
            </button>
          </div>
        </div>

        <div v-if="needsReason(entry)" class="mt-2">
          <Select
            :model-value="entry.absenceReason"
            :options="REASON_OPTIONS"
            option-label="label"
            option-value="value"
            :placeholder="$t('school.attendance.label.reason')"
            class="w-full text-sm mb-2"
            @update:model-value="(v) => updateEntry(index, { absenceReason: v })"
          />
        </div>

        <div class="mt-2">
          <InputText
            :model-value="entry.comment ?? ''"
            :placeholder="$t('school.attendance.label.comment')"
            class="w-full text-sm"
            @update:model-value="(v) => updateEntry(index, { comment: v || undefined })"
          />
        </div>
      </div>
    </div>
  </div>
</template>
