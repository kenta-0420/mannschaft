<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })
const form = defineModel<{ reason: string; guidelineSection: string }>('form', { required: true })

const emit = defineEmits<{
  escalate: []
}>()
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('admin_report.escalate.header')"
    :style="{ width: '500px' }"
    modal
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('admin_report.escalate.reason') }}</label>
        <Textarea v-model="form.reason" rows="3" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('admin_report.escalate.guideline_section') }}</label>
        <InputText v-model="form.guidelineSection" class="w-full" />
      </div>
    </div>
    <template #footer>
      <div class="flex justify-end gap-2">
        <Button :label="$t('admin_report.escalate.cancel')" severity="secondary" @click="visible = false" />
        <Button :label="$t('admin_report.escalate.submit')" severity="warn" @click="emit('escalate')" />
      </div>
    </template>
  </Dialog>
</template>
