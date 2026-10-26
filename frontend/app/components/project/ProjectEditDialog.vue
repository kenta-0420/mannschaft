<script setup lang="ts">
import type { UpdateProjectRequest } from '~/types/project'

const { t } = useI18n()

const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  form: UpdateProjectRequest
}>()

const emit = defineEmits<{
  save: [form: UpdateProjectRequest]
}>()

const localForm = ref<UpdateProjectRequest>({ ...props.form })

watch(() => props.form, (val) => {
  localForm.value = { ...val }
}, { deep: true })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('project.dialog.edit_title')"
    modal
    class="w-full max-w-lg"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('project.dialog.title_label') }}</label>
        <InputText v-model="localForm.title" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('project.dialog.description_label') }}</label>
        <Textarea v-model="localForm.description" class="w-full" rows="3" />
      </div>
      <ProjectAppearanceFields
        v-model:emoji="localForm.emoji"
        v-model:color="localForm.color"
      />
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('project.dialog.due_date_label') }}</label>
        <InputText v-model="localForm.dueDate" type="date" class="w-full" />
      </div>
    </div>
    <template #footer>
      <Button :label="t('project.dialog.cancel')" text @click="visible = false" />
      <Button :label="t('project.dialog.update')" @click="emit('save', localForm)" />
    </template>
  </Dialog>
</template>
