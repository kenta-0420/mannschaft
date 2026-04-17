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
const { createAnnouncement } = useAnnouncementFeed(
  props.scopeType as 'TEAM' | 'ORGANIZATION',
  props.scopeId,
)

const title = ref('')
const body = ref('')
const categoryId = ref<number | undefined>(undefined)
const priority = ref<BulletinPriority>('INFO')
const categories = ref<BulletinCategory[]>([])
const submitting = ref(false)
// お知らせウィジェットに表示するフラグ
const displayInAnnouncement = ref(false)

// 添付ファイル
const attachedFiles = ref<File[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)

// 下書き自動保存
const draftKey = computed(() => `bulletin-draft-${props.scopeType}-${props.scopeId}`)

function saveDraft() {
  localStorage.setItem(draftKey.value, JSON.stringify({
    title: title.value,
    body: body.value,
    categoryId: categoryId.value,
    priority: priority.value,
  }))
}

function restoreDraft() {
  const saved = localStorage.getItem(draftKey.value)
  if (!saved) return
  try {
    const draft = JSON.parse(saved)
    title.value = draft.title ?? ''
    body.value = draft.body ?? ''
    categoryId.value = draft.categoryId
    priority.value = draft.priority ?? 'INFO'
  } catch { /* ignore */ }
}

function clearDraft() {
  localStorage.removeItem(draftKey.value)
}

// title・body が変化したら下書き保存（200ms debounce）
watch([title, body, categoryId, priority], () => {
  if (!title.value && !body.value) return
  saveDraft()
})

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files) return
  const newFiles = Array.from(input.files)
  // 合計5ファイル以下
  const remaining = 5 - attachedFiles.value.length
  attachedFiles.value.push(...newFiles.slice(0, remaining))
  input.value = '' // リセット
}

function removeFile(index: number) {
  attachedFiles.value.splice(index, 1)
}

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
    const res = await createThread(props.scopeType, props.scopeId, {
      title: title.value.trim(),
      body: body.value.trim(),
      categoryId: categoryId.value,
      priority: priority.value,
    }, attachedFiles.value)
    // お知らせウィジェットに表示する場合、スレッド作成後に登録
    if (displayInAnnouncement.value && res?.data?.id) {
      await createAnnouncement({
        sourceType: 'BULLETIN_THREAD',
        sourceId: res.data.id,
      }).catch(() => {
        // お知らせ登録失敗は投稿自体には影響しない（silent fail）
        showError('お知らせへの登録に失敗しました。後から手動で登録してください。')
      })
    }
    showSuccess('スレッドを作成しました')
    clearDraft()
    attachedFiles.value = []
    visible.value = false
    title.value = ''
    body.value = ''
    categoryId.value = undefined
    priority.value = 'INFO'
    displayInAnnouncement.value = false
    emit('saved')
  } catch {
    showError('作成に失敗しました')
  } finally {
    submitting.value = false
  }
}

watch(visible, (v) => {
  if (v) {
    loadCategories()
    restoreDraft()
  }
})
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

      <!-- 添付ファイル -->
      <div>
        <label class="mb-1 block text-sm font-medium">添付ファイル（最大5件・各6MB以下）</label>
        <div class="flex flex-col gap-2">
          <div v-for="(file, i) in attachedFiles" :key="i" class="flex items-center gap-2 rounded border border-surface-200 px-3 py-2 text-sm dark:border-surface-700">
            <i class="pi pi-file text-surface-400" />
            <span class="flex-1 truncate">{{ file.name }}</span>
            <span class="text-surface-400">{{ (file.size / 1024 / 1024).toFixed(1) }}MB</span>
            <Button icon="pi pi-times" text rounded severity="danger" size="small" @click="removeFile(i)" />
          </div>
          <Button
            v-if="attachedFiles.length < 5"
            label="ファイルを追加"
            icon="pi pi-paperclip"
            text
            size="small"
            @click="fileInputRef?.click()"
          />
          <input
            ref="fileInputRef"
            type="file"
            multiple
            accept="image/*,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
            class="hidden"
            @change="onFileChange"
          />
        </div>
      </div>

      <!-- お知らせウィジェット表示フラグ -->
      <AnnouncementAnnouncementToggle v-model="displayInAnnouncement" />

      <!-- 下書き保存インジケーター -->
      <div v-if="title || body" class="text-right text-xs text-surface-400">
        <i class="pi pi-save" /> 下書き自動保存中
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button label="投稿" :loading="submitting" :disabled="!title.trim() || !body.trim()" @click="onSubmit" />
    </template>
  </Dialog>
</template>
