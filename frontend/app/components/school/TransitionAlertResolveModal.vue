<script setup lang="ts">
import { ref } from 'vue'
import type { TransitionAlertResponse } from '~/types/school'

const props = defineProps<{
  visible: boolean
  alert: TransitionAlertResponse
  teamId: number
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  resolved: [alertId: number]
}>()

const { t } = useI18n()
const api = useTransitionAlertApi()
const { error: notifyError, success: notifySuccess } = useNotification()

const note = ref('')
const submitting = ref(false)

function close(): void {
  note.value = ''
  emit('update:visible', false)
}

async function onConfirm(): Promise<void> {
  if (!note.value.trim()) return

  submitting.value = true
  try {
    await api.resolveAlert(props.teamId, props.alert.id, note.value.trim())
    notifySuccess(t('school.transitionAlert.resolveSuccess'))
    emit('resolved', props.alert.id)
    note.value = ''
  } catch {
    notifyError(t('school.transitionAlert.title'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="props.visible"
    :header="$t('school.transitionAlert.resolve')"
    modal
    :closable="!submitting"
    :style="{ width: '480px', maxWidth: '95vw' }"
    @update:visible="close"
  >
    <!-- アラート情報 -->
    <div class="mb-4 p-3 rounded-lg bg-surface-50 dark:bg-surface-800 border border-surface-200 dark:border-surface-700">
      <div class="flex items-center gap-2 mb-2">
        <span
          class="inline-flex items-center px-2 py-0.5 rounded text-xs font-bold"
          :class="{
            'bg-red-600 text-white': props.alert.alertLevel === 'URGENT',
            'bg-red-200 text-red-800 dark:bg-red-800 dark:text-red-100': props.alert.alertLevel === 'NORMAL',
          }"
        >
          {{ $t(`school.transitionAlert.alertLevel.${props.alert.alertLevel}`) }}
        </span>
        <span class="text-sm font-medium text-surface-700 dark:text-surface-200">
          {{ $t('school.transitionAlert.title') }}
        </span>
      </div>
      <p class="text-sm text-surface-600 dark:text-surface-300 m-0">
        {{
          $t('school.transitionAlert.message', {
            studentName: String(props.alert.studentUserId),
            previousPeriod: props.alert.previousPeriodNumber,
            currentPeriod: props.alert.currentPeriodNumber,
          })
        }}
      </p>
    </div>

    <!-- 解決理由入力 -->
    <div class="mb-4">
      <label class="block text-sm font-medium text-surface-700 dark:text-surface-200 mb-1">
        {{ $t('school.transitionAlert.resolveNote') }}
        <span class="text-red-500 ml-1">*</span>
      </label>
      <Textarea
        v-model="note"
        :placeholder="$t('school.transitionAlert.resolveNotePlaceholder')"
        rows="4"
        class="w-full"
        :disabled="submitting"
      />
    </div>

    <!-- フッターボタン -->
    <template #footer>
      <Button
        :label="$t('common.cancel')"
        text
        :disabled="submitting"
        @click="close"
      />
      <Button
        :label="$t('school.transitionAlert.resolve')"
        severity="danger"
        :loading="submitting"
        :disabled="!note.trim()"
        @click="onConfirm"
      />
    </template>
  </Dialog>
</template>
