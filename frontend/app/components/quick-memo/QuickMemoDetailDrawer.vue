<script setup lang="ts">
import type { QuickMemoResponse, TagResponse, UpdateQuickMemoRequest } from '~/types/quickMemo'

const props = defineProps<{
  memoId: number | null
}>()

const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ updated: []; archived: []; deleted: []; converted: [todoId: number] }>()

const { t } = useI18n()
const notification = useNotification()
const memoApi = useQuickMemoApi()
const tagApi = useTagApi()

const memo = ref<QuickMemoResponse | null>(null)
const personalTags = ref<TagResponse[]>([])
const loading = ref(false)
const editing = ref(false)
const saving = ref(false)
const showConvertDialog = ref(false)

const editForm = ref<UpdateQuickMemoRequest>({ title: '', body: '', tagIds: [] })

watch(
  () => [visible.value, props.memoId],
  async ([vis, id]) => {
    if (vis && id) {
      await loadMemo(id as number)
      loadTags()
    }
  },
)

async function loadMemo(id: number) {
  loading.value = true
  try {
    const res = await memoApi.getMemo(id)
    memo.value = res.data
  } catch {
    notification.error(t('quick_memo.load_error'))
    visible.value = false
  } finally {
    loading.value = false
  }
}

async function loadTags() {
  try {
    const res = await tagApi.listTags('personal')
    personalTags.value = res.data
  } catch {
    // silent
  }
}

function startEdit() {
  if (!memo.value) return
  editForm.value = {
    title: memo.value.title,
    body: memo.value.body ?? '',
    tagIds: memo.value.tags.map((t) => t.id),
  }
  editing.value = true
}

async function saveEdit() {
  if (!memo.value) return
  saving.value = true
  try {
    const res = await memoApi.updateMemo(memo.value.id, {
      title: editForm.value.title?.trim() || undefined,
      body: editForm.value.body,
      tagIds: editForm.value.tagIds,
    })
    memo.value = res.data
    editing.value = false
    notification.success(t('quick_memo.updated'))
    emit('updated')
  } catch {
    notification.error(t('quick_memo.update_error'))
  } finally {
    saving.value = false
  }
}

async function handleArchive() {
  if (!memo.value) return
  try {
    await memoApi.archiveMemo(memo.value.id)
    visible.value = false
    notification.success(t('quick_memo.action.archived'))
    emit('archived')
  } catch {
    notification.error(t('quick_memo.action.archive_error'))
  }
}

async function handleDelete() {
  if (!memo.value) return
  try {
    await memoApi.deleteMemo(memo.value.id)
    visible.value = false
    notification.success(t('quick_memo.action.deleted'))
    emit('deleted')
  } catch {
    notification.error(t('quick_memo.action.delete_error'))
  }
}
</script>

<template>
  <Drawer
    v-model:visible="visible"
    position="right"
    class="w-full sm:w-[480px]"
    :header="memo?.title ?? t('quick_memo.detail')"
  >
    <div v-if="loading" class="flex items-center justify-center py-8">
      <ProgressSpinner />
    </div>

    <div v-else-if="memo" class="space-y-4 p-1">
      <!-- 閲覧モード -->
      <template v-if="!editing">
        <!-- タグ -->
        <div v-if="memo.tags.length > 0" class="flex flex-wrap gap-1">
          <span
            v-for="tag in memo.tags"
            :key="tag.id"
            class="rounded-full px-2 py-0.5 text-xs text-white"
            :style="{ backgroundColor: tag.color ?? '#6366f1' }"
          >
            {{ tag.name }}
          </span>
        </div>

        <!-- 本文 -->
        <p class="whitespace-pre-wrap break-words text-sm text-surface-700 dark:text-surface-200">
          {{ memo.body ?? t('quick_memo.no_body') }}
        </p>

        <!-- リマインダー情報 -->
        <div
          v-if="memo.reminders.some((r) => r.scheduledAt)"
          class="rounded-lg bg-amber-50 p-3 text-xs dark:bg-amber-900/20"
        >
          <p class="mb-1 font-medium text-amber-700 dark:text-amber-400">
            <i class="pi pi-bell mr-1" />{{ t('quick_memo.reminder.label') }}
          </p>
          <ul class="space-y-0.5 text-amber-600 dark:text-amber-400">
            <li v-for="r in memo.reminders.filter((r) => r.scheduledAt)" :key="r.slot">
              {{ t(`quick_memo.reminder.slot${r.slot}`) }}:
              {{ r.scheduledAt ? new Date(r.scheduledAt).toLocaleString('ja-JP') : '-' }}
              <span v-if="r.sentAt" class="ml-1 text-green-600">✓</span>
            </li>
          </ul>
        </div>

        <!-- アクションボタン -->
        <div class="flex flex-wrap gap-2 pt-2">
          <Button
            :label="t('button.edit')"
            icon="pi pi-pencil"
            size="small"
            @click="startEdit"
          />
          <Button
            v-if="memo.status === 'UNSORTED'"
            :label="t('quick_memo.action.convert')"
            icon="pi pi-check-square"
            size="small"
            severity="success"
            @click="showConvertDialog = true"
          />
          <Button
            v-if="memo.status === 'UNSORTED'"
            :label="t('quick_memo.action.archive')"
            icon="pi pi-inbox"
            size="small"
            severity="secondary"
            @click="handleArchive"
          />
          <Button
            :label="t('button.delete')"
            icon="pi pi-trash"
            size="small"
            severity="danger"
            @click="handleDelete"
          />
        </div>
      </template>

      <!-- 編集モード -->
      <template v-else>
        <div class="space-y-3">
          <InputText v-model="editForm.title" class="w-full" maxlength="200" />
          <Textarea v-model="editForm.body" class="w-full" rows="8" maxlength="10000" />
          <QuickMemoTagPicker v-model="editForm.tagIds" :tags="personalTags" />
        </div>
        <div class="flex gap-2 pt-2">
          <Button :label="t('button.cancel')" severity="secondary" @click="editing = false" />
          <Button :label="t('button.save')" :loading="saving" @click="saveEdit" />
        </div>
      </template>
    </div>

    <!-- TODOに変換ダイアログ -->
    <QuickMemoConvertDialog
      v-if="memo && showConvertDialog"
      v-model:visible="showConvertDialog"
      :memo="memo"
      @converted="(id) => { visible = false; emit('converted', id) }"
    />
  </Drawer>
</template>
