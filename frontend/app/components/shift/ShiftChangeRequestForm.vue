<script setup lang="ts">
import type { ChangeRequestType, CreateChangeRequestPayload } from '~/types/shift'

const props = defineProps<{
  scheduleId: number
  slotId?: number
}>()

const emit = defineEmits<{
  submitted: []
}>()

const { t } = useI18n()

const { createRequest } = useChangeRequest(computed(() => props.scheduleId))

const requestType = ref<ChangeRequestType>('PRE_CONFIRM_EDIT')
const reason = ref('')
const isSubmitting = ref(false)

const typeOptions = computed(() => [
  { label: t('shift.changeRequest.type.PRE_CONFIRM_EDIT'), value: 'PRE_CONFIRM_EDIT' as ChangeRequestType },
  { label: t('shift.changeRequest.type.INDIVIDUAL_SWAP'), value: 'INDIVIDUAL_SWAP' as ChangeRequestType },
  { label: t('shift.changeRequest.type.OPEN_CALL'), value: 'OPEN_CALL' as ChangeRequestType },
])

async function onSubmit(): Promise<void> {
  isSubmitting.value = true
  try {
    const payload: CreateChangeRequestPayload = {
      scheduleId: props.scheduleId,
      requestType: requestType.value,
      reason: reason.value || undefined,
    }
    if (props.slotId !== undefined) {
      payload.slotId = props.slotId
    }
    await createRequest(payload)
    reason.value = ''
    emit('submitted')
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <SectionCard>
    <h2 class="mb-4 text-base font-semibold text-surface-800">
      {{ $t('shift.changeRequest.title') }}
    </h2>

    <div class="space-y-4">
      <!-- 依頼種別選択 -->
      <div>
        <SelectButton
          v-model="requestType"
          :options="typeOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <!-- 理由（任意） -->
      <div>
        <label class="mb-1 block text-sm text-surface-600">
          {{ $t('shift.changeRequest.reason') }}
        </label>
        <Textarea
          v-model="reason"
          :placeholder="$t('shift.changeRequest.reason')"
          rows="3"
          class="w-full"
        />
      </div>

      <!-- オープンコール制限案内 -->
      <p v-if="requestType === 'OPEN_CALL'" class="text-xs text-orange-600">
        {{ $t('shift.changeRequest.openCallLimit') }}
      </p>

      <!-- 送信ボタン -->
      <Button
        :label="$t('shift.changeRequest.submit')"
        icon="pi pi-send"
        :loading="isSubmitting"
        :disabled="isSubmitting"
        class="w-full"
        @click="onSubmit"
      />
    </div>
  </SectionCard>
</template>
