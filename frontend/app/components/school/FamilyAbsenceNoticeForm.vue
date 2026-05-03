<script setup lang="ts">
import { ref, computed } from 'vue'
import type { FamilyAttendanceNoticeRequest, FamilyNoticeType, FamilyNoticeReason } from '~/types/school'

const props = defineProps<{
  teamId: number
  studentUserId: number
  submitting?: boolean
}>()

const emit = defineEmits<{
  submit: [payload: FamilyAttendanceNoticeRequest]
}>()

const { t } = useI18n()

const today = new Date().toISOString().slice(0, 10)

const attendanceDate = ref(today)
const noticeType = ref<FamilyNoticeType>('ABSENCE')
const reason = ref<FamilyNoticeReason | undefined>(undefined)
const reasonDetail = ref('')
const expectedArrivalTime = ref('')
const expectedLeaveTime = ref('')

const NOTICE_TYPE_OPTIONS: { value: FamilyNoticeType; label: () => string }[] = [
  { value: 'ABSENCE', label: () => t('school.familyNotice.noticeType.ABSENCE') },
  { value: 'LATE', label: () => t('school.familyNotice.noticeType.LATE') },
  { value: 'EARLY_LEAVE', label: () => t('school.familyNotice.noticeType.EARLY_LEAVE') },
  { value: 'OTHER', label: () => t('school.familyNotice.noticeType.OTHER') },
]

const REASON_OPTIONS: { value: FamilyNoticeReason; label: () => string }[] = [
  { value: 'SICK', label: () => t('school.familyNotice.reason.SICK') },
  { value: 'INJURY', label: () => t('school.familyNotice.reason.INJURY') },
  { value: 'FAMILY_REASON', label: () => t('school.familyNotice.reason.FAMILY_REASON') },
  { value: 'BEREAVEMENT', label: () => t('school.familyNotice.reason.BEREAVEMENT') },
  { value: 'INFECTIOUS_DISEASE', label: () => t('school.familyNotice.reason.INFECTIOUS_DISEASE') },
  { value: 'MENTAL_HEALTH', label: () => t('school.familyNotice.reason.MENTAL_HEALTH') },
  { value: 'OFFICIAL_BUSINESS', label: () => t('school.familyNotice.reason.OFFICIAL_BUSINESS') },
  { value: 'OTHER', label: () => t('school.familyNotice.reason.OTHER') },
]

const showArrivalTime = computed(() => noticeType.value === 'LATE')
const showLeaveTime = computed(() => noticeType.value === 'EARLY_LEAVE')

function onNoticeTypeChange(type: FamilyNoticeType): void {
  noticeType.value = type
  reason.value = undefined
  expectedArrivalTime.value = ''
  expectedLeaveTime.value = ''
}

function onSubmit(): void {
  const payload: FamilyAttendanceNoticeRequest = {
    teamId: props.teamId,
    studentUserId: props.studentUserId,
    attendanceDate: attendanceDate.value,
    noticeType: noticeType.value,
    reason: reason.value,
    reasonDetail: reasonDetail.value || undefined,
    expectedArrivalTime: expectedArrivalTime.value || undefined,
    expectedLeaveTime: expectedLeaveTime.value || undefined,
  }
  emit('submit', payload)
}
</script>

<template>
  <div class="family-absence-notice-form" data-testid="family-notice-form">
    <div class="flex flex-col gap-4">
      <!-- 対象日 -->
      <div>
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.familyNotice.attendanceDate') }}
        </label>
        <InputText
          v-model="attendanceDate"
          type="date"
          class="w-full"
        />
      </div>

      <!-- 連絡種別 -->
      <div>
        <label class="text-sm text-surface-500 mb-2 block">
          {{ $t('school.attendance.label.status') }}
        </label>
        <div class="flex gap-2 flex-wrap" data-testid="family-notice-type">
          <button
            v-for="opt in NOTICE_TYPE_OPTIONS"
            :key="opt.value"
            type="button"
            class="px-3 py-2 rounded-lg text-sm font-medium transition-colors"
            :class="
              noticeType === opt.value
                ? 'bg-primary-500 text-white'
                : 'bg-surface-100 dark:bg-surface-800 text-surface-600 dark:text-surface-300 hover:bg-surface-200 dark:hover:bg-surface-700'
            "
            @click="onNoticeTypeChange(opt.value)"
          >
            {{ opt.label() }}
          </button>
        </div>
      </div>

      <!-- 理由（欠席・早退時） -->
      <div v-if="noticeType === 'ABSENCE' || noticeType === 'EARLY_LEAVE' || noticeType === 'LATE'">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.attendance.label.reason') }}
        </label>
        <Select
          v-model="reason"
          :options="REASON_OPTIONS"
          option-label="label"
          option-value="value"
          :placeholder="$t('school.attendance.label.reason')"
          class="w-full"
          data-testid="family-notice-reason"
        />
      </div>

      <!-- 登校予定時刻（遅刻時） -->
      <div v-if="showArrivalTime">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.familyNotice.expectedArrivalTime') }}
        </label>
        <InputText
          v-model="expectedArrivalTime"
          type="time"
          class="w-full"
        />
      </div>

      <!-- 早退予定時刻（早退時） -->
      <div v-if="showLeaveTime">
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.familyNotice.expectedLeaveTime') }}
        </label>
        <InputText
          v-model="expectedLeaveTime"
          type="time"
          class="w-full"
        />
      </div>

      <!-- 詳細（任意） -->
      <div>
        <label class="text-sm text-surface-500 mb-1 block">
          {{ $t('school.familyNotice.reasonDetail') }}
        </label>
        <Textarea
          v-model="reasonDetail"
          :placeholder="$t('school.familyNotice.reasonDetail')"
          rows="3"
          class="w-full"
        />
      </div>

      <!-- 送信ボタン -->
      <div class="mt-2">
        <Button
          :label="$t('school.familyNotice.submit')"
          :loading="submitting"
          :disabled="!attendanceDate"
          class="w-full"
          data-testid="family-notice-submit"
          @click="onSubmit"
        />
      </div>
    </div>
  </div>
</template>
