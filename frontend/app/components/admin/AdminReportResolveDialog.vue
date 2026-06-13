<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })
const form = defineModel<{ actionType: string; note: string; guidelineSection: string }>('form', { required: true })

const emit = defineEmits<{
  resolve: []
}>()

const { t } = useI18n()

const actionOptions = computed(() => [
  { label: t('admin_report.resolve.action.warning'), value: 'WARNING' },
  { label: t('admin_report.resolve.action.content_delete'), value: 'CONTENT_DELETE' },
  { label: t('admin_report.resolve.action.account_freeze'), value: 'ACCOUNT_FREEZE' },
])
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="$t('admin_report.resolve.header')"
    :style="{ width: '500px' }"
    modal
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('admin_report.resolve.action_type') }}</label>
        <Select
          v-model="form.actionType"
          :options="actionOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('admin_report.resolve.note') }}</label>
        <Textarea v-model="form.note" rows="3" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('admin_report.resolve.guideline_section') }}</label>
        <InputText v-model="form.guidelineSection" class="w-full" />
      </div>
    </div>
    <template #footer>
      <div class="flex justify-end gap-2">
        <Button :label="$t('admin_report.resolve.cancel')" severity="secondary" @click="visible = false" />
        <Button :label="$t('admin_report.resolve.submit')" severity="success" @click="emit('resolve')" />
      </div>
    </template>
  </Dialog>
</template>
