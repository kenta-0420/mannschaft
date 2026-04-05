<script setup lang="ts">
import type { IncidentCategoryResponse, IncidentPriority } from '~/types/incident'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  scopeType: string
  scopeId: number
  editId?: number
}>()

const emit = defineEmits<{
  saved: []
}>()

const { reportIncident, getIncident, updateIncident, listCategories } = useIncidentApi()
const { success: showSuccess, error: showError } = useNotification()

const title = ref('')
const description = ref('')
const categoryId = ref<number | undefined>(undefined)
const priority = ref<IncidentPriority>('MEDIUM')
const categories = ref<IncidentCategoryResponse[]>([])
const submitting = ref(false)
const isEdit = computed(() => !!props.editId)

const priorityOptions = [
  { label: '低', value: 'LOW' },
  { label: '中', value: 'MEDIUM' },
  { label: '高', value: 'HIGH' },
  { label: '緊急', value: 'CRITICAL' },
]

async function loadCategories() {
  try {
    const res = await listCategories(props.scopeType, props.scopeId)
    categories.value = res.data.filter((c: IncidentCategoryResponse) => c.isActive)
  } catch { /* silent */ }
}

async function loadExisting() {
  if (!props.editId) return
  try {
    const res = await getIncident(props.editId)
    title.value = res.data.title
    description.value = res.data.description || ''
    categoryId.value = res.data.categoryId ?? undefined
    priority.value = res.data.priority
  } catch {
    showError('インシデントの取得に失敗しました')
  }
}

async function onSubmit() {
  if (!title.value.trim() || submitting.value) return
  submitting.value = true
  try {
    if (isEdit.value && props.editId) {
      await updateIncident(props.editId, {
        title: title.value.trim(),
        description: description.value.trim() || undefined,
        priority: priority.value,
      })
      showSuccess('インシデントを更新しました')
    } else {
      await reportIncident({
        scopeType: props.scopeType,
        scopeId: props.scopeId,
        categoryId: categoryId.value,
        title: title.value.trim(),
        description: description.value.trim() || undefined,
        priority: priority.value,
      })
      showSuccess('インシデントを報告しました')
    }
    visible.value = false
    resetForm()
    emit('saved')
  } catch {
    showError(isEdit.value ? '更新に失敗しました' : '報告に失敗しました')
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  title.value = ''
  description.value = ''
  categoryId.value = undefined
  priority.value = 'MEDIUM'
}

watch(visible, (v) => {
  if (v) {
    loadCategories()
    if (props.editId) loadExisting()
    else resetForm()
  }
})
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="isEdit ? 'インシデント編集' : 'インシデント報告'"
    modal
    class="w-full max-w-2xl"
  >
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">タイトル</label>
        <InputText v-model="title" class="w-full" placeholder="インシデントのタイトル" />
      </div>
      <div class="flex gap-4">
        <div class="flex-1">
          <label class="mb-1 block text-sm font-medium">カテゴリ</label>
          <Select
            v-model="categoryId"
            :options="categories"
            option-label="name"
            option-value="id"
            placeholder="未分類"
            class="w-full"
            :disabled="isEdit"
          />
        </div>
        <div class="flex-1">
          <label class="mb-1 block text-sm font-medium">優先度</label>
          <Select
            v-model="priority"
            :options="priorityOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea
          v-model="description"
          auto-resize
          rows="6"
          class="w-full"
          placeholder="インシデントの詳細..."
        />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button
        :label="isEdit ? '更新' : '報告'"
        :loading="submitting"
        :disabled="!title.trim()"
        @click="onSubmit"
      />
    </template>
  </Dialog>
</template>
