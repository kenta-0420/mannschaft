<script setup lang="ts">
import type { FamilyAttendanceNoticeResponse, FamilyNoticeStatus } from '~/types/school'

defineProps<{
  records: FamilyAttendanceNoticeResponse[]
  processing?: boolean
}>()

const emit = defineEmits<{
  acknowledge: [noticeId: number]
  apply: [noticeId: number]
}>()

const { t } = useI18n()

function noticeTypeLabel(type: string): string {
  return t(`school.familyNotice.noticeType.${type}`)
}

function reasonLabel(reason: string | undefined): string {
  if (!reason) return ''
  return t(`school.familyNotice.reason.${reason}`)
}

function statusLabel(status: FamilyNoticeStatus): string {
  return t(`school.familyNotice.status.${status}`)
}

function statusClass(status: FamilyNoticeStatus): string {
  switch (status) {
    case 'PENDING':
      return 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200'
    case 'ACKNOWLEDGED':
      return 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200'
    case 'APPLIED':
      return 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200'
    default:
      return 'bg-surface-100 text-surface-600 dark:bg-surface-800 dark:text-surface-300'
  }
}
</script>

<template>
  <div class="teacher-inbox-notice-list" data-testid="teacher-notice-list">
    <div v-if="records.length === 0" class="text-center text-surface-400 py-8" data-testid="teacher-notice-empty">
      {{ $t('school.familyNotice.noNotices') }}
    </div>

    <div v-else class="flex flex-col gap-3">
      <div
        v-for="record in records"
        :key="record.id"
        class="rounded-lg border border-surface-200 dark:border-surface-700 bg-surface-0 dark:bg-surface-900 p-4"
        :data-testid="'teacher-notice-item-' + record.id"
      >
        <!-- ヘッダー行 -->
        <div class="flex items-center justify-between mb-2">
          <div class="flex items-center gap-2">
            <span class="font-semibold text-surface-800 dark:text-surface-100">
              {{ $t('school.familyNotice.studentName') }} #{{ record.studentUserId }}
            </span>
            <span
              class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
              :class="statusClass(record.status)"
              :data-testid="'teacher-notice-status-' + record.id"
              :data-status="record.status"
            >
              {{ statusLabel(record.status) }}
            </span>
          </div>
          <span class="text-xs text-surface-400">
            {{ record.attendanceDate }}
          </span>
        </div>

        <!-- 連絡内容 -->
        <div class="flex flex-wrap gap-x-4 gap-y-1 text-sm text-surface-600 dark:text-surface-300 mb-3">
          <span>
            <span class="font-medium">{{ $t('school.attendance.label.status') }}:</span>
            {{ noticeTypeLabel(record.noticeType) }}
          </span>
          <span v-if="record.reason">
            <span class="font-medium">{{ $t('school.attendance.label.reason') }}:</span>
            {{ reasonLabel(record.reason) }}
          </span>
          <span v-if="record.expectedArrivalTime">
            <span class="font-medium">{{ $t('school.familyNotice.expectedArrivalTime') }}:</span>
            {{ record.expectedArrivalTime }}
          </span>
          <span v-if="record.expectedLeaveTime">
            <span class="font-medium">{{ $t('school.familyNotice.expectedLeaveTime') }}:</span>
            {{ record.expectedLeaveTime }}
          </span>
        </div>

        <div v-if="record.reasonDetail" class="text-sm text-surface-500 dark:text-surface-400 mb-3 italic">
          {{ record.reasonDetail }}
        </div>

        <!-- アクションボタン -->
        <div class="flex gap-2">
          <Button
            v-if="record.status === 'PENDING'"
            :label="$t('school.familyNotice.acknowledge')"
            size="small"
            severity="secondary"
            :loading="processing"
            :data-testid="'teacher-notice-acknowledge-' + record.id"
            @click="emit('acknowledge', record.id)"
          />
          <Button
            v-if="record.status !== 'APPLIED'"
            :label="$t('school.familyNotice.apply')"
            size="small"
            :loading="processing"
            :data-testid="'teacher-notice-apply-' + record.id"
            @click="emit('apply', record.id)"
          />
        </div>
      </div>
    </div>
  </div>
</template>
