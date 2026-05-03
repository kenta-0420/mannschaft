<script setup lang="ts">
import { ref } from 'vue'
import type { ResolveEvaluationRequest } from '~/types/school'

const props = defineProps<{
  visible: boolean
  evaluationId?: number
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  resolve: [id: number, req: ResolveEvaluationRequest]
}>()

const resolutionNote = ref('')

function onResolve(): void {
  if (!props.evaluationId || !resolutionNote.value.trim()) return
  emit('resolve', props.evaluationId, { resolutionNote: resolutionNote.value.trim() })
  resolutionNote.value = ''
}

function onHide(): void {
  resolutionNote.value = ''
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    data-testid="requirement-resolution-modal"
    :visible="visible"
    :header="$t('school.evaluation.resolveTitle')"
    modal
    :draggable="false"
    style="width: 28rem"
    @update:visible="onHide"
  >
    <div class="mb-4">
      <label class="text-sm text-surface-500 mb-1 block">
        {{ $t('school.evaluation.resolveNote') }} *
      </label>
      <Textarea
        v-model="resolutionNote"
        class="w-full"
        rows="3"
        :placeholder="$t('school.evaluation.resolveNoteHint')"
      />
    </div>

    <template #footer>
      <Button
        data-testid="resolution-cancel-btn"
        :label="$t('common.cancel')"
        severity="secondary"
        @click="onHide"
      />
      <Button
        data-testid="resolution-submit-btn"
        :label="$t('school.evaluation.resolve')"
        severity="danger"
        :disabled="!resolutionNote.trim()"
        @click="onResolve"
      />
    </template>
  </Dialog>
</template>
