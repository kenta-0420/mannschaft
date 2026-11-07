<script setup lang="ts">
import type { CreateMilestoneRequest, MilestoneResponse } from '~/types/project'

const { t } = useI18n()

const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  form: CreateMilestoneRequest
  editing: MilestoneResponse | null
}>()

const emit = defineEmits<{
  save: [form: CreateMilestoneRequest]
}>()

const localForm = ref<CreateMilestoneRequest>({ ...props.form })

watch(() => props.form, (val) => {
  localForm.value = { ...val }
}, { deep: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="editing ? t('project.milestone_dialog.edit_title') : t('project.milestone_dialog.add_title')"
    modal
    class="w-full max-w-md"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('project.milestone_dialog.title_label') }}</label>
        <InputText v-model="localForm.title" class="w-full" :placeholder="t('project.milestone_dialog.title_placeholder')" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('project.milestone_dialog.due_date_label') }}</label>
        <InputText v-model="localForm.dueDate" type="date" class="w-full" />
      </div>
    </div>
    <template #footer>
      <Button :label="t('project.dialog.cancel')" text @click="visible = false" />
      <Button :label="editing ? t('project.dialog.update') : t('project.milestone_dialog.add')" @click="emit('save', localForm)" />
    </template>
  </Dialog>
</template>
