<script setup lang="ts">
import type { QuickMemoResponse, ConvertToTodoRequest } from '~/types/quickMemo'

const props = defineProps<{
  memo: QuickMemoResponse
}>()

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ converted: [todoId: number] }>()

const { t } = useI18n()
const notification = useNotification()
const memoApi = useQuickMemoApi()

const form = ref<ConvertToTodoRequest>({
  priority: 'MEDIUM',
  dueDate: undefined,
  projectId: undefined,
})
const loading = ref(false)
const dueDateObj = ref<Date | null>(null)

const priorityOptions = [
  { label: t('todo.priority.LOW'), value: 'LOW' },
  { label: t('todo.priority.MEDIUM'), value: 'MEDIUM' },
  { label: t('todo.priority.HIGH'), value: 'HIGH' },
  { label: t('todo.priority.URGENT'), value: 'URGENT' },
]

async function submit() {
  loading.value = true
  try {
    const body: ConvertToTodoRequest = {
      priority: form.value.priority,
      dueDate: dueDateObj.value ? dueDateObj.value.toISOString().slice(0, 10) : undefined,
    }
    const res = await memoApi.convertToTodo(props.memo.id, body)
    visible.value = false
    notification.success(t('quick_memo.convert_modal.success'))
    emit('converted', res.data.todoId)
  } catch {
    notification.error(t('quick_memo.convert_modal.error'))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.value = { priority: 'MEDIUM', dueDate: undefined, projectId: undefined }
  dueDateObj.value = null
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('quick_memo.convert_modal.title')"
    modal
    class="w-full max-w-lg"
    @hide="resetForm"
  >
    <!-- メモ内容プレビュー（タイトル・本文はそのまま継承） -->
    <div class="mb-4 rounded-lg bg-surface-100 p-3 text-sm dark:bg-surface-700">
      <p class="font-medium">{{ memo.title }}</p>
      <p v-if="memo.body" class="mt-1 line-clamp-2 text-surface-500">{{ memo.body }}</p>
    </div>

    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('quick_memo.convert_modal.priority') }}</label>
        <Select
          v-model="form.priority"
          :options="priorityOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('quick_memo.convert_modal.due_date') }}</label>
        <DatePicker
          v-model="dueDateObj"
          :min-date="new Date()"
          date-format="yy-mm-dd"
          class="w-full"
        />
      </div>
    </div>

    <template #footer>
      <Button :label="t('button.cancel')" severity="secondary" @click="visible = false" />
      <Button
        :label="t('quick_memo.convert_modal.submit')"
        :loading="loading"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
