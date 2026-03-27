<script setup lang="ts">
import type { BulletinCategory, BulletinPriority, BulletinScopeType } from '~/types/bulletin'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  scopeType: BulletinScopeType
  scopeId: number
}>()

const emit = defineEmits<{
  saved: []
}>()

const { createThread, getCategories } = useBulletinApi()
const { showSuccess, showError } = useNotification()

const title = ref('')
const body = ref('')
const categoryId = ref<number | undefined>(undefined)
const priority = ref<BulletinPriority>('INFO')
const categories = ref<BulletinCategory[]>([])
const submitting = ref(false)

const priorityOptions = [
  { label: '緊急', value: 'CRITICAL' },
  { label: '重要', value: 'IMPORTANT' },
  { label: '注意', value: 'WARNING' },
  { label: '情報', value: 'INFO' },
  { label: '低', value: 'LOW' },
]

async function loadCategories() {
  try {
    const res = await getCategories(props.scopeType, props.scopeId)
    categories.value = res.data
  } catch { /* silent */ }
}

async function onSubmit() {
  if (!title.value.trim() || !body.value.trim() || submitting.value) return
  submitting.value = true
  try {
    await createThread(props.scopeType, props.scopeId, {
      title: title.value.trim(),
      body: body.value.trim(),
      categoryId: categoryId.value,
      priority: priority.value,
    })
    showSuccess('スレッドを作成しました')
    visible.value = false
    title.value = ''
    body.value = ''
    categoryId.value = undefined
    priority.value = 'INFO'
    emit('saved')
  } catch {
    showError('作成に失敗しました')
  } finally {
    submitting.value = false
  }
}

watch(visible, (v) => { if (v) loadCategories() })
</script>

<template>
  <Dialog v-model:visible="visible" header="新規スレッド" modal class="w-full max-w-2xl">
    <div class="flex flex-col gap-4">
      <div>
        <label class="mb-1 block text-sm font-medium">タイトル</label>
        <InputText v-model="title" class="w-full" placeholder="スレッドのタイトル" />
      </div>
      <div class="flex gap-4">
        <div class="flex-1">
          <label class="mb-1 block text-sm font-medium">カテゴリ</label>
          <Select v-model="categoryId" :options="categories" option-label="name" option-value="id" placeholder="未分類" class="w-full" />
        </div>
        <div class="flex-1">
          <label class="mb-1 block text-sm font-medium">重要度</label>
          <Select v-model="priority" :options="priorityOptions" option-label="label" option-value="value" class="w-full" />
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">本文</label>
        <Textarea v-model="body" auto-resize rows="8" class="w-full" placeholder="本文を入力..." />
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button label="投稿" :loading="submitting" :disabled="!title.trim() || !body.trim()" @click="onSubmit" />
    </template>
  </Dialog>
</template>
