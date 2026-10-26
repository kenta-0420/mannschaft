<template>
  <div
    v-if="pendingRun"
    class="sticky top-0 z-40 bg-red-600 text-white px-4 py-3 flex items-center justify-between gap-4 shadow-md"
  >
    <div class="flex items-center gap-2">
      <i class="pi pi-exclamation-triangle text-yellow-300" />
      <span class="text-sm font-medium">{{ $t('shift.autoAssign.resultBanner') }}</span>
      <span class="text-xs text-red-200">
        {{ $t('shift.autoAssign.slotsFilled', { filled: pendingRun.slotsFilled, total: pendingRun.slotsTotal }) }}
      </span>
      <span v-if="(pendingRun.warnings?.length ?? 0) > 0" class="text-xs text-yellow-300">
        {{ $t('shift.autoAssign.warnings', { count: pendingRun.warnings?.length ?? 0 }) }}
      </span>
    </div>
    <div class="flex items-center gap-2 flex-shrink-0">
      <Button
        :label="$t('shift.autoAssign.confirm')"
        size="small"
        severity="contrast"
        outlined
        @click="onConfirm"
      />
      <Button
        size="small"
        :label="$t('shift.autoAssign.revoke')"
        severity="danger"
        text
        class="!text-white hover:!text-red-200"
        @click="$emit('revoke')"
      />
    </div>
  </div>

  <ShiftVisualReviewModal
    v-model:visible="reviewVisible"
    @submit="onReviewSubmit"
  />
</template>

<script setup lang="ts">
import type { AssignmentRun } from '~/types/shift'

interface Props {
  pendingRun: AssignmentRun | null
}

defineProps<Props>()

const emit = defineEmits<{
  confirm: [note: string | undefined]
  revoke: []
}>()

const reviewVisible = ref(false)

function onConfirm(): void {
  reviewVisible.value = true
}

function onReviewSubmit(note: string | undefined): void {
  emit('confirm', note)
}
</script>
