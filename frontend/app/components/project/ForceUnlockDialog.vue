<script setup lang="ts">
import type { MilestoneResponse } from '~/types/project'

const props = defineProps<{
  visible: boolean
  milestone: MilestoneResponse | null
  submitting?: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  confirm: [reason: string]
  cancel: []
}>()

const reason = ref('')

// ダイアログを閉じるときは理由入力欄をリセット
watch(
  () => props.visible,
  (v) => {
    if (!v) reason.value = ''
  },
)

function handleConfirm() {
  const trimmed = reason.value.trim()
  if (!trimmed || trimmed.length > 100) return
  emit('confirm', trimmed)
}

function handleCancel() {
  emit('update:visible', false)
  emit('cancel')
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="$t('project.force_unlock_title')"
    :modal="true"
    :closable="true"
    class="w-96"
    data-testid="force-unlock-dialog"
    @update:visible="emit('update:visible', $event)"
  >
    <div v-if="milestone" class="space-y-4">
      <p class="text-sm text-surface-700 dark:text-surface-300">
        {{ $t('project.force_unlock_description', { title: milestone.title }) }}
      </p>

      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ $t('project.force_unlock_reason_label') }}
          <span class="text-red-500">*</span>
        </label>
        <Textarea
          v-model="reason"
          :placeholder="$t('project.force_unlock_reason_placeholder')"
          rows="3"
          class="w-full"
          :maxlength="100"
          data-testid="force-unlock-reason-input"
        />
        <p class="mt-1 text-xs text-surface-500">{{ reason.length }} / 100</p>
      </div>

      <div
        class="rounded-md bg-yellow-50 p-3 text-sm text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-200"
      >
        <i class="pi pi-exclamation-triangle mr-2" />
        {{ $t('project.force_unlock_warning') }}
      </div>
    </div>

    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        text
        data-testid="force-unlock-cancel"
        @click="handleCancel"
      />
      <Button
        :label="$t('project.force_unlock_submit')"
        severity="danger"
        :disabled="!reason.trim() || reason.length > 100 || submitting"
        :loading="submitting"
        data-testid="force-unlock-submit"
        @click="handleConfirm"
      />
    </template>
  </Dialog>
</template>
