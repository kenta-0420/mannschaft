<script setup lang="ts">
import { computed } from 'vue'
import type { CancellationFeeEstimateResponse } from '~/types/recruitment'

interface Props {
  visible: boolean
  estimate: CancellationFeeEstimateResponse | null
  loading?: boolean
}
const props = withDefaults(defineProps<Props>(), {
  loading: false,
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  agree: []
  cancel: []
}>()

const { t } = useI18n()

const isFree = computed(() => !props.estimate || props.estimate.feeAmount === 0)
const formattedFee = computed(() => {
  if (!props.estimate) return ''
  return `¥${props.estimate.feeAmount.toLocaleString()}`
})

function onAgree() {
  emit('agree')
}

function onCancel() {
  emit('cancel')
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t('recruitment.confirmModal.cancellationFee.title')"
    :modal="true"
    :closable="false"
    style="width: 28rem"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-4">
      <p>{{ isFree ? t('recruitment.confirmModal.cancellationFee.freeMessage') : t('recruitment.confirmModal.cancellationFee.message') }}</p>

      <div v-if="!isFree" class="rounded border border-red-300 bg-red-50 p-3">
        <div class="text-sm text-gray-600">
          {{ t('recruitment.confirmModal.cancellationFee.feeAmountLabel') }}
        </div>
        <div class="text-2xl font-bold text-red-700">
          {{ formattedFee }}
        </div>
      </div>
    </div>

    <template #footer>
      <Button
        :label="t('recruitment.confirmModal.cancellationFee.disagreeButton')"
        severity="secondary"
        :disabled="loading"
        @click="onCancel"
      />
      <Button
        :label="t('recruitment.confirmModal.cancellationFee.agreeButton')"
        severity="danger"
        :loading="loading"
        @click="onAgree"
      />
    </template>
  </Dialog>
</template>
