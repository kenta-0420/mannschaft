<script setup lang="ts">
const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  todoId?: number // 編集時
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const todoApi = useTodoApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.todoId)

const form = ref({
  title: '',
  description: '',
  priority: 'MEDIUM' as string,
  dueDate: null as Date | null,
  assigneeIds: [] as number[],
})

const priorityOptions = [
  { label: '低', value: 'LOW' },
  { label: '中', value: 'MEDIUM' },
  { label: '高', value: 'HIGH' },
  { label: '緊急', value: 'URGENT' },
]

// 編集時にデータ読み込み
watch(
  () => [props.visible, props.todoId],
  async ([visible, todoId]) => {
    if (visible && todoId) {
      try {
        const res = await todoApi.getTodo(props.scopeType, props.scopeId, todoId as number)
        form.value.title = res.data.title
        form.value.description = res.data.description ?? ''
        form.value.priority = res.data.priority
        form.value.dueDate = res.data.dueDate ? new Date(res.data.dueDate) : null
        form.value.assigneeIds = res.data.assignees.map((a: { userId: number }) => a.userId)
      } catch {
        notification.error('TODO情報の取得に失敗しました')
      }
    } else if (visible && !todoId) {
      resetForm()
    }
  },
)

async function submit() {
  if (!form.value.title.trim()) {
    fieldErrors.value = { title: 'タイトルは必須です' }
    return
  }

  submitting.value = true
  fieldErrors.value = {}

  const body: Record<string, unknown> = {
    title: form.value.title.trim(),
    description: form.value.description.trim() || undefined,
    priority: form.value.priority,
    dueDate: form.value.dueDate ? form.value.dueDate.toISOString().split('T')[0] : undefined,
    assigneeIds: form.value.assigneeIds.length > 0 ? form.value.assigneeIds : undefined,
  }

  try {
    if (isEdit.value && props.todoId) {
      await todoApi.updateTodo(props.scopeType, props.scopeId, props.todoId, body)
      notification.success('TODOを更新しました')
    } else {
      await todoApi.createTodo(props.scopeType, props.scopeId, body)
      notification.success('TODOを作成しました')
    }
    emit('saved')
    close()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0) {
      handleApiError(error, isEdit.value ? 'TODO更新' : 'TODO作成')
    }
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = { title: '', description: '', priority: 'MEDIUM', dueDate: null, assigneeIds: [] }
  fieldErrors.value = {}
}

function close() {
  emit('update:visible', false)
  resetForm()
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="isEdit ? 'TODOを編集' : 'TODOを作成'"
    :style="{ width: '500px' }"
    modal
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >タイトル <span class="text-red-500">*</span></label
        >
        <InputText
          v-model="form.title"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors.title }"
          placeholder="TODOのタイトル"
        />
        <small v-if="fieldErrors.title" class="text-red-500">{{ fieldErrors.title }}</small>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea
          v-model="form.description"
          rows="3"
          class="w-full"
          placeholder="詳細な説明（任意）"
        />
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">優先度</label>
          <Select
            v-model="form.priority"
            :options="priorityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div>
          <label for="todo-due-date" class="mb-1 block text-sm font-medium">期限</label>
          <DatePicker
            v-model="form.dueDate"
            input-id="todo-due-date"
            date-format="yy/mm/dd"
            class="w-full"
            show-icon
          />
        </div>
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button
        :label="isEdit ? '更新' : '作成'"
        icon="pi pi-check"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
