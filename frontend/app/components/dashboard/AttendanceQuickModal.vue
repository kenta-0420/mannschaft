<script setup lang="ts">
import type { AttendanceActionItem, ScopeTabType } from '~/types/dashboard-scope'

/**
 * 要対応ウィジェット — 出席確認クイックモーダル。
 *
 * 出席確認アイテムをクリックしたときにページ遷移せず、このモーダルで出欠を回答できる。
 * - イベントタイトル・開始日時を表示
 * - 出席 / 欠席 / 未定 の 3 ボタンで即時回答
 * - 回答成功 → モーダル閉じ・件数1減
 *
 * 設計書: docs/features/F22.1_swipe_scope_dashboard/04_widgets.md §5
 */
const props = defineProps<{
  visible: boolean
  item: AttendanceActionItem
  scopeType: ScopeTabType
  scopeId: string
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submitted: []
}>()

const { t } = useI18n()
const { showError, showSuccess } = useNotification()
const { respondAttendance } = useScheduleApi()

const submitting = ref(false)

function close() {
  emit('update:visible', false)
}

function formatDateTime(dateStr: string): string {
  const d = new Date(dateStr)
  return d.toLocaleString()
}

async function respond(status: 'ATTENDING' | 'ABSENT' | 'UNDECIDED') {
  submitting.value = true
  try {
    const scopeType = props.scopeType === 'TEAM' ? 'team' : 'organization'
    await respondAttendance(scopeType, props.scopeId, props.item.scheduleId, { status })
    showSuccess(t('swipeWidgets.actionRequired.attendanceModal.success'))
    emit('submitted')
    close()
  } catch {
    showError(t('swipeWidgets.actionRequired.attendanceModal.error'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="$t('swipeWidgets.actionRequired.attendanceModal.title')"
    modal
    class="w-full max-w-sm"
    @update:visible="emit('update:visible', $event)"
  >
    <div class="space-y-4">
      <div>
        <p class="text-sm font-semibold text-surface-900 dark:text-surface-0">
          {{ item.eventTitle }}
        </p>
        <p class="mt-1 text-xs text-surface-500">
          <i class="pi pi-clock mr-1" />
          {{ formatDateTime(item.startsAt) }}
        </p>
      </div>

      <div class="flex gap-2 justify-center">
        <Button
          :label="$t('swipeWidgets.actionRequired.attendanceModal.attending')"
          icon="pi pi-check"
          severity="success"
          :loading="submitting"
          @click="respond('ATTENDING')"
        />
        <Button
          :label="$t('swipeWidgets.actionRequired.attendanceModal.absent')"
          icon="pi pi-times"
          severity="danger"
          :loading="submitting"
          @click="respond('ABSENT')"
        />
        <Button
          :label="$t('swipeWidgets.actionRequired.attendanceModal.undecided')"
          icon="pi pi-question"
          severity="secondary"
          :loading="submitting"
          @click="respond('UNDECIDED')"
        />
      </div>
    </div>

    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        :disabled="submitting"
        @click="close"
      />
    </template>
  </Dialog>
</template>
