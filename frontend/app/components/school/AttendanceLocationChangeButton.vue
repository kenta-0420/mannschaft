<script setup lang="ts">
import { ref, computed } from 'vue'
import type {
  AttendanceLocation,
  AttendanceLocationChangeReason,
  LocationChangeResponse,
} from '~/types/school'

const props = defineProps<{
  teamId: number
  studentUserId: number
  attendanceDate: string
  currentLocation: AttendanceLocation
}>()

const emit = defineEmits<{
  (e: 'changed', response: LocationChangeResponse): void
}>()

const { t } = useI18n()
const teamIdRef = computed(() => props.teamId)
const { changeLocation, submitting } = useAttendanceLocation(teamIdRef)

// ダイアログ表示フラグ
const showDialog = ref(false)
const selectedToLocation = ref<AttendanceLocation | null>(null)
const selectedReason = ref<AttendanceLocationChangeReason | null>(null)
const noteValue = ref('')

// ロケーション一覧（すべての移動先候補）
const allLocations: AttendanceLocation[] = [
  'CLASSROOM',
  'SICK_BAY',
  'SEPARATE_ROOM',
  'LIBRARY',
  'ONLINE',
  'HOME_LEARNING',
  'OUT_OF_SCHOOL',
  'NOT_APPLICABLE',
]

// 移動先候補（現在のロケーションを除く）
const targetLocations = computed<AttendanceLocation[]>(() =>
  allLocations.filter((loc) => loc !== props.currentLocation),
)

// 理由一覧
const reasons: AttendanceLocationChangeReason[] = [
  'FELT_SICK',
  'INJURY',
  'MENTAL_HEALTH',
  'SCHEDULED',
  'RECOVERED',
  'RETURNED_TO_CLASS',
  'OTHER',
]

// ボタン種別に応じたスタイルとラベル
const buttonConfig = computed(() => {
  if (props.currentLocation === 'CLASSROOM') {
    return {
      label: t('school.location.SICK_BAY'),
      className:
        'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium bg-yellow-100 text-yellow-800 border border-yellow-300 hover:bg-yellow-200 dark:bg-yellow-900 dark:text-yellow-200 dark:border-yellow-700 dark:hover:bg-yellow-800 transition-colors',
      presetTo: 'SICK_BAY' as AttendanceLocation,
    }
  } else if (props.currentLocation === 'SICK_BAY') {
    return {
      label: t('school.location.CLASSROOM'),
      className:
        'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium bg-green-100 text-green-800 border border-green-300 hover:bg-green-200 dark:bg-green-900 dark:text-green-200 dark:border-green-700 dark:hover:bg-green-800 transition-colors',
      presetTo: 'CLASSROOM' as AttendanceLocation,
    }
  } else {
    return {
      label: t('school.location.changeButton'),
      className:
        'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium bg-surface-100 text-surface-700 border border-surface-300 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-200 dark:border-surface-600 dark:hover:bg-surface-700 transition-colors',
      presetTo: null as AttendanceLocation | null,
    }
  }
})

function openDialog() {
  selectedToLocation.value = buttonConfig.value.presetTo
  selectedReason.value = null
  noteValue.value = ''
  showDialog.value = true
}

function closeDialog() {
  showDialog.value = false
}

async function submit() {
  if (!selectedToLocation.value || !selectedReason.value) return

  const response = await changeLocation(
    {
      fromLocation: props.currentLocation,
      toLocation: selectedToLocation.value,
      reason: selectedReason.value,
      note: noteValue.value || undefined,
    },
    props.attendanceDate,
  )

  if (response) {
    emit('changed', response)
    closeDialog()
  }
}
</script>

<template>
  <div>
    <!-- メインボタン -->
    <button
      data-testid="location-change-button"
      type="button"
      :disabled="submitting"
      :class="buttonConfig.className"
      @click="openDialog"
    >
      {{ buttonConfig.label }}
    </button>

    <!-- ダイアログ -->
    <Teleport to="body">
      <div
        v-if="showDialog"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
        @click.self="closeDialog"
      >
        <div
          class="bg-surface-0 dark:bg-surface-900 rounded-xl shadow-xl w-full max-w-sm mx-4 p-5"
          role="dialog"
          aria-modal="true"
        >
          <h3 class="text-base font-semibold text-surface-800 dark:text-surface-100 mb-4">
            {{ $t('school.location.changeTitle') }}
          </h3>

          <!-- 移動元 -->
          <div class="mb-3">
            <label class="block text-xs text-surface-500 mb-1">
              {{ $t('school.location.from') }}
            </label>
            <div class="text-sm font-medium text-surface-700 dark:text-surface-200">
              {{ $t(`school.location.${currentLocation}`) }}
            </div>
          </div>

          <!-- 移動先 -->
          <div class="mb-3">
            <label class="block text-xs text-surface-500 mb-1">
              {{ $t('school.location.to') }}
            </label>
            <select
              v-model="selectedToLocation"
              class="w-full rounded-md border border-surface-300 dark:border-surface-600 bg-surface-0 dark:bg-surface-800 text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option :value="null" disabled>
                {{ $t('school.location.to') }}
              </option>
              <option
                v-for="loc in targetLocations"
                :key="loc"
                :value="loc"
              >
                {{ $t(`school.location.${loc}`) }}
              </option>
            </select>
          </div>

          <!-- 理由 -->
          <div class="mb-3">
            <label class="block text-xs text-surface-500 mb-1">
              {{ $t('school.location.reason') }}
            </label>
            <select
              v-model="selectedReason"
              class="w-full rounded-md border border-surface-300 dark:border-surface-600 bg-surface-0 dark:bg-surface-800 text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option :value="null" disabled>
                {{ $t('school.location.reason') }}
              </option>
              <option
                v-for="r in reasons"
                :key="r"
                :value="r"
              >
                {{ $t(`school.location.reason_${r}`) }}
              </option>
            </select>
          </div>

          <!-- メモ -->
          <div class="mb-5">
            <label class="block text-xs text-surface-500 mb-1">
              {{ $t('school.location.note') }}
            </label>
            <input
              v-model="noteValue"
              type="text"
              class="w-full rounded-md border border-surface-300 dark:border-surface-600 bg-surface-0 dark:bg-surface-800 text-sm px-3 py-2 focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
          </div>

          <!-- ボタン行 -->
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="px-4 py-2 rounded-md text-sm text-surface-600 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800 transition-colors"
              @click="closeDialog"
            >
              {{ $t('common.cancel') }}
            </button>
            <button
              type="button"
              :disabled="!selectedToLocation || !selectedReason || submitting"
              class="px-4 py-2 rounded-md text-sm font-medium bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              @click="submit"
            >
              {{ $t('school.location.submit') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
