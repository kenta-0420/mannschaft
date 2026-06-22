<script setup lang="ts">
/**
 * ダッシュボード「ポイっとメモ」ウィジェット。
 * 未整理の最新 5 件を表示し、行ごとに以下の軽量操作を提供する:
 *  - タイトルのインライン編集
 *  - TODO 化（即時変換）
 *  - アーカイブ
 *  - 詳細ページへの遷移（本格的な編集はそちら）
 */
import type { QuickMemoResponse } from '~/types/quickMemo'

const { t } = useI18n()
const notification = useNotification()
const memoApi = useQuickMemoApi()
const router = useRouter()

const WIDGET_SIZE = 5

const memos = ref<QuickMemoResponse[]>([])
const loading = ref(true)
const editingId = ref<number | null>(null)
const editingTitle = ref('')
// v-for 内の v-if 配下なので function ref で1要素のみ捕捉する
let editingInputEl: HTMLInputElement | null = null
function setEditingInputRef(el: unknown) {
  if (!el) {
    editingInputEl = null
    return
  }
  const r = el as { $el?: HTMLInputElement } | HTMLInputElement
  editingInputEl = '$el' in r && r.$el ? r.$el : (r as HTMLInputElement)
}

async function load() {
  loading.value = true
  try {
    const res = await memoApi.listMemos({ status: 'UNSORTED', page: 1, size: WIDGET_SIZE })
    memos.value = res.data
  }
  catch {
    memos.value = []
  }
  finally {
    loading.value = false
  }
}

function snippet(text: string | null | undefined, max = 60): string {
  if (!text) return ''
  return text.length > max ? `${text.slice(0, max)}…` : text
}

async function startEdit(memo: QuickMemoResponse) {
  editingId.value = memo.id
  editingTitle.value = memo.title
  await nextTick()
  editingInputEl?.focus()
  editingInputEl?.select?.()
}

function cancelEdit() {
  editingId.value = null
  editingTitle.value = ''
}

function handleInputKeydown(e: KeyboardEvent, memo: QuickMemoResponse) {
  if (e.key === 'Enter') {
    e.preventDefault()
    commitEdit(memo)
  } else if (e.key === 'Escape') {
    e.preventDefault()
    cancelEdit()
  }
}

async function commitEdit(memo: QuickMemoResponse) {
  const next = editingTitle.value.trim()
  if (!next || next === memo.title) {
    cancelEdit()
    return
  }
  try {
    const res = await memoApi.updateMemo(memo.id, { title: next })
    const idx = memos.value.findIndex(m => m.id === memo.id)
    if (idx >= 0) memos.value[idx] = res.data
    notification.success(t('quick_memo.updated'))
  }
  catch {
    notification.error(t('quick_memo.update_error'))
  }
  finally {
    cancelEdit()
  }
}

async function convertToTodo(memo: QuickMemoResponse) {
  try {
    await memoApi.convertToTodo(memo.id, {})
    memos.value = memos.value.filter(m => m.id !== memo.id)
    notification.success(t('quick_memo.dashboard_widget.converted'))
    // 抜けた分を補充
    void load()
  }
  catch {
    notification.error(t('quick_memo.dashboard_widget.convert_error'))
  }
}

async function archive(memo: QuickMemoResponse) {
  try {
    await memoApi.archiveMemo(memo.id)
    memos.value = memos.value.filter(m => m.id !== memo.id)
    notification.success(t('quick_memo.action.archived'))
    void load()
  }
  catch {
    notification.error(t('quick_memo.action.archive_error'))
  }
}

function openDetail(memo: QuickMemoResponse) {
  router.push({ path: '/quick-memos', query: { id: String(memo.id) } })
}

function openAll() {
  router.push('/quick-memos')
}

onMounted(load)
</script>

<template>
  <DashboardWidgetCard
    :title="t('quick_memo.dashboard_widget.title')"
    icon="pi pi-bolt"
    to="/quick-memos"
    :loading="loading"
    refreshable
    @refresh="load"
  >
    <p v-if="memos.length === 0" class="text-sm text-gray-500">
      {{ t('quick_memo.dashboard_widget.empty') }}
    </p>

    <ul v-if="memos.length > 0" class="space-y-2">
      <li
        v-for="memo in memos"
        :key="memo.id"
        class="rounded border-2 border-surface-300 dark:border-surface-600 px-2 py-1.5 text-sm"
      >
        <!-- タイトル行（クリックで編集モード） -->
        <div class="flex items-center gap-1">
          <template v-if="editingId === memo.id">
            <InputText
              :ref="setEditingInputRef"
              v-model="editingTitle"
              class="flex-1 text-sm"
              maxlength="200"
              @keydown="handleInputKeydown($event, memo)"
              @blur="commitEdit(memo)"
            />
          </template>
          <template v-else>
            <button
              type="button"
              class="flex-1 text-left font-medium truncate hover:text-primary"
              :title="t('quick_memo.dashboard_widget.click_to_edit')"
              @click="startEdit(memo)"
            >
              {{ memo.title }}
            </button>
            <Button
              icon="pi pi-check-square"
              severity="success"
              text
              rounded
              size="small"
              :title="t('quick_memo.action.convert')"
              @click="convertToTodo(memo)"
            />
            <Button
              icon="pi pi-inbox"
              severity="secondary"
              text
              rounded
              size="small"
              :title="t('quick_memo.action.archive')"
              @click="archive(memo)"
            />
            <Button
              icon="pi pi-external-link"
              severity="secondary"
              text
              rounded
              size="small"
              :title="t('quick_memo.dashboard_widget.open_detail')"
              @click="openDetail(memo)"
            />
          </template>
        </div>

        <!-- 本文冒頭 -->
        <p
          v-if="memo.body && editingId !== memo.id"
          class="mt-0.5 text-xs text-gray-500 line-clamp-2"
        >
          {{ snippet(memo.body, 80) }}
        </p>
      </li>
    </ul>

    <div class="flex items-center justify-end pt-2 mt-2 border-t border-gray-100 dark:border-surface-700">
      <button
        type="button"
        class="text-xs text-primary hover:underline"
        @click="openAll"
      >
        {{ t('quick_memo.dashboard_widget.see_all') }} →
      </button>
    </div>
  </DashboardWidgetCard>
</template>
