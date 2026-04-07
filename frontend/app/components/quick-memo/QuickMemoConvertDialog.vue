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
  title: '',
  description: '',
  dueDate: undefined,
})
const loading = ref(false)
const dueDateObj = ref<Date | null>(null)

watch(
  () => props.memo,
  (memo) => {
    form.value.title = memo.title
    form.value.description = memo.body ?? ''
  },
  { immediate: true },
)

async function submit() {
  if (!form.value.title.trim()) return
  loading.value = true
  try {
    const body: ConvertToTodoRequest = {
      title: form.value.title.trim(),
      description: form.value.description?.trim() || undefined,
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
  form.value = { title: props.memo.title, description: props.memo.body ?? '', dueDate: undefined }
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
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('quick_memo.convert_modal.todo_title') }}
          <span class="ml-1 text-red-500">*</span>
        </label>
        <InputText v-model="form.title" class="w-full" maxlength="200" />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('quick_memo.convert_modal.description') }}
        </label>
        <Textarea v-model="form.description" class="w-full" rows="4" maxlength="5000" />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('quick_memo.convert_modal.due_date') }}
        </label>
        <DatePicker v-model="dueDateObj" :min-date="new Date()" date-format="yy-mm-dd" class="w-full" />
      </div>
    </div>

    <template #footer>
      <Button :label="t('button.cancel')" severity="secondary" @click="visible = false" />
      <Button
        :label="t('quick_memo.convert_modal.submit')"
        :loading="loading"
        :disabled="!form.title.trim()"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
