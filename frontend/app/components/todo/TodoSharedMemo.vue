<script setup lang="ts">
import type { SharedMemoEntry } from '~/types/todo'

const { t } = useI18n()

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  todoId: number
}>()

const memoApi = useTodoMemo()
const notification = useNotification()

const memos = ref<SharedMemoEntry[]>([])
const loading = ref(false)
const currentPage = ref(0)
const totalPages = ref(1)

// 投稿フォーム
const newMemoText = ref('')
const quotedEntry = ref<SharedMemoEntry | null>(null)
const submitting = ref(false)

// 編集状態
const editingId = ref<number | null>(null)
const editingText = ref('')

// 削除確認
const deletingId = ref<number | null>(null)

async function loadMemos(page: number = 0) {
  loading.value = true
  try {
    const res = await memoApi.getSharedMemos(props.scopeType, props.scopeId, props.todoId, page)
    memos.value = res.data
    currentPage.value = res.meta.page
    totalPages.value = res.meta.totalPages
  } catch {
    notification.error(t('common.dialog.error'))
  } finally {
    loading.value = false
  }
}

async function submitMemo() {
  const text = newMemoText.value.trim()
  if (!text) return
  submitting.value = true
  try {
    await memoApi.createSharedMemo(props.scopeType, props.scopeId, props.todoId, {
      memo: text,
      quotedEntryId: quotedEntry.value?.id,
    })
    newMemoText.value = ''
    quotedEntry.value = null
    await loadMemos(0)
  } catch {
    notification.error(t('common.dialog.error'))
  } finally {
    submitting.value = false
  }
}

function startQuoteReply(entry: SharedMemoEntry) {
  quotedEntry.value = entry
}

function cancelQuote() {
  quotedEntry.value = null
}

function startEdit(entry: SharedMemoEntry) {
  editingId.value = entry.id
  editingText.value = entry.memo
}

function cancelEdit() {
  editingId.value = null
  editingText.value = ''
}

async function saveEdit() {
  if (editingId.value === null) return
  try {
    await memoApi.updateSharedMemo(
      props.scopeType,
      props.scopeId,
      props.todoId,
      editingId.value,
      { memo: editingText.value },
    )
    cancelEdit()
    await loadMemos(currentPage.value)
  } catch {
    notification.error(t('common.dialog.error'))
  }
}

function requestDelete(id: number) {
  deletingId.value = id
}

function cancelDelete() {
  deletingId.value = null
}

async function confirmDelete() {
  if (deletingId.value === null) return
  try {
    await memoApi.deleteSharedMemo(
      props.scopeType,
      props.scopeId,
      props.todoId,
      deletingId.value,
    )
    deletingId.value = null
    await loadMemos(currentPage.value)
  } catch {
    notification.error(t('common.dialog.error'))
  }
}

