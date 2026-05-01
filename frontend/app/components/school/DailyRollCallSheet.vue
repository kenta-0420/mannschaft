<script setup lang="ts">
import type { DailyRollCallEntry } from '~/types/school'

interface StudentEntry extends DailyRollCallEntry {
  displayName: string
}

const props = defineProps<{
  entries: StudentEntry[]
  date: string
}>()

const emit = defineEmits<{
  change: [entries: StudentEntry[]]
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

function updateEntry(index: number, patch: Partial<StudentEntry>): void {
  const updated = props.entries.map((e, i) => (i === index ? { ...e, ...patch } : e))
  emit('change', updated)
}

function needsReason(entry: StudentEntry): boolean {
  return entry.status === 'ABSENT' || entry.status === 'PARTIAL'
}

function needsTimes(entry: StudentEntry): boolean {
  return entry.status === 'PARTIAL'
}
</script>

<template>
  <div class="daily-roll-call-sheet">
    <div v-if="entries.length === 0" class="text-center text-surface-400 py-8">
      {{ $t('school.attendance.dailyRollCall.noStudents') }}
    </div>

    <div v-else class="flex flex-col gap-3">
      <div
        v-for="(entry, index) in entries"
        :key="entry.studentUserId"
        class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4"
      >
        <div class="flex items-center justify-between mb-3">
          <span class="font-semibold text-surface-800 dark:text-surface-100">
            {{ entry.displayName }}
          </span>
          <div class="flex gap-1">
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
              @click="updateEntry(index, { status: opt.value as DailyRollCallEntry['status'] })"
            >
              {{ opt.label() }}
            </button>
          </div>
        </div>

        <div v-if="needsReason(entry)" class="grid grid-cols-2 gap-2 mt-2">
          <div>
            <label class="text-xs text-surface-500 mb-1 block">
              {{ $t('school.attendance.label.reason') }}
            </label>
            <Select
              :model-value="entry.absenceReason"
              :options="REASON_OPTIONS"
              option-label="label"
              option-value="value"
              :placeholder="$t('school.attendance.label.reason')"
              class="w-full text-sm"
              @update:model-value="(v) => updateEntry(index, { absenceReason: v })"
            />
          </div>

          <div v-if="needsTimes(entry)" class="flex gap-2">
            <div class="flex-1">
              <label class="text-xs text-surface-500 mb-1 block">
                {{ $t('school.attendance.dailyRollCall.arrivalTime') }}
              </label>
              <InputText
                :model-value="entry.arrivalTime ?? ''"
                type="time"
                class="w-full text-sm"
                @update:model-value="(v) => updateEntry(index, { arrivalTime: v || undefined })"
              />
            </div>
            <div class="flex-1">
              <label class="text-xs text-surface-500 mb-1 block">
                {{ $t('school.attendance.dailyRollCall.leaveTime') }}
              </label>
              <InputText
                :model-value="entry.leaveTime ?? ''"
                type="time"
                class="w-full text-sm"
                @update:model-value="(v) => updateEntry(index, { leaveTime: v || undefined })"
              />
            </div>
          </div>
        </div>

        <div class="mt-2">
          <InputText
            :model-value="entry.comment ?? ''"
            :placeholder="$t('school.attendance.dailyRollCall.comment')"
            class="w-full text-sm"
            @update:model-value="(v) => updateEntry(index, { comment: v || undefined })"
          />
        </div>
      </div>
    </div>
  </div>
</template>
