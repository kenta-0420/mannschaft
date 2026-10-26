<script setup lang="ts">
import type { UnsealRequestResponse } from '~/types/succession'

const props = defineProps<{
  visible: boolean
  orgId: string
  request: UnsealRequestResponse | null
  mode: 'FIRST_APPROVE' | 'SECOND_APPROVE' | 'CANCEL'
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  done: []
}>()

const { t } = useI18n()
const { approveRequest, secondApproveRequest, cancelRequest } = useUnsealRequestApi()
const comment = ref('')
const loading = ref(false)

async function submit() {
  if (!props.request) return
  loading.value = true
  try {
    if (props.mode === 'FIRST_APPROVE') {
      await approveRequest(props.orgId, props.request.id, { comment: comment.value || null })
    }
    else if (props.mode === 'SECOND_APPROVE') {
      await secondApproveRequest(props.orgId, props.request.id, { comment: comment.value || null })
    }
    else {
      await cancelRequest(props.orgId, props.request.id)
    }
    emit('done')
    emit('update:visible', false)
    comment.value = ''
  }
  finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="t(`succession.unseal.dialog.${mode}.title`)"
    modal
    style="width: 480px"
    @update:visible="emit('update:visible', $event)"
  >
    <div v-if="mode !== 'CANCEL'" class="flex flex-col gap-2 mb-4">
      <label class="font-medium text-sm">{{ t('succession.unseal.dialog.comment') }}</label>
      <Textarea v-model="comment" rows="3" />
    </div>
    <p v-else class="mb-4 text-surface-600 dark:text-surface-300">
      {{ t('succession.unseal.dialog.CANCEL.confirm') }}
    </p>
    <template #footer>
      <Button
        :label="t('common.cancel')"
        severity="secondary"
        outlined
        @click="emit('update:visible', false)"
      />
      <Button
        :label="t(`succession.unseal.dialog.${mode}.submit`)"
        :severity="mode === 'CANCEL' ? 'danger' : 'primary'"
        :loading="loading"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