function formatDateTime(dateStr: string): string {
  return new Date(dateStr).toLocaleString('ja-JP', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function goPage(page: number) {
  loadMemos(page)
}

onMounted(() => loadMemos())
</script>

<template>
  <div class="space-y-4">
    <h3 class="font-semibold text-surface-800 dark:text-surface-100">
      {{ t('todo.enhancement.shared_memo.title') }}
    </h3>

    <!-- メモ一覧 -->
    <div v-if="loading" class="space-y-3">
      <Skeleton v-for="i in 3" :key="i" height="4rem" />
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="entry in memos"
        :key="entry.id"
        class="rounded-lg border border-surface-300 bg-surface-0 p-3 dark:border-surface-600 dark:bg-surface-800"
      >
        <!-- ヘッダー（ユーザー名 + 日時） -->
        <div class="mb-1 flex items-center justify-between">
          <span class="text-sm font-bold text-surface-800 dark:text-surface-100">
            {{ entry.userDisplayName }}
          </span>
          <span class="text-xs text-surface-400">{{ formatDateTime(entry.createdAt) }}</span>
        </div>

        <!-- 引用ボックス -->
        <div
          v-if="entry.quotedEntryId !== null && entry.quotedMemoPreview"
          class="mb-2 rounded border-l-4 border-surface-300 bg-surface-100 px-3 py-1.5 dark:border-surface-500 dark:bg-surface-700"
        >
          <p class="truncate text-xs text-surface-500 dark:text-surface-400">
            {{ entry.quotedMemoPreview }}
          </p>
        </div>

        <!-- 編集フォーム -->
        <div v-if="editingId === entry.id" class="mt-2 space-y-2">
          <textarea
            v-model="editingText"
            rows="3"
            class="w-full rounded-lg border border-surface-300 bg-surface-0 p-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-600 dark:bg-surface-700"
          />
          <div class="flex gap-2">
            <button
              type="button"
              class="rounded-lg bg-primary px-3 py-1 text-xs text-white hover:bg-primary/90"
              @click="saveEdit"
            >
              {{ t('button.save') }}
            </button>
            <button
              type="button"
              class="rounded-lg border border-surface-300 px-3 py-1 text-xs text-surface-600 hover:bg-surface-100 dark:border-surface-600 dark:text-surface-400"
              @click="cancelEdit"
            >
              {{ t('button.cancel') }}
            </button>
          </div>
        </div>

        <!-- 本文 -->
        <p
          v-else
          class="whitespace-pre-wrap text-sm text-surface-700 dark:text-surface-300"
        >
          {{ entry.memo }}
        </p>

        <!-- アクションボタン -->
        <div v-if="editingId !== entry.id" class="mt-2 flex gap-2">
          <button
            type="button"
            class="text-xs text-primary hover:underline"
            @click="startQuoteReply(entry)"
          >
            {{ t('todo.enhancement.shared_memo.quote_reply') }}
          </button>
          <button
            v-if="entry.isEditable"
            type="button"
            class="text-xs text-surface-500 hover:text-primary"
            @click="startEdit(entry)"
          >
            {{ t('button.edit') }}
          </button>
          <button
            v-if="entry.isOwnMemo"
            type="button"
            class="text-xs text-red-400 hover:text-red-600"
            @click="requestDelete(entry.id)"
          >
            {{ t('button.delete') }}
          </button>
        </div>
      </div>

      <div v-if="memos.length === 0" class="py-6 text-center text-sm text-surface-400">
        {{ t('todo.enhancement.shared_memo.no_data') }}
      </div>
    </div>

    <!-- ページネーション -->
    <div v-if="totalPages > 1" class="flex justify-center gap-2">
      <button
        v-for="page in totalPages"
        :key="page"
        type="button"
        class="h-8 w-8 rounded-full text-xs transition-colors"
        :class="page - 1 === currentPage
          ? 'bg-primary text-white'
          : 'border border-surface-300 text-surface-600 hover:bg-surface-100 dark:border-surface-600'"
        @click="goPage(page - 1)"
      >
        {{ page }}
      </button>
    </div>

    <!-- 投稿フォーム -->
    <div class="rounded-lg border border-surface-300 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-800">
      <!-- 引用表示 -->
      <div
        v-if="quotedEntry"
        class="mb-2 flex items-start justify-between rounded border-l-4 border-primary bg-surface-100 px-3 py-1.5 dark:bg-surface-700"
      >
        <p class="text-xs text-surface-600 dark:text-surface-300">
          <span class="font-semibold">{{ quotedEntry.userDisplayName }}</span>:
          {{ quotedEntry.memo.slice(0, 80) }}{{ quotedEntry.memo.length > 80 ? '…' : '' }}
        </p>
        <button
          type="button"
          class="ml-2 text-surface-400 hover:text-surface-600"
          @click="cancelQuote"
        >
          <i class="pi pi-times text-xs" />
        </button>
      </div>

      <textarea
        v-model="newMemoText"
        rows="3"
        :placeholder="t('todo.enhancement.shared_memo.placeholder')"
        class="w-full resize-none rounded-lg border border-surface-300 bg-surface-0 p-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary dark:border-surface-600 dark:bg-surface-700"
      />
      <div class="mt-2 flex justify-end">
        <button
          type="button"
          :disabled="submitting || !newMemoText.trim()"
          class="rounded-lg bg-primary px-4 py-1.5 text-sm text-white transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
          @click="submitMemo"
        >
          {{ t('button.submit') }}
        </button>
      </div>
    </div>

    <!-- 削除確認モーダル -->
    <Teleport to="body">
      <div
        v-if="deletingId !== null"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
        @click.self="cancelDelete"
      >
        <div class="w-full max-w-sm rounded-xl bg-surface-0 p-6 shadow-xl dark:bg-surface-800">
          <h3 class="mb-2 text-base font-semibold text-surface-800 dark:text-surface-100">
            {{ t('common.dialog.confirm_title') }}
          </h3>
          <p class="mb-5 text-sm text-surface-600 dark:text-surface-400">
            {{ t('common.dialog.confirm_delete') }}
          </p>
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="rounded-lg border border-surface-300 px-4 py-2 text-sm text-surface-600 hover:bg-surface-100 dark:border-surface-600 dark:text-surface-400"
              @click="cancelDelete"
            >
              {{ t('button.cancel') }}
            </button>
            <button
              type="button"
              class="rounded-lg bg-red-500 px-4 py-2 text-sm text-white hover:bg-red-600"
              @click="confirmDelete"
            >
              {{ t('button.delete') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
