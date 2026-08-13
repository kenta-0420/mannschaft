<script setup lang="ts">
/**
 * F03.11.1 キャンセル料の免除 確認モーダル（設計書 §12.1）。
 *
 * 文言は §12.1 の方針に厳密に従う:
 *   - 「申込制限が解除されます」と言い切らない
 *   - 1 文目に必ず起こること（債権の放棄・不可逆）、2 文目に条件付きであること
 *   - 「解除されます」ではなく「残っている場合は解除されません」の否定形
 * 文言そのものはロケールファイル（recruitment.cancellationFeeWaive.confirmDialog.message）
 * に集約し、直書きしない。
 */
import { computed, ref, watch } from 'vue'
import type { RecruitmentCancellationRecordSummary } from '~/types/recruitment'

interface Props {
  visible: boolean
  record: RecruitmentCancellationRecordSummary | null
  loading?: boolean
}
const props = withDefaults(defineProps<Props>(), {
  loading: false,
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirm: [reason: string]
  cancel: []
}>()

const { t } = useI18n()

const reason = ref('')

watch(() => props.visible, (v) => {
  if (v) {
    reason.value = ''
  }
})

const confirmMessage = computed(() => {
  if (!props.record) return ''
  return t('recruitment.cancellationFeeWaive.confirmDialog.message', {
    amount: props.record.feeAmount.toLocaleString(),
  })
})

const reasonInvalid = computed(() => reason.value.trim().length === 0)

function onConfirm() {
  if (reasonInvalid.value) return
  emit('confirm', reason.value.trim())
}

function onCancel() {
  emit('cancel')
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('recruitment.cancellationFeeWaive.confirmDialog.title')"
    :modal="true"
    :closable="false"
    style="width: 32rem"
    data-testid="waive-confirm-dialog"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4">
      <p class="whitespace-pre-line" data-testid="waive-confirm-message">{{ confirmMessage }}</p>

      <div class="flex flex-col gap-1">
        <label for="waive-reason" class="text-sm font-medium">
          {{ t('recruitment.cancellationFeeWaive.reasonLabel') }}
        </label>
        <Textarea
          id="waive-reason"
          v-model="reason"
          rows="3"
          :placeholder="t('recruitment.cancellationFeeWaive.reasonPlaceholder')"
          :disabled="loading"
          data-testid="waive-reason-input"
        />
        <span v-if="reasonInvalid" class="text-sm text-red-600" data-testid="waive-reason-error">
          {{ t('recruitment.cancellationFeeWaive.reasonRequired') }}
        </span>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('recruitment.cancellationFeeWaive.confirmDialog.cancelButton')"
        severity="secondary"
        :disabled="loading"
        data-testid="waive-cancel-button"
        @click="onCancel"
      />
      <Button
        :label="t('recruitment.cancellationFeeWaive.confirmDialog.confirmButton')"
        severity="danger"
        :loading="loading"
        :disabled="reasonInvalid"
        data-testid="waive-confirm-button"
        @click="onConfirm"
      />
    </template>
  </Dialog>
</template>
