<script setup lang="ts">
import type { UpdateProjectRequest } from '~/types/project'

const visible = defineModel<boolean>('visible', { required: true })

const props = defineProps<{
  form: UpdateProjectRequest
}>()

const emit = defineEmits<{
  save: []
}>()
</script>

<template>
  <Dialog
    v-model:visible="visible"
    header="プロジェクト編集"
    modal
    class="w-full max-w-lg"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">タイトル</label>
        <InputText v-model="props.form.title" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="props.form.description" class="w-full" rows="3" />
      </div>
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">絵文字</label>
          <InputText v-model="props.form.emoji" class="w-full" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">カラー</label>
          <InputText v-model="props.form.color" type="color" class="w-full" />
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">期限</label>
        <InputText v-model="props.form.dueDate" type="date" class="w-full" />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button label="更新" @click="emit('save')" />
    </template>
  </Dialog>
</template>
