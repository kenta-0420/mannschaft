<script setup lang="ts">
import type { CreateMilestoneRequest, MilestoneResponse } from '~/types/project'

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
    :header="editing ? 'マイルストーン編集' : 'マイルストーン追加'"
    modal
    class="w-full max-w-md"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">タイトル</label>
        <InputText v-model="localForm.title" class="w-full" placeholder="マイルストーン名" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">期限</label>
        <InputText v-model="localForm.dueDate" type="date" class="w-full" />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button :label="editing ? '更新' : '追加'" @click="emit('save', localForm)" />
    </template>
  </Dialog>
</template>
